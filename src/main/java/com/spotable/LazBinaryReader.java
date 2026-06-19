package com.spotable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Reads header-only metadata from a LAS or LAZ file: the 2D bounding box (min/max X and Y),
 * the point count, the horizontal CRS, its linear unit, and the vertical CRS.
 * <p>
 * A LAZ file begins with the same uncompressed LAS public header block as a
 * plain LAS file — only the point records that follow are compressed. Everything
 * read here lives in that header and its Variable Length Records, so we never
 * touch (or decompress) any point data.
 * <p>
 * Because only the leading bytes are needed, remote objects on S3 are read with a
 * single ranged GET — no full download, even for multi-gigabyte tiles.
 * <p>
 * The coordinate reference system, when declared in the file's projection VLR
 * (GeoTIFF GeoKeys or an OGC WKT VLR), is extracted so the bounding box can be
 * emitted as georeferenced EWKT ({@code SRID=<epsg>;POLYGON(...)}); the linear
 * unit (used to report area in metres) and the vertical CRS come from the same VLR.
 */
public class LazBinaryReader {

    // Byte offsets of fields within the public header block.
    private static final int OFF_SIGNATURE = 0;          // "LASF" (4 bytes)
    private static final int OFF_HEADER_SIZE = 94;        // u16
    private static final int OFF_OFFSET_TO_POINT_DATA = 96; // u32
    private static final int OFF_NUMBER_OF_VLRS = 100;   // u32
    private static final int OFF_VERSION_MINOR = 25;     // u8
    private static final int OFF_LEGACY_POINT_COUNT = 107; // u32 (LAS 1.0-1.3, legacy in 1.4)
    private static final int OFF_MAX_X = 179;            // doubles from here on
    private static final int OFF_MIN_X = 187;
    private static final int OFF_MAX_Y = 195;
    private static final int OFF_MIN_Y = 203;
    private static final int OFF_EXTENDED_POINT_COUNT = 247; // u64 (LAS 1.4 header)

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
    // GeoKey ids, EPSG unit codes, and the WKT/GeoKey parsing all live in GeoCrs.

    public final double minX, maxX, minY, maxY;
    /** EPSG code of the file's CRS, or {@code null} if none could be determined. */
    public final Integer epsg;
    /** Number of point records declared in the header. */
    public final long pointCount;
    /** Metres per horizontal CRS unit (1.0 for metre CRSs, ~0.3048 for US-foot CRSs). */
    public final double unitMetres;
    /** EPSG code of the file's vertical CRS (e.g. 5703 = NAVD88 height), or {@code null}. */
    public final Integer verticalEpsg;
    /** Human-readable vertical CRS name (e.g. {@code NAVD88 height (m)}), or {@code null}. */
    public final String verticalCrs;

    private LazBinaryReader(double minX, double maxX, double minY, double maxY, Integer epsg,
                      long pointCount, double unitMetres, Integer verticalEpsg, String verticalCrs) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.epsg = epsg;
        this.pointCount = pointCount;
        this.unitMetres = unitMetres;
        this.verticalEpsg = verticalEpsg;
        this.verticalCrs = verticalCrs;
    }

    /** Ground area of the bounding box in square metres (uses {@link #unitMetres}). */
    public double areaSqMetres() {
        return (maxX - minX) * (maxY - minY) * unitMetres * unitMetres;
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
    public static LazBinaryReader read(Path file) throws IOException {
        return read(new LocalSource(file));
    }

    /** Reads header bounds/CRS/point-count from an S3 object via a single ranged GET. */
    public static LazBinaryReader readS3(S3Client s3, String bucket, String key) throws IOException {
        return read(new S3Source(s3, bucket, key));
    }

    static LazBinaryReader read(Source source) throws IOException {
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

        // Point count: LAS 1.4 keeps a u64 in the larger header; older versions a u32. The legacy
        // field is 0 in some 1.4 files, so prefer the extended field when the header reaches it.
        int versionMinor = buf[OFF_VERSION_MINOR] & 0xFF;
        long pointCount = u32(buf, OFF_LEGACY_POINT_COUNT);
        if (versionMinor >= 4 && headerSize >= OFF_EXTENDED_POINT_COUNT + 8) {
            long extended = u64(buf, OFF_EXTENDED_POINT_COUNT);
            if (extended > 0) pointCount = extended;
        }

        Integer epsg = null;
        double unitMetres = 1.0;
        Vert vert = new Vert(null, null);
        if (numVlrs > 0 && headerSize >= MIN_HEADER_SIZE && offsetToPointData > headerSize) {
            // The VLR block is [headerSize, offsetToPointData). Make sure we've read it.
            if (offsetToPointData > buf.length && offsetToPointData <= VLR_READ_CAP) {
                buf = readPrefix(source, (int) offsetToPointData);
            }
            int vlrEnd = (int) Math.min(offsetToPointData, buf.length);
            epsg = findEpsg(buf, headerSize, vlrEnd, numVlrs);
            unitMetres = findUnitMetres(buf, headerSize, vlrEnd, numVlrs);
            vert = findVertical(buf, headerSize, vlrEnd, numVlrs);
        }

        return new LazBinaryReader(minX, maxX, minY, maxY, epsg, pointCount, unitMetres,
                vert.epsg(), vert.name());
    }

    /** A file's vertical CRS: EPSG code and/or human-readable name (either may be null). */
    private record Vert(Integer epsg, String name) {}

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
                int[] codes = GeoCrs.geoKeyEpsgs(geoKeyShorts(buf, dataStart, recordLen));
                if (codes[0] != 0) projected = codes[0];
                if (codes[1] != 0) geographic = codes[1];
            } else if (recordId == OGC_WKT_RECORD_ID && PROJECTION_USER_ID.equals(userId)) {
                fromWkt = GeoCrs.horizontalEpsg(new String(buf, dataStart, recordLen, StandardCharsets.UTF_8));
            }
            pos = dataStart + recordLen;
        }
        // Prefer a projected CRS; then an explicit WKT code; then a geographic CRS.
        return projected != null ? projected : (fromWkt != null ? fromWkt : geographic);
    }

    /** The GeoKeyDirectory VLR payload as its sequence of u16 shorts (for {@link GeoCrs}). */
    private static int[] geoKeyShorts(byte[] buf, int dataStart, int recordLen) {
        int n = recordLen / 2;
        int[] gk = new int[n];
        for (int i = 0; i < n; i++) gk[i] = u16(buf, dataStart + 2 * i);
        return gk;
    }

    /**
     * Scans the VLR block for the horizontal CRS's linear unit and returns metres-per-unit.
     * Reads the WKT {@code PROJCS} {@code UNIT} factor when present, else the GeoTIFF
     * {@code ProjLinearUnitsGeoKey}; defaults to 1.0 (metre) when neither is found.
     */
    private static double findUnitMetres(byte[] buf, int start, int end, long numVlrs) {
        Double fromWkt = null, fromGeoKey = null;
        int pos = start;
        for (long i = 0; i < numVlrs && pos + VLR_HEADER_LEN <= end; i++) {
            String userId = cstr(buf, pos + 2, 16);
            int recordId = u16(buf, pos + 18);
            int recordLen = u16(buf, pos + 20);
            int dataStart = pos + VLR_HEADER_LEN;
            if (dataStart + recordLen > end) break;

            if (recordId == OGC_WKT_RECORD_ID && PROJECTION_USER_ID.equals(userId)) {
                fromWkt = GeoCrs.projUnitMetres(new String(buf, dataStart, recordLen, StandardCharsets.UTF_8));
            } else if (recordId == GEOKEY_DIRECTORY_RECORD_ID) {
                int uom = GeoCrs.geoKey(geoKeyShorts(buf, dataStart, recordLen), GeoCrs.KEY_PROJ_LINEAR_UNITS);
                if (uom != 0) fromGeoKey = GeoCrs.metresPerUnit(uom);
            }
            pos = dataStart + recordLen;
        }
        if (fromWkt != null) return fromWkt;
        return fromGeoKey != null ? fromGeoKey : 1.0;
    }

    /**
     * Scans the VLR block for the file's vertical CRS. Reads the WKT {@code VERT_CS} name and its
     * EPSG when present, else the GeoTIFF {@code VerticalCSTypeGeoKey} code; null fields when absent.
     */
    private static Vert findVertical(byte[] buf, int start, int end, long numVlrs) {
        Integer epsg = null, geoKeyEpsg = null;
        String name = null;
        int pos = start;
        for (long i = 0; i < numVlrs && pos + VLR_HEADER_LEN <= end; i++) {
            String userId = cstr(buf, pos + 2, 16);
            int recordId = u16(buf, pos + 18);
            int recordLen = u16(buf, pos + 20);
            int dataStart = pos + VLR_HEADER_LEN;
            if (dataStart + recordLen > end) break;

            if (recordId == OGC_WKT_RECORD_ID && PROJECTION_USER_ID.equals(userId)) {
                String wkt = new String(buf, dataStart, recordLen, StandardCharsets.UTF_8);
                String vert = GeoCrs.firstBracketedSpan(wkt, "VERT_CS", "VERTCRS", "VERTICALCRS");
                if (vert != null) {
                    epsg = GeoCrs.lastEpsg(vert);
                    Matcher m = GeoCrs.NAME.matcher(vert);
                    if (m.find()) name = m.group(1);
                }
            } else if (recordId == GEOKEY_DIRECTORY_RECORD_ID) {
                int v = GeoCrs.geoKey(geoKeyShorts(buf, dataStart, recordLen), GeoCrs.KEY_VERTICAL_CS);
                if (v != 0) geoKeyEpsg = v;
            }
            pos = dataStart + recordLen;
        }
        return new Vert(epsg != null ? epsg : geoKeyEpsg, name);
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

    private static long u64(byte[] b, int o) {
        long lo = u32(b, o), hi = u32(b, o + 4);
        return lo | (hi << 32);
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
            System.err.println("Usage: java com.spotable.LazBinaryReader -i <file|dir|glob|s3-uri> [-i ...] [-o out.csv]");
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
            List<Source> sources = Sources.collect(inputArgs, s3, LazBinaryReader::isLasLaz,
                    LocalSource::new, (bucket, key) -> new S3Source(s3, bucket, key));
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

    /** Name filter passed to {@link Sources}: keeps {@code *.las}/{@code *.laz} during listings. */
    private static boolean isLasLaz(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".las") || lower.endsWith(".laz");
    }
}
