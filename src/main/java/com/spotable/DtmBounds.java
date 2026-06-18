package com.spotable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Reads the georeferenced bounding box and raster metadata from a (Geo)TIFF DTM,
 * the raster counterpart to {@link LazBounds}.
 * <p>
 * Only the TIFF header, the first Image File Directory (IFD), and the handful of
 * georeferencing tags it points at are read — never the pixel data. A TIFF's IFD
 * may sit anywhere in the file (GDAL frequently writes it near the end), and the
 * georef tag payloads live at their own offsets, so the {@link Source} abstraction
 * reads arbitrary byte ranges; for S3 each range is a single ranged GET.
 * <p>
 * The footprint is derived from {@code ModelPixelScaleTag}+{@code ModelTiepointTag}
 * (or {@code ModelTransformationTag}); the CRS from the same GeoTIFF {@code GeoKeyDirectoryTag}
 * that {@link LazBounds} reads. Like {@link LazBounds}, it prints two CSV fields per
 * file — the path and the box as georeferenced EWKT ({@code SRID=<epsg>;POLYGON(...)}).
 * BigTIFF (version 43) is supported alongside classic TIFF.
 */
public class DtmBounds {

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

    // GeoKey ids holding an EPSG code directly (TIFFTagLocation == 0).
    private static final int KEY_PROJECTED_CS = 3072;   // ProjectedCSTypeGeoKey
    private static final int KEY_GEOGRAPHIC_CS = 2048;  // GeographicTypeGeoKey
    private static final int GEOKEY_UNDEFINED = 0;
    private static final int GEOKEY_USER_DEFINED = 32767;

    // How far we'll read at a single offset (IFD window, and any tag payload).
    private static final int READ_WINDOW = 64 * 1024;
    private static final int READ_CAP = 16 * 1024 * 1024;

    private static final Pattern WKT_EPSG = Pattern.compile(
            "(?i)(?:AUTHORITY|ID)\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*\\]");

    public final double minX, maxX, minY, maxY;
    /** EPSG code of the horizontal CRS, or {@code null} if none could be determined. */
    public final Integer epsg;

    private DtmBounds(double minX, double maxX, double minY, double maxY, Integer epsg) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.epsg = epsg;
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
    public static DtmBounds read(Path file) throws IOException {
        return read(new LocalSource(file));
    }

    static DtmBounds read(Source source) throws IOException {
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
        int[] codes = geoKeyEpsgs(tiff.shorts(TAG_GEO_KEY_DIRECTORY));
        Integer epsg = codes[0] != 0 ? codes[0] : (codes[1] != 0 ? codes[1] : null);
        if (epsg == null) {
            String citation = tiff.ascii(TAG_GEO_ASCII_PARAMS);
            if (looksLikeWkt(citation)) epsg = wktEpsg(citation);
        }

        return new DtmBounds(box[0], box[1], box[2], box[3], epsg);
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

    // ---- CRS helpers (shared shape with LazBounds) ----

    /** Parses a GeoKeyDirectory short array into {@code [projectedEpsg, geographicEpsg]} (0 if absent). */
    private static int[] geoKeyEpsgs(int[] gk) {
        int projected = 0, geographic = 0;
        if (gk != null && gk.length >= 4) {
            int numKeys = gk[3];
            for (int k = 0; k < numKeys; k++) {
                int base = 4 + k * 4;
                if (base + 4 > gk.length) break;
                int keyId = gk[base], location = gk[base + 1], value = gk[base + 3];
                if (location != 0 || value == GEOKEY_UNDEFINED || value == GEOKEY_USER_DEFINED) continue;
                if (keyId == KEY_PROJECTED_CS) projected = value;
                else if (keyId == KEY_GEOGRAPHIC_CS) geographic = value;
            }
        }
        return new int[] { projected, geographic };
    }

    private static boolean looksLikeWkt(String s) {
        if (s == null) return false;
        String u = s.toUpperCase(Locale.ROOT);
        return u.contains("PROJCS") || u.contains("GEOGCS") || u.contains("COMPD_CS")
                || u.contains("PROJCRS") || u.contains("GEOGCRS") || u.contains("COMPOUNDCRS");
    }

    /** EPSG of the horizontal CRS in an OGC WKT string (projected, else geographic), or null. */
    private static Integer wktEpsg(String wkt) {
        String horizontal = firstBracketedSpan(wkt, "PROJCS", "PROJCRS");
        if (horizontal == null) {
            horizontal = firstBracketedSpan(wkt, "GEOGCS", "GEOGCRS", "GEODCRS");
        }
        Integer epsg = horizontal != null ? lastEpsg(horizontal) : null;
        return epsg != null ? epsg : lastEpsg(wkt);
    }

    private static Integer lastEpsg(String wkt) {
        Matcher m = WKT_EPSG.matcher(wkt);
        Integer last = null;
        while (m.find()) last = Integer.valueOf(m.group(1));
        return last;
    }

    private static String firstBracketedSpan(String wkt, String... keywords) {
        String upper = wkt.toUpperCase(Locale.ROOT);
        for (String kw : keywords) {
            for (int idx = upper.indexOf(kw); idx >= 0; idx = upper.indexOf(kw, idx + 1)) {
                boolean leftOk = idx == 0 || !Character.isLetterOrDigit(upper.charAt(idx - 1));
                int open = idx + kw.length();
                while (open < upper.length() && Character.isWhitespace(upper.charAt(open))) open++;
                if (leftOk && open < upper.length()
                        && (upper.charAt(open) == '[' || upper.charAt(open) == '(')) {
                    return balancedSpan(wkt, open);
                }
            }
        }
        return null;
    }

    private static String balancedSpan(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '[' || c == '(') depth++;
                else if (c == ']' || c == ')') {
                    if (--depth == 0) return s.substring(open, i + 1);
                }
            }
        }
        return s.substring(open);
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
            System.err.println("Usage: java com.spotable.DtmBounds -i <file|dir|glob|s3-uri> [-i ...] [-o out.csv]");
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
            List<Source> sources = collectSources(inputArgs, s3);
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

    // ---- Source resolution (local + S3, mirroring LazBounds) ----

    static List<Source> collectSources(String[] args, S3Client s3) throws IOException {
        Map<String, Source> sources = new TreeMap<>();   // sorted + de-duplicated
        for (String arg : args) {
            if (arg.startsWith("s3://")) {
                collectS3(arg, s3, sources);
            } else {
                collectLocal(arg, sources);
            }
        }
        return new ArrayList<>(sources.values());
    }

    private static void collectLocal(String arg, Map<String, Source> out) throws IOException {
        Path p = Path.of(arg);
        if (Files.isDirectory(p)) {
            walkLocal(p, k -> true, true, out);
        } else if (Files.isRegularFile(p)) {
            out.put(p.toString(), new LocalSource(p));
        } else if (hasGlob(arg)) {
            expandLocalGlob(arg, out);
        } else {
            System.err.println("No such file or directory: " + arg);
        }
    }

    private static void walkLocal(Path dir, Predicate<Path> match, boolean tiffOnly,
                                  Map<String, Source> out) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !tiffOnly || isTiff(p.getFileName().toString()))
                .filter(match)
                .forEach(p -> out.put(p.toString(), new LocalSource(p)));
        }
    }

    private static void expandLocalGlob(String pattern, Map<String, Source> out) throws IOException {
        String abs = Path.of(pattern).toAbsolutePath().normalize().toString();
        int firstGlob = indexOfGlob(abs);
        int sep = abs.lastIndexOf(File.separatorChar, firstGlob);
        Path base = Path.of(sep <= 0 ? File.separator : abs.substring(0, sep));
        if (!Files.isDirectory(base)) {
            System.err.println("No matches for glob: " + pattern);
            return;
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + abs);
        walkLocal(base, matcher::matches, false, out);
    }

    private static void collectS3(String uri, S3Client s3, Map<String, Source> out) {
        String rest = uri.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            System.err.println("Malformed S3 URI (expected s3://bucket/key): " + uri);
            return;
        }
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);

        if (hasGlob(key)) {
            int g = indexOfGlob(key);
            int lastSlash = key.lastIndexOf('/', g);
            String prefix = lastSlash < 0 ? "" : key.substring(0, lastSlash + 1);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + key);
            listS3(s3, bucket, prefix, k -> matcher.matches(Path.of(k)), out);
        } else if (key.isEmpty() || key.endsWith("/")) {
            listS3(s3, bucket, key, DtmBounds::isTiff, out);
        } else {
            out.put("s3://" + bucket + "/" + key, new S3Source(s3, bucket, key));
        }
    }

    private static void listS3(S3Client s3, String bucket, String prefix,
                               Predicate<String> keyMatch, Map<String, Source> out) {
        ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix).build();
        boolean any = false;
        for (S3Object o : s3.listObjectsV2Paginator(req).contents()) {
            String key = o.key();
            if (key.endsWith("/")) continue;
            if (keyMatch.test(key)) {
                out.put("s3://" + bucket + "/" + key, new S3Source(s3, bucket, key));
                any = true;
            }
        }
        if (!any) {
            System.err.println("No matches under s3://" + bucket + "/" + prefix);
        }
    }

    private static boolean isTiff(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".tif") || lower.endsWith(".tiff");
    }

    private static boolean hasGlob(String s) {
        return indexOfGlob(s) >= 0;
    }

    private static int indexOfGlob(String s) {
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '*', '?', '[', '{' -> { return i; }
            }
        }
        return -1;
    }
}
