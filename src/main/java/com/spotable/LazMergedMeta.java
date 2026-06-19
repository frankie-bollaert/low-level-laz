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
 * Second pass over {@link LazNameBounds}'s {@code --merged} output: copies each sub-project row and
 * appends measured point-density figures, leaving the existing columns (including the horizontal
 * projection) untouched.
 * <p>
 * The name-based merge can describe a project's footprint and CRS, but not how dense the cloud is —
 * that lives in the point files. For each project we sample a handful of its tiles, read only the
 * LAS/LAZ <em>header</em> of each from S3 (a ranged GET, no point decompression), and from the
 * declared point count and bounding box compute the tile's average density,
 * {@code points / area}. The CRS's linear unit is honoured so the area is always in square metres.
 * The per-project figure reported is the <em>median</em> over the sampled tiles, which is robust to
 * the occasional sparse edge tile.
 * <p>
 * Two columns are added: {@code point_density_per_m2} (points per m&sup2;) and
 * {@code avg_point_spacing_m} (nominal ground sample distance, {@code 1/sqrt(density)}).
 * <pre>
 *   java com.spotable.LazMergedMeta --merged laz_merged.csv --bounds laz_bounds.csv \
 *       --output laz_merged_meta.csv --prefix s3://bucket/point-cloud/us --sample 5
 * </pre>
 * The tile keys come from {@code --bounds} (its {@code filename} column, joined to each merged
 * row by the shared {@code project} key); {@code --prefix} is prepended to form the S3 URI.
 */
public final class LazMergedMeta {

    private LazMergedMeta() {}

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
            System.err.println("Usage: java com.spotable.LazMergedMeta --merged <merged.csv> --bounds <bounds.csv>");
            System.err.println("                                       [--output <out.csv>] [--prefix <base>] [--sample <N>]");
            System.err.println("  --merged  the per-project CSV from LazNameBounds --merged (required, input).");
            System.err.println("  --bounds  the per-tile CSV from LazNameBounds (required); supplies the sample file keys.");
            System.err.println("  --output  (-o) destination CSV (default: stdout). Copies --merged and appends");
            System.err.println("                vertical_epsg, vertical_projection, point_density_per_m2, avg_point_spacing_m");
            System.err.println("                (geometry kept last), measured from N sampled tiles per project.");
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
                List<String> f = parseCsv(line);
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
            int geomIdx = parseCsv(header).indexOf("geometry");
            // New columns sit after the existing ones; geometry is moved to the very end.
            writeRow(out, reorder(parseCsv(header), geomIdx,
                    "vertical_epsg", "vertical_projection", "point_density_per_m2", "avg_point_spacing_m"));

            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) rows.add(parseCsv(line).toArray(new String[0]));
            }
            total = rows.size();
            for (String[] row : rows) {
                String project = row.length > 0 ? row[0] : "";
                Meta meta = sampleMeta(s3, prefix, filesByProject.getOrDefault(project, List.of()), sample);
                String dens = Double.isNaN(meta.density()) ? "" : String.format(Locale.ROOT, "%.4f", meta.density());
                String spacing = Double.isNaN(meta.density()) || meta.density() <= 0
                        ? "" : String.format(Locale.ROOT, "%.3f", 1.0 / Math.sqrt(meta.density()));
                writeRow(out, reorder(List.of(row), geomIdx,
                        meta.verticalEpsg(), meta.verticalCrs(), dens, spacing));
                System.err.printf(Locale.ROOT, "  [%d/%d] %-44s vert=%-9s density=%s spacing=%s%n",
                        ++done, total, project, meta.verticalEpsg().isEmpty() ? "?" : meta.verticalEpsg(),
                        dens.isEmpty() ? "?" : dens, spacing.isEmpty() ? "?" : spacing + "m");
            }
        }
        System.err.println("Wrote " + done + " rows" + (output != null ? " to " + output : ""));
    }

    /** Per-project measurements sampled from the point files. */
    private record Meta(double density, String verticalEpsg, String verticalCrs) {}

    /**
     * Samples up to {@code sample} evenly spread tiles: median density (points/m^2) plus the most
     * common vertical CRS seen across them. Tiles that can't be read are skipped.
     */
    private static Meta sampleMeta(S3Client s3, String prefix, List<String> files, int sample) {
        if (files.isEmpty() || s3 == null) return new Meta(Double.NaN, "", "");
        List<Double> densities = new ArrayList<>();
        List<String> vEpsgs = new ArrayList<>(), vNames = new ArrayList<>();
        int step = Math.max(1, files.size() / sample);
        for (int i = 0; i < files.size() && densities.size() < sample; i += step) {
            String key = (prefix + "/" + files.get(i).replaceAll("^/+", "")).substring("s3://".length());
            int slash = key.indexOf('/');
            if (slash < 0) continue;
            try {
                LazBinaryReader b = LazBinaryReader.readS3(s3, key.substring(0, slash), key.substring(slash + 1));
                double area = b.areaSqMetres();
                if (b.pointCount > 0 && area > 0) densities.add(b.pointCount / area);
                if (b.verticalEpsg != null) vEpsgs.add("EPSG:" + b.verticalEpsg);
                if (b.verticalCrs != null) vNames.add(b.verticalCrs);
            } catch (Exception e) {
                // skip unreadable / missing / truncated tiles; keep sampling the rest
            }
        }
        return new Meta(median(densities), mostCommon(vEpsgs), mostCommon(vNames));
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
