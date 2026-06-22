package com.spotable.laz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Writes a complete fixed-chunk PDRF&nbsp;6 LAZ from the uncompressed reference records
 * (encoding with {@link Point14Compressor}, framing with {@link LazWriter}), then reads
 * it back with our own parser/decoder and checks every point round-trips. The file is
 * left at {@code /tmp/mine.laz} so PDAL can independently confirm it reads it (see the
 * conversation that introduced this test).
 */
public class LazWriterTest {

    private static final int CHUNK = 50000;

    @Test
    public void writesPdalReadableLaz() throws Exception {
        Path refPath = Path.of("/tmp/rr6u.las");
        assumeTrue("fixture missing; see Point14DecodeTest", Files.exists(refPath));

        byte[] ref = Files.readAllBytes(refPath);
        LasHeader refHdr = LasHeader.parse(ref);
        int base = (int) refHdr.pointOffset;
        long count = refHdr.pointCount;

        List<byte[]> chunks = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (long index = 0; index < count; ) {
            int n = (int) Math.min(CHUNK, count - index);
            Point14Compressor enc = new Point14Compressor();
            for (int i = 0; i < n; i++) enc.compress(ref, base + (int) (index + i) * Point14.SIZE);
            chunks.add(enc.finish());
            counts.add(n);
            index += n;
        }
        int[] chunkCounts = counts.stream().mapToInt(Integer::intValue).toArray();

        byte[] header = new byte[375];
        System.arraycopy(ref, 0, header, 0, 375);
        List<LazWriter.Vlr> vlrs = List.of(
                new LazWriter.Vlr("laszip encoded", 22204, "http://laszip.org",
                        LazWriter.laszipVlrDataPdrf6(CHUNK)));

        byte[] laz = LazWriter.write(header, vlrs, chunks, chunkCounts, List.of(), false);
        Path out = Path.of("/tmp/mine.laz");
        Files.write(out, laz);

        // Read it back with our own stack and verify every point byte-for-byte.
        LasHeader h = LasHeader.parse(laz);
        assertEquals(6, h.pointFormat);
        assertEquals(true, h.compressed);
        assertEquals(3, h.lazCompressor);
        assertEquals(CHUNK, h.chunkSize);
        assertEquals(count, h.pointCount);

        ByteCursor cursor = new ByteCursor(laz, (int) h.pointOffset + 8);
        byte[] dec = new byte[Point14.SIZE];
        long index = 0;
        while (index < count) {
            int n = (int) Math.min(h.chunkSize, count - index);
            Point14Decompressor d = new Point14Decompressor(cursor);
            for (int i = 0; i < n; i++) {
                d.decompress(dec, 0);
                int ro = base + (int) (index + i) * Point14.SIZE;
                for (int k = 0; k < Point14.SIZE; k++) {
                    if (dec[k] != ref[ro + k]) {
                        assertEquals("point " + (index + i) + " byte " + k, ref[ro + k] & 0xFF, dec[k] & 0xFF);
                    }
                }
            }
            index += n;
        }
        assertEquals(count, index);
        System.out.println("[LazWriterTest] wrote " + out + " (" + laz.length + " bytes), "
                + count + " points round-trip OK");
    }
}
