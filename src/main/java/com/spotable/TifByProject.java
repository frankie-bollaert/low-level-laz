package com.spotable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Second pass over {@link TifNameBounds}'s {@code --merged} output, the raster counterpart to
 * {@link LazByProject}: copies each sub-project row and appends measurements read from the actual
 * GeoTIFFs, leaving the existing columns (including the horizontal projection) untouched and moving
 * {@code geometry} to the end. The CSV/sampling skeleton is shared with {@link LazByProject} via
 * {@link ByProject}; this class supplies only the GeoTIFF measurement.
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
        ByProject.run(args, new Spec());
    }

    /** The GeoTIFF measurement: median pixel size (metres) and the header's vertical CRS. */
    private static final class Spec implements ByProject.Spec {

        public String tool() { return "TifByProject"; }

        public List<String> metricColumns() {
            return List.of("resolution_m");
        }

        public ByProject.TileMeta readTile(S3Client s3, String bucket, String key) throws IOException {
            TifBinaryReader t = TifBinaryReader.readS3(s3, bucket, key);
            return new ByProject.TileMeta(t.resolutionMetres,
                    t.verticalEpsg != null ? "EPSG:" + t.verticalEpsg : null, t.verticalCrs);
        }

        public ByProject.MetricView render(double resolution) {
            String res = Double.isNaN(resolution) ? "" : String.format(Locale.ROOT, "%.4f", resolution);
            return new ByProject.MetricView(List.of(res),
                    "resolution=" + (res.isEmpty() ? "?" : res + "m"));
        }
    }
}
