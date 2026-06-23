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
        int extraBytes = h.pointRecordLength - Point14.SIZE;
        if (extraBytes < 0) {
            throw new IllegalStateException("point record length " + h.pointRecordLength + " < " + Point14.SIZE);
        }
        // The chunk table gives each chunk's byte start and point count, so chunks decode
        // independently rather than by walking inline framing sizes.
        ChunkTable table = ChunkTable.read(file, h, count);
        int idx = 0;
        for (int ci = 0; ci < table.starts.length; ci++) {
            ByteCursor cursor = new ByteCursor(file, (int) table.starts[ci]);
            Point14Decompressor d = new Point14Decompressor(cursor, extraBytes);
            for (int j = 0; j < table.counts[ci]; j++) d.decompress(records, (idx++) * Point14.SIZE);
        }
        if (idx != count) {
            throw new IllegalStateException("decoded " + idx + " points, expected " + count);
        }
        return records;
    }

    /**
     * The LASzip chunk table (at the offset stored in the first 8 bytes of the point data):
     * per-chunk point counts and byte start offsets. Fixed-size chunks store only the byte
     * sizes; variable-size chunks store a point count before each size.
     */
    private static final class ChunkTable {
        final int[] counts;
        final long[] starts;

        private ChunkTable(int[] counts, long[] starts) {
            this.counts = counts;
            this.starts = starts;
        }

        static ChunkTable read(byte[] file, LasHeader h, int totalPoints) {
            long tableOffset = i64(file, (int) h.pointOffset);
            int nChunks = (int) u32(file, (int) tableOffset + 4); // [0]=version, [1]=count
            boolean variable = h.variableChunks();
            int[] counts = new int[nChunks];
            long[] starts = new long[nChunks];
            ArithmeticDecoder dec = new ArithmeticDecoder(file, (int) tableOffset + 8);
            IntegerDecompressor ic = new IntegerDecompressor(32, 2);
            ic.init();
            int countPred = 0, sizePred = 0;
            long start = h.pointOffset + 8;
            int assigned = 0;
            for (int i = 0; i < nChunks; i++) {
                if (variable) {
                    countPred = ic.decompress(dec, countPred, 0);
                    counts[i] = countPred;
                } else {
                    counts[i] = Math.min(h.chunkSize, totalPoints - assigned);
                }
                assigned += counts[i];
                sizePred = ic.decompress(dec, sizePred, 1);
                starts[i] = start;
                start += sizePred & 0xFFFFFFFFL;
            }
            return new ChunkTable(counts, starts);
        }
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
