package com.spotable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parsing of a file's <b>CRS</b> (Coordinate Reference System — the spatial reference that
 * says what real-world location an {@code (x, y)} pair denotes) for the two binary readers,
 * {@link LazBinaryReader} (LAS/LAZ point clouds) and {@link TifBinaryReader} (GeoTIFF rasters).
 * Both file formats describe their CRS the same two ways, so the parsing lives here once.
 *
 * <h2>The two encodings this class understands</h2>
 * <ol>
 *   <li><b>A GeoTIFF GeoKeyDirectory</b> — a compact table of integer "GeoKeys". GeoTIFF is the
 *       georeferencing convention used by both TIFFs (in a TIFF tag) and LAS/LAZ files (in a
 *       projection <b>VLR</b> — Variable Length Record, the LAS container for extra metadata). Each
 *       entry is four 16-bit values: {@code keyId, tiffTagLocation, count, value}. When
 *       {@code tiffTagLocation == 0} the {@code value} is the answer inline (e.g. an EPSG code);
 *       otherwise the value lives elsewhere and we ignore it (none of the keys we need use that).
 *       See {@link #geoKey(int[], int)}.</li>
 *   <li><b>OGC WKT</b> — "Well-Known Text", a parenthesised text description of a CRS standardised by
 *       the OGC (Open Geospatial Consortium), e.g.
 *       {@code PROJCS["NAD83 / UTM zone 17N", ... AUTHORITY["EPSG","26917"]]}. See
 *       {@link #horizontalEpsg(String)}.</li>
 * </ol>
 *
 * <h2>Acronyms used throughout</h2>
 * <ul>
 *   <li><b>CRS</b> — Coordinate Reference System.</li>
 *   <li><b>EPSG</b> — the public registry of CRSs and units of measure (originally the European
 *       Petroleum Survey Group). An "EPSG code" is a small integer naming one entry, e.g.
 *       {@code 26917} = "NAD83 / UTM zone 17N", {@code 5703} = "NAVD88 height (metre)".</li>
 *   <li><b>WKT</b> — Well-Known Text (the CRS description format above). WKT1 uses keywords like
 *       {@code PROJCS}/{@code GEOGCS}/{@code COMPD_CS}; the newer WKT2 uses
 *       {@code PROJCRS}/{@code GEOGCRS}/{@code COMPOUNDCRS}. Both are handled.</li>
 *   <li><b>GeoKey</b> — one entry in a GeoTIFF GeoKeyDirectory.</li>
 *   <li><b>UoM</b> — Unit of Measure (here, the CRS's linear unit: metre, foot, or US survey foot).</li>
 *   <li><b>Horizontal vs. vertical CRS</b> — the horizontal CRS positions you on the map (a
 *       projected CRS like UTM, or a geographic lon/lat CRS); a vertical CRS gives heights (e.g.
 *       NAVD88). A "compound" CRS bundles one of each.</li>
 *   <li><b>NAVD88</b> — North American Vertical Datum of 1988, the usual height reference here.</li>
 *   <li><b>ftUS</b> — US survey foot (1200/3937 m ≈ 0.3048006 m), distinct from the international
 *       foot (exactly 0.3048 m).</li>
 *   <li><b>UTM / NAD83</b> — Universal Transverse Mercator (a projected CRS) on the North American
 *       Datum of 1983; the typical horizontal CRS for this data.</li>
 * </ul>
 *
 * <p>All methods are static and side-effect-free; this class is not instantiable.
 */
final class GeoCrs {

    private GeoCrs() {}

    // ---- GeoKey directory ids and EPSG unit-of-measure codes ----
    //
    // The GeoKey ids below are fixed by the GeoTIFF spec. We read each key's inline EPSG value.

    /** GeoKey id of the <b>geographic</b> (lon/lat) CRS, e.g. value {@code 4269} = NAD83. */
    static final int KEY_GEOGRAPHIC_CS = 2048;      // GeographicTypeGeoKey
    /** GeoKey id of the <b>projected</b> CRS, e.g. value {@code 26917} = NAD83 / UTM zone 17N. */
    static final int KEY_PROJECTED_CS = 3072;       // ProjectedCSTypeGeoKey
    /** GeoKey id of the projected CRS's linear unit, as an EPSG UoM code (see {@code UOM_*}). */
    static final int KEY_PROJ_LINEAR_UNITS = 3076;  // ProjLinearUnitsGeoKey
    /** GeoKey id of the <b>vertical</b> CRS, e.g. value {@code 5703} = NAVD88 height (metre). */
    static final int KEY_VERTICAL_CS = 4096;        // VerticalCSTypeGeoKey

    // Two reserved GeoKey values that mean "no usable code": 0 (the key is absent / not set) and
    // 32767 (the CRS is "user-defined", i.e. spelled out elsewhere rather than named by an EPSG
    // code). We treat both as "no EPSG code here".
    private static final int UNDEFINED = 0, USER_DEFINED = 32767;

    // EPSG unit-of-measure codes for the linear units we expect, used with KEY_PROJ_LINEAR_UNITS.
    private static final int UOM_METRE = 9001;      // metre (1 m per unit)
    private static final int UOM_FOOT = 9002;       // international foot (0.3048 m)
    private static final int UOM_US_FOOT = 9003;    // US survey foot (1200/3937 m)
    /** One US survey foot in metres ({@code 1200/3937} ≈ 0.3048006096). */
    static final double US_SURVEY_FOOT = 1200.0 / 3937.0;

    /**
     * Looks up the inline value of GeoKey {@code keyId} in a GeoKeyDirectory.
     * <p>
     * {@code gk} is the directory as its raw run of unsigned 16-bit values: {@code gk[0..2]} are
     * version fields, {@code gk[3]} is the number of keys, then four values per key
     * ({@code keyId, tiffTagLocation, count, value}). Only keys stored inline
     * ({@code tiffTagLocation == 0}) are read; an absent, {@link #UNDEFINED} or
     * {@link #USER_DEFINED} value all return {@code 0}.
     *
     * <p>Example — read the projected CRS's EPSG code:
     * <pre>{@code
     * int[] gk = ...;                              // the GeoKeyDirectory shorts
     * int epsg = GeoCrs.geoKey(gk, GeoCrs.KEY_PROJECTED_CS);   // e.g. 26917, or 0 if not present
     * }</pre>
     *
     * @param gk    the GeoKeyDirectory as 16-bit values (may be {@code null})
     * @param keyId the GeoKey id to look up, e.g. {@link #KEY_PROJECTED_CS}
     * @return the key's inline value, or {@code 0} if absent / undefined / user-defined
     */
    static int geoKey(int[] gk, int keyId) {
        if (gk == null || gk.length < 4) return 0;
        int numKeys = gk[3];
        for (int k = 0; k < numKeys; k++) {
            int base = 4 + k * 4;                       // first value of the k-th key entry
            if (base + 4 > gk.length) break;            // truncated directory; stop
            if (gk[base] == keyId && gk[base + 1] == 0) {   // matching key, stored inline
                int value = gk[base + 3];
                return value == UNDEFINED || value == USER_DEFINED ? 0 : value;
            }
        }
        return 0;
    }

    /**
     * The horizontal CRS EPSG codes in a GeoKeyDirectory as {@code [projectedEpsg, geographicEpsg]},
     * each {@code 0} when absent. A file usually carries one or the other: a projected CRS
     * (e.g. {@code [26917, 0]}) or, for un-projected data, a geographic one (e.g. {@code [0, 4269]}).
     *
     * @param gk the GeoKeyDirectory as 16-bit values
     * @return a two-element array {@code {projectedEpsg, geographicEpsg}}
     */
    static int[] geoKeyEpsgs(int[] gk) {
        return new int[] { geoKey(gk, KEY_PROJECTED_CS), geoKey(gk, KEY_GEOGRAPHIC_CS) };
    }

    /**
     * Converts an EPSG unit-of-measure code to metres per unit, so areas/resolutions can always be
     * reported in metres regardless of the CRS's native unit. Unknown or absent codes default to
     * metre ({@code 1.0}), which is the common case.
     *
     * <p>Examples: {@code metresPerUnit(9001)} → {@code 1.0} (metre);
     * {@code metresPerUnit(9003)} → {@code 0.30480061} (US survey foot).
     *
     * @param uom an EPSG unit-of-measure code (e.g. from {@link #geoKey} with
     *            {@link #KEY_PROJ_LINEAR_UNITS}); {@code 0} when none was found
     * @return metres per CRS unit
     */
    static double metresPerUnit(int uom) {
        return switch (uom) {
            case UOM_FOOT -> 0.3048;
            case UOM_US_FOOT -> US_SURVEY_FOOT;
            default -> 1.0;   // metre, undefined, or absent
        };
    }

    // ---- OGC WKT ----

    // Matches an AUTHORITY["EPSG","<code>"] (WKT1) or ID["EPSG",<code>] (WKT2) clause and captures
    // the numeric code. Case-insensitive; the quotes around the code are optional (WKT2 omits them).
    private static final Pattern WKT_EPSG = Pattern.compile(
            "(?i)(?:AUTHORITY|ID)\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*\\]");
    // Matches a UNIT["<name>", <factor>] clause and captures the conversion factor (metres per
    // unit for a linear UNIT, radians per unit for an angular one).
    private static final Pattern WKT_UNIT = Pattern.compile(
            "(?i)UNIT\\s*\\[\\s*\"[^\"]*\"\\s*,\\s*([0-9.]+)");
    /**
     * Matches the first double-quoted token in a WKT element — i.e. that element's name. Handy on a
     * span from {@link #firstBracketedSpan}: applied to {@code VERT_CS["NAVD88 height (metre)", ...]}
     * its first match group is {@code NAVD88 height (metre)}.
     */
    static final Pattern NAME = Pattern.compile("\"([^\"]*)\"");

    /**
     * A cheap test of whether a string looks like OGC WKT (so we only try to parse real WKT).
     * True when it contains a top-level CRS keyword in either WKT1 or WKT2 spelling
     * ({@code PROJCS}/{@code PROJCRS}, {@code GEOGCS}/{@code GEOGCRS}, {@code COMPD_CS}/{@code COMPOUNDCRS}).
     *
     * @param s candidate text (may be {@code null})
     * @return {@code true} if it appears to be a WKT CRS description
     */
    static boolean looksLikeWkt(String s) {
        if (s == null) return false;
        String u = s.toUpperCase(Locale.ROOT);
        return u.contains("PROJCS") || u.contains("GEOGCS") || u.contains("COMPD_CS")
                || u.contains("PROJCRS") || u.contains("GEOGCRS") || u.contains("COMPOUNDCRS");
    }

    /**
     * The EPSG code of a WKT string's <b>horizontal</b> CRS, or {@code null} if none is present.
     * <p>
     * The subtlety this method exists for: a <b>compound</b> CRS lists a horizontal sub-CRS and a
     * vertical sub-CRS, each with its own {@code AUTHORITY["EPSG",...]} — e.g.
     * <pre>{@code
     * COMPD_CS["NAD83 / Florida North (ftUS) + NAVD88 height (ftUS)",
     *          PROJCS["NAD83 / Florida North (ftUS)", ... AUTHORITY["EPSG","2238"]],
     *          VERT_CS["NAVD88 height (ftUS)",          ... AUTHORITY["EPSG","6360"]]]
     * }</pre>
     * Blindly taking the last EPSG code would return the <em>vertical</em> one ({@code 6360}), which
     * cannot georeference a footprint. So we look <em>inside the projected sub-CRS first</em>, then
     * the geographic one, and only fall back to a whole-string scan when neither is found. For the
     * example above this returns {@code 2238} (the horizontal Florida North code).
     *
     * @param wkt an OGC WKT CRS string
     * @return the horizontal CRS's EPSG code, or {@code null}
     */
    static Integer horizontalEpsg(String wkt) {
        String horizontal = firstBracketedSpan(wkt, "PROJCS", "PROJCRS");
        if (horizontal == null) horizontal = firstBracketedSpan(wkt, "GEOGCS", "GEOGCRS", "GEODCRS");
        Integer epsg = horizontal != null ? lastEpsg(horizontal) : null;
        return epsg != null ? epsg : lastEpsg(wkt);
    }

    /**
     * The <em>last</em> {@code AUTHORITY["EPSG",...]} / {@code ID["EPSG",...]} code in a WKT
     * fragment, or {@code null} if there is none.
     * <p>
     * Within a single CRS element the inner pieces (datum, spheroid, unit, ...) carry their own
     * EPSG codes and the element's <em>own</em> code comes last, so "last match wins" yields the
     * element's identity. Call this on a fragment already narrowed to one element (e.g. the result
     * of {@link #firstBracketedSpan}); calling it on a whole compound CRS would return the
     * vertical code — which is exactly why {@link #horizontalEpsg} narrows first.
     *
     * @param wkt a WKT fragment (ideally a single CRS element)
     * @return the last EPSG code found, or {@code null}
     */
    static Integer lastEpsg(String wkt) {
        Matcher m = WKT_EPSG.matcher(wkt);
        Integer last = null;
        while (m.find()) last = Integer.valueOf(m.group(1));   // an element's own authority is its last match
        return last;
    }

    /**
     * The projected CRS's <b>linear</b> unit factor (metres per unit) from a WKT string, or
     * {@code null} when there is no {@code PROJCS}/{@code PROJCRS} with a {@code UNIT}.
     * <p>
     * A {@code PROJCS} contains two units: the nested {@code GEOGCS}'s angular unit (degree,
     * ~0.0174533) appears first, and the projected linear unit (metre = 1, or US survey foot
     * ≈ 0.3048006) appears last — so we deliberately keep the <em>last</em> {@code UNIT} match.
     *
     * <p>Example: for {@code PROJCS[..., UNIT["US survey foot", 0.304800609601219]]} this returns
     * {@code 0.3048006...}.
     *
     * @param wkt an OGC WKT CRS string
     * @return metres per projected unit, or {@code null} if not determinable
     */
    static Double projUnitMetres(String wkt) {
        String projcs = firstBracketedSpan(wkt, "PROJCS", "PROJCRS");
        if (projcs == null) return null;
        Matcher m = WKT_UNIT.matcher(projcs);
        Double factor = null;
        while (m.find()) factor = Double.parseDouble(m.group(1));   // keep the last (linear) UNIT
        return factor;
    }

    /**
     * Returns the bracket-balanced substring of the first WKT element named by any of
     * {@code keywords}, or {@code null} if none appears.
     * <p>
     * For example, with keyword {@code "PROJCS"} and input
     * {@code COMPD_CS["..", PROJCS["NAD83 / UTM zone 17N", ...], VERT_CS[..]]}, this returns the
     * whole {@code PROJCS["NAD83 / UTM zone 17N", ...]} substring (from its {@code [} through the
     * matching {@code ]}). Passing several keywords lets callers accept WKT1 and WKT2 spellings in
     * one call, e.g. {@code firstBracketedSpan(wkt, "GEOGCS", "GEOGCRS", "GEODCRS")}.
     * <p>
     * Matching is case-insensitive, the keyword must be a whole word (so {@code GEOGCS} does not
     * match inside {@code BASEGEOGCRS}), bracketed names in quotes are skipped so brackets inside a
     * name don't affect nesting, and both {@code []} and {@code ()} delimiters are accepted.
     *
     * @param wkt      the full WKT string
     * @param keywords element keywords to try, in order (e.g. {@code "PROJCS", "PROJCRS"})
     * @return the first matching element including its brackets, or {@code null}
     */
    static String firstBracketedSpan(String wkt, String... keywords) {
        String upper = wkt.toUpperCase(Locale.ROOT);
        for (String kw : keywords) {
            for (int idx = upper.indexOf(kw); idx >= 0; idx = upper.indexOf(kw, idx + 1)) {
                // Require a word boundary on the left so e.g. GEOGCS != ...BASEGEOGCRS substring.
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

    /**
     * The substring from {@code open} (the index of a {@code [} or {@code (}) through its matching
     * close bracket, ignoring brackets that appear inside double-quoted names. Returns the rest of
     * the string if the brackets are unbalanced (defensive; shouldn't happen in valid WKT).
     */
    private static String balancedSpan(String s, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inString = !inString;                       // toggle: brackets inside names don't count
            } else if (!inString) {
                if (c == '[' || c == '(') depth++;
                else if (c == ']' || c == ')') {
                    if (--depth == 0) return s.substring(open, i + 1);
                }
            }
        }
        return s.substring(open);   // unbalanced; return the remainder
    }
}
