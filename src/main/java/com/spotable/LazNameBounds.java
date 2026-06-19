package com.spotable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a USGS LPC point-cloud tile's WGS84 footprint <em>from its filename alone</em>
 * for the MGRS-gridded naming form, and writes a CSV of {@code filename,geometry}. This is
 * the point-cloud counterpart to {@link TifNameBounds}.
 * <p>
 * It handles the "FortDrum-style" tile id, an MGRS grid reference embedded in the name:
 * <pre>
 *   USGS_LPC_&lt;project&gt;_&lt;zone&gt;&lt;band&gt;&lt;col&gt;&lt;row&gt;&lt;EEE&gt;&lt;NNN&gt;.laz
 *   e.g.  USGS_LPC_FL_2017FortDrum_C22_17RNL070510.laz
 * </pre>
 * where {@code 17}=UTM zone, {@code R}=latitude band, {@code NL}=100&nbsp;km square (column N,
 * row L), and {@code 070}/{@code 510} are the SW-corner easting/northing <em>within</em> that
 * square at 100&nbsp;m precision (so {@code 070}&rarr;7000&nbsp;m E, {@code 510}&rarr;51000&nbsp;m N).
 * The 100&nbsp;km square is resolved to absolute UTM via the MGRS column/row lettering, the
 * SW corner plus a square {@link #tileMetres} tile is reprojected to lon/lat with
 * {@link TifNameBounds.Utm}, and emitted as EWKT {@code SRID=4326;POLYGON ((...))}.
 * <p>
 * It also decodes two grid-indexed families whose corner is encoded in the project's own CRS,
 * each gated to projects verified against the actual LAZ header bounds + CRS VLR:
 * <ul>
 *   <li>Florida State Plane LID grids ({@code ..._LID2019_447196_W.laz}); see {@link #parseLid}.</li>
 *   <li>USGS {@code eNNNNnNNNN}/{@code wNNNNnNNNN} grids ({@code ..._e0853n0913.laz} in CONUS
 *       Albers, {@code ..._w314000n3291500.laz} in UTM); see {@link #parseEastNorth}.</li>
 *   <li>Florida State Plane names without the {@code LID} token: a packed column/row index
 *       ({@code ..._038576_N.laz}, {@code ..._471241.laz}) or an absolute-feet corner
 *       ({@code ..._692500_725000.laz}); see {@link #parseStatePlane}.</li>
 * </ul>
 * Other LPC naming families in the wild are out of scope here and are skipped (logged to
 * stderr): EPT octree-node keys ({@code ept-data/12-339-2750-2044.laz}, geometry not in the
 * name), and unverified per-project schemes such as {@code 692500_725000}, {@code GAW_20100945}.
 * <p>
 * The tile size is a project property the single name can't carry, so it is derived per
 * project from the minimum grid step among that project's tiles in the input list (e.g.
 * 1500&nbsp;m for FortDrum, 1000&nbsp;m for Everglades, 500&nbsp;m for Choctawhatchee). A fixed
 * size for all projects can be forced with {@code --tile <metres>}.
 */
public final class LazNameBounds {

    private static final int WGS84 = 4326;

    /** MGRS tile id at the end of the name: zone, band, col, row, then 3+3 digits, then .laz. */
    private static final Pattern MGRS_TILE = Pattern.compile(
            "_(\\d{2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d{3})(\\d{3})\\.laz$",
            Pattern.CASE_INSENSITIVE);

    // MGRS lettering, with the ambiguous I and O omitted.
    private static final String COLS_SET0 = "ABCDEFGH";   // zones where (zone-1)%3 == 0
    private static final String COLS_SET1 = "JKLMNPQR";   //                          == 1
    private static final String COLS_SET2 = "STUVWXYZ";   //                          == 2
    private static final String ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";  // 20, each 100 km
    private static final String BAND_LETTERS = "CDEFGHJKLMNPQRSTUVWX"; // 20, each 8deg from -80

    private static final double HUNDRED_KM = 100_000.0;
    private static final double NORTHING_CYCLE = 2_000_000.0;

    private LazNameBounds() {}

    /** A resolved MGRS tile: UTM zone and the SW-corner easting/northing in metres. */
    record Tile(int zone, double swEasting, double swNorthing) {}

    /** Parses a USGS LPC MGRS-style name into its tile, or {@code null} if it is another form. */
    static Tile parse(String line) {
        Matcher m = MGRS_TILE.matcher(line);
        if (!m.find()) return null;
        int zone = Integer.parseInt(m.group(1));
        char band = Character.toUpperCase(m.group(2).charAt(0));
        char col = Character.toUpperCase(m.group(3).charAt(0));
        char row = Character.toUpperCase(m.group(4).charAt(0));
        double eastOff = Integer.parseInt(m.group(5)) * 100.0;   // 3 digits at 100 m
        double northOff = Integer.parseInt(m.group(6)) * 100.0;

        double baseE = squareEasting(zone, col);
        double baseN = squareNorthing(zone, band, row);
        if (baseE < 0 || baseN < 0) return null;  // letter not valid for this zone
        return new Tile(zone, baseE + eastOff, baseN + northOff);
    }

    /** Absolute easting (m) of the 100 km square's west edge from its column letter. */
    private static double squareEasting(int zone, char col) {
        String set = switch ((zone - 1) % 3) {
            case 0 -> COLS_SET0;
            case 1 -> COLS_SET1;
            default -> COLS_SET2;
        };
        int ci = set.indexOf(col);
        return ci < 0 ? -1 : (ci + 1) * HUNDRED_KM;   // columns map to 100..800 km
    }

    /** Absolute northing (m) of the 100 km square's south edge from band + row letter. */
    private static double squareNorthing(int zone, char band, char row) {
        int ri = ROW_LETTERS.indexOf(row);
        int bi = BAND_LETTERS.indexOf(band);
        if (ri < 0 || bi < 0) return -1;
        // Even zones start the row lettering 5 places north of odd zones.
        if (zone % 2 == 0) ri = (ri - 5 + ROW_LETTERS.length()) % ROW_LETTERS.length();
        double northing = ri * HUNDRED_KM;
        // Lift into the correct 2,000,000 m cycle using the latitude band's south edge.
        double bandMin = K0 * meridianArc(Math.toRadians(-80 + 8 * bi));
        while (northing < bandMin) northing += NORTHING_CYCLE;
        return northing;
    }

    /** MGRS tile footprint as EWKT (reprojected from its UTM zone). */
    static String toWgs84Wkt(Tile t, double tileMetres) {
        return toWgs84Wkt(Proj.utm(t.zone()), t.swEasting(), t.swNorthing(), tileMetres);
    }

    /** Tile footprint as EWKT {@code SRID=4326;POLYGON ((...))}, ring SW, SE, NE, NW, SW. */
    static String toWgs84Wkt(Proj proj, double swE, double swN, double tileMetres) {
        double w = swE, s = swN, e = swE + tileMetres, n = swN + tileMetres;
        double[] sw = proj.lonLat(w, s);
        double[] se = proj.lonLat(e, s);
        double[] ne = proj.lonLat(e, n);
        double[] nw = proj.lonLat(w, n);
        String polygon = String.format(Locale.ROOT,
                "POLYGON ((%s %s, %s %s, %s %s, %s %s, %s %s))",
                fmt(sw[0]), fmt(sw[1]), fmt(se[0]), fmt(se[1]),
                fmt(ne[0]), fmt(ne[1]), fmt(nw[0]), fmt(nw[1]),
                fmt(sw[0]), fmt(sw[1]));
        return "SRID=" + WGS84 + ";" + polygon;
    }

    private static String fmt(double deg) {
        return String.format(Locale.ROOT, "%.8f", deg);
    }

    // ---- WGS84 meridian arc, for resolving the MGRS northing cycle ----

    private static final double A = 6_378_137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double E2 = F * (2 - F);
    private static final double EP2 = E2 / (1 - E2);
    private static final double K0 = 0.9996;

    /** Meridian distance from the equator to latitude {@code phi} (radians) on WGS84. */
    private static double meridianArc(double phi) {
        double e2 = E2, e4 = e2 * e2, e6 = e4 * e2;
        return A * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi)
                - (35 * e6 / 3072) * Math.sin(6 * phi));
    }

    // ---- Projections: inverse Transverse Mercator + Lambert Conformal Conic ----

    /** A map projection that can be inverted to WGS84 lon/lat, with its native CRS identity. */
    sealed interface Proj permits TmProj, LccProj, AeaProj {
        double[] lonLat(double easting, double northing);
        /** EPSG code of the native horizontal CRS, e.g. {@code EPSG:26917}. */
        String epsg();
        /** Human-readable native horizontal CRS name, e.g. {@code NAD83 / UTM zone 17N}. */
        String crs();
        /** UTM zone as a Transverse-Mercator projection (metres), NAD83. */
        static Proj utm(int zone) {
            return new TmProj(zone * 6 - 183, 0, 0.9996, 500_000,
                    26900 + zone, "NAD83 / UTM zone " + zone + "N");
        }
        /** UTM zone as Transverse-Mercator with an explicit EPSG (e.g. NAD83(2011) 6345/6346). */
        static Proj utm(int zone, int epsgCode, String crs) {
            return new TmProj(zone * 6 - 183, 0, 0.9996, 500_000, epsgCode, crs);
        }
    }

    /** Transverse Mercator (UTM, or Florida State Plane East/West). N hemisphere, FN=0. */
    record TmProj(double lon0, double lat0, double k0, double fe, int epsgCode, String crs)
            implements Proj {
        public double[] lonLat(double e, double n) { return tmToLonLat(e, n, lon0, lat0, k0, fe); }
        public String epsg() { return "EPSG:" + epsgCode; }
    }

    /** Lambert Conformal Conic 2SP (Florida State Plane North). FN=0. */
    record LccProj(double lon0, double lat0, double lat1, double lat2, double fe,
                   int epsgCode, String crs) implements Proj {
        public double[] lonLat(double e, double n) {
            return lccToLonLat(e, n, lon0, lat0, lat1, lat2, fe);
        }
        public String epsg() { return "EPSG:" + epsgCode; }
    }

    /** Albers Equal-Area Conic (e.g. NAD83 / Conus Albers). FE/FN as given. */
    record AeaProj(double lon0, double lat0, double lat1, double lat2, double fe, double fn,
                   int epsgCode, String crs) implements Proj {
        public double[] lonLat(double e, double n) {
            return aeaToLonLat(e, n, lon0, lat0, lat1, lat2, fe, fn);
        }
        public String epsg() { return "EPSG:" + epsgCode; }
    }

    /** Inverse Albers Equal-Area Conic (ellipsoidal): easting/northing (metres) -> {lonDeg, latDeg}. */
    static double[] aeaToLonLat(double easting, double northing,
                               double lon0deg, double lat0deg, double lat1deg, double lat2deg,
                               double fe, double fn) {
        double e = Math.sqrt(E2);
        double lon0 = Math.toRadians(lon0deg), lat0 = Math.toRadians(lat0deg),
               lat1 = Math.toRadians(lat1deg), lat2 = Math.toRadians(lat2deg);
        double m1 = lccM(lat1, e), m2 = lccM(lat2, e);          // cosφ/√(1-e²sin²φ), shared with LCC
        double q0 = aeaQ(lat0, e), q1 = aeaQ(lat1, e), q2 = aeaQ(lat2, e);
        double n = (m1 * m1 - m2 * m2) / (q2 - q1);
        double c = m1 * m1 + n * q1;
        double rho0 = A * Math.sqrt(c - n * q0) / n;
        double x = easting - fe, y = northing - fn;
        double rho = Math.sqrt(x * x + (rho0 - y) * (rho0 - y));
        double theta = Math.atan2(x, rho0 - y);                 // n > 0 for these CRSs
        double q = (c - rho * rho * n * n / (A * A)) / n;
        double phi = Math.asin(q / 2);                          // initial guess
        for (int i = 0; i < 12; i++) {
            double s = Math.sin(phi), oneMinus = 1 - E2 * s * s;
            double dphi = oneMinus * oneMinus / (2 * Math.cos(phi))
                    * (q / (1 - E2) - s / oneMinus
                       + (1 / (2 * e)) * Math.log((1 - e * s) / (1 + e * s)));
            phi += dphi;
            if (Math.abs(dphi) < 1e-12) break;
        }
        double lon = lon0 + theta / n;
        return new double[]{ Math.toDegrees(lon), Math.toDegrees(phi) };
    }

    /** Authalic-area function q(φ) used by the Albers equal-area projection. */
    private static double aeaQ(double phi, double e) {
        double s = Math.sin(phi);
        return (1 - e * e) * (s / (1 - e * e * s * s)
                - (1 / (2 * e)) * Math.log((1 - e * s) / (1 + e * s)));
    }

    /** Inverse Transverse Mercator: easting/northing (metres) -> {lonDeg, latDeg}, WGS84 ellipsoid. */
    static double[] tmToLonLat(double easting, double northing,
                              double lon0deg, double lat0deg, double k0, double fe) {
        double x = easting - fe;
        double m = meridianArc(Math.toRadians(lat0deg)) + northing / k0;
        double mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256));
        double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
        double phi1 = mu
                + (3 * e1 / 2 - 27 * Math.pow(e1, 3) / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * Math.pow(e1, 4) / 32) * Math.sin(4 * mu)
                + (151 * Math.pow(e1, 3) / 96) * Math.sin(6 * mu)
                + (1097 * Math.pow(e1, 4) / 512) * Math.sin(8 * mu);
        double sin1 = Math.sin(phi1), cos1 = Math.cos(phi1), tan1 = Math.tan(phi1);
        double c1 = EP2 * cos1 * cos1, t1 = tan1 * tan1;
        double n1 = A / Math.sqrt(1 - E2 * sin1 * sin1);
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * sin1 * sin1, 1.5);
        double d = x / (n1 * k0);
        double lat = phi1 - (n1 * tan1 / r1) * (d * d / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * Math.pow(d, 4) / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * Math.pow(d, 6) / 720);
        double lon = Math.toRadians(lon0deg) + (d
                - (1 + 2 * t1 + c1) * Math.pow(d, 3) / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * Math.pow(d, 5) / 120) / cos1;
        return new double[]{ Math.toDegrees(lon), Math.toDegrees(lat) };
    }

    /** Inverse Lambert Conformal Conic (2SP): easting/northing (metres) -> {lonDeg, latDeg}. */
    static double[] lccToLonLat(double easting, double northing,
                               double lon0deg, double lat0deg, double lat1deg, double lat2deg, double fe) {
        double e = Math.sqrt(E2);
        double lon0 = Math.toRadians(lon0deg), lat0 = Math.toRadians(lat0deg),
               lat1 = Math.toRadians(lat1deg), lat2 = Math.toRadians(lat2deg);
        double m1 = lccM(lat1, e), m2 = lccM(lat2, e);
        double t0 = lccT(lat0, e), t1 = lccT(lat1, e), t2 = lccT(lat2, e);
        double n = (Math.log(m1) - Math.log(m2)) / (Math.log(t1) - Math.log(t2));
        double fc = m1 / (n * Math.pow(t1, n));
        double rho0 = A * fc * Math.pow(t0, n);
        double x = easting - fe, y = northing;                  // FN = 0
        double rho = Math.signum(n) * Math.sqrt(x * x + (rho0 - y) * (rho0 - y));
        double t = Math.pow(rho / (A * fc), 1 / n);
        double theta = Math.atan2(x, rho0 - y);
        double lon = lon0 + theta / n;
        double phi = Math.PI / 2 - 2 * Math.atan(t);             // converges in a few iterations
        for (int i = 0; i < 8; i++) {
            double es = e * Math.sin(phi);
            phi = Math.PI / 2 - 2 * Math.atan(t * Math.pow((1 - es) / (1 + es), e / 2));
        }
        return new double[]{ Math.toDegrees(lon), Math.toDegrees(phi) };
    }

    private static double lccM(double phi, double e) {
        return Math.cos(phi) / Math.sqrt(1 - e * e * Math.sin(phi) * Math.sin(phi));
    }

    private static double lccT(double phi, double e) {
        double es = e * Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2) / Math.pow((1 - es) / (1 + es), e / 2);
    }

    // ---- Florida State Plane LID-gridded names (e.g. ..._LID2019_447196_W.laz) ----
    //
    // These tiles carry a 6-digit index into a 5000-ft Florida State Plane grid:
    //     index = C - 300*row + col,  where row = N/5000ft, col = E/5000ft (SW corner)
    // col is always < 300 (the zone's easting span), so the index inverts uniquely. The origin
    // constant C is per State Plane zone (East/West), determined and verified against tile
    // metadata. It is shared by the FL_Peninsular_* projects below but is NOT statewide (e.g.
    // FL_ManateeCounty_B25 uses a different origin), so decoding is gated to confirmed projects.

    private static final double US_FOOT = 1200.0 / 3937.0;          // US survey foot in metres
    private static final double SP_K0 = 0.9999411764705882;         // FL TM zone scale (1 - 1/17000)
    private static final double SP_TM_LAT0 = 24 + 20.0 / 60.0;      // TM grid origin latitude 24°20'N
    private static final double SP_TM_FE = 200_000.0;               // TM false easting (metres)
    private static final Proj SP_WEST =
            new TmProj(-82, SP_TM_LAT0, SP_K0, SP_TM_FE, 2237, "NAD83 / Florida West ftUS");
    private static final Proj SP_EAST =
            new TmProj(-81, SP_TM_LAT0, SP_K0, SP_TM_FE, 2236, "NAD83 / Florida East ftUS");
    private static final Proj SP_NORTH =
            new LccProj(-84.5, 29.0, 30.75, 29 + 35.0 / 60.0, 600_000.0, 2238,
                    "NAD83 / Florida North ftUS");
    /** Per-State-Plane-zone grid: default origin constant C, the index modulus, and projection. */
    private record ZoneDef(int c, int modulus, Proj proj) {}

    // The index modulus is the zone's column span (must exceed any tile's column so the index
    // inverts uniquely): 300 for the TM zones, 540 for the wide North zone. C is the default
    // grid origin per zone; some projects override it (see LID_SCHEMES).
    private static final java.util.Map<Character, ZoneDef> LID_ZONES = java.util.Map.of(
            'W', new ZoneDef(549701, 300, SP_WEST),
            'E', new ZoneDef(349767, 300, SP_EAST),
            'N', new ZoneDef(704051, 540, SP_NORTH));

    /**
     * Per-project LID grid scheme (each verified against tile metadata):
     * {@code forcedZone} when the name carries no E/W/N suffix (else read from the suffix);
     * {@code overrideC} when the project's grid origin differs from the zone default;
     * {@code tileFt} tile size (5000, or 2500 for quadrant projects); {@code quad} if the name
     * ends with an A/B/C/D 2500-ft quadrant of a 5000-ft parent.
     */
    private record LidScheme(Character forcedZone, Integer overrideC, double tileFt, boolean quad) {}

    private static final java.util.Map<String, LidScheme> LID_SCHEMES = java.util.Map.of(
            "FL_Peninsular_2018_D18",                   new LidScheme(null, null, 5000, false),
            "FL_Peninsular_FDEM_2018_D19_DRRA",         new LidScheme(null, null, 5000, false),
            "FL_HurricaneMichael_2020_D20",             new LidScheme(null, null, 5000, false),
            "FL_ManateeCounty_B25",                     new LidScheme(null, null, 5000, false),
            "FL_Osceola_2015",                          new LidScheme(null, 149767, 2500, true),
            "FL_Suwannee_River_FL_QL2_LiDAR_FY14_14",   new LidScheme(null, 104051, 5000, false),
            "FL_MiamiDade_D23",                         new LidScheme('E',  null,   5000, false));

    // ..._LID2019_447196_W   ..._LID2015_060759_E_A   (zone suffix, optional A-D quadrant)
    private static final Pattern LID_STD =
            Pattern.compile("_LID\\d{4}_(\\d{4,6})_([EWN])(?:_([A-D]))?\\.laz$");
    // ..._LID2024_313031_0901   (no zone suffix, trailing delivery code)
    private static final Pattern LID_NOZONE =
            Pattern.compile("_LID\\d{4}_(\\d{4,6})(?:_\\d{2,4})?\\.laz$");

    /** Decodes a Florida State Plane LID-gridded name into a footprint, or {@code null}. */
    static Matched parseLid(String line) {
        String project = lidProject(line);
        LidScheme sc = project == null ? null : LID_SCHEMES.get(project);
        if (sc == null) return null;                          // only confirmed grid origins

        int idx; char zone; Character quad = null;
        Matcher m = LID_STD.matcher(line);
        if (m.find()) {
            idx = Integer.parseInt(m.group(1));
            zone = m.group(2).charAt(0);
            if (m.group(3) != null) quad = m.group(3).charAt(0);
        } else if (sc.forcedZone() != null && (m = LID_NOZONE.matcher(line)).find()) {
            idx = Integer.parseInt(m.group(1));
            zone = sc.forcedZone();
        } else {
            return null;
        }
        if (quad != null && !sc.quad()) return null;          // unexpected quadrant suffix

        ZoneDef z = LID_ZONES.get(zone);
        if (z == null) return null;
        int c = sc.overrideC() != null ? sc.overrideC() : z.c();
        int k = c - idx;
        if (k <= 0) return null;
        int row = (k + z.modulus() - 1) / z.modulus();        // ceil(k / modulus)
        int col = z.modulus() * row - k;
        if (col < 0 || col >= z.modulus()) return null;

        double e = col * 5000 * US_FOOT, n = row * 5000 * US_FOOT;
        if (quad != null) {                                   // 2500-ft quadrant of the parent
            if (quad == 'B' || quad == 'D') e += 2500 * US_FOOT;   // east half
            if (quad == 'A' || quad == 'B') n += 2500 * US_FOOT;   // north half
        }
        return new Matched(line, groupKey(line), z.proj(), e, n, sc.tileFt() * US_FOOT);
    }

    /** Top-level project encoded in a {@code USGS_LPC_<project>_LID...} filename, or {@code null}. */
    static String lidProject(String line) {
        String name = line.substring(line.lastIndexOf('/') + 1);
        int start = name.startsWith("USGS_LPC_") ? "USGS_LPC_".length() : 0;
        int lid = name.indexOf("_LID");
        return lid > start ? name.substring(start, lid) : null;
    }

    // ---- USGS "eNNNNnNNNN" / "wNNNNnNNNN" grid-indexed names ----
    //
    // Many 3DEP deliveries name a tile by its SW corner in the project's CRS, e.g.
    //     USGS_LPC_FL_Panhandle_2018_B18_e0853n0913.laz   (CONUS Albers, e/n in km)
    //     USGS_LPC_FL_GulfCoast_Topography_2018_w314000n3291500.laz   (UTM 16N, e/n in metres)
    // The leading e/w is just a label (eastings are positive in all these zones). The value's
    // unit is per-project and NOT inferable from the name (Southwest uses km easting but 100 m
    // northing; GulfCoast uses raw metres), so each scheme below was read from, and verified
    // against, the actual LAZ header bounds + CRS VLR. Only confirmed projects are decoded.

    private static final Proj CONUS_ALBERS =
            new AeaProj(-96, 23, 29.5, 45.5, 0, 0, 6350, "NAD83(2011) / Conus Albers");

    /** Per-project grid: projection, easting/northing index multipliers (m), and tile size (m). */
    private record EnScheme(Proj proj, double eMul, double nMul, double tileMetres) {}

    private static final java.util.Map<String, EnScheme> EN_SCHEMES = java.util.Map.of(
            "FL_Panhandle_2018_B18",               new EnScheme(CONUS_ALBERS, 1000, 1000, 1000),
            "GA_Statewide_2018_B18_DRRA",          new EnScheme(CONUS_ALBERS, 1000, 1000, 1000),
            "FL_Southeast_2018_D18_SUPPLEMENTAL",  new EnScheme(CONUS_ALBERS, 1000, 1000, 1000),
            "FL_WestEvergladesNP_2018_B18",        new EnScheme(CONUS_ALBERS, 1000, 1000, 1000),
            "FL_Southwest_2018_D18_SUPPLEMENTAL",  new EnScheme(CONUS_ALBERS, 1000,  100, 1000),
            "FL_Panhandle_B3_2018",                new EnScheme(CONUS_ALBERS, 1000, 1000, 1000),
            "FL_TopobathyFLKeysNOAA_2019_D20",
                    new EnScheme(Proj.utm(17, 6346, "NAD83(2011) / UTM zone 17N"), 1000, 1000, 1000),
            "FL_GulfCoast_Topography_2018",
                    new EnScheme(Proj.utm(16, 6345, "NAD83(2011) / UTM zone 16N"), 1, 1, 500));

    // ..._e0853n0913.laz   ..._w314000n3291500.laz   ..._e1039n0791_LAS_2019.laz
    // (e/w label, easting digits, n, northing digits, optional trailing delivery code)
    private static final Pattern EN_TILE =
            Pattern.compile("_[ew](\\d{3,})n(\\d{3,})(?:_[A-Za-z]+_\\d+)?\\.laz$", Pattern.CASE_INSENSITIVE);

    /** Decodes a USGS e/n grid-indexed name into a footprint, or {@code null}. */
    static Matched parseEastNorth(String line) {
        String name = line.substring(line.lastIndexOf('/') + 1);
        Matcher m = EN_TILE.matcher(name);
        if (!m.find()) return null;
        int start = name.startsWith("USGS_LPC_") ? "USGS_LPC_".length() : 0;
        if (m.start() <= start) return null;
        String project = name.substring(start, m.start());
        EnScheme sc = EN_SCHEMES.get(project);
        if (sc == null) return null;                          // only confirmed grids
        double e = Long.parseLong(m.group(1)) * sc.eMul();
        double n = Long.parseLong(m.group(2)) * sc.nMul();
        return new Matched(line, groupKey(line), sc.proj(), e, n, sc.tileMetres());
    }

    // ---- Florida State Plane names WITHOUT the "LID" token ----
    //
    // Some deliveries name a tile in State Plane feet but omit the "LID" token used above:
    //   * Packed column/row index, same packing as the LID grids (idx = C - modulus*row + col
    //     over 5000 ft tiles), keeping the zone letter or dropping it:
    //       ..._038576_N.laz (FL North), ..._050300_E.laz (FL East), ..._471241.laz (FL West).
    //   * SW corner in absolute State Plane feet: ..._692500_725000.laz (Palm Beach, FL East).
    // Each project's zone, origin C and CRS was read from and verified against the LAZ headers.
    // These are the NAD83(2011) ftUS realizations (same projection maths as the NAD83 zones above).
    //
    // Two superficially similar families are deliberately NOT decoded, because reading their
    // headers showed the name does not map to a corner consistently: FL_Suwannee_River_Lidar_2016
    // (no single C/modulus fits its index), and GA_SW_Georgia_..._B17 (the GAW/GAE corner field's
    // scale differs between delivery blocks). Decoding them from the name would emit wrong extents.

    private static final Proj FL_EAST_2011 =
            new TmProj(-81, SP_TM_LAT0, SP_K0, SP_TM_FE, 6438, "NAD83(2011) / Florida East (ftUS)");
    private static final Proj FL_WEST_2011 =
            new TmProj(-82, SP_TM_LAT0, SP_K0, SP_TM_FE, 6442, "NAD83(2011) / Florida West (ftUS)");
    private static final Proj FL_NORTH_2011 =
            new LccProj(-84.5, 29.0, 30.75, 29 + 35.0 / 60.0, 600_000.0, 6441,
                    "NAD83(2011) / Florida North (ftUS)");

    /** A packed-index State Plane project: its zone projection, origin constant C, and index modulus. */
    private record SpScheme(Proj proj, int c, int modulus) {}

    // Keyed by "<project>|<zone suffix>" (suffix empty when the name carries none): the origin C
    // is tied to the suffix, e.g. Upper Saint Johns numbers its "_E" and its unsuffixed tiles from
    // different origins (149767 vs the East default 349767).
    private static final java.util.Map<String, SpScheme> SP_SCHEMES = java.util.Map.of(
            "FL_LeonCountyProcessing_2018_D18|N",    new SpScheme(FL_NORTH_2011, 104051, 540),
            "FL_Upper_Saint_Johns_Lidar_2017_B17|E", new SpScheme(FL_EAST_2011,  149767, 300),
            "FL_Upper_Saint_Johns_Lidar_2017_B17|",  new SpScheme(FL_EAST_2011,  349767, 300),
            "FL_PeaceRiver_2014_C17|",               new SpScheme(FL_WEST_2011,  549701, 300));

    private static final String PALM_BEACH = "FL_Palm_Beach_County_LiDAR_2016_B16";

    private static final Pattern PB_TILE =                    // ..._692500_725000.laz
            Pattern.compile("_(\\d{6})_(\\d{6})\\.laz$");
    private static final Pattern SP_IDX_TILE =                // ..._038576_N.laz   ..._471241.laz
            Pattern.compile("_(\\d{4,6})(?:_([EWN]))?\\.laz$");

    /** Decodes a confirmed State Plane name lacking the LID token into a footprint, or {@code null}. */
    static Matched parseStatePlane(String line) {
        String name = line.substring(line.lastIndexOf('/') + 1);
        Matcher m;
        if ((m = PB_TILE.matcher(name)).find() && PALM_BEACH.equals(spProject(name, m.start()))) {
            double e = Integer.parseInt(m.group(1)) * US_FOOT;          // absolute feet
            double n = Integer.parseInt(m.group(2)) * US_FOOT;
            return new Matched(line, groupKey(line), FL_EAST_2011, e, n, 2500 * US_FOOT);
        }
        if ((m = SP_IDX_TILE.matcher(name)).find()) {
            String suffix = m.group(2) == null ? "" : m.group(2);
            SpScheme sc = SP_SCHEMES.get(spProject(name, m.start()) + "|" + suffix);
            if (sc == null) return null;                               // only confirmed grids
            double[] sw = spIndexCorner(Integer.parseInt(m.group(1)), sc.c(), sc.modulus());
            if (sw == null) return null;
            return new Matched(line, groupKey(line), sc.proj(), sw[0], sw[1], 5000 * US_FOOT);
        }
        return null;
    }

    /** The {@code USGS_LPC_<project>} token preceding the coordinate suffix at {@code end}. */
    private static String spProject(String name, int end) {
        int start = name.startsWith("USGS_LPC_") ? "USGS_LPC_".length() : 0;
        return end > start ? name.substring(start, end) : "";
    }

    /** Packed column/row index -> SW corner (metres) on a 5000 ft grid, or {@code null} if out of range. */
    private static double[] spIndexCorner(int idx, int c, int modulus) {
        int k = c - idx;
        if (k <= 0) return null;
        int row = (k + modulus - 1) / modulus;        // ceil(k / modulus)
        int col = modulus * row - k;
        if (col < 0 || col >= modulus) return null;
        return new double[]{ col * 5000 * US_FOOT, row * 5000 * US_FOOT };
    }

    // ---- Per-project merge: union of grid cells -> WGS84 MULTIPOLYGON ----
    //
    // The tiles of a project form a regular UTM grid, so their union is the union of unit
    // grid cells. We trace it by edge-cancellation: every cell contributes its four boundary
    // edges CCW; an edge shared by two cells appears once in each direction and cancels, so the
    // survivors are exactly the outer/inner boundary, oriented (shells CCW, holes CW) for free.
    // Survivors are stitched into rings, collinear runs are collapsed, holes are matched to the
    // shell that contains them, and corners are reprojected to lon/lat.

    /** Grid resolution cap: cell column/row indices must be < GRID (so corner keys stay packed). */
    static final int GRID = 4096;

    record Polygon(int[][] shell, java.util.List<int[][]> holes) {}

    /** Merged coverage of a project's tiles (possibly mixed projections) as EWKT MULTIPOLYGON. */
    static String mergedWkt(java.util.List<Matched> tiles, double tile) {
        // A project may mix projections (e.g. UTM zones, or State Plane East+West); union each
        // projection's cells separately on its own grid, then combine into one MULTIPOLYGON.
        java.util.Map<Proj, java.util.List<Matched>> byProj = new java.util.LinkedHashMap<>();
        for (Matched m : tiles) {
            byProj.computeIfAbsent(m.proj(), k -> new java.util.ArrayList<>()).add(m);
        }
        StringBuilder sb = new StringBuilder("SRID=" + WGS84 + ";MULTIPOLYGON (");
        boolean firstPoly = true;
        for (var pe : byProj.entrySet()) {
            Proj proj = pe.getKey();
            java.util.List<Matched> zt = pe.getValue();
            double minE = Double.MAX_VALUE, minN = Double.MAX_VALUE;
            for (Matched m : zt) { minE = Math.min(minE, m.swE()); minN = Math.min(minN, m.swN()); }
            java.util.Set<Integer> cells = new java.util.HashSet<>();
            for (Matched m : zt) {
                int c = (int) Math.round((m.swE() - minE) / tile);
                int r = (int) Math.round((m.swN() - minN) / tile);
                if (c < 0 || r < 0 || c >= GRID - 1 || r >= GRID - 1) {
                    System.err.println("  merge: grid too large for " + zt.get(0).project() + ", skipping a projection");
                    return "SRID=" + WGS84 + ";MULTIPOLYGON EMPTY";
                }
                cells.add(c * GRID + r);
            }
            for (Polygon p : buildPolygons(boundaryRings(cells))) {
                if (!firstPoly) sb.append(", ");
                firstPoly = false;
                appendPolygon(sb, p, minE, minN, tile, proj);
            }
        }
        sb.append(')');
        return firstPoly ? "SRID=" + WGS84 + ";MULTIPOLYGON EMPTY" : sb.toString();
    }

    /** Boundary rings of a set of grid cells, closed and collinear-cleaned, in (c,r) corners. */
    static java.util.List<int[][]> boundaryRings(java.util.Set<Integer> cells) {
        // Edge-cancellation. Corner key = c*GRID + r; directed edge key = start*GRID^2 + end.
        long span = (long) GRID * GRID;
        java.util.Set<Long> edges = new java.util.HashSet<>();
        for (int cell : cells) {
            int c = cell / GRID, r = cell % GRID;
            int bl = c * GRID + r, br = (c + 1) * GRID + r,
                tr = (c + 1) * GRID + (r + 1), tl = c * GRID + (r + 1);
            toggle(edges, span, bl, br);   // CCW: bottom, right, top, left
            toggle(edges, span, br, tr);
            toggle(edges, span, tr, tl);
            toggle(edges, span, tl, bl);
        }
        // Adjacency: start corner -> outgoing end corners.
        java.util.Map<Integer, java.util.List<Integer>> adj = new java.util.HashMap<>();
        for (long e : edges) {
            int a = (int) (e / span), b = (int) (e % span);
            adj.computeIfAbsent(a, k -> new java.util.ArrayList<>()).add(b);
        }
        java.util.List<int[][]> rings = new java.util.ArrayList<>();
        while (true) {
            Integer start = pickStart(adj);
            if (start == null) break;
            rings.add(cleanCollinear(traceRing(adj, start)));
        }
        return rings;
    }

    private static void toggle(java.util.Set<Long> edges, long span, int a, int b) {
        long rev = (long) b * span + a;
        if (!edges.remove(rev)) edges.add((long) a * span + b);
    }

    /** A corner with outgoing edges, preferring a simple (degree-1) corner over a pinch. */
    private static Integer pickStart(java.util.Map<Integer, java.util.List<Integer>> adj) {
        Integer any = null;
        for (var e : adj.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (e.getValue().size() == 1) return e.getKey();
            any = e.getKey();
        }
        return any;
    }

    /** Walks a closed ring from {@code start}, turning most-counterclockwise at each junction. */
    private static int[][] traceRing(java.util.Map<Integer, java.util.List<Integer>> adj, int start) {
        java.util.List<Integer> ring = new java.util.ArrayList<>();
        int cur = start, indx = -1, indy = -1;     // incoming direction (none yet)
        boolean haveIn = false;
        ring.add(cur);
        do {
            java.util.List<Integer> outs = adj.get(cur);
            int pick = 0;
            if (haveIn && outs.size() > 1) {
                int best = Integer.MAX_VALUE;
                for (int k = 0; k < outs.size(); k++) {
                    int n = outs.get(k);
                    int dx = sign(n / GRID - cur / GRID), dy = sign(n % GRID - cur % GRID);
                    int rank = turnRank(indx, indy, dx, dy);   // 0 = sharpest left
                    if (rank < best) { best = rank; pick = k; }
                }
            }
            int next = outs.remove(pick);
            indx = sign(next / GRID - cur / GRID);
            indy = sign(next % GRID - cur % GRID);
            haveIn = true;
            cur = next;
            ring.add(cur);
        } while (cur != start);
        int[][] out = new int[ring.size()][2];
        for (int i = 0; i < ring.size(); i++) out[i] = new int[]{ ring.get(i) / GRID, ring.get(i) % GRID };
        return out;
    }

    private static int sign(int v) { return Integer.compare(v, 0); }

    /** Ranks an outgoing direction relative to the incoming one: left(0) < straight(1) < right(2) < U(3). */
    private static int turnRank(int idx, int idy, int odx, int ody) {
        int cross = idx * ody - idy * odx;     // >0 left, <0 right
        int dot = idx * odx + idy * ody;       // >0 straight, <0 reverse
        if (cross > 0) return 0;
        if (cross == 0 && dot > 0) return 1;
        if (cross < 0) return 2;
        return 3;
    }

    /** Drops vertices that sit on a straight run (rectilinear corners only survive). */
    private static int[][] cleanCollinear(int[][] ring) {
        int n = ring.length - 1;               // ring[n] == ring[0]
        java.util.List<int[]> keep = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            int[] prev = ring[(i - 1 + n) % n], cur = ring[i], next = ring[(i + 1) % n];
            int cross = (cur[0] - prev[0]) * (next[1] - cur[1]) - (cur[1] - prev[1]) * (next[0] - cur[0]);
            if (cross != 0) keep.add(cur);
        }
        int[][] out = new int[keep.size() + 1][2];
        for (int i = 0; i < keep.size(); i++) out[i] = keep.get(i);
        out[keep.size()] = keep.get(0);        // re-close
        return out;
    }

    /** Splits rings into shells (CCW, area>0) and holes (CW), nesting each hole in its shell. */
    static java.util.List<Polygon> buildPolygons(java.util.List<int[][]> rings) {
        java.util.List<int[][]> shells = new java.util.ArrayList<>();
        java.util.List<int[][]> holes = new java.util.ArrayList<>();
        for (int[][] r : rings) (signedArea(r) > 0 ? shells : holes).add(r);
        java.util.List<Polygon> polys = new java.util.ArrayList<>();
        java.util.Map<int[][], java.util.List<int[][]>> holesOf = new java.util.IdentityHashMap<>();
        for (int[][] s : shells) holesOf.put(s, new java.util.ArrayList<>());
        for (int[][] h : holes) {
            double[] p = interiorPoint(h);
            int[][] owner = null; double best = Double.MAX_VALUE;
            for (int[][] s : shells) {
                if (!pointInRing(p[0], p[1], s)) continue;
                double area = Math.abs(signedArea(s));
                if (area < best) { best = area; owner = s; }
            }
            if (owner != null) holesOf.get(owner).add(h);
        }
        for (int[][] s : shells) polys.add(new Polygon(s, holesOf.get(s)));
        return polys;
    }

    private static double signedArea(int[][] ring) {
        double a = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            a += (double) ring[i][0] * ring[i + 1][1] - (double) ring[i + 1][0] * ring[i][1];
        }
        return a / 2;
    }

    /** A point just inside a hole: step half a cell to the hole's interior (right of a CW edge). */
    private static double[] interiorPoint(int[][] hole) {
        int[] a = hole[0], b = hole[1];
        int dx = sign(b[0] - a[0]), dy = sign(b[1] - a[1]);
        double mx = (a[0] + b[0]) / 2.0, my = (a[1] + b[1]) / 2.0;
        return new double[]{ mx + 0.5 * dy, my - 0.5 * dx };   // right normal of (dx,dy)
    }

    private static boolean pointInRing(double x, double y, int[][] ring) {
        boolean in = false;
        for (int i = 0, j = ring.length - 2; i < ring.length - 1; j = i++) {
            double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) in = !in;
        }
        return in;
    }

    private static void appendPolygon(StringBuilder sb, Polygon p,
                                      double minE, double minN, double tile, Proj proj) {
        sb.append('(');
        appendRing(sb, p.shell(), minE, minN, tile, proj);
        for (int[][] h : p.holes()) { sb.append(", "); appendRing(sb, h, minE, minN, tile, proj); }
        sb.append(')');
    }

    private static void appendRing(StringBuilder sb, int[][] ring,
                                   double minE, double minN, double tile, Proj proj) {
        sb.append('(');
        for (int i = 0; i < ring.length; i++) {
            double e = minE + ring[i][0] * tile, n = minN + ring[i][1] * tile;
            double[] ll = proj.lonLat(e, n);
            if (i > 0) sb.append(", ");
            sb.append(fmt(ll[0])).append(' ').append(fmt(ll[1]));
        }
        sb.append(')');
    }

    // ---- CLI ----

    /**
     * A matched line ready to emit: its text, project, projection, decoded SW corner (metres in
     * that projection), and a fixed tile size (LID tiles) or {@code null} to derive per project.
     */
    record Matched(String line, String project, Proj proj, double swE, double swN, Double fixedTile) {}

    /** Returns {@code args[i]} or exits with an error if the flag {@code flag} has no value. */
    private static String requireArg(String[] args, int i, String flag) {
        if (i >= args.length) { System.err.println(flag + " needs a value"); System.exit(2); }
        return args[i];
    }

    public static void main(String[] args) throws IOException {
        Path input = null, output = null, mergedOut = null;
        Double forcedTile = null;
        String prefix = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--input", "-i" -> input = Path.of(requireArg(args, ++i, a));
                case "--output", "-o" -> output = Path.of(requireArg(args, ++i, a));
                case "--merged" -> mergedOut = Path.of(requireArg(args, ++i, a));
                case "--tile" -> forcedTile = Double.parseDouble(requireArg(args, ++i, a));
                case "--prefix" -> prefix = requireArg(args, ++i, a).replaceAll("/+$", "");
                default -> { System.err.println("Unknown argument: " + a); System.exit(2); }
            }
        }
        if (input == null) {
            System.err.println("Usage: java com.spotable.LazNameBounds --input <list.csv> [--output <bounds.csv>]");
            System.err.println("                                       [--merged <file>] [--tile <metres>] [--prefix <base>]");
            System.err.println("  --input   (-i) list of USGS LPC paths, one per line (required).");
            System.err.println("  --output  (-o) per-tile CSV \"<project>\",\"<filename>\",\"SRID=4326;POLYGON ((...))\"");
            System.err.println("                 (default: stdout). Decodes MGRS names (..._17RNL070510.laz), confirmed");
            System.err.println("                 Florida State Plane LID names (..._LID2019_447196_W.laz, _N, _E_A, ...),");
            System.err.println("                 e/n grids (..._e0853n0913.laz CONUS Albers, ..._w314000n3291500.laz UTM),");
            System.err.println("                 and State Plane names without the LID token. Other forms are skipped.");
            System.err.println("  --merged  also write one row per sub-project:");
            System.err.println("            \"<project>\",\"<directory>\",\"<files>\",\"<year>\",\"<EPSG>\",\"<horizontal CRS>\",\"SRID=4326;MULTIPOLYGON(...)\"");
            System.err.println("            (the union of that group's tiles, gaps preserved, collinear vertices removed).");
            System.err.println("  --tile    force a fixed tile size (metres) for every tile (default: derived per project).");
            System.err.println("  --prefix  base path (e.g. s3://bucket/point-cloud/us) prepended to the directory column.");
            System.exit(2);
            return;
        }

        // Pass 1: collect matched lines (only ~thousands match, so keep them in memory).
        java.util.List<Matched> matched = new java.util.ArrayList<>();
        long skipped = 0;
        try (BufferedReader in = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                Tile t = parse(line);
                if (t != null) {
                    matched.add(new Matched(line, groupKey(line),
                            Proj.utm(t.zone()), t.swEasting(), t.swNorthing(), null));
                    continue;
                }
                Matched lid = parseLid(line);
                if (lid != null) { matched.add(lid); continue; }
                Matched en = parseEastNorth(line);
                if (en != null) { matched.add(en); continue; }
                Matched sp = parseStatePlane(line);
                if (sp != null) { matched.add(sp); continue; }
                skipped++;
            }
        }

        // Derive each project's tile size (min grid step), unless one is forced.
        java.util.Map<String, Double> tileByProject = forcedTile != null
                ? java.util.Map.of()
                : deriveTileSizes(matched);

        try (Writer out = output != null
                ? Files.newBufferedWriter(output, StandardCharsets.UTF_8) : null) {
            String header = TifNameBounds.csvRow("project", "filename", "geometry");
            if (out != null) { out.write(header); out.write('\n'); } else System.out.println(header);
            for (Matched mtc : matched) {
                double tile = forcedTile != null
                        ? forcedTile
                        : tileByProject.getOrDefault(mtc.project(), DEFAULT_TILE);
                String row = TifNameBounds.csvRow(mtc.project(), mtc.line(),
                        toWgs84Wkt(mtc.proj(), mtc.swE(), mtc.swN(), tile));
                if (out != null) { out.write(row); out.write('\n'); }
                else System.out.println(row);
            }
        }
        System.err.println("Wrote " + matched.size() + " rows, skipped " + skipped + " non-MGRS names");
        if (forcedTile == null) {
            tileByProject.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> System.err.println("  tile size " + (long) (double) e.getValue()
                            + " m  " + e.getKey()));
        }

        if (mergedOut != null) {
            // Group tiles by sub-project, derive its merged coverage, one CSV row per group.
            // Columns: group, directory, horizontal CRS, merged WGS84 geometry.
            java.util.Map<String, java.util.List<Matched>> byProject = new java.util.TreeMap<>();
            for (Matched m : matched) {
                byProject.computeIfAbsent(m.project(), k -> new java.util.ArrayList<>()).add(m);
            }
            try (Writer out = Files.newBufferedWriter(mergedOut, StandardCharsets.UTF_8)) {
                out.write(TifNameBounds.csvRow("project", "directory", "files", "year",
                        "horizontal_epsg", "horizontal_projection", "geometry"));
                out.write('\n');
                for (var e : byProject.entrySet()) {
                    java.util.List<Matched> g = e.getValue();
                    double tile = forcedTile != null
                            ? forcedTile
                            : tileByProject.getOrDefault(e.getKey(), DEFAULT_TILE);
                    String directory = groupDir(g.get(0).line());
                    if (prefix != null) directory = prefix + "/" + directory.replaceAll("^/+", "");
                    java.util.TreeSet<String> epsg = new java.util.TreeSet<>();
                    java.util.TreeSet<String> crs = new java.util.TreeSet<>();
                    for (Matched m : g) { epsg.add(m.proj().epsg()); crs.add(m.proj().crs()); }
                    out.write(TifNameBounds.csvRow(e.getKey(), directory,
                            Integer.toString(g.size()), year(e.getKey(), g.get(0).line()),
                            String.join("; ", epsg), String.join("; ", crs), mergedWkt(g, tile)));
                    out.write('\n');
                }
            }
            System.err.println("Wrote " + byProject.size() + " merged project rows to " + mergedOut);
        }
    }

    /**
     * Grouping key for the merge: the directory that contains the {@code LAZ}/{@code TIFF} folder
     * (the survey sub-project, e.g. {@code FL_Peninsular_FDEM_Alachua_2018}). This is taken from
     * the path, not the filename, so distinct sub-projects under one collection stay separate and
     * any bucket/prefix in the path is ignored. Falls back to the file's parent directory.
     */
    static String groupKey(String line) {
        String[] p = line.split("/");
        for (int i = p.length - 1; i > 0; i--) {
            if (p[i].equalsIgnoreCase("LAZ") || p[i].equalsIgnoreCase("TIFF")) return p[i - 1];
        }
        return p.length >= 2 ? p[p.length - 2] : line;
    }

    /** Full directory path of the group (everything up to the {@code LAZ}/{@code TIFF} folder). */
    static String groupDir(String line) {
        String[] p = line.split("/");
        for (int i = p.length - 1; i > 0; i--) {
            if (p[i].equalsIgnoreCase("LAZ") || p[i].equalsIgnoreCase("TIFF")) {
                return String.join("/", java.util.Arrays.copyOfRange(p, 0, i));
            }
        }
        int slash = line.lastIndexOf('/');
        return slash < 0 ? "" : line.substring(0, slash);
    }

    /** A plausible survey year (1900–2099) standing alone in a digit run. */
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)");

    /**
     * Survey year for a group, read from the project name first (e.g. {@code 2017FortDrum},
     * {@code FL_Peninsular_2018_D18}) and falling back to the path (e.g. {@code _LID2019_}).
     * Returns {@code ""} if no year-like token is present.
     */
    static String year(String project, String line) {
        Matcher m = YEAR.matcher(project);
        if (m.find()) return m.group(1);
        m = YEAR.matcher(line);
        return m.find() ? m.group(1) : "";
    }

    private static final double DEFAULT_TILE = 1500.0;

    /**
     * Tile size per project = the smallest positive gap between adjacent SW-corner grid lines
     * (easting or northing) within that project. Projects with a single distinct line on both
     * axes fall back to {@link #DEFAULT_TILE}.
     */
    private static java.util.Map<String, Double> deriveTileSizes(java.util.List<Matched> matched) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        java.util.Map<String, java.util.TreeSet<Double>> e = new java.util.HashMap<>();
        java.util.Map<String, java.util.TreeSet<Double>> n = new java.util.HashMap<>();
        for (Matched m : matched) {
            if (m.fixedTile() != null) {            // LID tiles carry a known size
                out.putIfAbsent(m.project(), m.fixedTile());
                continue;
            }
            e.computeIfAbsent(m.project(), k -> new java.util.TreeSet<>()).add(m.swE());
            n.computeIfAbsent(m.project(), k -> new java.util.TreeSet<>()).add(m.swN());
        }
        for (String p : e.keySet()) {
            double step = Math.min(minGap(e.get(p)), minGap(n.get(p)));
            out.put(p, Double.isFinite(step) ? step : DEFAULT_TILE);
        }
        return out;
    }

    /** Smallest positive difference between consecutive values, or +inf if fewer than two. */
    private static double minGap(java.util.TreeSet<Double> vals) {
        double prev = Double.NaN, min = Double.POSITIVE_INFINITY;
        for (double v : vals) {
            if (!Double.isNaN(prev)) min = Math.min(min, v - prev);
            prev = v;
        }
        return min;
    }
}
