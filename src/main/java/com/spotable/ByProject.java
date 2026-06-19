package com.spotable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Shared skeleton for the two "by project" second passes, {@link LazByProject} (point clouds) and
 * {@link TifByProject} (rasters). Both do the same thing: copy each sub-project row from a
 * {@code *NameBounds --merged} CSV, sample a handful of that project's tiles, read only their
 * headers from S3, and append the <b>vertical CRS</b> plus one or two measured columns — leaving the
 * existing columns untouched and moving {@code geometry} to the very end.
 * <p>
 * Only the per-tile measurement differs between the two (point density vs. raster resolution), so
 * everything else — argument parsing, the bounds/merged CSV plumbing, the even-spread sampling loop,
 * median/most-common aggregation, row reordering and progress output — lives here once, and each
 * tool supplies just a {@link Spec}.
 */
final class ByProject {

    private ByProject() {}

    /** Columns every tool prepends to its own metric columns. */
    private static final List<String> VERTICAL_COLUMNS = List.of("vertical_epsg", "vertical_projection");

    /** One sampled tile's measurement: a metric ({@code NaN} to ignore) and its vertical CRS, if any. */
    record TileMeta(double metric, String verticalEpsg, String verticalName) {}

    /** A project's aggregate: the median metric and the most common vertical CRS over its sample. */
    record Sample(double metric, String verticalEpsg, String verticalName) {}

    /** A rendered metric: one CSV cell per metric column, plus a fragment for the progress line. */
    record MetricView(List<String> cells, String summary) {}

    /** The per-format parts the shared skeleton needs. */
    interface Spec {
        /** Simple class name of the tool, for the usage banner (e.g. {@code "LazByProject"}). */
        String tool();
        /** The metric column headers, appended after {@link #VERTICAL_COLUMNS}. */
        List<String> metricColumns();
        /** Reads one tile's measurement from S3, or {@code null} if it can't be read. */
        TileMeta readTile(S3Client s3, String bucket, String key) throws IOException;
        /** Formats a project's aggregate metric into a cell per {@link #metricColumns()} + a summary. */
        MetricView render(double metric);
    }

    static void run(String[] args, Spec spec) throws IOException {
        Path merged = null, bounds = null, output = null, convert = null;
        String prefix = null, format = null;
        int sample = 5;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--merged" -> merged = Path.of(requireArg(args, ++i, a));
                case "--bounds" -> bounds = Path.of(requireArg(args, ++i, a));
                case "--output", "-o" -> output = Path.of(requireArg(args, ++i, a));
                case "--prefix" -> prefix = requireArg(args, ++i, a).replaceAll("/+$", "");
                case "--sample" -> sample = Integer.parseInt(requireArg(args, ++i, a));
                case "--format" -> format = requireArg(args, ++i, a);
                case "--convert" -> convert = Path.of(requireArg(args, ++i, a));
                default -> { System.err.println("Unknown argument: " + a); System.exit(2); }
            }
        }
        if (format != null && !format.equalsIgnoreCase("csv") && !format.equalsIgnoreCase("geojson")) {
            System.err.println("--format must be csv or geojson"); System.exit(2); return;
        }
        // Default the format from --format, else infer from a .geojson/.json output name, else CSV.
        boolean geojson = format != null ? format.equalsIgnoreCase("geojson") : isGeoJsonName(output);

        // Conversion mode: re-serialise an existing by-project CSV in the chosen format (no S3,
        // no re-sampling) — the rows already carry the measured columns and the geometry.
        if (convert != null) {
            List<String> columns = new ArrayList<>();
            List<List<String>> rows = readCsv(convert, columns);
            writeOutput(columns, rows, output, geojson);
            System.err.println("Wrote " + rows.size() + (geojson ? " features" : " rows")
                    + (output != null ? " to " + output : ""));
            return;
        }

        if (merged == null || bounds == null) {
            usage(spec);
            System.exit(2);
            return;
        }

        // Tile keys per project, taken from the per-file bounds CSV (columns: project, filename, ...).
        Map<String, List<String>> filesByProject = new LinkedHashMap<>();
        try (BufferedReader in = Files.newBufferedReader(bounds, StandardCharsets.UTF_8)) {
            in.readLine();   // header
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parseCsv(line);
                if (f.size() < 2) continue;
                filesByProject.computeIfAbsent(f.get(0), k -> new ArrayList<>()).add(f.get(1));
            }
        }

        // Read the merged rows up front; geometry moves to the end and the new columns sit before it.
        List<String> columns;
        int geomIdx = -1;
        List<String[]> mergedRows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(merged, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (header == null) { System.err.println("Empty merged file"); System.exit(2); return; }
            List<String> mergedCols = parseCsv(header);
            geomIdx = mergedCols.indexOf("geometry");
            columns = reorder(mergedCols, geomIdx, appendedColumns(spec));
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) mergedRows.add(parseCsv(line).toArray(new String[0]));
            }
        }

        List<List<String>> outRows = new ArrayList<>();
        int done = 0, total = mergedRows.size();
        boolean needsS3 = prefix != null && prefix.startsWith("s3://");
        try (S3Client s3 = needsS3 ? S3Client.create() : null) {
            for (String[] row : mergedRows) {
                String project = row.length > 0 ? row[0] : "";
                Sample s = sampleProject(s3, prefix, filesByProject.getOrDefault(project, List.of()), sample, spec);
                MetricView view = spec.render(s.metric());
                List<String> cells = new ArrayList<>(List.of(s.verticalEpsg(), s.verticalName()));
                cells.addAll(view.cells());
                outRows.add(reorder(List.of(row), geomIdx, cells.toArray(new String[0])));
                System.err.printf(Locale.ROOT, "  [%d/%d] %-44s vert=%-9s %s%n",
                        ++done, total, project, s.verticalEpsg().isEmpty() ? "?" : s.verticalEpsg(),
                        view.summary());
            }
        }
        writeOutput(columns, outRows, output, geojson);
        System.err.println("Wrote " + done + (geojson ? " features" : " rows")
                + (output != null ? " to " + output : ""));
    }

    /** Whether a path's name ends with a GeoJSON extension ({@code .geojson}/{@code .json}). */
    private static boolean isGeoJsonName(Path p) {
        if (p == null) return false;
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".geojson") || n.endsWith(".json");
    }

    /** Reads a CSV file, collecting its header into {@code columns} and returning the data rows. */
    private static List<List<String>> readCsv(Path csv, List<String> columns) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = in.readLine();
            if (header == null) return rows;
            columns.addAll(parseCsv(header));
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) rows.add(parseCsv(line));
            }
        }
        return rows;
    }

    /** Writes {@code rows} (with {@code columns}) to {@code output} (or stdout) as CSV or GeoJSON. */
    private static void writeOutput(List<String> columns, List<List<String>> rows, Path output,
                                    boolean geojson) throws IOException {
        try (Writer out = output != null
                ? Files.newBufferedWriter(output, StandardCharsets.UTF_8) : null) {
            if (geojson) {
                Writer w = out != null ? out : new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
                GeoJson.writeFeatureCollection(columns, rows, w);
                w.flush();
            } else {
                writeRow(out, columns);
                for (List<String> row : rows) writeRow(out, row);
            }
        }
    }

    /** {@link #VERTICAL_COLUMNS} followed by the spec's metric columns, as a {@code reorder} arg. */
    private static String[] appendedColumns(Spec spec) {
        List<String> cols = new ArrayList<>(VERTICAL_COLUMNS);
        cols.addAll(spec.metricColumns());
        return cols.toArray(new String[0]);
    }

    /**
     * Samples up to {@code sample} evenly spread tiles: the median metric plus the most common
     * vertical CRS seen across them. Tiles that can't be read are skipped.
     */
    private static Sample sampleProject(S3Client s3, String prefix, List<String> files, int sample, Spec spec) {
        if (files.isEmpty() || s3 == null) return new Sample(Double.NaN, "", "");
        List<Double> metrics = new ArrayList<>();
        List<String> vEpsgs = new ArrayList<>(), vNames = new ArrayList<>();
        int step = Math.max(1, files.size() / sample);
        for (int i = 0; i < files.size() && metrics.size() < sample; i += step) {
            Sources.S3Ref ref = Sources.parseS3(prefix + "/" + files.get(i).replaceAll("^/+", ""));
            if (ref == null) continue;
            try {
                TileMeta m = spec.readTile(s3, ref.bucket(), ref.key());
                if (m == null) continue;
                if (!Double.isNaN(m.metric()) && m.metric() > 0) metrics.add(m.metric());
                if (m.verticalEpsg() != null) vEpsgs.add(m.verticalEpsg());
                if (m.verticalName() != null) vNames.add(m.verticalName());
            } catch (Exception e) {
                // skip unreadable / missing / truncated tiles; keep sampling the rest
            }
        }
        return new Sample(median(metrics), mostCommon(vEpsgs), mostCommon(vNames));
    }

    private static void usage(Spec spec) {
        String tool = spec.tool();
        String source = tool.replace("ByProject", "NameBounds");
        String pad = " ".repeat(("Usage: java com.spotable." + tool + " ").length());
        List<String> allColumns = new ArrayList<>(VERTICAL_COLUMNS);
        allColumns.addAll(spec.metricColumns());
        System.err.println("Usage: java com.spotable." + tool + " --merged <merged.csv> --bounds <bounds.csv>");
        System.err.println(pad + "[--output <out>] [--format csv|geojson] [--prefix <base>] [--sample <N>]");
        System.err.println("  --merged  the per-project CSV from " + source + " --merged (required, input).");
        System.err.println("  --bounds  the per-tile CSV from " + source + " (required); supplies the sample file keys.");
        System.err.println("  --output  (-o) destination file (default: stdout). Copies --merged and appends");
        System.err.println("                " + String.join(", ", allColumns) + " (geometry kept last),");
        System.err.println("                measured from N sampled tiles per project.");
        System.err.println("  --format  csv (default) or geojson; inferred as geojson from a .geojson/.json --output.");
        System.err.println("  --prefix  base path prepended to each sampled filename to form its s3:// URI.");
        System.err.println("  --sample  tiles to sample per project (default 5); headers read via ranged GET.");
        System.err.println("  --convert re-serialise an already-written by-project CSV in --format (no S3/sampling);");
        System.err.println("            use instead of --merged/--bounds, e.g. --convert out.csv -o out.geojson.");
    }

    // ---- shared helpers ----

    /** The most frequently seen value, or {@code ""} if none. */
    static String mostCommon(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String v : values) counts.merge(v, 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
    }

    /** Drops {@code geomIdx} from {@code base}, appends {@code extra} and re-appends geometry last. */
    static List<String> reorder(List<String> base, int geomIdx, String... extra) {
        List<String> out = new ArrayList<>(base);
        String geom = geomIdx >= 0 && geomIdx < out.size() ? out.remove(geomIdx) : "";
        out.addAll(List.of(extra));
        out.add(geom);
        return out;
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        v.sort(null);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    static void writeRow(Writer out, List<String> fields) throws IOException {
        String row = TifNameBounds.csvRow(fields.toArray(new String[0]));
        if (out != null) { out.write(row); out.write('\n'); } else System.out.println(row);
    }

    static String requireArg(String[] args, int i, String flag) {
        if (i >= args.length) { System.err.println(flag + " needs a value"); System.exit(2); }
        return args[i];
    }

    /** Parses one RFC 4180 record: comma-separated, double-quoted fields with doubled quotes. */
    static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
