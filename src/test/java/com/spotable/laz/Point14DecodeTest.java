package com.spotable.laz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Reference-compatibility test for the v3 point14 decoder: decode every point of a
 * real PDRF&nbsp;6 LAZ produced by PDAL and compare the 30-byte records byte-for-byte
 * against the same file written uncompressed by PDAL. A pass proves the whole stack
 * (arithmetic coder, models, integer codec, point14 field, chunk framing) is
 * bit-compatible with reference LASzip.
 * <p>
 * Fixtures are generated under /tmp (see the conversation that introduced this file):
 * <pre>
 *   pdal translate red-rocks.laz /tmp/rr6.laz  --writers.las.minor_version=4 --writers.las.dataformat_id=6
 *   pdal translate /tmp/rr6.laz  /tmp/rr6u.las --writers.las.dataformat_id=6 --writers.las.minor_version=4
 * </pre>
 * The test skips itself if the fixtures are absent.
 */
public class Point14DecodeTest {

    @Test
    public void decodesPdrf6ByteForByte() throws Exception {
        Path lazPath = Path.of("/tmp/rr6.laz");
        Path refPath = Path.of("/tmp/rr6u.las");
        assumeTrue("fixtures missing; see class javadoc", Files.exists(lazPath) && Files.exists(refPath));

        byte[] laz = Files.readAllBytes(lazPath);
        byte[] ref = Files.readAllBytes(refPath);

        LasHeader h = LasHeader.parse(laz);
        assertEquals("point format", 6, h.pointFormat);
        assertEquals("compressor", 3, h.lazCompressor);

        LasHeader refHdr = LasHeader.parse(ref);
        assertEquals("reference must be uncompressed", false, refHdr.compressed);
        assertEquals("reference record length", 30, refHdr.pointRecordLength);
        assertEquals("point counts agree", h.pointCount, refHdr.pointCount);

        ByteCursor cursor = new ByteCursor(laz, (int) h.pointOffset + 8); // skip chunk-table offset
        int refBase = (int) refHdr.pointOffset;

        byte[] dec = new byte[Point14.SIZE];
        long remaining = h.pointCount;
        long index = 0;
        int chunkIdx = 0;
        while (remaining > 0) {
            int n = (int) Math.min(h.chunkSize, remaining);
            Point14Decompressor d = new Point14Decompressor(cursor, 0);
            for (int i = 0; i < n; i++) {
                d.decompress(dec, 0);
                int ro = refBase + (int) (index + i) * Point14.SIZE;
                for (int k = 0; k < Point14.SIZE; k++) {
                    if (dec[k] != ref[ro + k]) {
                        assertEquals("chunk " + chunkIdx + " point " + (index + i) + " byte " + k,
                                ref[ro + k] & 0xFF, dec[k] & 0xFF);
                    }
                }
            }
            assertEquals("chunk " + chunkIdx + " declared count", n, d.chunkCount);
            remaining -= n;
            index += n;
            chunkIdx++;
        }
        assertEquals("decoded all points", h.pointCount, index);
    }
}
