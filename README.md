# low-level-laz

Tools for deriving USGS LPC point-cloud (LAZ) tile footprints and metadata, mostly
**from the file name alone**, plus a second pass that reads a small sample of real
file headers from S3 to attach measured point density and the vertical CRS.

The pipeline turns a flat list of object paths into two CSVs:

- `laz_bounds.csv` — one WGS84 footprint per tile (name-derived, no network).
- `laz_merged.csv` — one row per sub-project: merged footprint + horizontal CRS (name-derived).
- `laz_merged_meta.csv` — `laz_merged.csv` enriched with the **vertical CRS** and measured
  **point density**, read from a sample of actual headers on S3.

## Prerequisites

- Java 17+ and Maven.
- For the metadata step only: AWS credentials with read access to the bucket, exposed as a
  profile named `west` (the bucket is in `us-west-2`):

  ```sh
  export AWS_PROFILE=west      # or prefix each command with AWS_PROFILE=west
  ```

  Steps 1 (name-only) needs no AWS access; step 2 reads object headers via ranged GETs.

## Build

```sh
mvn -q package -DskipTests
```

This produces a self-contained "fat" jar with the AWS SDK bundled:

```
target/low-level-laz-1.0-SNAPSHOT.jar
```

All commands below run a specific `main` class out of that one jar:

```sh
JAR=target/low-level-laz-1.0-SNAPSHOT.jar
```

## Input

A text file (`point-cloud.csv`) with **one object path per line**, e.g.

```
FL_Panhandle_2018_B18/FL_Panhandle_B1_2018/LAZ/USGS_LPC_FL_Panhandle_2018_B18_e0853n0913.laz
FL_2017FortDrum_C22/FL_2017FortDrum_1_C22/LAZ/USGS_LPC_FL_2017FortDrum_C22_17RNL070510.laz
```

Paths are relative to a base prefix that is supplied separately with `--prefix`.

## The sequence

### Step 1 — name-derived footprints (no network)

```sh
java -cp "$JAR" com.spotable.LazNameBounds \
    --input point-cloud.csv \
    --output laz_bounds.csv \
    --merged laz_merged.csv \
    --prefix s3://spotable-geo-us-west-2/point-cloud/us
```

| flag | required | meaning |
| --- | --- | --- |
| `--input` (`-i`) | yes | list of object paths, one per line |
| `--output` (`-o`) | no | per-tile CSV (default: stdout) |
| `--merged` | no | also write the per-project merged CSV to this path |
| `--tile` | no | force a fixed tile size in metres (default: inferred per project from the grid step) |
| `--prefix` | no | base path prepended to the `directory` column |

- `laz_bounds.csv` columns: `project, filename, geometry`
- `laz_merged.csv` columns: `project, directory, files, year, horizontal_epsg,
  horizontal_projection, geometry`
- `--prefix` is prepended to the `directory` column only (the footprints are computed from
  the names, so this step makes no network calls).

Names that can't be decoded to a footprint are skipped and counted on stderr (see
[Coverage](#coverage)).

### Step 2 — enrich with vertical CRS + point density (reads S3)

```sh
AWS_PROFILE=west java -cp "$JAR" com.spotable.LazMergedMeta \
    --merged laz_merged.csv \
    --bounds laz_bounds.csv \
    --output laz_merged_meta.csv \
    --prefix s3://spotable-geo-us-west-2/point-cloud/us \
    --sample 5
```

| flag | required | meaning |
| --- | --- | --- |
| `--merged` | yes | the per-project CSV from step 1 (the file being enriched) |
| `--bounds` | yes | the per-tile CSV from step 1 (supplies the sample file keys) |
| `--output` (`-o`) | no | destination CSV (default: stdout) |
| `--prefix` | no | base path prepended to each sampled filename to form its `s3://` URI |
| `--sample` | no | tiles to sample per project (default 5) |

For each project it samples `--sample` tiles (default 5, evenly spread), reads **only each
file's header** (a single ranged GET — no point decompression), and records:

- `vertical_epsg` / `vertical_projection` — the file's vertical CRS (e.g. `EPSG:5703`,
  `NAVD88 height (m)`), taken as the most common across the samples.
- `point_density_per_m2` — median of `point_count / bbox_area` over the samples; the CRS
  linear unit is honoured, so the area is always in m² (US-foot CRSs are converted).
- `avg_point_spacing_m` — nominal ground sample distance, `1 / sqrt(density)`.

`laz_merged_meta.csv` columns: `project, directory, files, year, horizontal_epsg,
horizontal_projection, vertical_epsg, vertical_projection, point_density_per_m2,
avg_point_spacing_m, geometry` (geometry kept last).

`--prefix` is prepended to each sampled `filename` to form the S3 URI; it must point at the
same base used in step 1.

## Quick reference

```sh
mvn -q package -DskipTests
JAR=target/low-level-laz-1.0-SNAPSHOT.jar
PREFIX=s3://spotable-geo-us-west-2/point-cloud/us

# 1. footprints (offline)
java -cp "$JAR" com.spotable.LazNameBounds --input point-cloud.csv --output laz_bounds.csv \
    --merged laz_merged.csv --prefix "$PREFIX"

# 2. + vertical CRS & density (reads headers from S3)
AWS_PROFILE=west java -cp "$JAR" com.spotable.LazMergedMeta \
    --merged laz_merged.csv --bounds laz_bounds.csv --output laz_merged_meta.csv \
    --prefix "$PREFIX" --sample 5
```

## Coverage

Step 1 decodes several USGS LPC naming families, each footprint reprojected to WGS84:

- **MGRS tile ids** — `..._17RNL070510.laz` (UTM via the MGRS lettering).
- **Florida State Plane LID grids** — `..._LID2019_447196_W.laz` and confirmed variants.
- **`eNNNNnNNNN` / `wNNNNnNNNN` grids** — `..._e0853n0913.laz` (CONUS Albers, EPSG 6350),
  `..._w314000n3291500.laz` (UTM), etc.
- **Florida State Plane names without the `LID` token** — packed column/row index
  (`..._038576_N.laz`, `..._471241.laz`) and absolute-feet corners (`..._692500_725000.laz`).

Every grid-indexed scheme was verified against the actual LAZ header bounds + CRS before
being enabled. A few families are deliberately **not** decoded because their names don't map
to a corner consistently (verified by reading headers): `GA_SW_Georgia_..._B17` (the
GAW/GAE field's scale differs between delivery blocks) and `FL_Suwannee_River_Lidar_2016`
(no single origin fits its index). EPT octree keys and other unconfirmed per-project schemes
are also skipped. The run prints the converted/skipped counts to stderr.

## Related tools (in the same jar)

- `com.spotable.LazBinaryReader` — read the true bounding box / CRS / point count from one or more
  LAS/LAZ files (local, directory, glob, or `s3://`), via ranged GETs. Used by step 2.
- `com.spotable.DtmNameBounds` / `com.spotable.DtmBounds` — the DTM (raster) counterparts.
