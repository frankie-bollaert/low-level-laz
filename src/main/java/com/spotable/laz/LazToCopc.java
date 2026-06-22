package com.spotable.laz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line tool that converts a LAS/LAZ file (point data record format&nbsp;6)
 * into a COPC (Cloud Optimized Point Cloud) file, entirely in Java with no native
 * dependencies.
 * <p>
 * The input is read into memory and decoded to raw 30-byte records — uncompressed
 * LAS, or LAZ with either fixed- or variable-size chunks — then re-clustered into a
 * COPC octree by {@link CopcWriter}. The CRS WKT VLR, scale/offset and GUID are
 * carried over from the source. PDRF&nbsp;7/8 (RGB/NIR) input is not yet supported.
 * <pre>
 *   java com.spotable.laz.LazToCopc -i input.laz [-o output.copc.laz]
 * </pre>
 */
public final class LazToCopc {

    public static void main(String[] args) throws IOException {
        Path input = null, output = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-i", "--input" -> input = Path.of(requireArg(args, ++i, "-i"));
                case "-o", "--out" -> output = Path.of(requireArg(args, ++i, "-o"));
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }
        if (input == null) {
            usage();
            System.exit(2);
        }
        if (output == null) output = defaultOutput(input);

        byte[] file = Files.readAllBytes(input);
        LasHeader h = LasHeader.parse(file);
        if (h.pointFormat != 6) {
            System.err.println("Only point data record format 6 is supported (got " + h.pointFormat
                    + "). PDRF 7/8 support is pending RGB/NIR fields.");
            System.exit(1);
        }
        if (h.pointCount > Integer.MAX_VALUE / Point14.SIZE) {
            System.err.println("Too many points for the in-memory converter: " + h.pointCount);
            System.exit(1);
        }

        int count = (int) h.pointCount;
        byte[] records = decodeRecords(file, h, count);
        LazWriter.Vlr wkt = CopcWriter.extractVlr(file, "LASF_Projection", 2112);

        byte[] copc = CopcWriter.build(file, records, count, wkt);
        Files.write(output, copc);

        System.out.printf("Wrote %s: %,d points, %,d bytes (from %s)%n",
                output, count, copc.length, input);
    }

    /** Decodes all point records to a contiguous 30-byte-per-point buffer. */
    private static byte[] decodeRecords(byte[] file, LasHeader h, int count) {
        byte[] records = new byte[count * Point14.SIZE];
        if (!h.compressed) {
            int base = (int) h.pointOffset;
            for (int i = 0; i < count; i++) {
                System.arraycopy(file, base + i * h.pointRecordLength, records, i * Point14.SIZE, Point14.SIZE);
            }
            return records;
        }
        if (h.lazCompressor != 3) {
            throw new IllegalStateException("only LASzip v3 (PDRF 6) input is supported");
        }
        int[] counts = perChunkCounts(file, h, count);
        ByteCursor cursor = new ByteCursor(file, (int) h.pointOffset + 8);
        int idx = 0;
        for (int n : counts) {
            Point14Decompressor d = new Point14Decompressor(cursor);
            for (int j = 0; j < n; j++) d.decompress(records, (idx++) * Point14.SIZE);
        }
        if (idx != count) {
            throw new IllegalStateException("decoded " + idx + " points, expected " + count);
        }
        return records;
    }

    /** Per-chunk point counts: derived from the chunk size for fixed chunks, decoded for variable. */
    private static int[] perChunkCounts(byte[] file, LasHeader h, int count) {
        if (!h.variableChunks()) {
            int chunk = h.chunkSize;
            int nChunks = (int) ((count + (long) chunk - 1) / chunk);
            int[] counts = new int[nChunks];
            int remaining = count;
            for (int i = 0; i < nChunks; i++) {
                counts[i] = Math.min(chunk, remaining);
                remaining -= counts[i];
            }
            return counts;
        }
        // Variable chunks: the table is at the offset stored at the start of the point data.
        long tableOffset = i64(file, (int) h.pointOffset);
        int nChunks = (int) u32(file, (int) tableOffset + 4); // [0]=version, [1]=count
        ArithmeticDecoder dec = new ArithmeticDecoder(file, (int) tableOffset + 8);
        IntegerDecompressor ic = new IntegerDecompressor(32, 2);
        ic.init();
        int[] counts = new int[nChunks];
        int countPred = 0, offsetPred = 0;
        for (int i = 0; i < nChunks; i++) {
            countPred = ic.decompress(dec, countPred, 0);
            counts[i] = countPred;
            offsetPred = ic.decompress(dec, offsetPred, 1); // decoded to keep the stream in sync
        }
        return counts;
    }

    private static Path defaultOutput(Path input) {
        String name = input.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String stem = lower.endsWith(".laz") || lower.endsWith(".las")
                ? name.substring(0, name.length() - 4) : name;
        Path parent = input.toAbsolutePath().getParent();
        return parent.resolve(stem + ".copc.laz");
    }

    private static String requireArg(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println(flag + " requires a value");
            usage();
            System.exit(2);
        }
        return args[i];
    }

    private static void usage() {
        System.err.println("Usage: java com.spotable.laz.LazToCopc -i <input.las|input.laz> [-o <output.copc.laz>]");
        System.err.println("  Converts a PDRF 6 LAS/LAZ file to COPC. Defaults output to <input>.copc.laz.");
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long i64(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[o + i] & 0xFFL) << (8 * i);
        return v;
    }

    private LazToCopc() {}
}
