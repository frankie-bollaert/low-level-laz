package com.spotable.laz;

import java.nio.charset.StandardCharsets;

/**
 * The fields of a LAS public header block (plus the LASzip VLR essentials) that the
 * COPC codec needs: point layout, counts, the scale/offset transform, and — for a
 * compressed file — the LASzip compressor version and chunk size.
 * <p>
 * Only enough of the header and VLR block is parsed to read or write PDRF&nbsp;6/7/8
 * point data; see {@link LazBinaryReader} for the CRS/bbox-oriented header reader.
 */
final class LasHeader {
    static final int VARIABLE_CHUNK_SIZE = 0xFFFFFFFF;

    int versionMinor;
    int headerSize;
    long pointOffset;
    long vlrCount;
    int pointFormat;            // masked to 0..8 (compression bits removed)
    boolean compressed;
    int pointRecordLength;
    long pointCount;
    double scaleX, scaleY, scaleZ;
    double offsetX, offsetY, offsetZ;

    // From the LASzip VLR (record_id 22204); valid only when compressed.
    int lazCompressor;          // 2 = v2 (PDRF 0-5), 3 = v3 (PDRF 6-10)
    int chunkSize;

    boolean variableChunks() {
        return chunkSize == VARIABLE_CHUNK_SIZE;
    }

    static LasHeader parse(byte[] b) {
        if (b.length < 227 || b[0] != 'L' || b[1] != 'A' || b[2] != 'S' || b[3] != 'F') {
            throw new IllegalArgumentException("not a LAS/LAZ file");
        }
        LasHeader h = new LasHeader();
        h.versionMinor = b[25] & 0xFF;
        h.headerSize = u16(b, 94);
        h.pointOffset = u32(b, 96);
        h.vlrCount = u32(b, 100);
        int rawFormat = b[104] & 0xFF;
        h.compressed = (rawFormat & 0x80) != 0 || (rawFormat & 0x40) != 0;
        h.pointFormat = rawFormat & 0x3F;
        h.pointRecordLength = u16(b, 105);
        h.pointCount = u32(b, 107);
        if (h.versionMinor >= 4 && h.headerSize >= 247 + 8) {
            long extended = u64(b, 247);
            if (extended > 0) h.pointCount = extended;
        }
        h.scaleX = dbl(b, 131);
        h.scaleY = dbl(b, 139);
        h.scaleZ = dbl(b, 147);
        h.offsetX = dbl(b, 155);
        h.offsetY = dbl(b, 163);
        h.offsetZ = dbl(b, 171);
        if (h.compressed) parseLazVlr(b, h);
        return h;
    }

    private static void parseLazVlr(byte[] b, LasHeader h) {
        int pos = h.headerSize;
        int end = (int) Math.min(h.pointOffset, b.length);
        for (long i = 0; i < h.vlrCount && pos + 54 <= end; i++) {
            String userId = cstr(b, pos + 2, 16);
            int recordId = u16(b, pos + 18);
            int recordLen = u16(b, pos + 20);
            int data = pos + 54;
            if ("laszip encoded".equals(userId) && recordId == 22204) {
                h.lazCompressor = u16(b, data);
                // data layout: compressor(2) coder(2) ver(4) options(4) chunk_size(4) ...
                h.chunkSize = (int) u32(b, data + 12);
                return;
            }
            pos = data + recordLen;
        }
        throw new IllegalArgumentException("compressed file missing LASzip VLR (22204)");
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }

    private static double dbl(byte[] b, int o) {
        return Double.longBitsToDouble(u64(b, o));
    }

    private static String cstr(byte[] b, int off, int len) {
        int e = off, max = off + len;
        while (e < max && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.US_ASCII);
    }
}
