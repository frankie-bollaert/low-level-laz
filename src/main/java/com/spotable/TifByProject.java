package com.spotable;

import java.io.BufferedReader;
import java.io.IOException;
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
 * Second pass over {@link TifNameBounds}'s {@code --merged} output, the raster counterpart to
 * {@link LazByProject}: copies each sub-project row and appends measurements read from the actual
 * GeoTIFFs, leaving the existing columns (including the horizontal projection) untouched and moving
 * {@code geometry} to the end.
 * <p>
 * The name-based merge describes a project's footprint and horizontal CRS, but not its vertical CRS
 * or its raster resolution — those live in the files. For each project we sample a handful of its
 * tiles and read only each GeoTIFF's <em>header</em> from S3 (ranged GETs, never the pixels): the
 * pixel size (honouring the CRS's linear unit, so it is reported in metres) and the vertical CRS.
 * The per-project resolution is the <em>median</em> over the sampled tiles; the vertical CRS is the
 * most common seen (usually none — 1&nbsp;m DEMs rarely declare one).
 * <p>
 * Three columns are appended, then {@code geometry} (kept last):
 * <ul>
 *   <li>{@code vertical_epsg} / {@code vertical_projection} — the file's vertical CRS, when present
 *       (e.g. {@code EPSG:5703}, {@code NAVD88 height (m)}).</li>
 *   <li>{@code resolution_m} — median ground sample distance (pixel size) in metres.</li>
 * </ul>
 * <pre>
 *   java com.spotable.TifByProject --merged dtm_merged.csv --bounds dtm_bounds.csv \
 *       --output dtm_merged_meta.csv --prefix s3://bucket/dtm/us --sample 5
 * </pre>
 * The tile keys come from {@code --bounds} (its {@code filename} column, joined to each merged
 * row by the shared {@code project} key); {@code --prefix} is prepended to form the S3 URI.
 */
public final class TifByProject {

    private TifByProject() {}

    public static void main(String[] args) throws IOException {
        Path merged = null, bounds = null, output = null;
        String prefix = null;
        int sample = 5;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--merged" -> merged = Path.of(requireArg(args, ++i, a));
                case "--bounds" -> bounds = Path.of(requireArg(args, ++i, a));
                case "--output", "-o" -> output = Path.of(requireArg(args, ++i, a));
                case "--prefix" -> prefix = requireArg(args, ++i, a).replaceAll("/+$", "");
                case "--sample" -> sample = Integer.parseInt(requireArg(args, ++i, a));
                default -> { System.err.println("Unknown argument: " + a); System.exit(2); }
            }
        }
        if (merged == null || bounds == null) {
            System.err.println("Usage: java com.spotable.TifByProject --merged <merged.csv> --bounds <bounds.csv>");
            System.err.println("                                      [--output <out.csv>] [--prefix <base>] [--sample <N>]");
            System.err.println("  --merged  the per-project CSV from TifNameBounds --merged (required, input).");
            System.err.println("  --bounds  the per-tile CSV from TifNameBounds (required); supplies the sample file keys.");
            System.err.println("  --output  (-o) destination CSV (default: stdout). Copies --merged and appends");
            System.err.println("                vertical_epsg, vertical_projection, resolution_m (geometry kept last),");
            System.err.println("                measured from N sampled tiles per project.");
            System.err.println("  --prefix  base path prepended to each sampled filename to form its s3:// URI.");
            System.err.println("  --sample  tiles to sample per project (default 5); headers read via ranged GET.");
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
                List<String> f = LazByProject.parseCsv(line);
                if (f.size() < 2) continue;
                filesByProject.computeIfAbsent(f.get(0), k -> new ArrayList<>()).add(f.get(1));
            }
        }

        boolean needsS3 = prefix != null && prefix.startsWith("s3://");
        int done = 0, total;
        try (S3Client s3 = needsS3 ? S3Client.create() : null;
             BufferedReader in = Files.newBufferedReader(merged, StandardCharsets.UTF_8);
             Writer out = output != null
                     ? Files.newBufferedWriter(output, StandardCharsets.UTF_8) : null) {

            String header = in.readLine();
            if (header == null) { System.err.println("Empty merged file"); System.exit(2); return; }
            int geomIdx = LazByProject.parseCsv(header).indexOf("geometry");
            writeRow(out, reorder(LazByProject.parseCsv(header), geomIdx,
                    "vertical_epsg", "vertical_projection", "resolution_m"));

            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) rows.add(LazByProject.parseCsv(line).toArray(new String[0]));
            }
            total = rows.size();
            for (String[] row : rows) {
                String project = row.length > 0 ? row[0] : "";
                Meta meta = sampleMeta(s3, prefix, filesByProject.getOrDefault(project, List.of()), sample);
                String res = Double.isNaN(meta.resolution()) ? ""
                        : String.format(Locale.ROOT, "%.4f", meta.resolution());
                writeRow(out, reorder(List.of(row), geomIdx,
                        meta.verticalEpsg(), meta.verticalCrs(), res));
                System.err.printf(Locale.ROOT, "  [%d/%d] %-44s vert=%-9s resolution=%s%n",
                        ++done, total, project, meta.verticalEpsg().isEmpty() ? "?" : meta.verticalEpsg(),
                        res.isEmpty() ? "?" : res + "m");
            }
        }
        System.err.println("Wrote " + done + " rows" + (output != null ? " to " + output : ""));
    }

    /** Per-project measurements sampled from the raster files. */
    private record Meta(double resolution, String verticalEpsg, String verticalCrs) {}

    /**
     * Samples up to {@code sample} evenly spread tiles: median pixel size (metres) plus the most
     * common vertical CRS seen across them. Tiles that can't be read are skipped.
     */
    private static Meta sampleMeta(S3Client s3, String prefix, List<String> files, int sample) {
        if (files.isEmpty() || s3 == null) return new Meta(Double.NaN, "", "");
        List<Double> resolutions = new ArrayList<>();
        List<String> vEpsgs = new ArrayList<>(), vNames = new ArrayList<>();
        int step = Math.max(1, files.size() / sample);
        for (int i = 0; i < files.size() && resolutions.size() < sample; i += step) {
            String key = (prefix + "/" + files.get(i).replaceAll("^/+", "")).substring("s3://".length());
            int slash = key.indexOf('/');
            if (slash < 0) continue;
            try {
                TifBinaryReader t = TifBinaryReader.readS3(s3, key.substring(0, slash), key.substring(slash + 1));
                if (!Double.isNaN(t.resolutionMetres) && t.resolutionMetres > 0) resolutions.add(t.resolutionMetres);
                if (t.verticalEpsg != null) vEpsgs.add("EPSG:" + t.verticalEpsg);
                if (t.verticalCrs != null) vNames.add(t.verticalCrs);
            } catch (Exception e) {
                // skip unreadable / missing tiles; keep sampling the rest
            }
        }
        return new Meta(median(resolutions), mostCommon(vEpsgs), mostCommon(vNames));
    }

    /** The most frequently seen value, or {@code ""} if none. */
    private static String mostCommon(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String v : values) counts.merge(v, 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
    }

    /** Drops {@code geomIdx} from {@code base}, appends {@code extra} and re-appends geometry last. */
    private static List<String> reorder(List<String> base, int geomIdx, String... extra) {
        List<String> out = new ArrayList<>(base);
        String geom = geomIdx >= 0 && geomIdx < out.size() ? out.remove(geomIdx) : "";
        out.addAll(List.of(extra));
        out.add(geom);
        return out;
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        v.sort(null);
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
    }

    private static void writeRow(Writer out, List<String> fields) throws IOException {
        String row = TifNameBounds.csvRow(fields.toArray(new String[0]));
        if (out != null) { out.write(row); out.write('\n'); } else System.out.println(row);
    }

    private static String requireArg(String[] args, int i, String flag) {
        if (i >= args.length) { System.err.println(flag + " needs a value"); System.exit(2); }
        return args[i];
    }
}
