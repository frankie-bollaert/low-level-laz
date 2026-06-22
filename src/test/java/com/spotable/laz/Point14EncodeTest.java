package com.spotable.laz;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Verifies the v3 point14 <em>encoder</em>. Reads the uncompressed PDRF&nbsp;6
 * reference records, re-encodes them chunk-by-chunk with {@link Point14Compressor},
 * and feeds each chunk back through the (already reference-verified)
 * {@link Point14Decompressor}, asserting the round-trip is loss-free.
 * <p>
 * Because the encoder mirrors laz-perf decision-for-decision and arithmetic coding is
 * deterministic, the produced chunk bytes are also expected to match PDAL's original
 * LAZ chunk-for-chunk; that stronger equality is checked too (and reported, not just
 * asserted, in case PDAL used a byte-differing-but-valid encoder).
 *
 * @see Point14DecodeTest for fixture generation
 */
public class Point14EncodeTest {

    private static final int CHUNK = 50000;

    @Test
    public void encodeDecodeRoundTripsAllPoints() throws Exception {
        Path refPath = Path.of("/tmp/rr6u.las");
        Path lazPath = Path.of("/tmp/rr6.laz");
        assumeTrue("fixtures missing; see Point14DecodeTest", Files.exists(refPath) && Files.exists(lazPath));

        byte[] ref = Files.readAllBytes(refPath);
        LasHeader refHdr = LasHeader.parse(ref);
        int base = (int) refHdr.pointOffset;
        long count = refHdr.pointCount;

        // Original PDAL chunk bytes, for the (optional) byte-identity comparison.
        byte[] laz = Files.readAllBytes(lazPath);
        LasHeader lazHdr = LasHeader.parse(laz);
        ByteCursor origChunks = new ByteCursor(laz, (int) lazHdr.pointOffset + 8);

        byte[] decoded = new byte[Point14.SIZE];
        long index = 0;
        int chunkIdx = 0;
        boolean allBytesIdentical = true;
        while (index < count) {
            int n = (int) Math.min(CHUNK, count - index);

            // Encode this chunk from the reference records.
            Point14Compressor enc = new Point14Compressor();
            for (int i = 0; i < n; i++) enc.compress(ref, base + (int) (index + i) * Point14.SIZE);
            byte[] chunk = enc.finish();

            // Decode it back and compare to the originals.
            Point14Decompressor dec = new Point14Decompressor(new ByteCursor(chunk, 0));
            for (int i = 0; i < n; i++) {
                dec.decompress(decoded, 0);
                int ro = base + (int) (index + i) * Point14.SIZE;
                for (int k = 0; k < Point14.SIZE; k++) {
                    if (decoded[k] != ref[ro + k]) {
                        assertEquals("chunk " + chunkIdx + " point " + (index + i) + " byte " + k,
                                ref[ro + k] & 0xFF, decoded[k] & 0xFF);
                    }
                }
            }
            assertEquals("chunk " + chunkIdx + " declared count", n, dec.chunkCount);

            // Compare against the original PDAL chunk bytes (same length region).
            byte[] orig = origChunks.take(chunk.length);
            if (allBytesIdentical) {
                for (int k = 0; k < chunk.length; k++) {
                    if (orig[k] != chunk[k]) { allBytesIdentical = false; break; }
                }
            }

            index += n;
            chunkIdx++;
        }
        assertEquals("encoded all points", count, index);
        System.out.println("[Point14EncodeTest] round-trip OK for " + count + " points in "
                + chunkIdx + " chunks; byte-identical to PDAL: " + allBytesIdentical);
    }

    /** A focused round-trip on a tiny synthetic chunk, independent of external fixtures. */
    @Test
    public void encodeDecodeTinyChunk() {
        int n = 64;
        byte[] in = new byte[n * Point14.SIZE];
        Point14 p = new Point14();
        for (int i = 0; i < n; i++) {
            p.x = 1000 + i * 7;
            p.y = -2000 + i * 3;
            p.z = 50 + (i % 5);
            p.intensity = (i * 13) & 0xFFFF;
            p.setReturnNum(1 + (i % 3));
            p.setNumReturns(3);
            p.setScannerChannel(i % 4);
            p.classification = 2;
            p.userData = i & 0xFF;
            p.scanAngle = (i % 30) - 15;
            p.pointSourceId = 1000 + (i % 4);
            p.gpstime = Double.doubleToRawLongBits(1.0e8 + i * 0.5);
            p.pack(in, i * Point14.SIZE);
        }

        Point14Compressor enc = new Point14Compressor();
        for (int i = 0; i < n; i++) enc.compress(in, i * Point14.SIZE);
        byte[] chunk = enc.finish();

        Point14Decompressor dec = new Point14Decompressor(new ByteCursor(chunk, 0));
        byte[] out = new byte[n * Point14.SIZE];
        for (int i = 0; i < n; i++) dec.decompress(out, i * Point14.SIZE);

        assertArrayEquals(in, out);
        assertEquals(n, dec.chunkCount);
    }
}
