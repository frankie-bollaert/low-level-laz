package com.spotable.laz;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Assembles a compressed LAS 1.4 (LAZ) file around already-encoded point chunks:
 * the public header, the variable length records (including the mandatory LASzip
 * VLR), the per-chunk byte blobs, and the compressed chunk table. Supports both
 * fixed-size chunks (plain LAZ) and variable-size chunks (one per node, as COPC
 * needs).
 * <p>
 * The header is derived from a template (typically the source file's 375-byte
 * header) so the scale/offset, bounding box, GPS-time encoding and GUID are carried
 * over unchanged; only the layout-dependent fields (point-data offset, VLR/EVLR
 * counts and offsets, point count, the compression bit) are rewritten.
 * <p>
 * Targets PDRF&nbsp;6 (point14, 30-byte records); the LASzip VLR item list would gain
 * RGB14/NIR14 entries for PDRF&nbsp;7/8.
 */
final class LazWriter {

    /** One variable length record: a 54-byte header plus its payload. */
    record Vlr(String userId, int recordId, String description, byte[] data) {
        int totalSize() {
            return 54 + data.length;
        }
    }

    /** One extended VLR: a 60-byte header plus its (possibly large) payload. */
    record Evlr(String userId, int recordId, String description, byte[] data) {
        long totalSize() {
            return 60L + data.length;
        }
    }

    private LazWriter() {}

    /** Builds the 40-byte LASzip VLR payload for a fixed/variable-chunked PDRF 6 file. */
    static byte[] laszipVlrDataPdrf6(int chunkSize) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u16(o, 3);            // compressor: 3 = LAYERED_CHUNKED (v3)
        u16(o, 0);            // coder: arithmetic
        o.write(3);           // version major
        o.write(4);           // version minor
        u16(o, 3);            // version revision
        u32(o, 0);            // options
        u32(o, chunkSize);    // chunk size (0xFFFFFFFF = variable)
        i64(o, -1);           // number of special EVLRs
        i64(o, -1);           // offset to special EVLRs
        u16(o, 1);            // number of items
        u16(o, 10);           // item type 10 = POINT14
        u16(o, 30);           // item size
        u16(o, 3);            // item version
        return o.toByteArray();
    }

    /**
     * Writes the whole file.
     *
     * @param headerTemplate the source 375-byte LAS 1.4 public header (copied, then patched)
     * @param vlrs           VLRs to write, in order (must include the LASzip VLR)
     * @param chunks         per-chunk encoded byte blobs, in file order
     * @param chunkCounts    point count of each chunk (parallel to {@code chunks})
     * @param evlrs          extended VLRs to append after the chunk table (may be empty)
     * @param variableChunks true to record per-chunk counts in the chunk table (COPC)
     */
    static byte[] write(byte[] headerTemplate, List<Vlr> vlrs, List<byte[]> chunks,
            int[] chunkCounts, List<Evlr> evlrs, boolean variableChunks) {
        if (headerTemplate.length < 375) {
            throw new IllegalArgumentException("header template must be a LAS 1.4 header (>=375 bytes)");
        }
        byte[] header = new byte[375];
        System.arraycopy(headerTemplate, 0, header, 0, 375);

        int vlrTotal = 0;
        for (Vlr v : vlrs) vlrTotal += v.totalSize();
        long pointOffset = 375 + vlrTotal;
        long pointCount = 0;
        for (int c : chunkCounts) pointCount += c & 0xFFFFFFFFL;

        // Point-data section: 8-byte chunk-table offset, then the chunk blobs.
        ByteArrayOutputStream points = new ByteArrayOutputStream();
        long chunkBytes = 0;
        for (byte[] c : chunks) chunkBytes += c.length;
        long chunkTableOffset = pointOffset + 8 + chunkBytes;

        i64(points, chunkTableOffset);
        for (byte[] c : chunks) points.writeBytes(c);

        byte[] chunkTable = buildChunkTable(chunks, chunkCounts, variableChunks);
        long evlrOffset = chunkTableOffset + chunkTable.length;

        // --- patch the header ---
        int rawFormat = header[104] & 0x3F;
        header[104] = (byte) (rawFormat | 0x80);             // set compression bit
        header[105] = 30; header[106] = 0;                   // point record length: 30-byte PDRF 6 (extra bytes dropped)
        u32(header, 96, (int) pointOffset);
        u32(header, 100, vlrs.size());
        // LAS 1.4: legacy counts are zero for PDRF > 5; the extended fields carry the count.
        u32(header, 107, 0);
        for (int i = 0; i < 20; i++) header[111 + i] = 0;     // legacy points-by-return
        u64(header, 247, pointCount);
        i64(header, 227, 0);                                  // start of waveform data
        if (evlrs.isEmpty()) {
            i64(header, 235, 0);
            u32(header, 243, 0);
        } else {
            i64(header, 235, evlrOffset);
            u32(header, 243, evlrs.size());
        }

        // --- emit the file: header, then each VLR header immediately followed by its data ---
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(header);
        for (Vlr v : vlrs) {
            writeVlrHeader(file, v.userId(), v.recordId(), v.description(), v.data().length);
            file.writeBytes(v.data());
        }
        file.writeBytes(points.toByteArray());
        file.writeBytes(chunkTable);
        for (Evlr e : evlrs) {
            writeEvlrHeader(file, e.userId(), e.recordId(), e.description(), e.data().length);
            file.writeBytes(e.data());
        }
        return file.toByteArray();
    }

    /** Arithmetic-codes the chunk table: {@code [u32 version=0][u32 count][coded sizes]}. */
    static byte[] buildChunkTable(List<byte[]> chunks, int[] chunkCounts, boolean variable) {
        ArithmeticEncoder enc = new ArithmeticEncoder();
        IntegerCompressor ic = new IntegerCompressor(32, 2);
        ic.init();
        int countPred = 0, offsetPred = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (variable) {
                int count = chunkCounts[i];
                ic.compress(enc, countPred, count, 0);
                countPred = count;
            }
            int size = chunks.get(i).length;
            ic.compress(enc, offsetPred, size, 1);
            offsetPred = size;
        }
        byte[] coded = enc.done();

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        u32(o, 0);                  // chunk table version
        u32(o, chunks.size());      // number of chunks
        o.writeBytes(coded);
        return o.toByteArray();
    }

    private static void writeVlrHeader(ByteArrayOutputStream o, String userId, int recordId,
            String description, int dataLen) {
        u16(o, 0);                          // reserved
        fixedString(o, userId, 16);
        u16(o, recordId);
        u16(o, dataLen);                    // record length after header
        fixedString(o, description, 32);
    }

    private static void writeEvlrHeader(ByteArrayOutputStream o, String userId, int recordId,
            String description, long dataLen) {
        u16(o, 0);                          // reserved
        fixedString(o, userId, 16);
        u16(o, recordId);
        i64(o, dataLen);                    // record length after header (u64)
        fixedString(o, description, 32);
    }

    private static void fixedString(ByteArrayOutputStream o, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < len; i++) o.write(i < b.length ? b[i] : 0);
    }

    // ---- little-endian writers (stream) ----
    private static void u16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    private static void i64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) o.write((int) ((v >>> (8 * i)) & 0xFF));
    }

    // ---- little-endian writers (into a byte[]) ----
    private static void u32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void u64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }

    private static void i64(byte[] b, int o, long v) {
        u64(b, o, v);
    }
}
