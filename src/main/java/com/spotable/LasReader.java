package com.spotable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A reader for uncompressed LAS point cloud files (LAS 1.0 - 1.4).
 * Loads the entire file into memory and exposes points as objects.
 */
public class LasReader {

    // ---- Public Header Block ----
    public static class Header {
        public String fileSignature;        // "LASF"
        public int fileSourceId;
        public int globalEncoding;
        public byte versionMajor;
        public byte versionMinor;
        public String systemIdentifier;
        public String generatingSoftware;
        public int fileCreationDayOfYear;
        public int fileCreationYear;
        public int headerSize;
        public long offsetToPointData;
        public long numberOfVlrs;
        public short pointDataRecordFormat;
        public int pointDataRecordLength;
        public long legacyNumberOfPointRecords;
        public long[] legacyNumberOfPointsByReturn = new long[5];
        public double xScale, yScale, zScale;
        public double xOffset, yOffset, zOffset;
        public double maxX, minX, maxY, minY, maxZ, minZ;

        // LAS 1.3+
        public long startOfWaveformDataPacket;
        // LAS 1.4+
        public long startOfFirstExtendedVlr;
        public long numberOfExtendedVlrs;
        public long numberOfPointRecords;          // 64-bit, authoritative in 1.4
        public long[] numberOfPointsByReturn = new long[15];

        /** Returns the real point count regardless of LAS version. */
        public long pointCount() {
            return (versionMajor == 1 && versionMinor >= 4 && numberOfPointRecords > 0)
                    ? numberOfPointRecords
                    : legacyNumberOfPointRecords;
        }
    }

    // ---- Variable Length Record ----
    public static class Vlr {
        public int reserved;
        public String userId;
        public int recordId;
        public int recordLengthAfterHeader;
        public String description;
        public byte[] data;
    }

    // ---- A single decoded point ----
    public static class Point {
        public double x, y, z;          // real-world coordinates (scaled + offset)
        public int rawX, rawY, rawZ;    // stored integers
        public int intensity;
        public int returnNumber;
        public int numberOfReturns;
        public int scanDirectionFlag;
        public int edgeOfFlightLine;
        public int classification;
        public boolean synthetic, keyPoint, withheld, overlap; // overlap is 1.4-only
        public double scanAngle;        // degrees
        public int userData;
        public int pointSourceId;
        public double gpsTime;          // present for some formats
        public int red, green, blue;    // present for color formats
        public int nir;                 // present for format 8/10
    }

    public final Header header = new Header();
    public final List<Vlr> vlrs = new ArrayList<>();
    public Point[] points;

    public static LasReader read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        LasReader r = new LasReader();
        r.parseHeader(buf);
        r.parseVlrs(buf);
        r.parsePoints(buf);
        return r;
    }

    private void parseHeader(ByteBuffer b) {
        b.position(0);
        header.fileSignature = readString(b, 4);
        if (!"LASF".equals(header.fileSignature)) {
            throw new IllegalArgumentException("Not a LAS file (bad signature)");
        }
        header.fileSourceId = u16(b.getShort());
        header.globalEncoding = u16(b.getShort());
        // Project ID GUID (16 bytes) — skip
        b.position(b.position() + 16);
        header.versionMajor = b.get();
        header.versionMinor = b.get();
        header.systemIdentifier = readString(b, 32);
        header.generatingSoftware = readString(b, 32);
        header.fileCreationDayOfYear = u16(b.getShort());
        header.fileCreationYear = u16(b.getShort());
        header.headerSize = u16(b.getShort());
        header.offsetToPointData = u32(b.getInt());
        header.numberOfVlrs = u32(b.getInt());
        header.pointDataRecordFormat = (short) (b.get() & 0xFF);
        header.pointDataRecordLength = u16(b.getShort());
        header.legacyNumberOfPointRecords = u32(b.getInt());
        for (int i = 0; i < 5; i++) header.legacyNumberOfPointsByReturn[i] = u32(b.getInt());
        header.xScale = b.getDouble();
        header.yScale = b.getDouble();
        header.zScale = b.getDouble();
        header.xOffset = b.getDouble();
        header.yOffset = b.getDouble();
        header.zOffset = b.getDouble();
        header.maxX = b.getDouble();
        header.minX = b.getDouble();
        header.maxY = b.getDouble();
        header.minY = b.getDouble();
        header.maxZ = b.getDouble();
        header.minZ = b.getDouble();

        // LAS 1.3+ : waveform data packet record start
        if (versionAtLeast(1, 3)) {
            header.startOfWaveformDataPacket = b.getLong();
        }
        // LAS 1.4+ : extended VLRs and 64-bit point counts
        if (versionAtLeast(1, 4)) {
            header.startOfFirstExtendedVlr = b.getLong();
            header.numberOfExtendedVlrs = u32(b.getInt());
            header.numberOfPointRecords = b.getLong();
            for (int i = 0; i < 15; i++) header.numberOfPointsByReturn[i] = b.getLong();
        }
    }

    private void parseVlrs(ByteBuffer b) {
        // VLRs begin immediately after the header.
        b.position(header.headerSize);
        for (long i = 0; i < header.numberOfVlrs; i++) {
            Vlr v = new Vlr();
            v.reserved = u16(b.getShort());
            v.userId = readString(b, 16);
            v.recordId = u16(b.getShort());
            v.recordLengthAfterHeader = u16(b.getShort());
            v.description = readString(b, 32);
            v.data = new byte[v.recordLengthAfterHeader];
            b.get(v.data);
            vlrs.add(v);
        }
    }

    private void parsePoints(ByteBuffer b) {
        long count = header.pointCount();
        int fmt = header.pointDataRecordFormat;
        int recLen = header.pointDataRecordLength;
        points = new Point[(int) count];

        for (int i = 0; i < count; i++) {
            long recStart = header.offsetToPointData + (long) i * recLen;
            b.position((int) recStart);
            Point p = new Point();

            p.rawX = b.getInt();
            p.rawY = b.getInt();
            p.rawZ = b.getInt();
            p.x = p.rawX * header.xScale + header.xOffset;
            p.y = p.rawY * header.yScale + header.yOffset;
            p.z = p.rawZ * header.zScale + header.zOffset;
            p.intensity = u16(b.getShort());

            if (fmt <= 5) {
                // ---- Legacy formats 0-5 ----
                int bits = b.get() & 0xFF;
                p.returnNumber   = bits & 0b0000_0111;
                p.numberOfReturns = (bits >> 3) & 0b0000_0111;
                p.scanDirectionFlag = (bits >> 6) & 0x1;
                p.edgeOfFlightLine  = (bits >> 7) & 0x1;

                int classByte = b.get() & 0xFF;
                p.classification = classByte & 0b0001_1111;
                p.synthetic = (classByte & 0b0010_0000) != 0;
                p.keyPoint  = (classByte & 0b0100_0000) != 0;
                p.withheld  = (classByte & 0b1000_0000) != 0;

                p.scanAngle = (byte) b.get();          // signed, 1 degree per unit
                p.userData = b.get() & 0xFF;
                p.pointSourceId = u16(b.getShort());

                if (fmt == 1 || fmt == 3 || fmt == 4 || fmt == 5) {
                    p.gpsTime = b.getDouble();
                }
                if (fmt == 2 || fmt == 3 || fmt == 5) {
                    p.red = u16(b.getShort());
                    p.green = u16(b.getShort());
                    p.blue = u16(b.getShort());
                }
                // (formats 4/5 also carry wave-packet fields; ignored here)
            } else {
                // ---- LAS 1.4 formats 6-10 ----
                int bits1 = b.get() & 0xFF;
                p.returnNumber    = bits1 & 0b0000_1111;
                p.numberOfReturns = (bits1 >> 4) & 0b0000_1111;

                int bits2 = b.get() & 0xFF;
                p.synthetic = (bits2 & 0b0000_0001) != 0;
                p.keyPoint  = (bits2 & 0b0000_0010) != 0;
                p.withheld  = (bits2 & 0b0000_0100) != 0;
                p.overlap   = (bits2 & 0b0000_1000) != 0;
                p.scanDirectionFlag = (bits2 >> 6) & 0x1;
                p.edgeOfFlightLine  = (bits2 >> 7) & 0x1;

                p.classification = b.get() & 0xFF;     // full byte in 1.4
                p.userData = b.get() & 0xFF;
                p.scanAngle = ((short) (b.getShort() & 0xFFFF)) * 0.006; // 0.006 deg/unit
                p.pointSourceId = u16(b.getShort());
                p.gpsTime = b.getDouble();             // always present in 6-10

                if (fmt == 7 || fmt == 8 || fmt == 10) {
                    p.red = u16(b.getShort());
                    p.green = u16(b.getShort());
                    p.blue = u16(b.getShort());
                }
                if (fmt == 8 || fmt == 10) {
                    p.nir = u16(b.getShort());
                }
                // (formats 9/10 also carry wave-packet fields; ignored here)
            }
            points[i] = p;
        }
    }

    // ---- helpers ----
    private boolean versionAtLeast(int major, int minor) {
        return header.versionMajor > major
                || (header.versionMajor == major && header.versionMinor >= minor);
    }

    private static String readString(ByteBuffer b, int len) {
        byte[] raw = new byte[len];
        b.get(raw);
        int end = 0;
        while (end < len && raw[end] != 0) end++;   // stop at null terminator
        return new String(raw, 0, end, StandardCharsets.US_ASCII).trim();
    }

    // Unsigned widening helpers — Java has no unsigned primitives.
    private static int u16(short s) { return s & 0xFFFF; }
    private static long u32(int i)  { return i & 0xFFFFFFFFL; }

    // ---- demo ----
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: java las.LasReader <file.las>");
            return;
        }
        LasReader r = LasReader.read(Path.of(args[0]));
        System.out.printf("LAS %d.%d, format %d, %d points%n",
                r.header.versionMajor, r.header.versionMinor,
                r.header.pointDataRecordFormat, r.header.pointCount());
        System.out.printf("Bounds X[%.2f, %.2f] Y[%.2f, %.2f] Z[%.2f, %.2f]%n",
                r.header.minX, r.header.maxX,
                r.header.minY, r.header.maxY,
                r.header.minZ, r.header.maxZ);
        System.out.printf("VLRs: %d%n", r.vlrs.size());
        if (r.points.length > 0) {
            Point p = r.points[0];
            System.out.printf("First point: (%.3f, %.3f, %.3f) class=%d intensity=%d%n",
                    p.x, p.y, p.z, p.classification, p.intensity);
        }
    }
}