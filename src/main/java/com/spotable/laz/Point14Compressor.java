package com.spotable.laz;

import static com.spotable.laz.Point14Tables.RETURN_LEVEL_8;
import static com.spotable.laz.Point14Tables.RETURN_MAP_6;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Compresses PDRF&nbsp;6 records into one LASzip v3 "layered" chunk — the encode-side
 * mirror of {@link Point14Decompressor}, ported from laz-perf's
 * {@code detail::Point14Compressor} plus the {@code point_compressor_6} framing.
 * <p>
 * Feed points with {@link #compress(byte[], int)}; the first becomes the raw stored
 * point and primes the predictors, each later point is encoded as deltas into the
 * nine sub-streams. {@link #finish()} emits the complete chunk:
 * {@code [raw first point][u32 count][9 layer sizes][layer bytes]} — exactly the
 * layout the decoder expects. Layers for fields that never changed are emitted with
 * size zero.
 */
final class Point14Compressor {

    private static final int GPSTIME_MULTI = 500;
    private static final int GPSTIME_MULTI_MINUS = -10;
    private static final int GPSTIME_MULTI_CODE_FULL = 511;

    private static final class ChannelCtx {
        final ArithmeticModel[] changedValues = models(8, 128);
        final ArithmeticModel scannerChannel = new ArithmeticModel(3, true);
        final ArithmeticModel rnGpsSame = new ArithmeticModel(13, true);
        final ArithmeticModel[] nr = models(16, 16);
        final ArithmeticModel[] rn = models(16, 16);
        final ArithmeticModel[] classM = models(64, 256);
        final ArithmeticModel[] flag = models(64, 64);
        final ArithmeticModel[] userDataM = models(64, 256);
        final ArithmeticModel gpstimeMulti = new ArithmeticModel(515, true);
        final ArithmeticModel gpstime0diff = new ArithmeticModel(5, true);

        final IntegerCompressor dx = new IntegerCompressor(32, 2);
        final IntegerCompressor dy = new IntegerCompressor(32, 22);
        final IntegerCompressor zc = new IntegerCompressor(32, 20);
        final IntegerCompressor intensity = new IntegerCompressor(16, 4);
        final IntegerCompressor scanAngle = new IntegerCompressor(16, 2);
        final IntegerCompressor pointSourceId = new IntegerCompressor(16, 1);
        final IntegerCompressor gpstime = new IntegerCompressor(32, 9);

        boolean haveLast;
        final Point14 last = new Point14();
        final int[] lastIntensity = new int[8];
        final int[] lastZ = new int[8];
        final StreamingMedian[] xMedian = medians(12);
        final StreamingMedian[] yMedian = medians(12);
        int lastGpsSeq, nextGpsSeq;
        final long[] lastGpstime = new long[4];
        final int[] lastGpstimeDiff = new int[4];
        final int[] multiExtremeCounter = new int[4];
        boolean gpsTimeChange;

        ChannelCtx() {
            dx.init(); dy.init(); zc.init(); intensity.init();
            scanAngle.init(); pointSourceId.init(); gpstime.init();
        }

        private static ArithmeticModel[] models(int n, int symbols) {
            ArithmeticModel[] a = new ArithmeticModel[n];
            for (int i = 0; i < n; i++) a[i] = new ArithmeticModel(symbols, true);
            return a;
        }

        private static StreamingMedian[] medians(int n) {
            StreamingMedian[] a = new StreamingMedian[n];
            for (int i = 0; i < n; i++) a[i] = new StreamingMedian();
            return a;
        }
    }

    private final ChannelCtx[] ctx = {new ChannelCtx(), new ChannelCtx(), new ChannelCtx(), new ChannelCtx()};
    private int lastChannel = -1;
    private int chunkCount = 0;
    private final byte[] rawFirst = new byte[Point14.SIZE];

    // The nine layered sub-streams; xy and z are always emitted, the rest only if "made valid".
    private final ArithmeticEncoder xy = new ArithmeticEncoder(true);
    private final ArithmeticEncoder z = new ArithmeticEncoder(true);
    private final ArithmeticEncoder classEnc = new ArithmeticEncoder(false);
    private final ArithmeticEncoder flags = new ArithmeticEncoder(false);
    private final ArithmeticEncoder intensityEnc = new ArithmeticEncoder(false);
    private final ArithmeticEncoder scanAngleEnc = new ArithmeticEncoder(false);
    private final ArithmeticEncoder userData = new ArithmeticEncoder(false);
    private final ArithmeticEncoder pointSrc = new ArithmeticEncoder(false);
    private final ArithmeticEncoder gpstimeEnc = new ArithmeticEncoder(false);

    /** Encodes the next 30-byte PDRF 6 record read from {@code in} at {@code off}. */
    void compress(byte[] in, int off) {
        chunkCount++;
        Point14 point = new Point14();
        point.unpack(in, off);
        int sc = point.scannerChannel();

        if (lastChannel == -1) {
            System.arraycopy(in, off, rawFirst, 0, Point14.SIZE);
            ChannelCtx c = ctx[sc];
            c.last.copyFrom(point);
            c.haveLast = true;
            c.lastGpstime[0] = point.gpstime;
            lastChannel = sc;
            Arrays.fill(c.lastZ, point.z);
            Arrays.fill(c.lastIntensity, point.intensity);
            return;
        }

        ChannelCtx prev = ctx[lastChannel];
        int changeStream =
                (prev.last.returnNum() == 1 ? 1 : 0)
                | ((prev.last.returnNum() >= prev.last.numReturns() ? 1 : 0) << 1)
                | ((prev.gpsTimeChange ? 1 : 0) << 2);

        ChannelCtx c = ctx[sc];
        ChannelCtx old = c.haveLast ? c : prev;

        boolean gpsTimeChange =
                Double.longBitsToDouble(point.gpstime) != Double.longBitsToDouble(old.last.gpstime);
        boolean pointSourceChange = point.pointSourceId != old.last.pointSourceId;
        boolean scanAngleChange = point.scanAngle != old.last.scanAngle;
        int lastN = old.last.numReturns();
        int lastR = old.last.returnNum();
        int n = point.numReturns();
        int r = point.returnNum();
        boolean rnIncrements = r == ((lastR + 1) % 16);
        boolean rnDecrements = r == ((lastR + 15) % 16);
        boolean rnMiscChange = (r != lastR) && !rnIncrements && !rnDecrements;

        int changed =
                ((rnIncrements || rnMiscChange ? 1 : 0))
                | ((rnDecrements || rnMiscChange ? 1 : 0) << 1)
                | ((n != lastN ? 1 : 0) << 2)
                | ((scanAngleChange ? 1 : 0) << 3)
                | ((gpsTimeChange ? 1 : 0) << 4)
                | ((pointSourceChange ? 1 : 0) << 5)
                | ((sc != lastChannel ? 1 : 0) << 6);

        xy.encodeSymbol(prev.changedValues[changeStream], changed);

        if (sc > lastChannel) xy.encodeSymbol(prev.scannerChannel, sc - lastChannel - 1);
        else if (sc < lastChannel) xy.encodeSymbol(prev.scannerChannel, sc - lastChannel - 1 + 4);

        if (!c.haveLast) {
            c.haveLast = true;
            c.last.copyFrom(prev.last);
            Arrays.fill(c.lastZ, prev.last.z);
            Arrays.fill(c.lastIntensity, prev.last.intensity);
            c.lastGpstime[0] = prev.last.gpstime;
        }

        if (n != lastN) xy.encodeSymbol(c.nr[lastN], n);

        if (rnMiscChange) {
            if (gpsTimeChange) {
                xy.encodeSymbol(c.rn[lastR], r);
            } else {
                int diff = r - lastR;
                xy.encodeSymbol(c.rnGpsSame, diff > 1 ? diff - 2 : diff - 2 + 16);
            }
        }

        int rmCtx = (RETURN_MAP_6[n][r] << 1) | (gpsTimeChange ? 1 : 0);

        // X
        int median = c.xMedian[rmCtx].get();
        int diff = point.x - c.last.x;
        c.dx.compress(xy, median, diff, n == 1 ? 1 : 0);
        c.xMedian[rmCtx].add(diff);

        // Y
        int kbits = Math.min(c.dx.k, 20) & ~1;
        median = c.yMedian[rmCtx].get();
        diff = point.y - c.last.y;
        c.dy.compress(xy, median, diff, kbits | (n == 1 ? 1 : 0));
        c.yMedian[rmCtx].add(diff);

        // Z
        kbits = (c.dx.k + c.dy.k) / 2;
        kbits = Math.min(kbits, 18) & ~1;
        int zctx = RETURN_LEVEL_8[n][r];
        c.zc.compress(z, c.lastZ[zctx], point.z, kbits | (n == 1 ? 1 : 0));
        c.lastZ[zctx] = point.z;

        // Classification
        int cctx = ((r == 1 && r >= n) ? 1 : 0) | ((c.last.classification & 0x1F) << 1);
        if (point.classification != c.last.classification) classEnc.makeValid();
        classEnc.encodeSymbol(c.classM[cctx], point.classification);

        // Flags
        int merged = point.classFlags() | (point.scanDirFlag() << 4) | (point.eofFlag() << 5);
        int lastMerged = c.last.classFlags() | (c.last.scanDirFlag() << 4) | (c.last.eofFlag() << 5);
        if (merged != lastMerged) flags.makeValid();
        flags.encodeSymbol(c.flag[lastMerged], merged);

        // Intensity
        int ictx = (gpsTimeChange ? 1 : 0) | ((r >= n ? 1 : 0) << 1) | ((r == 1 ? 1 : 0) << 2);
        if (point.intensity != c.last.intensity) intensityEnc.makeValid();
        c.intensity.compress(intensityEnc, c.lastIntensity[ictx], point.intensity, ictx >> 1);
        c.lastIntensity[ictx] = point.intensity;

        // Scan angle
        if (point.scanAngle != c.last.scanAngle) {
            scanAngleEnc.makeValid();
            c.scanAngle.compress(scanAngleEnc, c.last.scanAngle, point.scanAngle, gpsTimeChange ? 1 : 0);
        }

        // User data
        int uctx = (c.last.userData & 0xFF) / 4;
        if (point.userData != c.last.userData) userData.makeValid();
        userData.encodeSymbol(c.userDataM[uctx], point.userData);

        // Point source id
        if (pointSourceChange) {
            pointSrc.makeValid();
            c.pointSourceId.compress(pointSrc, c.last.pointSourceId, point.pointSourceId, 0);
        }

        // GPS time
        if (gpsTimeChange) encodeGpsTime(point, c);

        lastChannel = sc;
        c.gpsTimeChange = gpsTimeChange;
        c.last.copyFrom(point);
    }

    /** Finalizes the chunk and returns its complete on-disk byte representation. */
    byte[] finish() {
        byte[] xyB = xy.done();
        byte[] zB = z.done();
        byte[] classB = classEnc.done();
        byte[] flagsB = flags.done();
        byte[] intensityB = intensityEnc.done();
        byte[] scanAngleB = scanAngleEnc.done();
        byte[] userDataB = userData.done();
        byte[] pointSrcB = pointSrc.done();
        byte[] gpstimeB = gpstimeEnc.done();

        byte[][] data = {xyB, zB, classB, flagsB, intensityB, scanAngleB, userDataB, pointSrcB, gpstimeB};
        int[] sizes = {
                xy.encodedSize(xyB), z.encodedSize(zB), classEnc.encodedSize(classB),
                flags.encodedSize(flagsB), intensityEnc.encodedSize(intensityB),
                scanAngleEnc.encodedSize(scanAngleB), userData.encodedSize(userDataB),
                pointSrc.encodedSize(pointSrcB), gpstimeEnc.encodedSize(gpstimeB)
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(rawFirst, 0, Point14.SIZE);
        writeU32(out, chunkCount);
        for (int s : sizes) writeU32(out, s);
        for (int i = 0; i < data.length; i++) {
            if (sizes[i] > 0) out.write(data[i], 0, sizes[i]);
        }
        return out.toByteArray();
    }

    private static void writeU32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private void encodeGpsTime(Point14 point, ChannelCtx c) {
        gpstimeEnc.makeValid();
        while (true) {
            if (c.lastGpstimeDiff[c.lastGpsSeq] == 0) {
                long[] diffOut = new long[1];
                int idx = findSeq(c, point.gpstime, 0, diffOut);
                if (idx == 0) {
                    int diff = (int) diffOut[0];
                    gpstimeEnc.encodeSymbol(c.gpstime0diff, 0);
                    c.gpstime.compress(gpstimeEnc, 0, diff, 0);
                    c.lastGpstimeDiff[c.lastGpsSeq] = diff;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                } else if (idx > 0) {
                    gpstimeEnc.encodeSymbol(c.gpstime0diff, idx + 1);
                    c.lastGpsSeq = (c.lastGpsSeq + idx) & 3;
                    continue;
                } else {
                    gpstimeEnc.encodeSymbol(c.gpstime0diff, 1);
                    c.gpstime.compress(gpstimeEnc, (int) (c.lastGpstime[c.lastGpsSeq] >>> 32),
                            (int) (point.gpstime >>> 32), 8);
                    gpstimeEnc.writeInt((int) point.gpstime);
                    c.lastGpsSeq = c.nextGpsSeq = (c.nextGpsSeq + 1) & 3;
                    c.lastGpstimeDiff[c.lastGpsSeq] = 0;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                }
                c.lastGpstime[c.lastGpsSeq] = point.gpstime;
            } else {
                long diff64 = point.gpstime - c.lastGpstime[c.lastGpsSeq];
                int diff = (int) diff64;
                if (diff64 == diff) {
                    int multi = roundToInt((float) diff / (float) c.lastGpstimeDiff[c.lastGpsSeq]);
                    if (multi > 0 && multi < GPSTIME_MULTI) {
                        int tag = multi == 1 ? 1 : (multi < 10 ? 2 : 3);
                        gpstimeEnc.encodeSymbol(c.gpstimeMulti, multi);
                        c.gpstime.compress(gpstimeEnc, multi * c.lastGpstimeDiff[c.lastGpsSeq], diff, tag);
                        if (tag == 1) c.multiExtremeCounter[c.lastGpsSeq] = 0;
                    } else if (multi >= GPSTIME_MULTI) {
                        gpstimeEnc.encodeSymbol(c.gpstimeMulti, GPSTIME_MULTI);
                        c.gpstime.compress(gpstimeEnc, GPSTIME_MULTI * c.lastGpstimeDiff[c.lastGpsSeq], diff, 4);
                        if (++c.multiExtremeCounter[c.lastGpsSeq] > 3) {
                            c.multiExtremeCounter[c.lastGpsSeq] = 0;
                            c.lastGpstimeDiff[c.lastGpsSeq] = diff;
                        }
                    } else if (multi < 0 && multi > GPSTIME_MULTI_MINUS) {
                        gpstimeEnc.encodeSymbol(c.gpstimeMulti, GPSTIME_MULTI - multi);
                        c.gpstime.compress(gpstimeEnc, multi * c.lastGpstimeDiff[c.lastGpsSeq], diff, 5);
                    } else if (multi <= GPSTIME_MULTI_MINUS) {
                        gpstimeEnc.encodeSymbol(c.gpstimeMulti, GPSTIME_MULTI - GPSTIME_MULTI_MINUS);
                        c.gpstime.compress(gpstimeEnc, GPSTIME_MULTI_MINUS * c.lastGpstimeDiff[c.lastGpsSeq], diff, 6);
                        if (++c.multiExtremeCounter[c.lastGpsSeq] > 3) {
                            c.multiExtremeCounter[c.lastGpsSeq] = 0;
                            c.lastGpstimeDiff[c.lastGpsSeq] = diff;
                        }
                    } else { // multi == 0
                        gpstimeEnc.encodeSymbol(c.gpstimeMulti, 0);
                        c.gpstime.compress(gpstimeEnc, 0, diff, 7);
                        if (++c.multiExtremeCounter[c.lastGpsSeq] > 3) {
                            c.multiExtremeCounter[c.lastGpsSeq] = 0;
                            c.lastGpstimeDiff[c.lastGpsSeq] = diff;
                        }
                    }
                } else {
                    long[] diffOut = new long[1];
                    int idx = findSeq(c, point.gpstime, 1, diffOut);
                    if (idx > 0) {
                        gpstimeEnc.encodeSymbol(c.gpstimeMulti, GPSTIME_MULTI_CODE_FULL + idx);
                        c.lastGpsSeq = (c.lastGpsSeq + idx) & 3;
                        continue;
                    }
                    gpstimeEnc.encodeSymbol(c.gpstimeMulti, GPSTIME_MULTI_CODE_FULL);
                    c.gpstime.compress(gpstimeEnc, (int) (c.lastGpstime[c.lastGpsSeq] >>> 32),
                            (int) (point.gpstime >>> 32), 8);
                    gpstimeEnc.writeInt((int) point.gpstime);
                    c.nextGpsSeq = c.lastGpsSeq = (c.nextGpsSeq + 1) & 3;
                    c.lastGpstimeDiff[c.lastGpsSeq] = 0;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                }
                c.lastGpstime[c.lastGpsSeq] = point.gpstime;
            }
            break;
        }
    }

    /**
     * Finds whether {@code gpstime} fits in 32 bits relative to one of the recent GPS
     * sequences starting at offset {@code start}; returns the offset (0..3) and writes
     * the 32-bit difference into {@code diffOut}, or -1 if none fits.
     */
    private int findSeq(ChannelCtx c, long gpstime, int start, long[] diffOut) {
        for (int i = start; i < 4; i++) {
            int testseq = (c.lastGpsSeq + i) & 3;
            long diff64 = gpstime - c.lastGpstime[testseq];
            int diff = (int) diff64;
            if (diff64 == diff) {
                diffOut[0] = diff;
                return i;
            }
        }
        return -1;
    }

    /** C++ {@code std::round(float)} semantics: round half away from zero. */
    private static int roundToInt(float v) {
        return (int) (v >= 0 ? Math.floor(v + 0.5f) : Math.ceil(v - 0.5f));
    }
}
