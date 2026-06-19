package com.spotable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Second pass over {@link LazNameBounds}'s {@code --merged} output: copies each sub-project row and
 * appends measurements read from the actual point files, leaving the existing columns (including the
 * horizontal projection) untouched and moving {@code geometry} to the end. The CSV/sampling skeleton
 * is shared with {@link TifByProject} via {@link ByProject}; this class supplies only the LAS/LAZ
 * measurement.
 * <p>
 * The name-based merge describes a project's footprint and horizontal CRS, but not its vertical CRS
 * or how dense the cloud is — those live in the point files. For each project we sample a handful of
 * its tiles and read only the LAS/LAZ <em>header</em> of each from S3 (a ranged GET, no point
 * decompression). From the declared point count and bounding box we compute the tile's density,
 * {@code points / area} (the CRS's linear unit is honoured so the area is always in square metres),
 * and we read the vertical CRS from the header. The per-project density is the <em>median</em> over
 * the sampled tiles (robust to a sparse edge tile); the vertical CRS is the most common seen.
 * <p>
 * Four columns are appended, then {@code geometry} (kept last):
 * <ul>
 *   <li>{@code vertical_epsg} / {@code vertical_projection} — the file's vertical CRS
 *       (e.g. {@code EPSG:5703}, {@code NAVD88 height (metre)}).</li>
 *   <li>{@code point_density_per_m2} — median points per m&sup2;.</li>
 *   <li>{@code avg_point_spacing_m} — nominal ground sample distance, {@code 1/sqrt(density)}.</li>
 * </ul>
 * <pre>
 *   java com.spotable.LazByProject --merged laz_merged.csv --bounds laz_bounds.csv \
 *       --output laz_merged_meta.csv --prefix s3://bucket/point-cloud/us --sample 5
 * </pre>
 * The tile keys come from {@code --bounds} (its {@code filename} column, joined to each merged
 * row by the shared {@code project} key); {@code --prefix} is prepended to form the S3 URI.
 */
public final class LazByProject {

    private LazByProject() {}

    public static void main(String[] args) throws IOException {
        ByProject.run(args, new Spec());
    }

    /** The LAS/LAZ measurement: median point density (points/m&sup2;) and the header's vertical CRS. */
    private static final class Spec implements ByProject.Spec {

        public String tool() { return "LazByProject"; }

        public List<String> metricColumns() {
            return List.of("point_density_per_m2", "avg_point_spacing_m");
        }

        public ByProject.TileMeta readTile(S3Client s3, String bucket, String key) throws IOException {
            LazBinaryReader b = LazBinaryReader.readS3(s3, bucket, key);
            double area = b.areaSqMetres();
            double density = b.pointCount > 0 && area > 0 ? b.pointCount / area : Double.NaN;
            return new ByProject.TileMeta(density,
                    b.verticalEpsg != null ? "EPSG:" + b.verticalEpsg : null, b.verticalCrs);
        }

        public ByProject.MetricView render(double density) {
            String dens = Double.isNaN(density) ? "" : String.format(Locale.ROOT, "%.4f", density);
            String spacing = Double.isNaN(density) || density <= 0
                    ? "" : String.format(Locale.ROOT, "%.3f", 1.0 / Math.sqrt(density));
            return new ByProject.MetricView(List.of(dens, spacing),
                    "density=" + (dens.isEmpty() ? "?" : dens)
                            + " spacing=" + (spacing.isEmpty() ? "?" : spacing + "m"));
        }
    }
}
