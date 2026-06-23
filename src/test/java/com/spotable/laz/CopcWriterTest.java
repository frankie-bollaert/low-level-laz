package com.spotable.laz;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

/**
 * Builds a COPC from an uncompressed PDRF&nbsp;6 fixture, then reads it back through the
 * COPC hierarchy with our own decoder and checks the full point set is preserved
 * (order changes because points are clustered by octree node, so the comparison is on
 * the sorted multiset of records). The file is written to {@code /tmp/mine.copc.laz}
 * for independent verification with PDAL.
 */
public class CopcWriterTest {

    @Test
    public void buildsReadableCopc() throws Exception {
        Path src = Path.of("/tmp/small.las");
        assumeTrue("fixture missing: pdal translate red-rocks.laz /tmp/small.las "
                + "decimation --filters.decimation.step=20 --writers.las.dataformat_id=6 "
                + "--writers.las.minor_version=4", Files.exists(src));

        byte[] in = Files.readAllBytes(src);
        LasHeader ih = LasHeader.parse(in);
        int count = (int) ih.pointCount;
        int base = (int) ih.pointOffset;

        // Pack just the 30-byte records (the fixture is already 30-byte PDRF 6).
        byte[] records = new byte[count * Point14.SIZE];
        System.arraycopy(in, base, records, 0, count * Point14.SIZE);

        byte[] copc = CopcWriter.build(in, records, count, /* wkt */ null);
        Path out = Path.of("/tmp/mine.copc.laz");
        Files.write(out, copc);

        // Parse back: must be a compressed, variable-chunk PDRF 6 file with a COPC info VLR.
        LasHeader h = LasHeader.parse(copc);
        assertEquals(6, h.pointFormat);
        assertTrue(h.compressed);
        assertEquals(3, h.lazCompressor);
        assertTrue("COPC uses variable chunks", h.variableChunks());
        assertEquals(count, (int) h.pointCount);

        CopcInfo info = readCopcInfo(copc);
        assertTrue("root hierarchy in file", info.rootHierOffset + info.rootHierSize <= copc.length);
        int nodeCount = (int) (info.rootHierSize / 32);
        assertTrue("at least one node", nodeCount >= 1);

        // Walk the hierarchy page, decode each node's chunk, accumulate all records.
        List<byte[]> decoded = new ArrayList<>(count);
        long total = 0;
        for (int i = 0; i < nodeCount; i++) {
            int e = (int) info.rootHierOffset + i * 32;
            long offset = i64(copc, e + 16);
            int pointCount = i32(copc, e + 28);
            assertTrue("node points > 0", pointCount > 0);
            Point14Decompressor d = new Point14Decompressor(new ByteCursor(copc, (int) offset), 0);
            for (int j = 0; j < pointCount; j++) {
                byte[] rec = new byte[Point14.SIZE];
                d.decompress(rec, 0);
                decoded.add(rec);
            }
            total += pointCount;
        }
        assertEquals("all points present across nodes", count, total);

        // Compare sorted multisets of records (order differs due to clustering).
        List<byte[]> original = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            original.add(Arrays.copyOfRange(records, i * Point14.SIZE, (i + 1) * Point14.SIZE));
        }
        Comparator<byte[]> cmp = Arrays::compare;
        original.sort(cmp);
        decoded.sort(cmp);
        for (int i = 0; i < count; i++) {
            assertArrayEquals("record " + i + " (sorted)", original.get(i), decoded.get(i));
        }
        System.out.println("[CopcWriterTest] wrote " + out + " (" + copc.length + " bytes), "
                + count + " points across " + nodeCount + " octree nodes, point set preserved");
    }

    private record CopcInfo(long rootHierOffset, long rootHierSize) {}

    private static CopcInfo readCopcInfo(byte[] file) {
        LasHeader h = LasHeader.parse(file);
        int pos = h.headerSize;
        for (long i = 0; i < h.vlrCount; i++) {
            String uid = cstr(file, pos + 2, 16);
            int rid = i16(file, pos + 18);
            int len = i16(file, pos + 20);
            int data = pos + 54;
            if (uid.equals("copc") && rid == 1) {
                return new CopcInfo(i64(file, data + 40), i64(file, data + 48));
            }
            pos = data + len;
        }
        throw new AssertionError("no COPC info VLR");
    }

    private static int i16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long i64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[o + i] & 0xFFL) << (8 * i);
        return v;
    }

    private static String cstr(byte[] b, int off, int len) {
        int e = off, max = off + len;
        while (e < max && b[e] != 0) e++;
        return new String(b, off, e - off, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
