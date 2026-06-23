package com.spotable.laz;

import java.util.Arrays;

/**
 * Decompresses one LASzip v3 "layered" chunk of PDRF&nbsp;6 records (the point14
 * field). A faithful port of laz-perf's {@code detail::Point14Decompressor} together
 * with the PDRF&nbsp;6 chunk framing from {@code point_decompressor_6}.
 * <p>
 * Chunk byte layout (read sequentially from the supplied cursor), where
 * {@code E = extraByteCount} is the point record length beyond the 30-byte core:
 * <pre>
 *   [raw first point: 30 + E bytes]
 *   [u32 point count]
 *   [9 × u32 POINT14 layer byte-sizes][E × u32 extra-byte layer byte-sizes]
 *   [POINT14 layer bytes: xy, z, classification, flags, intensity, scan-angle,
 *                 user-data, point-source-id, gps-time]   (zero-size layers omitted)
 *   [E extra-byte layer blocks]
 * </pre>
 * This matches LASzip v3: the {@code POINT14_v3} and {@code BYTE14_v3} readers each
 * contribute their layer sizes (then blocks) in turn, and {@code BYTE14_v3} carries one
 * sub-layer per extra byte. The first point is stored verbatim; every later point is
 * reconstructed from the nine POINT14 sub-streams. Layers whose size is zero were never
 * "made valid" by the encoder (no point in the chunk changed that field), so the
 * corresponding value is carried over from the previous point.
 * <p>
 * Only the 30-byte PDRF&nbsp;6 core is reconstructed: the extra-byte layers are skipped,
 * not decoded, so any per-point extra bytes are dropped. The RGB/NIR layers of
 * PDRF&nbsp;7/8 are not handled here.
 */
final class Point14Decompressor {

    private static final int GPSTIME_MULTI = 500;
    private static final int GPSTIME_MULTI_MINUS = -10;
    private static final int GPSTIME_MULTI_CODE_FULL = 511;

    // Context maps keyed by [numReturns][returnNum] live in Point14Tables (shared with the encoder).
    private static final int[][] RETURN_MAP_6 = Point14Tables.RETURN_MAP_6;
    private static final int[][] RETURN_LEVEL_8 = Point14Tables.RETURN_LEVEL_8;

    /** Per-scanner-channel context: adaptive models, integer predictors and last-point state. */
    private static final class ChannelCtx {
        final ArithmeticModel[] changedValues = models(8, 128);
        final ArithmeticModel scannerChannel = new ArithmeticModel(3, false);
        final ArithmeticModel rnGpsSame = new ArithmeticModel(13, false);
        final ArithmeticModel[] nr = models(16, 16);
        final ArithmeticModel[] rn = models(16, 16);
        final ArithmeticModel[] classM = models(64, 256);
        final ArithmeticModel[] flag = models(64, 64);
        final ArithmeticModel[] userDataM = models(64, 256);
        final ArithmeticModel gpstimeMulti = new ArithmeticModel(515, false);
        final ArithmeticModel gpstime0diff = new ArithmeticModel(5, false);

        final IntegerDecompressor dx = new IntegerDecompressor(32, 2);
        final IntegerDecompressor dy = new IntegerDecompressor(32, 22);
        final IntegerDecompressor zc = new IntegerDecompressor(32, 20);
        final IntegerDecompressor intensity = new IntegerDecompressor(16, 4);
        final IntegerDecompressor scanAngle = new IntegerDecompressor(16, 2);
        final IntegerDecompressor pointSourceId = new IntegerDecompressor(16, 1);
        final IntegerDecompressor gpstime = new IntegerDecompressor(32, 9);

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
            for (int i = 0; i < n; i++) a[i] = new ArithmeticModel(symbols, false);
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

    private final ByteCursor main;

    /** Point record length beyond the 30-byte core (the BYTE14 extra-byte layers). */
    private final int extraByteCount;

    // The nine layered sub-streams (null == empty/invalid layer; value carried over).
    private ArithmeticDecoder xy, z, classDec, flags, intensityDec, scanAngleDec, userData, pointSrc, gpstimeDec;
    int chunkCount;

    Point14Decompressor(ByteCursor main, int extraByteCount) {
        this.main = main;
        this.extraByteCount = extraByteCount;
    }

    /** Decodes the next point as a 30-byte PDRF 6 record into {@code out} at {@code off}. */
    void decompress(byte[] out, int off) {
        if (lastChannel == -1) {
            // First point of the chunk: stored raw (30-byte core + extra bytes), then framing.
            main.getBytes(out, off, Point14.SIZE);
            main.skip(extraByteCount);
            Point14 p = new Point14();
            p.unpack(out, off);
            int sc = p.scannerChannel();
            ChannelCtx c = ctx[sc];
            c.last.copyFrom(p);
            c.haveLast = true;
            c.lastGpstime[0] = p.gpstime;
            lastChannel = sc;
            Arrays.fill(c.lastZ, p.z);
            Arrays.fill(c.lastIntensity, p.intensity);
            readFraming();
            return;
        }

        ChannelCtx prev = ctx[lastChannel];
        int changeStream =
                (prev.last.returnNum() == 1 ? 1 : 0)
                | ((prev.last.returnNum() >= prev.last.numReturns() ? 1 : 0) << 1)
                | ((prev.gpsTimeChange ? 1 : 0) << 2);

        int changed = xy.decodeSymbol(prev.changedValues[changeStream]);
        boolean scannerChannelChanged = ((changed >> 6) & 1) != 0;
        boolean pointSourceChanged = ((changed >> 5) & 1) != 0;
        boolean gpsTimeChanged = ((changed >> 4) & 1) != 0;
        boolean scanAngleChanged = ((changed >> 3) & 1) != 0;
        boolean nrChanges = ((changed >> 2) & 1) != 0;
        boolean rnMinus = ((changed >> 1) & 1) != 0;
        boolean rnPlus = (changed & 1) != 0;
        boolean rnIncrements = rnPlus && !rnMinus;
        boolean rnDecrements = rnMinus && !rnPlus;
        boolean rnMiscChange = rnPlus && rnMinus;

        int sc = prev.last.scannerChannel();
        if (scannerChannelChanged) {
            int diff = xy.decodeSymbol(prev.scannerChannel);
            sc = (sc + diff + 1) % 4;
            lastChannel = sc;
        }

        ChannelCtx c = ctx[sc];
        if (!c.haveLast) {
            c.haveLast = true;
            c.last.copyFrom(prev.last);
            Arrays.fill(c.lastZ, prev.last.z);
            Arrays.fill(c.lastIntensity, prev.last.intensity);
            c.lastGpstime[0] = prev.last.gpstime;
        }
        c.last.setScannerChannel(sc);

        int n = c.last.numReturns();
        int r = c.last.returnNum();
        if (nrChanges) n = xy.decodeSymbol(c.nr[c.last.numReturns()]);
        c.last.setNumReturns(n);

        if (rnIncrements) r = (r + 1) % 16;
        else if (rnDecrements) r = (r + 15) % 16;
        else if (rnMiscChange) {
            if (gpsTimeChanged) r = xy.decodeSymbol(c.rn[r]);
            else r = (r + xy.decodeSymbol(c.rnGpsSame) + 2) % 16;
        }
        c.last.setReturnNum(r);

        int rmCtx = (RETURN_MAP_6[n][r] << 1) | (gpsTimeChanged ? 1 : 0);

        // X
        int median = c.xMedian[rmCtx].get();
        int diff = c.dx.decompress(xy, median, n == 1 ? 1 : 0);
        c.last.x += diff;
        c.xMedian[rmCtx].add(diff);

        // Y
        int kbits = Math.min(c.dx.k, 20) & ~1;
        median = c.yMedian[rmCtx].get();
        diff = c.dy.decompress(xy, median, (n == 1 ? 1 : 0) | kbits);
        c.last.y += diff;
        c.yMedian[rmCtx].add(diff);

        // Z
        if (z != null) {
            kbits = (c.dx.k + c.dy.k) / 2;
            kbits = Math.min(kbits, 18) & ~1;
            int zctx = RETURN_LEVEL_8[n][r];
            int zv = c.zc.decompress(z, c.lastZ[zctx], (n == 1 ? 1 : 0) | kbits);
            c.last.z = zv;
            c.lastZ[zctx] = zv;
        }

        // Classification
        if (classDec != null) {
            int cctx = ((r == 1 && r >= n) ? 1 : 0) | ((c.last.classification & 0x1F) << 1);
            c.last.classification = classDec.decodeSymbol(c.classM[cctx]);
        }

        // Flags
        if (flags != null) {
            int merged = c.last.classFlags() | (c.last.scanDirFlag() << 4) | (c.last.eofFlag() << 5);
            int f = flags.decodeSymbol(c.flag[merged]);
            c.last.setEofFlag((f >> 5) & 1);
            c.last.setScanDirFlag((f >> 4) & 1);
            c.last.setClassFlags(f & 0xF);
        }

        // Intensity
        if (intensityDec != null) {
            int ictx = (gpsTimeChanged ? 1 : 0) | ((r >= n ? 1 : 0) << 1) | ((r == 1 ? 1 : 0) << 2);
            int iv = c.intensity.decompress(intensityDec, c.lastIntensity[ictx], ictx >> 1) & 0xFFFF;
            c.lastIntensity[ictx] = iv;
            c.last.intensity = iv;
        }

        // Scan angle
        if (scanAngleChanged) {
            c.last.scanAngle = (short) c.scanAngle.decompress(scanAngleDec, c.last.scanAngle, gpsTimeChanged ? 1 : 0);
        }

        // User data
        if (userData != null) {
            int uctx = (c.last.userData & 0xFF) / 4;
            c.last.userData = userData.decodeSymbol(c.userDataM[uctx]);
        }

        // Point source id
        if (pointSourceChanged) {
            c.last.pointSourceId = c.pointSourceId.decompress(pointSrc, c.last.pointSourceId, 0) & 0xFFFF;
        }

        if (gpsTimeChanged) decodeGpsTime(c);
        c.gpsTimeChange = gpsTimeChanged;

        c.last.pack(out, off);
    }

    private void readFraming() {
        chunkCount = main.u32le();
        int[] sizes = new int[9];
        for (int i = 0; i < 9; i++) sizes[i] = main.u32le();
        // BYTE14 extra-byte layers contribute one size each, after the nine POINT14 sizes.
        for (int i = 0; i < extraByteCount; i++) main.u32le();
        xy = sub(sizes[0]);
        z = sub(sizes[1]);
        classDec = sub(sizes[2]);
        flags = sub(sizes[3]);
        intensityDec = sub(sizes[4]);
        scanAngleDec = sub(sizes[5]);
        userData = sub(sizes[6]);
        pointSrc = sub(sizes[7]);
        gpstimeDec = sub(sizes[8]);
    }

    /** Carves the next {@code size} bytes off the main cursor as an arithmetic sub-stream. */
    private ArithmeticDecoder sub(int size) {
        if (size == 0) return null;
        byte[] slice = main.take(size);
        return new ArithmeticDecoder(slice, 0);
    }

    private void decodeGpsTime(ChannelCtx c) {
        while (true) {
            if (c.lastGpstimeDiff[c.lastGpsSeq] == 0) {
                int multi = gpstimeDec.decodeSymbol(c.gpstime0diff);
                if (multi == 0) {
                    int sym = c.gpstime.decompress(gpstimeDec, 0, 0);
                    c.lastGpstimeDiff[c.lastGpsSeq] = sym;
                    c.lastGpstime[c.lastGpsSeq] += sym;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                } else if (multi == 1) {
                    c.nextGpsSeq = (c.nextGpsSeq + 1) & 3;
                    long lasttime = c.lastGpstime[c.lastGpsSeq];
                    int sym = c.gpstime.decompress(gpstimeDec, (int) (lasttime >>> 32), 8);
                    long lo = gpstimeDec.readInt() & 0xFFFFFFFFL;
                    c.lastGpstime[c.nextGpsSeq] = ((long) sym << 32) | lo;
                    c.lastGpsSeq = c.nextGpsSeq;
                    c.lastGpstimeDiff[c.lastGpsSeq] = 0;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                } else {
                    c.lastGpsSeq = (c.lastGpsSeq + multi - 1) & 3;
                    continue;
                }
            } else {
                int multi = gpstimeDec.decodeSymbol(c.gpstimeMulti);
                if (multi == 1) {
                    int sym = c.gpstime.decompress(gpstimeDec, c.lastGpstimeDiff[c.lastGpsSeq], 1);
                    c.lastGpstime[c.lastGpsSeq] += sym;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                } else if (multi < GPSTIME_MULTI_CODE_FULL) {
                    int gpstimeDiff;
                    if (multi == 0) {
                        gpstimeDiff = c.gpstime.decompress(gpstimeDec, 0, 7);
                        if (++c.multiExtremeCounter[c.lastGpsSeq] > 3) {
                            c.multiExtremeCounter[c.lastGpsSeq] = 0;
                            c.lastGpstimeDiff[c.lastGpsSeq] = gpstimeDiff;
                        }
                    } else if (multi < GPSTIME_MULTI) {
                        int tag = multi < 10 ? 2 : 3;
                        gpstimeDiff = c.gpstime.decompress(gpstimeDec, multi * c.lastGpstimeDiff[c.lastGpsSeq], tag);
                    } else if (multi == GPSTIME_MULTI) {
                        gpstimeDiff = c.gpstime.decompress(gpstimeDec, GPSTIME_MULTI * c.lastGpstimeDiff[c.lastGpsSeq], 4);
                        if (++c.multiExtremeCounter[c.lastGpsSeq] > 3) {
                            c.multiExtremeCounter[c.lastGpsSeq] = 0;
                            c.lastGpstimeDiff[c.lastGpsSeq] = gpstimeDiff;
                        }
                    } else {
                        int m = GPSTIME_MULTI - multi;
                        if (m > GPSTIME_MULTI_MINUS) {
                            gpstimeDiff = c.gpstime.decompress(gpstimeDec, m * c.lastGpstimeDiff[c.lastGpsSeq], 5);
                        } else {
                            gpstimeDiff = c.gpstime.decompress(gpstimeDec, GPSTIME_MULTI_MINUS * c.lastGpstimeDiff[c.lastGpsSeq], 6);
                            if (++c.multiExtremeCounter[c.lastGpsSeq] > 3) {
                                c.multiExtremeCounter[c.lastGpsSeq] = 0;
                                c.lastGpstimeDiff[c.lastGpsSeq] = gpstimeDiff;
                            }
                        }
                    }
                    c.lastGpstime[c.lastGpsSeq] += gpstimeDiff;
                } else if (multi == GPSTIME_MULTI_CODE_FULL) {
                    c.nextGpsSeq = (c.nextGpsSeq + 1) & 3;
                    long lasttime = c.lastGpstime[c.lastGpsSeq];
                    int sym = c.gpstime.decompress(gpstimeDec, (int) (lasttime >>> 32), 8);
                    long lo = gpstimeDec.readInt() & 0xFFFFFFFFL;
                    c.lastGpstime[c.nextGpsSeq] = ((long) sym << 32) | lo;
                    c.lastGpsSeq = c.nextGpsSeq;
                    c.lastGpstimeDiff[c.lastGpsSeq] = 0;
                    c.multiExtremeCounter[c.lastGpsSeq] = 0;
                } else {
                    c.lastGpsSeq = (c.lastGpsSeq + multi - GPSTIME_MULTI_CODE_FULL) & 3;
                    continue;
                }
            }
            break;
        }
        c.last.gpstime = c.lastGpstime[c.lastGpsSeq];
    }
}
