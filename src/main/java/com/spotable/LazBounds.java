package com.spotable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Reads only the 2D bounding box (min/max X and Y) from a LAS or LAZ file.
 * <p>
 * A LAZ file begins with the same uncompressed LAS public header block as a
 * plain LAS file — only the point records that follow are compressed. The
 * bounding box lives at fixed offsets in that header, so we can read it without
 * touching (or decompressing) any point data: just the first {@value #BYTES_NEEDED}
 * bytes of the file.
 * <p>
 * Because only the first few hundred bytes are needed, remote objects on S3 are
 * read with a single ranged GET — no full download, even for multi-gigabyte tiles.
 * <p>
 * The coordinate reference system, when declared in the file's projection VLR
 * (GeoTIFF GeoKeys or an OGC WKT VLR), is also extracted so the bounding box can
 * be emitted as georeferenced EWKT ({@code SRID=<epsg>;POLYGON(...)}).
 */
public class LazBounds {

    // Byte offsets of fields within the public header block.
    private static final int OFF_SIGNATURE = 0;          // "LASF" (4 bytes)
    private static final int OFF_HEADER_SIZE = 94;        // u16
    private static final int OFF_OFFSET_TO_POINT_DATA = 96; // u32
    private static final int OFF_NUMBER_OF_VLRS = 100;   // u32
    private static final int OFF_MAX_X = 179;            // doubles from here on
    private static final int OFF_MIN_X = 187;
    private static final int OFF_MAX_Y = 195;
    private static final int OFF_MIN_Y = 203;

    // Smallest possible header (LAS 1.0–1.2). Below this the file is not a valid LAS.
    private static final int MIN_HEADER_SIZE = OFF_MIN_Y + 8 + 16; // through Min/Max Z = 227

    // How much of the file's start to read. The bounding box needs only 211 bytes,
    // but the projection VLR sits in [headerSize, offsetToPointData); 64 KiB covers
    // that block in virtually every real file with a single read / ranged GET.
    private static final int INITIAL_READ = 64 * 1024;
    // Upper bound on how far we'll read to reach the VLR block, in case a file
    // declares an unusually large offsetToPointData.
    private static final int VLR_READ_CAP = 4 * 1024 * 1024;

    // VLR record header layout (54 bytes), and the projection record ids we care about.
    private static final int VLR_HEADER_LEN = 54;
    private static final int GEOKEY_DIRECTORY_RECORD_ID = 34735; // GeoTIFF GeoKeyDirectoryTag
    private static final int OGC_WKT_RECORD_ID = 2112;           // OGC Coordinate System WKT
    private static final String PROJECTION_USER_ID = "LASF_Projection";
    // GeoKey ids holding an EPSG code directly (TIFFTagLocation == 0).
    private static final int KEY_PROJECTED_CS = 3072;  // ProjectedCSTypeGeoKey
    private static final int KEY_GEOGRAPHIC_CS = 2048;  // GeographicTypeGeoKey
    private static final int GEOKEY_UNDEFINED = 0;
    private static final int GEOKEY_USER_DEFINED = 32767;

    private static final Pattern WKT_EPSG = Pattern.compile(
            "(?i)(?:AUTHORITY|ID)\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*\\]");

    public final double minX, maxX, minY, maxY;
    /** EPSG code of the file's CRS, or {@code null} if none could be determined. */
    public final Integer epsg;

    private LazBounds(double minX, double maxX, double minY, double maxY, Integer epsg) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.epsg = epsg;
    }

    // ---- Sources: anything that can yield the first bytes of a LAS/LAZ file ----

    /** A readable LAS/LAZ origin (local path or S3 object) with a display label. */
    interface Source {
        String label();
        /** Opens a stream at byte 0; for ranged sources, supplies at least {@code len} bytes. */
        InputStream open(int len) throws IOException;
    }

    private record LocalSource(Path path) implements Source {
        public String label() { return path.toString(); }
        public InputStream open(int len) throws IOException { return Files.newInputStream(path); }
    }

    private record S3Source(S3Client s3, String bucket, String key) implements Source {
        public String label() { return "s3://" + bucket + "/" + key; }
        public InputStream open(int len) {
            // Ranged GET: fetch only the leading bytes, never the point data.
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucket).key(key)
                    .range("bytes=0-" + (len - 1))
                    .build();
            return s3.getObject(req);
        }
    }

    // ---- Reading ----

    /** Reads the bounding box (and CRS, if present) from a local LAS/LAZ file. */
    public static LazBounds read(Path file) throws IOException {
        return read(new LocalSource(file));
    }

    static LazBounds read(Source source) throws IOException {
        byte[] buf = readPrefix(source, INITIAL_READ);
        if (buf.length < MIN_HEADER_SIZE) {
            throw new IllegalArgumentException("Too short to contain a LAS header: " + source.label());
        }
        if (!"LASF".equals(new String(buf, OFF_SIGNATURE, 4, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Not a LAS/LAZ file (bad signature): " + source.label());
        }

        ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        double minX = b.getDouble(OFF_MIN_X), maxX = b.getDouble(OFF_MAX_X);
        double minY = b.getDouble(OFF_MIN_Y), maxY = b.getDouble(OFF_MAX_Y);

        int headerSize = u16(buf, OFF_HEADER_SIZE);
        long offsetToPointData = u32(buf, OFF_OFFSET_TO_POINT_DATA);
        long numVlrs = u32(buf, OFF_NUMBER_OF_VLRS);

        Integer epsg = null;
        if (numVlrs > 0 && headerSize >= MIN_HEADER_SIZE && offsetToPointData > headerSize) {
            // The VLR block is [headerSize, offsetToPointData). Make sure we've read it.
            if (offsetToPointData > buf.length && offsetToPointData <= VLR_READ_CAP) {
                buf = readPrefix(source, (int) offsetToPointData);
            }
            int vlrEnd = (int) Math.min(offsetToPointData, buf.length);
            epsg = findEpsg(buf, headerSize, vlrEnd, numVlrs);
        }

        return new LazBounds(minX, maxX, minY, maxY, epsg);
    }

    /** Reads up to {@code len} leading bytes from the source (may return fewer at EOF). */
    private static byte[] readPrefix(Source source, int len) throws IOException {
        try (InputStream in = source.open(len)) {
            return in.readNBytes(len);
        }
    }

    // ---- CRS extraction from projection VLRs ----

    /** Scans the VLR block for a projection record and returns its EPSG code, or null. */
    private static Integer findEpsg(byte[] buf, int start, int end, long numVlrs) {
        Integer projected = null, geographic = null, fromWkt = null;
        int pos = start;
        for (long i = 0; i < numVlrs && pos + VLR_HEADER_LEN <= end; i++) {
            String userId = cstr(buf, pos + 2, 16);
            int recordId = u16(buf, pos + 18);
            int recordLen = u16(buf, pos + 20);
            int dataStart = pos + VLR_HEADER_LEN;
            if (dataStart + recordLen > end) break;   // VLR data truncated in our buffer

            if (recordId == GEOKEY_DIRECTORY_RECORD_ID) {
                int[] codes = geoKeyEpsgs(buf, dataStart, recordLen);
                if (codes[0] != 0) projected = codes[0];
                if (codes[1] != 0) geographic = codes[1];
            } else if (recordId == OGC_WKT_RECORD_ID && PROJECTION_USER_ID.equals(userId)) {
                fromWkt = wktEpsg(new String(buf, dataStart, recordLen, StandardCharsets.UTF_8));
            }
            pos = dataStart + recordLen;
        }
        // Prefer a projected CRS; then an explicit WKT code; then a geographic CRS.
        return projected != null ? projected : (fromWkt != null ? fromWkt : geographic);
    }

    /**
     * Parses a GeoTIFF GeoKeyDirectoryTag and returns {@code [projectedEpsg, geographicEpsg]}
     * (0 where absent). Only directly-encoded codes (TIFFTagLocation == 0) are read.
     */
    private static int[] geoKeyEpsgs(byte[] buf, int start, int len) {
        int projected = 0, geographic = 0;
        if (len >= 8) {
            int numKeys = u16(buf, start + 6);
            for (int k = 0; k < numKeys; k++) {
                int e = start + 8 + k * 8;
                if (e + 8 > start + len) break;
                int keyId = u16(buf, e);
                int location = u16(buf, e + 2);
                int value = u16(buf, e + 6);
                if (location != 0 || value == GEOKEY_UNDEFINED || value == GEOKEY_USER_DEFINED) continue;
                if (keyId == KEY_PROJECTED_CS) projected = value;
                else if (keyId == KEY_GEOGRAPHIC_CS) geographic = value;
            }
        }
        return new int[] { projected, geographic };
    }

    /**
     * Extracts the EPSG code of the file's <em>horizontal</em> CRS from an OGC WKT
     * string, or null.
     * <p>
     * A LAS/LAZ projection VLR often carries a compound CRS — e.g.
     * {@code COMPD_CS["...", PROJCS[... AUTHORITY["EPSG","6441"]], VERT_CS[... AUTHORITY["EPSG","6360"]]]}
     * — pairing a horizontal (projected/geographic) CRS with a vertical height CRS.
     * Scanning the whole string for the last {@code AUTHORITY["EPSG",...]} would
     * return the <em>vertical</em> code (e.g. 6360 = "NAVD88 height (ftUS)"), which is
     * not a horizontal CRS and cannot georeference the bounding box. So we look inside
     * the projected sub-CRS first, then the geographic one, and only fall back to a
     * whole-string scan when neither is present.
     */
    private static Integer wktEpsg(String wkt) {
        String horizontal = firstBracketedSpan(wkt, "PROJCS", "PROJCRS");
        if (horizontal == null) {
            horizontal = firstBracketedSpan(wkt, "GEOGCS", "GEOGCRS", "GEODCRS");
        }
        Integer epsg = horizontal != null ? lastEpsg(horizontal) : null;
        return epsg != null ? epsg : lastEpsg(wkt);
    }

    /** The last EPSG authority code in a WKT fragment (the element's own code), or null. */
    private static Integer lastEpsg(String wkt) {
        Matcher m = WKT_EPSG.matcher(wkt);
        Integer last = null;
        while (m.find()) {
            last = Integer.valueOf(m.group(1));   // an element's own authority is its last match
        }
        return last;
    }

    /**
     * Returns the bracket-balanced substring of the first WKT element named by any of
     * {@code keywords} (e.g. {@code PROJCS[...]}), or null if none appears. Quoted names
     * are skipped so brackets inside them don't affect nesting; both {@code []} and
     * {@code ()} delimiters are accepted.
     */
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

    /** The substring from {@code open} (a '[' or '(') through its matching close bracket. */
    private static String balancedSpan(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '[' || c == '(') {
                    depth++;
                } else if (c == ']' || c == ')') {
                    if (--depth == 0) return s.substring(open, i + 1);
                }
            }
        }
        return s.substring(open);   // unbalanced; return the remainder
    }

    /**
     * Returns the bounding box as a WKT polygon (closed ring), prefixed with
     * {@code SRID=<epsg>;} (EWKT) when the file declares a known CRS, e.g.
     * {@code SRID=26917;POLYGON ((minX minY, maxX minY, maxX maxY, minX maxY, minX minY))}.
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

    // Little-endian unsigned reads from a byte array.
    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    // Reads a fixed-width, null-padded ASCII field and trims it.
    private static String cstr(byte[] b, int off, int len) {
        int end = off, max = off + len;
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.US_ASCII).trim();
    }

    // ---- CLI ----

    /**
     * Formats one RFC&nbsp;4180 CSV record: every field is wrapped in double quotes
     * and any embedded quote is doubled. Always quoting keeps the WKT field — which
     * contains commas — from being split, and makes the column boundary unambiguous.
     */
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
            System.err.println("Usage: java com.spotable.LazBounds -i <file|dir|glob|s3-uri> [-i ...] [-o out.csv]");
            System.err.println("  -i/--input  (repeatable) source to read:");
            System.err.println("    Local: a LAS/LAZ file, a directory (recursive *.las/*.laz),");
            System.err.println("           or a glob such as 'data/**/*.laz'.");
            System.err.println("    S3:    s3://bucket/key.laz (one object via ranged GET),");
            System.err.println("           s3://bucket/prefix/ (recursive *.las/*.laz), or");
            System.err.println("           s3://bucket/prefix/*.laz (glob over listed keys).");
            System.err.println("  -o/--out    also write the CSV rows to this file.");
            System.err.println("  Prints one CSV row per file to stdout: \"path\",\"wkt\".");
            return;
        }

        String[] inputArgs = inputs.toArray(new String[0]);
        boolean needsS3 = inputs.stream().anyMatch(a -> a.startsWith("s3://"));
        int failures;
        // Open the CSV file (if requested) for the whole run so every row is appended
        // to the same file; it's truncated/created on open.
        try (S3Client s3 = needsS3 ? S3Client.create() : null;
             Writer csv = outFile != null
                     ? Files.newBufferedWriter(outFile, StandardCharsets.UTF_8) : null) {
            List<Source> sources = collectSources(inputArgs, s3);
            if (sources.isEmpty()) {
                System.err.println("No LAS/LAZ files matched.");
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

    /**
     * Resolves CLI arguments into a sorted, de-duplicated list of sources. Each
     * argument may be a local file, a local directory (walked recursively for
     * {@code *.las}/{@code *.laz}), a local glob, or an {@code s3://} URI (single
     * object, {@code prefix/}, or glob). S3 globs/prefixes are resolved with
     * ListObjectsV2; the client is only used when an {@code s3://} argument appears.
     */
    static List<Source> collectSources(String[] args, S3Client s3) throws IOException {
        // TreeMap keeps output deterministic (sorted by label) and drops duplicates.
        Map<String, Source> sources = new TreeMap<>();
        for (String arg : args) {
            if (arg.startsWith("s3://")) {
                collectS3(arg, s3, sources);
            } else {
                collectLocal(arg, sources);
            }
        }
        return new ArrayList<>(sources.values());
    }

    // ---- Local resolution ----

    private static void collectLocal(String arg, Map<String, Source> out) throws IOException {
        Path p = Path.of(arg);
        if (Files.isDirectory(p)) {
            walkLocal(p, k -> true, true, out);
        } else if (Files.isRegularFile(p)) {
            addLocal(p, out);
        } else if (hasGlob(arg)) {
            expandLocalGlob(arg, out);
        } else {
            System.err.println("No such file or directory: " + arg);
        }
    }

    private static void walkLocal(Path dir, Predicate<Path> match, boolean lasOnly,
                                  Map<String, Source> out) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !lasOnly || isLasLaz(p.getFileName().toString()))
                .filter(match)
                .forEach(p -> addLocal(p, out));
        }
    }

    private static void expandLocalGlob(String pattern, Map<String, Source> out) throws IOException {
        // Anchor the glob to an absolute path so matching is unambiguous, then
        // walk from the longest leading directory that contains no glob characters.
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

    private static void addLocal(Path p, Map<String, Source> out) {
        out.put(p.toString(), new LocalSource(p));
    }

    // ---- S3 resolution ----

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
            // List under the longest non-glob prefix, then match keys against the glob.
            int g = indexOfGlob(key);
            int lastSlash = key.lastIndexOf('/', g);
            String prefix = lastSlash < 0 ? "" : key.substring(0, lastSlash + 1);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + key);
            listS3(s3, bucket, prefix, k -> matcher.matches(Path.of(k)), out);
        } else if (key.isEmpty() || key.endsWith("/")) {
            // Prefix ("directory") listing: every *.las/*.laz under it.
            listS3(s3, bucket, key, LazBounds::isLasLaz, out);
        } else {
            // Single object.
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
            if (key.endsWith("/")) continue;        // skip "folder" placeholder keys
            if (keyMatch.test(key)) {
                out.put("s3://" + bucket + "/" + key, new S3Source(s3, bucket, key));
                any = true;
            }
        }
        if (!any) {
            System.err.println("No matches under s3://" + bucket + "/" + prefix);
        }
    }

    // ---- helpers ----

    private static boolean isLasLaz(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".las") || lower.endsWith(".laz");
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
