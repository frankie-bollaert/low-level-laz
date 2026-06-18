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
 * the point-cloud counterpart to {@link DtmNameBounds}.
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
 * {@link DtmNameBounds.Utm}, and emitted as EWKT {@code SRID=4326;POLYGON ((...))}.
 * <p>
 * Other LPC naming families in the wild are out of scope here and are skipped (logged to
 * stderr): EPT octree-node keys ({@code ept-data/12-339-2750-2044.laz}, geometry not in the
 * name), and per-project schemes such as {@code LID2019_651011_N}, {@code e0853n0913},
 * {@code 692500_725000}, {@code GAW_20100945}.
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
            "_(\\d{2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\\d{3})(\\d{3})\\.laz$");

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
        char band = m.group(2).charAt(0);
        char col = m.group(3).charAt(0);
        char row = m.group(4).charAt(0);
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

    /** Tile footprint as EWKT {@code SRID=4326;POLYGON ((...))}, ring SW, SE, NE, NW, SW. */
    static String toWgs84Wkt(Tile t, double tileMetres) {
        double w = t.swEasting(), s = t.swNorthing();
        double e = w + tileMetres, n = s + tileMetres;
        double[] sw = DtmNameBounds.Utm.toLonLat(w, s, t.zone());
        double[] se = DtmNameBounds.Utm.toLonLat(e, s, t.zone());
        double[] ne = DtmNameBounds.Utm.toLonLat(e, n, t.zone());
        double[] nw = DtmNameBounds.Utm.toLonLat(w, n, t.zone());
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
    private static final double K0 = 0.9996;

    /** Meridian distance from the equator to latitude {@code phi} (radians) on WGS84. */
    private static double meridianArc(double phi) {
        double e2 = E2, e4 = e2 * e2, e6 = e4 * e2;
        return A * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi)
                - (35 * e6 / 3072) * Math.sin(6 * phi));
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

    /** Merged coverage of a project's tiles (possibly mixed UTM zones) as EWKT MULTIPOLYGON. */
    static String mergedWkt(java.util.List<Matched> tiles, double tile) {
        // A single project may (rarely) mix UTM zones; union each zone separately, then combine.
        java.util.Map<Integer, java.util.List<Matched>> byZone = new java.util.TreeMap<>();
        for (Matched m : tiles) {
            byZone.computeIfAbsent(m.tile().zone(), k -> new java.util.ArrayList<>()).add(m);
        }
        StringBuilder sb = new StringBuilder("SRID=" + WGS84 + ";MULTIPOLYGON (");
        boolean firstPoly = true;
        for (var ze : byZone.entrySet()) {
            int zone = ze.getKey();
            java.util.List<Matched> zt = ze.getValue();
            double minE = Double.MAX_VALUE, minN = Double.MAX_VALUE;
            for (Matched m : zt) {
                minE = Math.min(minE, m.tile().swEasting());
                minN = Math.min(minN, m.tile().swNorthing());
            }
            java.util.Set<Integer> cells = new java.util.HashSet<>();
            for (Matched m : zt) {
                int c = (int) Math.round((m.tile().swEasting() - minE) / tile);
                int r = (int) Math.round((m.tile().swNorthing() - minN) / tile);
                if (c < 0 || r < 0 || c >= GRID - 1 || r >= GRID - 1) {
                    System.err.println("  merge: grid too large for " + zt.get(0).project() + ", skipping zone " + zone);
                    return "SRID=" + WGS84 + ";MULTIPOLYGON EMPTY";
                }
                cells.add(c * GRID + r);
            }
            for (Polygon p : buildPolygons(boundaryRings(cells))) {
                if (!firstPoly) sb.append(", ");
                firstPoly = false;
                appendPolygon(sb, p, minE, minN, tile, zone);
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
                                      double minE, double minN, double tile, int zone) {
        sb.append('(');
        appendRing(sb, p.shell(), minE, minN, tile, zone);
        for (int[][] h : p.holes()) { sb.append(", "); appendRing(sb, h, minE, minN, tile, zone); }
        sb.append(')');
    }

    private static void appendRing(StringBuilder sb, int[][] ring,
                                   double minE, double minN, double tile, int zone) {
        sb.append('(');
        for (int i = 0; i < ring.length; i++) {
            double e = minE + ring[i][0] * tile, n = minN + ring[i][1] * tile;
            double[] ll = DtmNameBounds.Utm.toLonLat(e, n, zone);
            if (i > 0) sb.append(", ");
            sb.append(fmt(ll[0])).append(' ').append(fmt(ll[1]));
        }
        sb.append(')');
    }

    // ---- CLI ----

    /** A matched line: its text, its project (first path segment), and the decoded tile. */
    private record Matched(String line, String project, Tile tile) {}

    public static void main(String[] args) throws IOException {
        Path input = null, output = null, mergedOut = null;
        Double forcedTile = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--tile")) {
                if (i + 1 >= args.length) { System.err.println("--tile needs a value"); System.exit(2); }
                forcedTile = Double.parseDouble(args[++i]);
            } else if (a.equals("--merged")) {
                if (i + 1 >= args.length) { System.err.println("--merged needs a file path"); System.exit(2); }
                mergedOut = Path.of(args[++i]);
            } else if (input == null) {
                input = Path.of(a);
            } else if (output == null) {
                output = Path.of(a);
            } else {
                System.err.println("Unexpected extra argument: " + a);
                System.exit(2);
            }
        }
        if (input == null) {
            System.err.println("Usage: java com.spotable.LazNameBounds <list.csv> [out.csv] [--tile <metres>] [--merged <file>]");
            System.err.println("  Reads one USGS LPC path per line, writes \"<filename>\",\"SRID=4326;POLYGON ((...))\"");
            System.err.println("  for MGRS-gridded names (e.g. ..._17RNL070510.laz). Other forms are skipped.");
            System.err.println("  Tile size is derived per project from the grid step in the list,");
            System.err.println("  unless --tile forces a fixed size (metres) for every tile.");
            System.err.println("  --merged  also write one row per project: \"<project>\",\"SRID=4326;MULTIPOLYGON(...)\"");
            System.err.println("            (the union of that project's tiles, gaps preserved, collinear vertices removed).");
            System.exit(2);
            return;
        }

        // Pass 1: collect the MGRS-matched lines (only ~thousands match, so keep them in memory).
        java.util.List<Matched> matched = new java.util.ArrayList<>();
        long skipped = 0;
        try (BufferedReader in = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                Tile t = parse(line);
                if (t == null) { skipped++; continue; }
                matched.add(new Matched(line, project(line), t));
            }
        }

        // Derive each project's tile size (min grid step), unless one is forced.
        java.util.Map<String, Double> tileByProject = forcedTile != null
                ? java.util.Map.of()
                : deriveTileSizes(matched);

        try (Writer out = output != null
                ? Files.newBufferedWriter(output, StandardCharsets.UTF_8) : null) {
            for (Matched mtc : matched) {
                double tile = forcedTile != null
                        ? forcedTile
                        : tileByProject.getOrDefault(mtc.project(), DEFAULT_TILE);
                String row = DtmNameBounds.csvRow(mtc.line(), toWgs84Wkt(mtc.tile(), tile));
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
            // Group tiles by project, derive its merged coverage, one CSV row per project.
            java.util.Map<String, java.util.List<Matched>> byProject = new java.util.TreeMap<>();
            for (Matched m : matched) {
                byProject.computeIfAbsent(m.project(), k -> new java.util.ArrayList<>()).add(m);
            }
            try (Writer out = Files.newBufferedWriter(mergedOut, StandardCharsets.UTF_8)) {
                for (var e : byProject.entrySet()) {
                    double tile = forcedTile != null
                            ? forcedTile
                            : tileByProject.getOrDefault(e.getKey(), DEFAULT_TILE);
                    out.write(DtmNameBounds.csvRow(e.getKey(), mergedWkt(e.getValue(), tile)));
                    out.write('\n');
                }
            }
            System.err.println("Wrote " + byProject.size() + " merged project rows to " + mergedOut);
        }
    }

    /** Project = first path segment (everything before the first {@code /}). */
    private static String project(String line) {
        int slash = line.indexOf('/');
        return slash < 0 ? line : line.substring(0, slash);
    }

    private static final double DEFAULT_TILE = 1500.0;

    /**
     * Tile size per project = the smallest positive gap between adjacent SW-corner grid lines
     * (easting or northing) within that project. Projects with a single distinct line on both
     * axes fall back to {@link #DEFAULT_TILE}.
     */
    private static java.util.Map<String, Double> deriveTileSizes(java.util.List<Matched> matched) {
        java.util.Map<String, java.util.TreeSet<Double>> e = new java.util.HashMap<>();
        java.util.Map<String, java.util.TreeSet<Double>> n = new java.util.HashMap<>();
        for (Matched m : matched) {
            e.computeIfAbsent(m.project(), k -> new java.util.TreeSet<>()).add(m.tile().swEasting());
            n.computeIfAbsent(m.project(), k -> new java.util.TreeSet<>()).add(m.tile().swNorthing());
        }
        java.util.Map<String, Double> out = new java.util.HashMap<>();
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
