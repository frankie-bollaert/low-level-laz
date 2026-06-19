// dtmbounds — read the georeferenced bounding box and CRS from a (Geo)TIFF DTM,
// the raster counterpart to lazbounds. A Rust port of com.spotable.TifBinaryReader.
//
// Only the TIFF header, the first Image File Directory (IFD), and the handful of
// georeferencing tags it points at are read — never the pixel data. A TIFF's IFD
// may sit anywhere in the file (GDAL frequently writes it near the end) and the
// georef payloads live at their own offsets, so the `Source` abstraction reads
// arbitrary byte ranges. Classic TIFF (version 42) and BigTIFF (version 43) are
// both supported.
//
// The footprint comes from ModelPixelScaleTag+ModelTiepointTag (or, when present,
// ModelTransformationTag); the CRS from the GeoKeyDirectoryTag, falling back to an
// EPSG embedded in a GeoAsciiParams WKT citation. One CSV row per file is printed:
// "path","SRID=<epsg>;POLYGON(...)".
//
// Unlike the Java original this port is local-only (no S3) and depends only on std,
// mirroring the single-file lazbounds.c. Accepts any mix of files, directories
// (walked recursively for *.tif/*.tiff) and glob patterns.
//
// Build:  rustc -O -o dtmbounds dtmbounds.rs
// Test:   rustc --test -o dtmbounds_test dtmbounds.rs && ./dtmbounds_test
// Usage:  dtmbounds -i <file|dir|glob> [-i ...] [-o out.csv]

use std::collections::{BTreeMap, HashMap};
use std::error::Error;
use std::fs::File;
use std::io::{self, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Component, Path, PathBuf};

// ---- TIFF magic / structure ----
const TIFF_CLASSIC: u16 = 42;
const TIFF_BIG: u16 = 43;

// GeoTIFF tags needed to place the raster footprint and read its CRS.
const TAG_IMAGE_WIDTH: u16 = 256; // SHORT/LONG
const TAG_IMAGE_LENGTH: u16 = 257; // SHORT/LONG
const TAG_MODEL_PIXEL_SCALE: u16 = 33550; // 3 doubles
const TAG_MODEL_TIEPOINT: u16 = 33922; // 6 doubles per tiepoint
const TAG_MODEL_TRANSFORMATION: u16 = 34264; // 16 doubles (4x4)
const TAG_GEO_KEY_DIRECTORY: u16 = 34735; // shorts
const TAG_GEO_ASCII_PARAMS: u16 = 34737; // ASCII (citation/WKT)

// GeoKey ids holding an EPSG code directly (TIFFTagLocation == 0).
const KEY_PROJECTED_CS: u16 = 3072; // ProjectedCSTypeGeoKey
const KEY_GEOGRAPHIC_CS: u16 = 2048; // GeographicTypeGeoKey
const GEOKEY_UNDEFINED: u16 = 0;
const GEOKEY_USER_DEFINED: u16 = 32767;

// How far we'll read at a single offset (IFD window, and any tag payload).
const READ_WINDOW: usize = 64 * 1024;
const READ_CAP: u64 = 16 * 1024 * 1024;

// ---- Little/big-endian readers over a byte slice ----

fn rd_u16(b: &[u8], p: usize, le: bool) -> u16 {
    let a = [b[p], b[p + 1]];
    if le { u16::from_le_bytes(a) } else { u16::from_be_bytes(a) }
}
fn rd_u32(b: &[u8], p: usize, le: bool) -> u32 {
    let a = [b[p], b[p + 1], b[p + 2], b[p + 3]];
    if le { u32::from_le_bytes(a) } else { u32::from_be_bytes(a) }
}
fn rd_u64(b: &[u8], p: usize, le: bool) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[p..p + 8]);
    if le { u64::from_le_bytes(a) } else { u64::from_be_bytes(a) }
}
fn rd_f64(b: &[u8], p: usize, le: bool) -> f64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[p..p + 8]);
    if le { f64::from_le_bytes(a) } else { f64::from_be_bytes(a) }
}

// ---- The parsed result ----

pub struct DtmBounds {
    pub min_x: f64,
    pub max_x: f64,
    pub min_y: f64,
    pub max_y: f64,
    /// EPSG code of the horizontal CRS, or None if none could be determined.
    pub epsg: Option<i32>,
}

impl DtmBounds {
    /// Bounding box as EWKT: `SRID=<epsg>;POLYGON ((...))` when the CRS is known,
    /// else a bare `POLYGON ((...))`.
    pub fn to_wkt(&self) -> String {
        let polygon = format!(
            "POLYGON (({} {}, {} {}, {} {}, {} {}, {} {}))",
            fmt(self.min_x), fmt(self.min_y),
            fmt(self.max_x), fmt(self.min_y),
            fmt(self.max_x), fmt(self.max_y),
            fmt(self.min_x), fmt(self.max_y),
            fmt(self.min_x), fmt(self.min_y),
        );
        match self.epsg {
            Some(e) => format!("SRID={};{}", e, polygon),
            None => polygon,
        }
    }
}

// Compact, locale-independent number formatting (avoids 1,234.5 in some locales).
fn fmt(v: f64) -> String {
    if v.is_finite() && v == v.round() {
        format!("{}", v as i64)
    } else {
        format!("{}", v)
    }
}

// ---- Sources: anything that can yield an arbitrary byte range of a TIFF ----

/// A readable TIFF origin with a display label.
pub trait Source {
    fn label(&self) -> String;
    /// Reads up to `len` bytes starting at `offset` (fewer at EOF).
    fn read(&self, offset: u64, len: usize) -> io::Result<Vec<u8>>;
}

struct LocalSource {
    path: PathBuf,
}

impl Source for LocalSource {
    fn label(&self) -> String {
        self.path.to_string_lossy().into_owned()
    }
    fn read(&self, offset: u64, len: usize) -> io::Result<Vec<u8>> {
        let mut f = File::open(&self.path)?;
        f.seek(SeekFrom::Start(offset))?;
        let mut buf = vec![0u8; len];
        let mut filled = 0;
        while filled < len {
            let n = f.read(&mut buf[filled..])?;
            if n == 0 {
                break;
            }
            filled += n;
        }
        buf.truncate(filled);
        Ok(buf)
    }
}

// ---- Reading ----

/// Reads bounds and metadata from a (Geo)TIFF source.
pub fn read_source(source: &dyn Source) -> Result<DtmBounds, Box<dyn Error>> {
    let head = source.read(0, 16)?;
    if head.len() < 8 {
        return Err(format!("Too short to be a TIFF: {}", source.label()).into());
    }
    let le = byte_order(&head, &source.label())?;
    let version = rd_u16(&head, 2, le);

    let (big, ifd_offset) = if version == TIFF_CLASSIC {
        (false, rd_u32(&head, 4, le) as u64)
    } else if version == TIFF_BIG {
        if head.len() < 16 {
            return Err(format!("Truncated BigTIFF header: {}", source.label()).into());
        }
        if rd_u16(&head, 4, le) != 8 {
            return Err(format!("Unsupported BigTIFF offset size: {}", source.label()).into());
        }
        (true, rd_u64(&head, 8, le))
    } else {
        return Err(format!("Not a TIFF (bad version {}): {}", version, source.label()).into());
    };

    let tiff = Tiff::new(source, le, big, ifd_offset)?;

    let width = tiff.int_value(TAG_IMAGE_WIDTH, -1);
    let height = tiff.int_value(TAG_IMAGE_LENGTH, -1);
    if width <= 0 || height <= 0 {
        return Err(format!("Missing image dimensions: {}", source.label()).into());
    }

    let bb = footprint(&tiff, width as u64, height as u64, &source.label())?;

    // CRS from the GeoKey directory; fall back to an EPSG embedded in a WKT citation
    // (used by GeoTIFFs with a user-defined CRS).
    let (projected, geographic) = geo_key_epsgs(tiff.shorts(TAG_GEO_KEY_DIRECTORY));
    let mut epsg = if projected != 0 {
        Some(projected)
    } else if geographic != 0 {
        Some(geographic)
    } else {
        None
    };
    if epsg.is_none() {
        let citation = tiff.ascii(TAG_GEO_ASCII_PARAMS);
        if looks_like_wkt(&citation) {
            epsg = wkt_epsg(&citation);
        }
    }

    Ok(DtmBounds {
        min_x: bb[0],
        max_x: bb[1],
        min_y: bb[2],
        max_y: bb[3],
        epsg,
    })
}

fn byte_order(head: &[u8], label: &str) -> Result<bool, Box<dyn Error>> {
    if head[0] == b'I' && head[1] == b'I' {
        Ok(true)
    } else if head[0] == b'M' && head[1] == b'M' {
        Ok(false)
    } else {
        Err(format!("Not a TIFF (bad byte-order mark): {}", label).into())
    }
}

/// Computes `[minX, maxX, minY, maxY]` from the model transform tags. Prefers an
/// explicit ModelTransformationTag (handles rotation/skew by projecting all four
/// corners), else ModelPixelScaleTag+ModelTiepointTag.
fn footprint(tiff: &Tiff, width: u64, height: u64, label: &str) -> Result<[f64; 4], Box<dyn Error>> {
    if let Some(t) = tiff.doubles(TAG_MODEL_TRANSFORMATION) {
        if t.len() >= 16 {
            // X = t0*col + t1*row + t3 ; Y = t4*col + t5*row + t7
            let cols = [0.0, width as f64, 0.0, width as f64];
            let rows = [0.0, 0.0, height as f64, height as f64];
            let (mut min_x, mut max_x) = (f64::MAX, -f64::MAX);
            let (mut min_y, mut max_y) = (f64::MAX, -f64::MAX);
            for i in 0..4 {
                let x = t[0] * cols[i] + t[1] * rows[i] + t[3];
                let y = t[4] * cols[i] + t[5] * rows[i] + t[7];
                min_x = min_x.min(x);
                max_x = max_x.max(x);
                min_y = min_y.min(y);
                max_y = max_y.max(y);
            }
            return Ok([min_x, max_x, min_y, max_y]);
        }
    }

    let scale = tiff.doubles(TAG_MODEL_PIXEL_SCALE);
    let tie = tiff.doubles(TAG_MODEL_TIEPOINT);
    if let (Some(scale), Some(tie)) = (scale, tie) {
        if scale.len() >= 2 && tie.len() >= 6 {
            let (sx, sy) = (scale[0], scale[1]);
            let (i, j, x, y) = (tie[0], tie[1], tie[3], tie[4]);
            // Raster row increases downward, so model Y decreases with row.
            let origin_x = x - i * sx;
            let origin_y = y + j * sy;
            let min_x = origin_x;
            let max_x = origin_x + width as f64 * sx;
            let max_y = origin_y;
            let min_y = origin_y - height as f64 * sy;
            return Ok([min_x, max_x, min_y, max_y]);
        }
    }
    Err(format!("No georeferencing tags (pixel scale/tiepoint): {}", label).into())
}

// ---- A parsed TIFF IFD plus typed tag accessors ----

struct Entry {
    typ: u16,
    count: u64,
    value_field: Vec<u8>,
}

struct Tiff<'a> {
    source: &'a dyn Source,
    le: bool,
    big: bool,
    tags: HashMap<u16, Entry>,
}

impl<'a> Tiff<'a> {
    fn new(source: &'a dyn Source, le: bool, big: bool, ifd_offset: u64) -> io::Result<Tiff<'a>> {
        let mut tiff = Tiff {
            source,
            le,
            big,
            tags: HashMap::new(),
        };
        tiff.read_ifd(ifd_offset)?;
        Ok(tiff)
    }

    fn read_ifd(&mut self, ifd_offset: u64) -> io::Result<()> {
        let entry_size: usize = if self.big { 20 } else { 12 };
        let header_len: usize = if self.big { 8 } else { 2 };
        let next_len: u64 = if self.big { 8 } else { 4 };

        let mut w = self.source.read(ifd_offset, READ_WINDOW)?;
        if w.len() < header_len {
            return Ok(()); // nothing parseable; tags stay empty
        }
        let count = if self.big {
            rd_u64(&w, 0, self.le)
        } else {
            rd_u16(&w, 0, self.le) as u64
        };
        let needed = header_len as u64 + count * entry_size as u64 + next_len;
        if needed > w.len() as u64 && needed <= READ_CAP {
            w = self.source.read(ifd_offset, needed as usize)?;
        }
        let n = count.min(((w.len() - header_len) / entry_size) as u64);
        for k in 0..n {
            let pos = header_len + k as usize * entry_size;
            let tag = rd_u16(&w, pos, self.le);
            let typ = rd_u16(&w, pos + 2, self.le);
            let cnt = if self.big {
                rd_u64(&w, pos + 4, self.le)
            } else {
                rd_u32(&w, pos + 4, self.le) as u64
            };
            let (v_off, v_len) = if self.big { (pos + 12, 8) } else { (pos + 8, 4) };
            let value_field = w[v_off..v_off + v_len].to_vec();
            self.tags.insert(tag, Entry { typ, count: cnt, value_field });
        }
        Ok(())
    }

    /// Raw payload bytes of a tag (inline value, or fetched from its offset), or None.
    fn data(&self, tag: u16) -> io::Result<Option<Vec<u8>>> {
        let e = match self.tags.get(&tag) {
            Some(e) => e,
            None => return Ok(None),
        };
        let byte_len = e.count * type_size(e.typ) as u64;
        if byte_len == 0 {
            return Ok(Some(Vec::new()));
        }
        let inline_cap: u64 = if self.big { 8 } else { 4 };
        if byte_len <= inline_cap {
            return Ok(Some(e.value_field[..byte_len as usize].to_vec()));
        }
        let off = if self.big {
            rd_u64(&e.value_field, 0, self.le)
        } else {
            rd_u32(&e.value_field, 0, self.le) as u64
        };
        let d = self.source.read(off, byte_len.min(READ_CAP) as usize)?;
        Ok(Some(d))
    }

    fn doubles(&self, tag: u16) -> Option<Vec<f64>> {
        let d = self.data(tag).ok().flatten()?;
        if d.len() < 8 {
            return None;
        }
        let mut out = Vec::with_capacity(d.len() / 8);
        for i in 0..d.len() / 8 {
            out.push(rd_f64(&d, i * 8, self.le));
        }
        Some(out)
    }

    fn shorts(&self, tag: u16) -> Option<Vec<u16>> {
        let d = self.data(tag).ok().flatten()?;
        if d.len() < 2 {
            return None;
        }
        let mut out = Vec::with_capacity(d.len() / 2);
        for i in 0..d.len() / 2 {
            out.push(rd_u16(&d, i * 2, self.le));
        }
        Some(out)
    }

    /// First element of a SHORT/LONG/BYTE tag, or `def` if absent.
    fn int_value(&self, tag: u16, def: i64) -> i64 {
        let e = match self.tags.get(&tag) {
            Some(e) => e,
            None => return def,
        };
        let d = match self.data(tag).ok().flatten() {
            Some(d) => d,
            None => return def,
        };
        match e.typ {
            3 if d.len() >= 2 => rd_u16(&d, 0, self.le) as i64, // SHORT
            4 if d.len() >= 4 => rd_u32(&d, 0, self.le) as i64, // LONG
            16 if d.len() >= 8 => rd_u64(&d, 0, self.le) as i64, // LONG8 (BigTIFF)
            1 if !d.is_empty() => d[0] as i64,                  // BYTE
            _ => def,
        }
    }

    /// ASCII tag value, trimmed of NUL padding, or empty string if absent.
    fn ascii(&self, tag: u16) -> String {
        let d = match self.data(tag).ok().flatten() {
            Some(d) => d,
            None => return String::new(),
        };
        let mut len = d.len();
        while len > 0 && d[len - 1] == 0 {
            len -= 1;
        }
        String::from_utf8_lossy(&d[..len]).trim().to_string()
    }
}

fn type_size(typ: u16) -> u32 {
    match typ {
        1 | 2 | 6 | 7 => 1,            // BYTE, ASCII, SBYTE, UNDEFINED
        3 | 8 => 2,                    // SHORT, SSHORT
        4 | 9 | 11 => 4,               // LONG, SLONG, FLOAT
        5 | 10 | 12 | 16 | 17 | 18 => 8, // RATIONAL, SRATIONAL, DOUBLE, LONG8, SLONG8, IFD8
        _ => 0,
    }
}

// ---- CRS helpers ----

/// Parses a GeoKeyDirectory short array into `(projectedEpsg, geographicEpsg)` (0 if absent).
fn geo_key_epsgs(gk: Option<Vec<u16>>) -> (i32, i32) {
    let mut projected = 0;
    let mut geographic = 0;
    if let Some(gk) = gk {
        if gk.len() >= 4 {
            let num_keys = gk[3] as usize;
            for k in 0..num_keys {
                let base = 4 + k * 4;
                if base + 4 > gk.len() {
                    break;
                }
                let key_id = gk[base];
                let location = gk[base + 1];
                let value = gk[base + 3];
                if location != 0 || value == GEOKEY_UNDEFINED || value == GEOKEY_USER_DEFINED {
                    continue;
                }
                if key_id == KEY_PROJECTED_CS {
                    projected = value as i32;
                } else if key_id == KEY_GEOGRAPHIC_CS {
                    geographic = value as i32;
                }
            }
        }
    }
    (projected, geographic)
}

fn looks_like_wkt(s: &str) -> bool {
    let u = s.to_uppercase();
    u.contains("PROJCS") || u.contains("GEOGCS") || u.contains("COMPD_CS")
        || u.contains("PROJCRS") || u.contains("GEOGCRS") || u.contains("COMPOUNDCRS")
}

/// EPSG of the horizontal CRS in an OGC WKT string (projected, else geographic), or None.
fn wkt_epsg(wkt: &str) -> Option<i32> {
    let horizontal = first_bracketed_span(wkt, &["PROJCS", "PROJCRS"])
        .or_else(|| first_bracketed_span(wkt, &["GEOGCS", "GEOGCRS", "GEODCRS"]));
    horizontal
        .as_deref()
        .and_then(last_epsg)
        .or_else(|| last_epsg(wkt))
}

/// Last `...["EPSG","<n>"]` (or `...["EPSG",<n>]`) authority code in the string.
fn last_epsg(wkt: &str) -> Option<i32> {
    let up = wkt.to_uppercase();
    let ub = up.as_bytes();
    let needle = b"\"EPSG\"";
    let mut last = None;
    let mut i = 0;
    while i + needle.len() <= ub.len() {
        if &ub[i..i + needle.len()] == needle {
            let mut j = i + needle.len();
            while j < ub.len() && ub[j].is_ascii_whitespace() {
                j += 1;
            }
            if j < ub.len() && ub[j] == b',' {
                j += 1;
                while j < ub.len() && ub[j].is_ascii_whitespace() {
                    j += 1;
                }
                if j < ub.len() && ub[j] == b'"' {
                    j += 1;
                }
                let start = j;
                while j < ub.len() && ub[j].is_ascii_digit() {
                    j += 1;
                }
                if j > start {
                    if let Ok(v) = up[start..j].parse::<i32>() {
                        last = Some(v);
                    }
                }
            }
            i += needle.len();
        } else {
            i += 1;
        }
    }
    last
}

fn first_bracketed_span(wkt: &str, keywords: &[&str]) -> Option<String> {
    let upper = wkt.to_uppercase();
    let ub = upper.as_bytes();
    let wb = wkt.as_bytes();
    for kw in keywords {
        let kb = kw.as_bytes();
        let mut search = 0;
        while let Some(idx) = find(ub, kb, search) {
            let left_ok = idx == 0 || !ub[idx - 1].is_ascii_alphanumeric();
            let mut open = idx + kb.len();
            while open < ub.len() && ub[open].is_ascii_whitespace() {
                open += 1;
            }
            if left_ok && open < ub.len() && (ub[open] == b'[' || ub[open] == b'(') {
                return Some(balanced_span(wb, open));
            }
            search = idx + 1;
        }
    }
    None
}

fn balanced_span(s: &[u8], open: usize) -> String {
    let mut depth = 0i32;
    let mut in_string = false;
    let mut i = open;
    while i < s.len() {
        let c = s[i];
        if c == b'"' {
            in_string = !in_string;
        } else if !in_string {
            if c == b'[' || c == b'(' {
                depth += 1;
            } else if c == b']' || c == b')' {
                depth -= 1;
                if depth == 0 {
                    return String::from_utf8_lossy(&s[open..i + 1]).into_owned();
                }
            }
        }
        i += 1;
    }
    String::from_utf8_lossy(&s[open..]).into_owned()
}

fn find(haystack: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    if needle.is_empty() || from > haystack.len() {
        return None;
    }
    let end = haystack.len().checked_sub(needle.len())?;
    (from..=end).find(|&i| &haystack[i..i + needle.len()] == needle)
}

// ---- Output ----

/// One RFC 4180 CSV record: each field double-quoted, embedded quotes doubled.
fn csv_row(fields: &[&str]) -> String {
    let mut sb = String::new();
    for (i, f) in fields.iter().enumerate() {
        if i > 0 {
            sb.push(',');
        }
        sb.push('"');
        sb.push_str(&f.replace('"', "\"\""));
        sb.push('"');
    }
    sb
}

// ---- Source resolution (local files / directories / globs) ----

fn collect_sources(args: &[String]) -> Vec<Box<dyn Source>> {
    let mut sources: BTreeMap<String, Box<dyn Source>> = BTreeMap::new(); // sorted + de-duplicated
    for arg in args {
        if arg.starts_with("s3://") {
            eprintln!("S3 inputs are not supported by this port: {}", arg);
        } else {
            collect_local(arg, &mut sources);
        }
    }
    sources.into_values().collect()
}

fn collect_local(arg: &str, out: &mut BTreeMap<String, Box<dyn Source>>) {
    let p = Path::new(arg);
    if p.is_dir() {
        walk_local(p, &mut |path| is_tiff(&path.to_string_lossy()), out);
    } else if p.is_file() {
        out.insert(p.to_string_lossy().into_owned(), Box::new(LocalSource { path: p.to_path_buf() }));
    } else if has_glob(arg) {
        expand_local_glob(arg, out);
    } else {
        eprintln!("No such file or directory: {}", arg);
    }
}

fn walk_local(
    dir: &Path,
    matcher: &mut dyn FnMut(&Path) -> bool,
    out: &mut BTreeMap<String, Box<dyn Source>>,
) {
    let entries = match std::fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            walk_local(&path, matcher, out);
        } else if path.is_file() && matcher(&path) {
            out.insert(path.to_string_lossy().into_owned(), Box::new(LocalSource { path }));
        }
    }
}

fn expand_local_glob(pattern: &str, out: &mut BTreeMap<String, Box<dyn Source>>) {
    let abs = absolutize(pattern);
    let first_glob = match index_of_glob(&abs) {
        Some(i) => i,
        None => return,
    };
    let sep = abs[..first_glob].rfind(std::path::MAIN_SEPARATOR);
    let base = match sep {
        Some(0) | None => PathBuf::from(std::path::MAIN_SEPARATOR.to_string()),
        Some(s) => PathBuf::from(&abs[..s]),
    };
    if !base.is_dir() {
        eprintln!("No matches for glob: {}", pattern);
        return;
    }
    let abs_bytes = abs.into_bytes();
    walk_local(
        &base,
        &mut |path| glob_match(&abs_bytes, path.to_string_lossy().as_bytes()),
        out,
    );
}

fn absolutize(p: &str) -> String {
    let path = Path::new(p);
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    };
    let mut out = PathBuf::new();
    for comp in abs.components() {
        match comp {
            Component::ParentDir => {
                out.pop();
            }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out.to_string_lossy().into_owned()
}

/// Glob match over path strings: `**` spans separators, `*`/`?` do not,
/// `[...]` is a (optionally negated, range-aware) character class.
fn glob_match(p: &[u8], t: &[u8]) -> bool {
    if p.is_empty() {
        return t.is_empty();
    }
    match p[0] {
        b'*' => {
            let double = p.len() >= 2 && p[1] == b'*';
            let rest = if double { &p[2..] } else { &p[1..] };
            if glob_match(rest, t) {
                return true;
            }
            let mut k = 0;
            while k < t.len() {
                if !double && t[k] == b'/' {
                    break;
                }
                k += 1;
                if glob_match(rest, &t[k..]) {
                    return true;
                }
            }
            false
        }
        b'?' => !t.is_empty() && t[0] != b'/' && glob_match(&p[1..], &t[1..]),
        b'[' => match class_match(p, t) {
            Some((pn, matched)) => matched && glob_match(&p[pn..], &t[1..]),
            None => !t.is_empty() && t[0] == b'[' && glob_match(&p[1..], &t[1..]),
        },
        c => !t.is_empty() && t[0] == c && glob_match(&p[1..], &t[1..]),
    }
}

/// Parses a `[...]` class at the start of `p`. Returns `(bytes_consumed, matches_t0)`
/// or None if the class is malformed (caller treats `[` literally).
fn class_match(p: &[u8], t: &[u8]) -> Option<(usize, bool)> {
    let mut i = 1;
    let negate = i < p.len() && (p[i] == b'!' || p[i] == b'^');
    if negate {
        i += 1;
    }
    let ch = t.first().copied();
    let mut matched = false;
    let start = i;
    while i < p.len() && (p[i] != b']' || i == start) {
        if i + 2 < p.len() && p[i + 1] == b'-' && p[i + 2] != b']' {
            if let Some(c) = ch {
                if c >= p[i] && c <= p[i + 2] {
                    matched = true;
                }
            }
            i += 3;
        } else {
            if Some(p[i]) == ch {
                matched = true;
            }
            i += 1;
        }
    }
    if i >= p.len() {
        return None; // unterminated class
    }
    let matched = ch.map_or(false, |c| (matched != negate) && c != b'/');
    Some((i + 1, matched))
}

fn is_tiff(name: &str) -> bool {
    let lower = name.to_lowercase();
    lower.ends_with(".tif") || lower.ends_with(".tiff")
}

fn has_glob(s: &str) -> bool {
    index_of_glob(s).is_some()
}

fn index_of_glob(s: &str) -> Option<usize> {
    s.bytes().position(|c| matches!(c, b'*' | b'?' | b'[' | b'{'))
}

// ---- CLI ----

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut out_file: Option<String> = None;
    let mut inputs: Vec<String> = Vec::new();

    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a == "-o" || a == "--out" {
            i += 1;
            if i >= args.len() {
                eprintln!("{} requires a file path", a);
                std::process::exit(2);
            }
            out_file = Some(args[i].clone());
        } else if a == "-i" || a == "--input" {
            i += 1;
            if i >= args.len() {
                eprintln!("{} requires a file/dir/glob", a);
                std::process::exit(2);
            }
            inputs.push(args[i].clone());
        } else {
            eprintln!("Unknown argument: {}", a);
            std::process::exit(2);
        }
        i += 1;
    }

    if inputs.is_empty() {
        eprintln!("Usage: dtmbounds -i <file|dir|glob> [-i ...] [-o out.csv]");
        eprintln!("  -i/--input  (repeatable) source to read:");
        eprintln!("    a .tif/.tiff file, a directory (recursive *.tif/*.tiff),");
        eprintln!("    or a glob such as 'dem/**/*.tif'.");
        eprintln!("  -o/--out    also write the CSV rows to this file.");
        eprintln!("  Prints one CSV row per file to stdout: \"path\",\"wkt\".");
        return;
    }

    let sources = collect_sources(&inputs);
    if sources.is_empty() {
        eprintln!("No TIFF files matched.");
        return;
    }

    let mut writer = match &out_file {
        Some(p) => match File::create(p) {
            Ok(f) => Some(BufWriter::new(f)),
            Err(e) => {
                eprintln!("Cannot open {}: {}", p, e);
                std::process::exit(1);
            }
        },
        None => None,
    };

    let mut failures = 0;
    for source in &sources {
        match read_source(source.as_ref()) {
            Ok(d) => {
                let row = csv_row(&[&source.label(), &d.to_wkt()]);
                println!("{}", row);
                if let Some(w) = writer.as_mut() {
                    let _ = writeln!(w, "{}", row);
                }
            }
            Err(e) => {
                eprintln!("Skipping {}: {}", source.label(), e);
                failures += 1;
            }
        }
    }

    if let Some(w) = writer.as_mut() {
        let _ = w.flush();
    }
    if failures > 0 {
        std::process::exit(1);
    }
}

// ---- Tests ----

#[cfg(test)]
mod tests {
    use super::*;

    /// In-memory Source over a byte buffer, mirroring the Java test harness.
    struct MemSource(Vec<u8>);
    impl Source for MemSource {
        fn label(&self) -> String {
            "test.tif".to_string()
        }
        fn read(&self, offset: u64, len: usize) -> io::Result<Vec<u8>> {
            let f = &self.0;
            if offset as usize >= f.len() {
                return Ok(Vec::new());
            }
            let from = offset as usize;
            let to = (from + len).min(f.len());
            Ok(f[from..to].to_vec())
        }
    }

    fn put_u16(b: &mut [u8], p: usize, v: u16) {
        b[p..p + 2].copy_from_slice(&v.to_le_bytes());
    }
    fn put_u32(b: &mut [u8], p: usize, v: u32) {
        b[p..p + 4].copy_from_slice(&v.to_le_bytes());
    }
    fn put_f64(b: &mut [u8], p: usize, v: f64) {
        b[p..p + 8].copy_from_slice(&v.to_le_bytes());
    }

    fn entry(b: &mut [u8], pos: usize, tag: u16, typ: u16, count: u32, value_or_offset: u32) -> usize {
        put_u16(b, pos, tag);
        put_u16(b, pos + 2, typ);
        put_u32(b, pos + 4, count);
        put_u32(b, pos + 8, value_or_offset);
        pos + 12
    }

    // "-99\0" packed little-endian into the 4-byte inline value field.
    fn pack_ascii() -> u32 {
        let s = b"-99\0";
        (s[0] as u32) | ((s[1] as u32) << 8) | ((s[2] as u32) << 16) | ((s[3] as u32) << 24)
    }

    /// Layout: 8-byte header, IFD at offset 8 (8 entries), then out-of-line payloads
    /// for pixel scale, tiepoint and the GeoKey directory.
    fn sample_geotiff() -> Vec<u8> {
        let width: u32 = 2000;
        let height: u32 = 1500;
        let sx = 2.5;
        let sy = 2.5;
        let origin_x = 2015000.0;
        let origin_y = 358703.62;

        let n_entries = 8usize;
        let ifd_offset = 8usize;
        let ifd_size = 2 + n_entries * 12 + 4; // count + entries + next-offset
        let data_start = ifd_offset + ifd_size;

        let scale_off = data_start; // 3 doubles = 24 bytes
        let tie_off = scale_off + 24; // 6 doubles = 48 bytes
        let geo_key_off = tie_off + 48; // 16 shorts = 32 bytes
        let total = geo_key_off + 32;

        let mut f = vec![0u8; total];
        f[0] = b'I';
        f[1] = b'I';
        put_u16(&mut f, 2, 42);
        put_u32(&mut f, 4, ifd_offset as u32);

        put_u16(&mut f, ifd_offset, n_entries as u16);
        let mut e = ifd_offset + 2;
        e = entry(&mut f, e, 256, 4, 1, width); // ImageWidth (LONG, inline)
        e = entry(&mut f, e, 257, 4, 1, height); // ImageLength (LONG, inline)
        e = entry(&mut f, e, 258, 3, 1, 32); // BitsPerSample (SHORT, inline)
        e = entry(&mut f, e, 339, 3, 1, 3); // SampleFormat = float (SHORT, inline)
        e = entry(&mut f, e, 33550, 12, 3, scale_off as u32); // ModelPixelScale (3 doubles, offset)
        e = entry(&mut f, e, 33922, 12, 6, tie_off as u32); // ModelTiepoint (6 doubles, offset)
        e = entry(&mut f, e, 34735, 3, 16, geo_key_off as u32); // GeoKeyDirectory (16 shorts, offset)
        e = entry(&mut f, e, 42113, 2, 4, pack_ascii()); // GDAL_NODATA "-99\0" (4 bytes, inline)
        put_u32(&mut f, e, 0); // next IFD = none

        put_f64(&mut f, scale_off, sx);
        put_f64(&mut f, scale_off + 8, sy);
        put_f64(&mut f, scale_off + 16, 0.0);

        put_f64(&mut f, tie_off, 0.0); // i
        put_f64(&mut f, tie_off + 8, 0.0); // j
        put_f64(&mut f, tie_off + 16, 0.0); // k
        put_f64(&mut f, tie_off + 24, origin_x); // x
        put_f64(&mut f, tie_off + 32, origin_y); // y
        put_f64(&mut f, tie_off + 40, 0.0); // z

        let gk: [u16; 16] = [
            1, 1, 0, 3, // version, revision, minor, numKeys
            1024, 0, 1, 1, // GTModelType = projected
            1025, 0, 1, 1, // GTRasterType
            3072, 0, 1, 6441, // ProjectedCSType = EPSG:6441
        ];
        for (i, v) in gk.iter().enumerate() {
            put_u16(&mut f, geo_key_off + i * 2, *v);
        }
        f
    }

    fn read_bytes(file: Vec<u8>) -> DtmBounds {
        read_source(&MemSource(file)).expect("parse")
    }

    #[test]
    fn parses_bounds_and_crs() {
        let d = read_bytes(sample_geotiff());
        assert!((d.min_x - 2015000.0).abs() < 1e-6);
        assert!((d.max_x - (2015000.0 + 2000.0 * 2.5)).abs() < 1e-6);
        assert!((d.max_y - 358703.62).abs() < 1e-6);
        assert!((d.min_y - (358703.62 - 1500.0 * 2.5)).abs() < 1e-6);
        assert_eq!(d.epsg, Some(6441));
    }

    /// Output mirrors LazBinaryReader: a bare bbox WKT carrying the SRID.
    #[test]
    fn emits_ewkt_geometry() {
        let d = read_bytes(sample_geotiff());
        assert_eq!(
            d.to_wkt(),
            "SRID=6441;POLYGON ((2015000 354953.62, 2020000 354953.62, \
             2020000 358703.62, 2015000 358703.62, 2015000 354953.62))"
        );
    }

    #[test]
    fn glob_basics() {
        assert!(glob_match(b"/a/*/c.tif", b"/a/b/c.tif"));
        assert!(!glob_match(b"/a/*/c.tif", b"/a/b/x/c.tif"));
        assert!(glob_match(b"/a/**/c.tif", b"/a/b/x/c.tif"));
        assert!(glob_match(b"/a/tile?.tif", b"/a/tile3.tif"));
        assert!(glob_match(b"/a/[0-9].tif", b"/a/7.tif"));
        assert!(!glob_match(b"/a/[!0-9].tif", b"/a/7.tif"));
    }
}
