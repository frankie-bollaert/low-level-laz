package com.spotable;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Serialises the {@link ByProject} per-project rows as <b>GeoJSON</b> — an RFC 7946
 * {@code FeatureCollection} — instead of CSV. Each row becomes a {@code Feature} whose geometry is
 * parsed from the row's EWKT {@code geometry} column and whose remaining columns become
 * {@code properties}.
 * <p>
 * The rows are already in WGS84 ({@code SRID=4326}), which is exactly the coordinate reference
 * system RFC 7946 mandates, so the {@code SRID=4326;} prefix is simply dropped. Only the geometry
 * types these pipelines emit are understood — {@code POLYGON} and {@code MULTIPOLYGON}; any other
 * EWKT yields a {@code null} geometry rather than a guess.
 * <p>
 * No JSON dependency: the structure here is small and fixed, so values are written directly with
 * minimal string escaping. All methods are static; this class is not instantiable.
 */
final class GeoJson {

    private GeoJson() {}

    // A bare JSON number (integer or decimal). Values matching this are emitted unquoted so GIS
    // tools see numbers; everything else (CRS codes like "EPSG:26917", names, paths) stays a string.
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /**
     * Writes {@code rows} as a GeoJSON {@code FeatureCollection} to {@code out}. The column named
     * {@code geometry} (or, failing that, the last column) supplies each feature's geometry; the
     * other columns become its properties, in column order. {@code out} is not closed.
     */
    static void writeFeatureCollection(List<String> columns, List<List<String>> rows, Writer out)
            throws IOException {
        int geomIdx = columns.indexOf("geometry");
        if (geomIdx < 0) geomIdx = columns.size() - 1;
        out.write("{\n\"type\": \"FeatureCollection\",\n\"features\": [\n");
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) out.write(",\n");
            out.write(feature(columns, rows.get(r), geomIdx));
        }
        out.write("\n]\n}\n");
    }

    private static String feature(List<String> columns, List<String> values, int geomIdx) {
        StringBuilder b = new StringBuilder("{\"type\": \"Feature\", \"properties\": {");
        boolean first = true;
        for (int i = 0; i < columns.size(); i++) {
            if (i == geomIdx) continue;
            if (!first) b.append(", ");
            first = false;
            String v = i < values.size() ? values.get(i) : "";
            b.append(string(columns.get(i))).append(": ").append(value(v));
        }
        String geom = geomIdx < values.size() ? values.get(geomIdx) : "";
        return b.append("}, \"geometry\": ").append(geometry(geom)).append("}").toString();
    }

    /** A property value: a JSON {@code null} for empty, a bare number where numeric, else a string. */
    private static String value(String raw) {
        if (raw == null || raw.isEmpty()) return "null";
        return NUMBER.matcher(raw).matches() ? raw : string(raw);
    }

    /**
     * The GeoJSON geometry object for an EWKT {@code POLYGON}/{@code MULTIPOLYGON} string (the
     * {@code SRID=…;} prefix is dropped), or the JSON literal {@code null} when the value is empty
     * or not a supported geometry.
     */
    static String geometry(String ewkt) {
        if (ewkt == null || ewkt.isBlank()) return "null";
        String s = ewkt.trim();
        if (s.regionMatches(true, 0, "SRID=", 0, 5)) {
            int semi = s.indexOf(';');
            if (semi >= 0) s = s.substring(semi + 1).trim();
        }
        String upper = s.toUpperCase(Locale.ROOT);
        String type;
        if (upper.startsWith("MULTIPOLYGON")) type = "MultiPolygon";
        else if (upper.startsWith("POLYGON")) type = "Polygon";
        else return "null";   // other geometry types aren't produced by these pipelines
        int open = s.indexOf('(');
        if (open < 0) return "null";
        return "{\"type\": \"" + type + "\", \"coordinates\": " + coords(s.substring(open)) + "}";
    }

    /**
     * Converts a balanced WKT coordinate group into nested JSON arrays. A group wrapping further
     * groups (e.g. a polygon's rings, or a multipolygon's polygons) recurses; the innermost group is
     * a ring — a comma-separated list of {@code "x y"} points — and becomes an array of
     * {@code [x, y]} pairs.
     */
    private static String coords(String group) {
        String inner = stripOuterParens(group);
        if (inner.trim().startsWith("(")) {
            StringBuilder b = new StringBuilder("[");
            List<String> parts = splitTopLevel(inner);
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) b.append(",");
                b.append(coords(parts.get(i)));
            }
            return b.append("]").toString();
        }
        // A ring: "x y, x y, ..." -> [[x,y],[x,y],...]
        StringBuilder b = new StringBuilder("[");
        List<String> points = splitTopLevel(inner);
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) b.append(",");
            String[] xy = points.get(i).trim().split("\\s+");
            b.append("[").append(xy[0]).append(",").append(xy[1]).append("]");
        }
        return b.append("]").toString();
    }

    /** Strips the matching outer {@code ( … )} from a trimmed bracket group. */
    private static String stripOuterParens(String s) {
        s = s.trim();
        return s.length() >= 2 && s.charAt(0) == '(' && s.charAt(s.length() - 1) == ')'
                ? s.substring(1, s.length() - 1) : s;
    }

    /** Splits on commas that sit at paren-depth zero (so nested groups/points stay intact). */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** A JSON string literal: wrapped in quotes with the mandatory characters escaped. */
    static String string(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append("\"").toString();
    }
}
