package com.spotable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Reads the georeferenced bounding box and raster metadata from a (Geo)TIFF DTM,
 * the raster counterpart to {@link LazBinaryReader}.
 * <p>
 * Only the TIFF header, the first Image File Directory (IFD), and the handful of
 * georeferencing tags it points at are read — never the pixel data. A TIFF's IFD
 * may sit anywhere in the file (GDAL frequently writes it near the end), and the
 * georef tag payloads live at their own offsets, so the {@link Source} abstraction
 * reads arbitrary byte ranges; for S3 each range is a single ranged GET.
 * <p>
 * The footprint is derived from {@code ModelPixelScaleTag}+{@code ModelTiepointTag}
 * (or {@code ModelTransformationTag}); the CRS from the same GeoTIFF {@code GeoKeyDirectoryTag}
 * that {@link LazBinaryReader} reads. Like {@link LazBinaryReader}, it prints two CSV fields per
 * file — the path and the box as georeferenced EWKT ({@code SRID=<epsg>;POLYGON(...)}).
 * BigTIFF (version 43) is supported alongside classic TIFF.
 */
public class TifBinaryReader {

    // ---- TIFF magic / structure ----
    private static final int TIFF_CLASSIC = 42;
    private static final int TIFF_BIG = 43;

    // GeoTIFF tags needed to place the raster footprint and read its CRS.
    private static final int TAG_IMAGE_WIDTH = 256;       // SHORT/LONG
    private static final int TAG_IMAGE_LENGTH = 257;      // SHORT/LONG
    private static final int TAG_MODEL_PIXEL_SCALE = 33550;     // 3 doubles
    private static final int TAG_MODEL_TIEPOINT = 33922;        // 6 doubles per tiepoint
    private static final int TAG_MODEL_TRANSFORMATION = 34264;  // 16 doubles (4x4)
    private static final int TAG_GEO_KEY_DIRECTORY = 34735;     // shorts
    private static final int TAG_GEO_ASCII_PARAMS = 34737;      // ASCII (citation/WKT)

    // GeoKey ids and unit handling live in GeoCrs (shared with LazBinaryReader).
    // Names for the vertical CRS codes seen in these DEMs (GeoTIFF rarely carries a vertical name).
    private static final Map<Integer, String> VERTICAL_NAMES = Map.of(
            5703, "NAVD88 height (m)", 6360, "NAVD88 height (ftUS)", 5714, "MSL height (m)");

    // How far we'll read at a single offset (IFD window, and any tag payload).
    private static final int READ_WINDOW = 64 * 1024;
    private static final int READ_CAP = 16 * 1024 * 1024;

    public final double minX, maxX, minY, maxY;
    /** EPSG code of the horizontal CRS, or {@code null} if none could be determined. */
    public final Integer epsg;
    /** Ground sample distance (pixel size) in metres, or {@code NaN} if unknown. */
    public final double resolutionMetres;
    /** EPSG code of the vertical CRS, or {@code null} (most DEMs declare none). */
    public final Integer verticalEpsg;
    /** Human-readable vertical CRS name, or {@code null}. */
    public final String verticalCrs;

    private TifBinaryReader(double minX, double maxX, double minY, double maxY, Integer epsg,
                           double resolutionMetres, Integer verticalEpsg, String verticalCrs) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.epsg = epsg;
        this.resolutionMetres = resolutionMetres;
        this.verticalEpsg = verticalEpsg;
        this.verticalCrs = verticalCrs;
    }

    // ---- Sources: anything that can yield an arbitrary byte range of a TIFF ----

    /** A readable TIFF origin (local path or S3 object) with a display label. */
    interface Source {
        String label();
        /** Reads up to {@code len} bytes starting at {@code offset} (fewer at EOF). */
        byte[] read(long offset, int len) throws IOException;
    }

    private record LocalSource(Path path) implements Source {
        public String label() { return path.toString(); }
        public byte[] read(long offset, int len) throws IOException {
            try (SeekableByteChannel ch = Files.newByteChannel(path)) {
                ch.position(offset);
                ByteBuffer bb = ByteBuffer.allocate(len);
                while (bb.hasRemaining() && ch.read(bb) > 0) { /* fill */ }
                return java.util.Arrays.copyOf(bb.array(), bb.position());
            }
        }
    }

    private record S3Source(S3Client s3, String bucket, String key) implements Source {
        public String label() { return "s3://" + bucket + "/" + key; }
        public byte[] read(long offset, int len) throws IOException {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucket).key(key)
                    .range("bytes=" + offset + "-" + (offset + len - 1))
                    .build();
            try (InputStream in = s3.getObject(req)) {
                return in.readAllBytes();
            }
        }
    }

    // ---- Reading ----

    /** Reads bounds and metadata from a local (Geo)TIFF file. */
    public static TifBinaryReader read(Path file) throws IOException {
        return read(new LocalSource(file));
    }

    /** Reads georeferencing/metadata from an S3 object via ranged GETs. */
    public static TifBinaryReader readS3(S3Client s3, String bucket, String key) throws IOException {
        return read(new S3Source(s3, bucket, key));
    }

    static TifBinaryReader read(Source source) throws IOException {
        byte[] head = source.read(0, 16);
        if (head.length < 8) {
            throw new IllegalArgumentException("Too short to be a TIFF: " + source.label());
        }
        ByteOrder order = byteOrder(head, source.label());
        ByteBuffer h = ByteBuffer.wrap(head).order(order);
        int version = h.getShort(2) & 0xFFFF;

        boolean bigTiff;
        long ifdOffset;
        if (version == TIFF_CLASSIC) {
            bigTiff = false;
            ifdOffset = h.getInt(4) & 0xFFFFFFFFL;
        } else if (version == TIFF_BIG) {
            bigTiff = true;
            if ((h.getShort(4) & 0xFFFF) != 8) {
                throw new IllegalArgumentException("Unsupported BigTIFF offset size: " + source.label());
            }
            ifdOffset = h.getLong(8);
        } else {
            throw new IllegalArgumentException("Not a TIFF (bad version " + version + "): " + source.label());
        }

        Tiff tiff = new Tiff(source, order, bigTiff, ifdOffset);

        long width = tiff.intValue(TAG_IMAGE_WIDTH, -1);
        long height = tiff.intValue(TAG_IMAGE_LENGTH, -1);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Missing image dimensions: " + source.label());
        }

        double[] box = footprint(tiff, width, height, source.label());  // {minX, maxX, minY, maxY}

        // CRS from the GeoKey directory; fall back to an EPSG embedded in a WKT citation
        // (used by GeoTIFFs with a user-defined CRS).
        int[] gk = tiff.shorts(TAG_GEO_KEY_DIRECTORY);
        int[] codes = GeoCrs.geoKeyEpsgs(gk);
        Integer epsg = codes[0] != 0 ? codes[0] : (codes[1] != 0 ? codes[1] : null);
        if (epsg == null) {
            String citation = tiff.ascii(TAG_GEO_ASCII_PARAMS);
            if (GeoCrs.looksLikeWkt(citation)) epsg = GeoCrs.horizontalEpsg(citation);
        }

        // Ground sample distance (pixel size) in metres, honouring the projected linear unit.
        double pixel = pixelSize(tiff);
        double unit = GeoCrs.metresPerUnit(GeoCrs.geoKey(gk, GeoCrs.KEY_PROJ_LINEAR_UNITS));
        double resolution = Double.isNaN(pixel) ? Double.NaN : pixel * unit;

        // Vertical CRS, when the DEM declares one (most do not).
        int vCode = GeoCrs.geoKey(gk, GeoCrs.KEY_VERTICAL_CS);
        Integer verticalEpsg = vCode > 0 ? vCode : null;
        String verticalCrs = verticalEpsg == null ? null
                : VERTICAL_NAMES.getOrDefault(verticalEpsg, "EPSG:" + verticalEpsg);

        return new TifBinaryReader(box[0], box[1], box[2], box[3], epsg,
                resolution, verticalEpsg, verticalCrs);
    }

    /** Pixel size in CRS units from the transform or pixel-scale tags, or {@code NaN}. */
    private static double pixelSize(Tiff tiff) {
        double[] t = tiff.doubles(TAG_MODEL_TRANSFORMATION);
        if (t != null && t.length >= 16) return Math.hypot(t[0], t[4]);   // |first column|
        double[] scale = tiff.doubles(TAG_MODEL_PIXEL_SCALE);
        if (scale != null && scale.length >= 1) return scale[0];
        return Double.NaN;
    }

    private static ByteOrder byteOrder(byte[] head, String label) {
        if (head[0] == 'I' && head[1] == 'I') return ByteOrder.LITTLE_ENDIAN;
        if (head[0] == 'M' && head[1] == 'M') return ByteOrder.BIG_ENDIAN;
        throw new IllegalArgumentException("Not a TIFF (bad byte-order mark): " + label);
    }

    /**
     * Computes {@code {minX, maxX, minY, maxY}} from the model transform tags. Prefers an
     * explicit {@code ModelTransformationTag} (handles rotation/skew by projecting all four
     * corners), else {@code ModelPixelScaleTag}+{@code ModelTiepointTag}.
     */
    private static double[] footprint(Tiff tiff, long width, long height, String label) {
        double[] t = tiff.doubles(TAG_MODEL_TRANSFORMATION);
        if (t != null && t.length >= 16) {
            // X = t0*col + t1*row + t3 ; Y = t4*col + t5*row + t7
            double[] cols = {0, width, 0, width};
            double[] rows = {0, 0, height, height};
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                double x = t[0] * cols[i] + t[1] * rows[i] + t[3];
                double y = t[4] * cols[i] + t[5] * rows[i] + t[7];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            return new double[] { minX, maxX, minY, maxY };
        }

        double[] scale = tiff.doubles(TAG_MODEL_PIXEL_SCALE);
        double[] tie = tiff.doubles(TAG_MODEL_TIEPOINT);
        if (scale != null && scale.length >= 2 && tie != null && tie.length >= 6) {
            double sx = scale[0], sy = scale[1];
            double i = tie[0], j = tie[1], x = tie[3], y = tie[4];
            // Raster row increases downward, so model Y decreases with row.
            double originX = x - i * sx;
            double originY = y + j * sy;
            double minX = originX, maxX = originX + width * sx;
            double maxY = originY, minY = originY - height * sy;
            return new double[] { minX, maxX, minY, maxY };
        }
        throw new IllegalArgumentException("No georeferencing tags (pixel scale/tiepoint): " + label);
    }

    // ---- A parsed TIFF IFD plus typed tag accessors ----

    private record Entry(int type, long count, byte[] valueField) {}

    private static final class Tiff {
        private final Source source;
        private final ByteOrder order;
        private final boolean bigTiff;
        private final Map<Integer, Entry> tags = new LinkedHashMap<>();

        Tiff(Source source, ByteOrder order, boolean bigTiff, long ifdOffset) throws IOException {
            this.source = source;
            this.order = order;
            this.bigTiff = bigTiff;
            readIfd(ifdOffset);
        }

        private void readIfd(long ifdOffset) throws IOException {
            int entrySize = bigTiff ? 20 : 12;
            int headerLen = bigTiff ? 8 : 2;
            int nextLen = bigTiff ? 8 : 4;

            byte[] w = source.read(ifdOffset, READ_WINDOW);
            ByteBuffer b = ByteBuffer.wrap(w).order(order);
            long count = bigTiff ? b.getLong(0) : (b.getShort(0) & 0xFFFF);
            long needed = headerLen + count * entrySize + nextLen;
            if (needed > w.length && needed <= READ_CAP) {
                w = source.read(ifdOffset, (int) needed);
                b = ByteBuffer.wrap(w).order(order);
            }
            int n = (int) Math.min(count, (w.length - headerLen) / entrySize);
            for (int k = 0; k < n; k++) {
                int pos = headerLen + k * entrySize;
                int tag = b.getShort(pos) & 0xFFFF;
                int type = b.getShort(pos + 2) & 0xFFFF;
                long cnt = bigTiff ? b.getLong(pos + 4) : (b.getInt(pos + 4) & 0xFFFFFFFFL);
                int vOff = bigTiff ? pos + 12 : pos + 8;
                int vLen = bigTiff ? 8 : 4;
                byte[] valueField = java.util.Arrays.copyOfRange(w, vOff, vOff + vLen);
                tags.put(tag, new Entry(type, cnt, valueField));
            }
        }

        /** Raw payload bytes of a tag (inline value, or fetched from its offset), or null. */
        private byte[] data(int tag) throws IOException {
            Entry e = tags.get(tag);
            if (e == null) return null;
            long byteLen = e.count() * typeSize(e.type());
            if (byteLen <= 0) return new byte[0];
            int inlineCap = bigTiff ? 8 : 4;
            if (byteLen <= inlineCap) {
                return java.util.Arrays.copyOf(e.valueField(), (int) byteLen);
            }
            long off = bigTiff
                    ? ByteBuffer.wrap(e.valueField()).order(order).getLong(0)
                    : (ByteBuffer.wrap(e.valueField()).order(order).getInt(0) & 0xFFFFFFFFL);
            return source.read(off, (int) Math.min(byteLen, READ_CAP));
        }

        double[] doubles(int tag) {
            try {
                byte[] d = data(tag);
                if (d == null || d.length < 8) return null;
                ByteBuffer b = ByteBuffer.wrap(d).order(order);
                double[] out = new double[d.length / 8];
                for (int i = 0; i < out.length; i++) out[i] = b.getDouble(i * 8);
                return out;
            } catch (IOException ex) {
                return null;
            }
        }

        int[] shorts(int tag) {
            try {
                byte[] d = data(tag);
                if (d == null || d.length < 2) return null;
                ByteBuffer b = ByteBuffer.wrap(d).order(order);
                int[] out = new int[d.length / 2];
                for (int i = 0; i < out.length; i++) out[i] = b.getShort(i * 2) & 0xFFFF;
                return out;
            } catch (IOException ex) {
                return null;
            }
        }

        /** First element of a SHORT/LONG/BYTE tag, or {@code def} if absent. */
        long intValue(int tag, long def) {
            Entry e = tags.get(tag);
            if (e == null) return def;
            try {
                byte[] d = data(tag);
                if (d == null) return def;
                ByteBuffer b = ByteBuffer.wrap(d).order(order);
                return switch (e.type()) {
                    case 3 -> b.getShort(0) & 0xFFFF;            // SHORT
                    case 4 -> b.getInt(0) & 0xFFFFFFFFL;         // LONG
                    case 16 -> b.getLong(0);                     // LONG8 (BigTIFF)
                    case 1 -> d[0] & 0xFF;                       // BYTE
                    default -> def;
                };
            } catch (IOException ex) {
                return def;
            }
        }

        /** ASCII tag value, trimmed of NUL padding, or empty string if absent. */
        String ascii(int tag) {
            try {
                byte[] d = data(tag);
                if (d == null) return "";
                int len = d.length;
                while (len > 0 && d[len - 1] == 0) len--;
                return new String(d, 0, len, StandardCharsets.US_ASCII).trim();
            } catch (IOException ex) {
                return "";
            }
        }
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;             // BYTE, ASCII, SBYTE, UNDEFINED
            case 3, 8 -> 2;                   // SHORT, SSHORT
            case 4, 9, 11 -> 4;               // LONG, SLONG, FLOAT
            case 5, 10, 12, 16, 17, 18 -> 8;  // RATIONAL, SRATIONAL, DOUBLE, LONG8, SLONG8, IFD8
            default -> 0;
        };
    }

    // ---- Output ----

    /**
     * Bounding box as EWKT: {@code SRID=<epsg>;POLYGON ((...))} when the CRS is known,
     * else a bare {@code POLYGON ((...))}.
     */
    public String toWkt() {
        String polygon = String.format(
                "POLYGON ((%s %s, %s %s, %s %s, %s %s, %s %s))",
                fmt(minX), fmt(minY),
                fmt(maxX), fmt(minY),
                fmt(maxX), fmt(maxY),
                fmt(minX), fmt(maxY),
                fmt(minX), fmt(minY));
        return epsg != null ? "SRID=" + epsg + ";" + polygon : polygon;
    }

    // Compact, locale-independent number formatting (avoids 1,234.5 in some locales).
    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    // ---- CLI ----

    /** One RFC 4180 CSV record: each field double-quoted, embedded quotes doubled. */
    static String csvRow(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(fields[i].replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        Path outFile = null;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("-o") || a.equals("--out")) {
                if (i + 1 >= args.length) {
                    System.err.println(a + " requires a file path");
                    System.exit(2);
                }
                outFile = Path.of(args[++i]);
            } else if (a.equals("-i") || a.equals("--input")) {
                if (i + 1 >= args.length) {
                    System.err.println(a + " requires a file/dir/glob/s3-uri");
                    System.exit(2);
                }
                inputs.add(args[++i]);
            } else {
                System.err.println("Unknown argument: " + a);
                System.exit(2);
            }
        }

        if (inputs.isEmpty()) {
            System.err.println("Usage: java com.spotable.TifBinaryReader -i <file|dir|glob|s3-uri> [-i ...] [-o out.csv]");
            System.err.println("  -i/--input  (repeatable) source to read:");
            System.err.println("    Local: a .tif/.tiff file, a directory (recursive *.tif/*.tiff),");
            System.err.println("           or a glob such as 'dem/**/*.tif'.");
            System.err.println("    S3:    s3://bucket/key.tif, s3://bucket/prefix/, or");
            System.err.println("           s3://bucket/prefix/*.tif (glob over listed keys).");
            System.err.println("  -o/--out    also write the CSV rows to this file.");
            System.err.println("  Prints one CSV row per file to stdout: \"path\",\"wkt\".");
            return;
        }

        String[] inputArgs = inputs.toArray(new String[0]);
        boolean needsS3 = inputs.stream().anyMatch(a -> a.startsWith("s3://"));
        int failures;
        try (S3Client s3 = needsS3 ? S3Client.create() : null;
             Writer csv = outFile != null
                     ? Files.newBufferedWriter(outFile, StandardCharsets.UTF_8) : null) {
            List<Source> sources = Sources.collect(inputArgs, s3, TifBinaryReader::isTiff,
                    LocalSource::new, (bucket, key) -> new S3Source(s3, bucket, key));
            if (sources.isEmpty()) {
                System.err.println("No TIFF files matched.");
                return;
            }
            failures = 0;
            for (Source source : sources) {
                try {
                    String row = csvRow(source.label(), read(source).toWkt());
                    System.out.println(row);
                    if (csv != null) {
                        csv.write(row);
                        csv.write('\n');
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("Skipping " + source.label() + ": " + e.getMessage());
                    failures++;
                }
            }
        }
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** Name filter passed to {@link Sources}: keeps {@code *.tif}/{@code *.tiff} during listings. */
    private static boolean isTiff(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tif") || lower.endsWith(".tiff");
    }
}
