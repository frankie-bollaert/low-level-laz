# low-level-laz

Tools for deriving USGS LPC point-cloud (LAZ) tile footprints and metadata, mostly
**from the file name alone**, plus a second pass that reads a small sample of real
file headers from S3 to attach measured point density and the vertical CRS.

The pipeline turns a flat list of object paths into three CSVs (step 1 can also split the
per-tile rows into one CSV per project — see `--partitioned`):

- `meta/laz-bounds.csv` — one WGS84 footprint per tile (name-derived, no network).
- `meta/laz-merged.csv` — one row per sub-project: merged footprint + horizontal CRS (name-derived).
- `meta/laz-by-project.csv` — `meta/laz-merged.csv` enriched with the **vertical CRS** and measured
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
    --output meta/laz-bounds.csv \
    --merged meta/laz-merged.csv \
    --prefix s3://spotable-geo-us-west-2/point-cloud/us
```

| flag | required | meaning |
| --- | --- | --- |
| `--input` (`-i`) | yes | list of object paths, one per line |
| `--output` (`-o`) | no | per-tile CSV (default: stdout, unless `--partitioned` is given) |
| `--merged` | no | also write the per-project merged CSV to this path |
| `--partitioned` | no | also write the per-tile rows split by project, one `<dir>/<project>.csv` per project |
| `--tile` | no | force a fixed tile size in metres (default: inferred per project from the grid step) |
| `--prefix` | no | base path prepended to the `directory` column |

- `meta/laz-bounds.csv` columns: `project, filename, geometry`
- `meta/laz-merged.csv` columns: `project, directory, files, year, horizontal_epsg,
  horizontal_projection, geometry`
- `--prefix` is prepended to the `directory` column only (the footprints are computed from
  the names, so this step makes no network calls).

Names that can't be decoded to a footprint are skipped and counted on stderr (see
[Coverage](#coverage)).

### Step 2 — enrich with vertical CRS + point density (reads S3)

```sh
AWS_PROFILE=west java -cp "$JAR" com.spotable.LazByProject \
    --merged meta/laz-merged.csv \
    --bounds meta/laz-bounds.csv \
    --output meta/laz-by-project.csv \
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

`meta/laz-by-project.csv` columns: `project, directory, files, year, horizontal_epsg,
horizontal_projection, vertical_epsg, vertical_projection, point_density_per_m2,
avg_point_spacing_m, geometry` (geometry kept last).

`--prefix` is prepended to each sampled `filename` to form the S3 URI; it must point at the
same base used in step 1.

#### GeoJSON output

Both `LazByProject` and `TifByProject` can emit a GeoJSON `FeatureCollection` instead of CSV — the
rows are already WGS84, so each becomes a `Feature` (the `geometry` column is the geometry; the
other columns are typed `properties`, with numbers bare and an absent vertical CRS as `null`). Pass
`--format geojson`, or just give a `.geojson`/`.json` `--output`:

```sh
AWS_PROFILE=west java -cp "$JAR" com.spotable.LazByProject \
    --merged meta/laz-merged.csv --bounds meta/laz-bounds.csv \
    --output meta/laz-by-project.geojson --prefix "$PREFIX" --sample 5
```

To convert an **already-written** by-project CSV without re-reading S3, use `--convert`:

```sh
java -cp "$JAR" com.spotable.LazByProject --convert meta/laz-by-project.csv -o meta/laz-by-project.geojson
java -cp "$JAR" com.spotable.TifByProject --convert meta/tif-by-project.csv -o meta/tif-by-project.geojson
```

## Quick reference

```sh
mvn -q package -DskipTests
JAR=target/low-level-laz-1.0-SNAPSHOT.jar
PREFIX=s3://spotable-geo-us-west-2/point-cloud/us
mkdir -p meta

# 1. footprints (offline)
java -cp "$JAR" com.spotable.LazNameBounds --input point-cloud.csv --output meta/laz-bounds.csv \
    --merged meta/laz-merged.csv --prefix "$PREFIX"

# 2. + vertical CRS & density (reads headers from S3)
AWS_PROFILE=west java -cp "$JAR" com.spotable.LazByProject \
    --merged meta/laz-merged.csv --bounds meta/laz-bounds.csv --output meta/laz-by-project.csv \
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

## Raster (DTM) pipeline

The same two-step shape exists for the 1&nbsp;m DEM rasters, over a `dtm.txt` list of GeoTIFF paths:

```sh
# 1. footprints + per-project merge (offline; names encode the UTM tile)
java -cp "$JAR" com.spotable.TifNameBounds --input dtm.txt --output meta/tif-bounds.csv \
    --merged meta/tif-merged.csv --prefix s3://spotable-geo-us-west-2/dtm/us

# 2. enrich each project with vertical CRS + raster resolution (reads GeoTIFF headers from S3)
AWS_PROFILE=west java -cp "$JAR" com.spotable.TifByProject \
    --merged meta/tif-merged.csv --bounds meta/tif-bounds.csv --output meta/tif-by-project.csv \
    --prefix s3://spotable-geo-us-west-2/dtm/us --sample 5
```

`TifByProject` mirrors `LazByProject` but appends `vertical_epsg`, `vertical_projection`, and
`resolution_m` (median pixel size in metres) instead of the point-density columns — rasters have a
resolution, not a point count. (1&nbsp;m DEMs rarely declare a vertical CRS, so that column is
usually blank.) Flags match `LazByProject`: `--merged`, `--bounds`, `--output`/`-o`, `--prefix`,
`--sample`.

## Related tools (in the same jar)

- `com.spotable.LazBinaryReader` / `com.spotable.TifBinaryReader` — read the true bounding box, CRS,
  and (LAS/LAZ) point count or (GeoTIFF) pixel size / vertical CRS from one or more files (local,
  directory, glob, or `s3://`), via ranged GETs. Used by the step-2 tools.
