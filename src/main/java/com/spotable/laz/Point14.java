package com.spotable.laz;

/**
 * A LAS 1.4 point-data-record-format 6 core record (30 bytes), the basis of PDRF
 * 6/7/8. Mirrors laz-perf's {@code las::point14}.
 * <p>
 * GPS time is held as the raw 64-bit IEEE-754 bit pattern (not a {@code double}) so
 * the codec's integer manipulation of it is exact and round-trips byte-for-byte; the
 * pack/unpack helpers move it to/from the little-endian record unchanged.
 */
final class Point14 {
    static final int SIZE = 30;

    int x, y, z;
    int intensity;              // u16
    int returns;                // u8: returnNum (low nibble) | numReturns (high nibble)
    int flags;                  // u8: classFlags | scannerChannel<<4 | scanDir<<6 | eof<<7
    int classification;         // u8
    int userData;               // u8
    int scanAngle;              // i16
    int pointSourceId;          // u16
    long gpstime;               // raw IEEE-754 bits of the GPS time double

    int returnNum() { return returns & 0xF; }
    void setReturnNum(int rn) { returns = (rn & 0xF) | (returns & 0xF0); }

    int numReturns() { return (returns >> 4) & 0xF; }
    void setNumReturns(int nr) { returns = ((nr & 0xF) << 4) | (returns & 0xF); }

    int classFlags() { return flags & 0xF; }
    void setClassFlags(int f) { flags = (f & 0xF) | (flags & 0xF0); }

    int scannerChannel() { return (flags >> 4) & 0x3; }
    void setScannerChannel(int c) { flags = ((c & 0x3) << 4) | (flags & ~0x30); }

    int scanDirFlag() { return (flags >> 6) & 1; }
    void setScanDirFlag(int f) { flags = ((f & 1) << 6) | (flags & 0xBF); }

    int eofFlag() { return (flags >> 7) & 1; }
    void setEofFlag(int f) { flags = ((f & 1) << 7) | (flags & 0x7F); }

    void copyFrom(Point14 o) {
        x = o.x; y = o.y; z = o.z; intensity = o.intensity; returns = o.returns;
        flags = o.flags; classification = o.classification; userData = o.userData;
        scanAngle = o.scanAngle; pointSourceId = o.pointSourceId; gpstime = o.gpstime;
    }

    /** Reads a 30-byte little-endian record from {@code b} at {@code off}. */
    void unpack(byte[] b, int off) {
        x = i32(b, off);
        y = i32(b, off + 4);
        z = i32(b, off + 8);
        intensity = u16(b, off + 12);
        returns = b[off + 14] & 0xFF;
        flags = b[off + 15] & 0xFF;
        classification = b[off + 16] & 0xFF;
        userData = b[off + 17] & 0xFF;
        scanAngle = (short) u16(b, off + 18);
        pointSourceId = u16(b, off + 20);
        gpstime = u32(b, off + 22) | (u32(b, off + 26) << 32);
    }

    /** Writes a 30-byte little-endian record into {@code b} at {@code off}. */
    void pack(byte[] b, int off) {
        i32(b, off, x);
        i32(b, off + 4, y);
        i32(b, off + 8, z);
        u16(b, off + 12, intensity);
        b[off + 14] = (byte) returns;
        b[off + 15] = (byte) flags;
        b[off + 16] = (byte) classification;
        b[off + 17] = (byte) userData;
        u16(b, off + 18, scanAngle & 0xFFFF);
        u16(b, off + 20, pointSourceId);
        i32(b, off + 22, (int) gpstime);
        i32(b, off + 26, (int) (gpstime >>> 32));
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long u32(byte[] b, int o) {
        return i32(b, o) & 0xFFFFFFFFL;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static void i32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    private static void u16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }
}
