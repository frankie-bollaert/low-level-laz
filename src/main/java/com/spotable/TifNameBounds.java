package com.spotable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives each USGS 3DEP 1-meter DEM tile's WGS84 footprint <em>from its filename alone</em>
 * (no file I/O against the rasters), and writes a CSV of {@code filename,geometry}.
 * <p>
 * This is the filename-only counterpart to {@link TifBinaryReader}, which reads the real GeoTIFF
 * headers. It exists because the USGS tile naming convention already encodes the footprint:
 * <pre>
 *   USGS_1M_&lt;zone&gt;_x&lt;XX&gt;y&lt;YYY&gt;_&lt;project&gt;.tif
 * </pre>
 * where {@code x}/{@code y} are the tile's NW corner in UTM, in units of 10,000 m, and each
 * tile spans a nominal 10&nbsp;km &times; 10&nbsp;km block. So {@code x43y313} is the block
 * easting 430000&ndash;440000, northing 3120000&ndash;3130000. (The delivered raster carries a
 * ~6&nbsp;m collar on each edge; that is ignored here, as the goal is a tile index footprint.)
 * <p>
 * Two older naming forms omit the zone:
 * <pre>
 *   USGS_1m_x&lt;XX&gt;y&lt;YYY&gt;_&lt;project&gt;.tif
 *   USGS_one_meter_x&lt;XX&gt;y&lt;YYY&gt;_&lt;project&gt;.tif
 * </pre>
 * A UTM easting is valid in any zone, so the zone genuinely cannot be recovered from these
 * names. {@link #LEGACY_ZONE} maps each such project to the UTM zone read from its GeoTIFF
 * CRS metadata (NAD83 / UTM zone 16N or 17N — Florida straddles the 84&deg;W boundary).
 * <p>
 * The four UTM corners are inverse-projected to geographic lon/lat with {@link Utm} (WGS84
 * ellipsoid) and emitted as EWKT {@code SRID=4326;POLYGON ((...))}, matching {@link TifBinaryReader}.
 */
public final class TifNameBounds {

    /** USGS tile side length in metres (nominal 10 km block). */
    private static final double TILE = 10_000.0;

    /** WGS84 SRID for the reprojected output. */
    private static final int WGS84 = 4326;

    // Modern form carries the UTM zone explicitly:  USGS_1M_<zone>_xXXyYYY_
    private static final Pattern WITH_ZONE =
            Pattern.compile("USGS_1M_(\\d+)_x(\\d+)y(\\d+)_");
    // Legacy forms omit the zone:  USGS_1m_xXXyYYY_  /  USGS_one_meter_xXXyYYY_
    private static final Pattern NO_ZONE =
            Pattern.compile("USGS_(?:1m|one_meter)_x(\\d+)y(\\d+)_");

    /**
     * UTM zone per project for the zone-less legacy filenames, read from each project's
     * GeoTIFF CRS (the filename cannot encode it). All Florida 3DEP, NAD83 / UTM 16N or 17N.
     */
    private static final Map<String, Integer> LEGACY_ZONE = Map.ofEntries(
            Map.entry("FL_GulfCoast_Topography_2018", 16),
            Map.entry("FL_LeonCo_2018", 17),
            Map.entry("FL_Lower_Choctawhatchee_2017", 16),
            Map.entry("FL_Lower_Choctawhatchee_TL_2017", 16),
            Map.entry("FL_Osceola_2015", 17),
            Map.entry("FL_PalmBeachCo_2016", 17),
            Map.entry("FL_Panhandle_B1_2018", 17),
            Map.entry("FL_Panhandle_B2_2018", 16),
            Map.entry("FL_Panhandle_B3_2018", 17),
            Map.entry("FL_PeaceRiver_2014", 17),
            Map.entry("FL_SRWMD_NC_2015", 17),
            Map.entry("FL_Southeast_B1_2018", 17),
            Map.entry("FL_Southeast_B2_2018", 17),
            Map.entry("FL_Southeast_TL_2018", 17),
            Map.entry("FL_Southwest_A_2018", 17),
            Map.entry("FL_Southwest_B_2018", 17),
            Map.entry("FL_Southwest_B_TL_2018", 17),
            Map.entry("FL_SuwanneeRiver_2017", 17),
            Map.entry("FL_Upper_Saint_Johns_2017", 17));

    private TifNameBounds() {}

    /** Resolved tile: UTM zone plus integer NW-corner indices (in units of {@link #TILE}). */
    record Tile(int zone, int xi, int yi) {}

    /**
     * Parses a USGS DEM path/filename into its tile, or returns {@code null} if it matches no
     * known naming form (or is a zone-less form from an unmapped project).
     */
    static Tile parse(String line) {
        Matcher m = WITH_ZONE.matcher(line);
        if (m.find()) {
            return new Tile(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        m = NO_ZONE.matcher(line);
        if (m.find()) {
            Integer zone = LEGACY_ZONE.get(project(line));
            if (zone == null) return null;
            return new Tile(zone, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return null;
    }

    /** Project name = first path segment (everything before the first {@code /}). */
    private static String project(String line) {
        int slash = line.indexOf('/');
        return slash < 0 ? line : line.substring(0, slash);
    }

    /**
     * The tile's WGS84 footprint as EWKT {@code SRID=4326;POLYGON ((...))}, lon/lat ordered,
     * ring SW &rarr; SE &rarr; NE &rarr; NW &rarr; SW.
     */
    static String toWgs84Wkt(Tile t) {
        double west = t.xi() * TILE;
        double north = t.yi() * TILE;
        double east = west + TILE;
        double south = north - TILE;

        double[] sw = Utm.toLonLat(west, south, t.zone());
        double[] se = Utm.toLonLat(east, south, t.zone());
        double[] ne = Utm.toLonLat(east, north, t.zone());
        double[] nw = Utm.toLonLat(west, north, t.zone());

        String polygon = String.format(Locale.ROOT,
                "POLYGON ((%s %s, %s %s, %s %s, %s %s, %s %s))",
                fmt(sw[0]), fmt(sw[1]),
                fmt(se[0]), fmt(se[1]),
                fmt(ne[0]), fmt(ne[1]),
                fmt(nw[0]), fmt(nw[1]),
                fmt(sw[0]), fmt(sw[1]));
        return "SRID=" + WGS84 + ";" + polygon;
    }

    private static String fmt(double deg) {
        return String.format(Locale.ROOT, "%.8f", deg);
    }

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
        Path input = null, output = null, mergedOut = null;
        String prefix = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--input", "-i" -> input = Path.of(requireArg(args, ++i, a));
                case "--output", "-o" -> output = Path.of(requireArg(args, ++i, a));
                case "--merged" -> mergedOut = Path.of(requireArg(args, ++i, a));
                case "--prefix" -> prefix = requireArg(args, ++i, a).replaceAll("/+$", "");
                default -> { System.err.println("Unknown argument: " + a); System.exit(2); }
            }
        }
        if (input == null) {
            System.err.println("Usage: java com.spotable.TifNameBounds --input <dtm.txt> [--output <bounds.csv>]");
            System.err.println("                                       [--merged <file>] [--prefix <base>]");
            System.err.println("  --input   (-i) list of USGS DEM paths, one per line (required).");
            System.err.println("  --output  (-o) per-tile CSV \"<project>\",\"<filename>\",\"SRID=4326;POLYGON ((...))\"");
            System.err.println("                 (default: stdout). Other naming forms are skipped (logged to stderr).");
            System.err.println("  --merged  also write one row per sub-project:");
            System.err.println("            \"<project>\",\"<directory>\",\"<files>\",\"<year>\",\"<EPSG>\",\"<horizontal CRS>\",\"SRID=4326;MULTIPOLYGON(...)\"");
            System.err.println("            (the union of that project's 10 km tiles, gaps preserved).");
            System.err.println("  --prefix  base path (e.g. s3://bucket/dtm/us) prepended to the directory column.");
            System.exit(2);
            return;
        }

        // Pass 1: collect the recognised tiles, as LazNameBounds.Matched so the merge can be reused.
        java.util.List<LazNameBounds.Matched> matched = new java.util.ArrayList<>();
        int skipped = 0;
        try (BufferedReader in = Files.newBufferedReader(input, StandardCharsets.UTF_8);
             Writer out = output != null
                     ? Files.newBufferedWriter(output, StandardCharsets.UTF_8) : null) {
            String header = csvRow("project", "filename", "geometry");
            if (out != null) { out.write(header); out.write('\n'); }
            else if (mergedOut == null) System.out.println(header);
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                Tile t = parse(line);
                if (t == null) {
                    System.err.println("Skipping unrecognised name: " + line);
                    skipped++;
                    continue;
                }
                String project = LazNameBounds.groupKey(line);
                matched.add(new LazNameBounds.Matched(line, project, LazNameBounds.Proj.utm(t.zone()),
                        t.xi() * TILE, t.yi() * TILE - TILE, TILE));   // SW corner; tile = 10 km
                String row = csvRow(project, line, toWgs84Wkt(t));
                if (out != null) { out.write(row); out.write('\n'); }
                else if (mergedOut == null) System.out.println(row);
            }
        }
        System.err.println("Wrote " + matched.size() + " rows" + (skipped > 0 ? ", skipped " + skipped : ""));

        if (mergedOut != null) {
            java.util.Map<String, java.util.List<LazNameBounds.Matched>> byProject = new java.util.TreeMap<>();
            for (LazNameBounds.Matched m : matched) {
                byProject.computeIfAbsent(m.project(), k -> new java.util.ArrayList<>()).add(m);
            }
            try (Writer out = Files.newBufferedWriter(mergedOut, StandardCharsets.UTF_8)) {
                out.write(csvRow("project", "directory", "files", "year",
                        "horizontal_epsg", "horizontal_projection", "geometry"));
                out.write('\n');
                for (var e : byProject.entrySet()) {
                    java.util.List<LazNameBounds.Matched> g = e.getValue();
                    String directory = LazNameBounds.groupDir(g.get(0).line());
                    if (prefix != null) directory = prefix + "/" + directory.replaceAll("^/+", "");
                    java.util.TreeSet<String> epsg = new java.util.TreeSet<>();
                    java.util.TreeSet<String> crs = new java.util.TreeSet<>();
                    for (LazNameBounds.Matched m : g) { epsg.add(m.proj().epsg()); crs.add(m.proj().crs()); }
                    out.write(csvRow(e.getKey(), directory, Integer.toString(g.size()),
                            LazNameBounds.year(e.getKey(), g.get(0).line()),
                            String.join("; ", epsg), String.join("; ", crs),
                            LazNameBounds.mergedWkt(g, TILE)));
                    out.write('\n');
                }
            }
            System.err.println("Wrote " + byProject.size() + " merged project rows to " + mergedOut);
        }
        if (skipped > 0) System.exit(1);
    }

    /** Returns {@code args[i]} or exits with an error if the flag {@code flag} has no value. */
    private static String requireArg(String[] args, int i, String flag) {
        if (i >= args.length) { System.err.println(flag + " needs a value"); System.exit(2); }
        return args[i];
    }

    /**
     * Inverse UTM (projected easting/northing &rarr; geographic lon/lat) on the WGS84 ellipsoid,
     * northern hemisphere. Snyder's series, accurate to well under a millimetre at UTM scales.
     * NAD83 source coordinates differ from WGS84 by ~1 m, which is immaterial for a tile index.
     */
    static final class Utm {
        private static final double A = 6_378_137.0;            // WGS84 semi-major axis
        private static final double F = 1.0 / 298.257223563;    // flattening
        private static final double K0 = 0.9996;                // UTM scale factor
        private static final double E0 = 500_000.0;             // false easting
        private static final double E2 = F * (2 - F);           // first eccentricity squared
        private static final double EP2 = E2 / (1 - E2);        // second eccentricity squared

        private Utm() {}

        /** @return {@code {lonDegrees, latDegrees}} for a northern-hemisphere UTM coordinate. */
        static double[] toLonLat(double easting, double northing, int zone) {
            double lon0 = Math.toRadians(zone * 6 - 183);       // central meridian
            double x = easting - E0;
            double y = northing;                                // false northing 0 (N hemisphere)

            double m = y / K0;
            double mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256));

            double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
            double phi1 = mu
                    + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                    + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                    + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                    + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);

            double sin1 = Math.sin(phi1), cos1 = Math.cos(phi1), tan1 = Math.tan(phi1);
            double c1 = EP2 * cos1 * cos1;
            double t1 = tan1 * tan1;
            double n1 = A / Math.sqrt(1 - E2 * sin1 * sin1);
            double r1 = A * (1 - E2) / Math.pow(1 - E2 * sin1 * sin1, 1.5);
            double d = x / (n1 * K0);

            double lat = phi1 - (n1 * tan1 / r1) * (d * d / 2
                    - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * Math.pow(d, 4) / 24
                    + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1)
                      * Math.pow(d, 6) / 720);

            double lon = lon0 + (d
                    - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                    + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1)
                      * Math.pow(d, 5) / 120) / cos1;

            return new double[] { Math.toDegrees(lon), Math.toDegrees(lat) };
        }
    }
}
