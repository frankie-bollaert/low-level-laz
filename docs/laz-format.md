# Understanding LAZ: a developer's guide to this codec

This document explains how the `com.spotable.laz` package works, and — more usefully —
*why* the LAS/LAZ point-cloud format is built the way it is. It is aimed at a developer
who is comfortable with bytes, bit-shifts and a debugger, but who has never touched
LiDAR data or entropy coding before.

The goal is that after reading this you can open any file in the package and know what
it is doing and where it sits in the bigger picture. Everything here is a faithful
port of **laz-perf** / **LASzip** (the reference C++ implementations), so the concepts
transfer directly to those projects too.

---

## 1. The 30,000-foot view

LiDAR scanners produce **point clouds**: huge lists of `(x, y, z)` measurements, often
with extra attributes per point (intensity, GPS timestamp, classification, …). A single
USGS tile can hold tens of millions of points. Stored naively that is gigabytes of
near-random-looking numbers.

Three formats are layered on top of each other:

| Format | What it is | In this code |
|--------|-----------|--------------|
| **LAS** | The uncompressed binary container. A header + a flat array of fixed-size point records. | `LasHeader`, `Point14` |
| **LAZ** | LAS, but the point records are *compressed* (LASzip). Same header, same logical data. | the whole codec |
| **COPC** | A LAZ file whose points are *reorganized into an octree* so a viewer can stream a region or a zoom-level without reading the whole file. | `CopcWriter`, `LazToCopc` |

The pipeline this package implements is **LAZ/LAS in → COPC out** (`LazToCopc`). To do
that it must:

1. **Decode** LAZ point records back to raw bytes (`Point14Decompressor` and the codec under it).
2. **Reorganize** the points into an octree (`CopcWriter`).
3. **Re-encode** each octree node as a LAZ chunk (`Point14Compressor`) and assemble a file (`LazWriter`).

So the package contains a *complete, byte-exact* LAZ encoder **and** decoder for the
modern point format, plus a COPC builder. Let's build up the concepts from the bottom.

---

## 2. What a point actually is — `Point14`

LAS defines several "Point Data Record Formats" (PDRFs). This codec handles **PDRF 6**
(and the 30-byte core shared by 7 and 8), the modern format introduced in LAS 1.4.
`Point14.java` is the in-memory model. One record is **30 bytes**:

| Bytes | Field | Notes |
|-------|-------|-------|
| 0–11  | `x, y, z` | three 32-bit **signed integers** |
| 12–13 | `intensity` | u16 |
| 14    | `returns` | two 4-bit fields packed in a byte: return number + number of returns |
| 15    | `flags` | classification flags, scanner channel, scan direction, edge-of-flight |
| 16    | `classification` | ground / building / vegetation / … |
| 17    | `userData` | u8 |
| 18–19 | `scanAngle` | i16 |
| 20–21 | `pointSourceId` | u16 |
| 22–29 | `gpstime` | 64-bit double |

Two things are worth pausing on, because they drive the whole compression design.

### 2.1 Coordinates are integers, not floats

`x`, `y`, `z` are **integers**. The real-world coordinate is reconstructed with a
per-file affine transform stored in the header:

```
realX = x * scaleX + offsetX
```

(See `CopcWriter.build`, lines computing `rx/ry/rz`.) A typical scale is `0.01`, i.e.
points are stored to the centimetre. Storing scaled integers instead of doubles is the
*first* compression trick the format plays: it throws away precision you do not need and
makes the values small and **predictable**, which is exactly what the entropy coder wants.

### 2.2 GPS time is kept as raw bits, not a `double`

Look at the field declaration:

```java
long gpstime;   // raw IEEE-754 bits of the GPS time double
```

The codec treats the timestamp as a 64-bit *integer bit-pattern*, not a floating-point
number. Why? Because compression does integer arithmetic on it (deltas, multipliers),
and we need the bytes to round-trip **exactly**. If we round-tripped through `double` we
could perturb a bit and produce a file that differs from the reference encoder. The only
place it is interpreted as a real number is when deciding "did the time change?"
(`Double.longBitsToDouble(...)` in the compressor) — because `+0.0` and `-0.0` are
different bit-patterns but the *same* time.

`pack`/`unpack` move the struct to and from the little-endian on-disk layout. Nothing
clever — just careful byte twiddling.

---

## 3. The core idea: predictive + entropy coding

LAZ compression rests on two ideas that combine multiplicatively.

### 3.1 Prediction (modelling)

Consecutive points in a LiDAR scan are *close together* — the scanner sweeps a beam, so
point N+1 is usually a few centimetres from point N. So instead of storing `x` we store
the **correction** relative to a prediction:

```
corrector = realValue - predictedValue
```

If the prediction is good, the corrector is a small number clustered around zero. We
have turned a stream of large, high-entropy integers into a stream of small ones. This
is **delta coding**, generalized: the "predictor" can be the previous value, a running
median of recent deltas, the value from the same return-level, etc. Choosing good
predictors per field is most of the art in `Point14Compressor`.

### 3.2 Entropy coding (arithmetic coding)

Prediction makes the values *small and skewed* (lots of zeros, few large values). An
**entropy coder** then spends few bits on common values and more bits on rare ones,
approaching the information-theoretic limit. LAZ uses **arithmetic coding**, which —
unlike Huffman — can use *fractional* bits per symbol and adapt its probabilities as it
goes.

These two stages are implemented by two layers of classes:

```
Point14Compressor / Decompressor      ← prediction + per-field context selection
        │  feeds correctors to
        ▼
IntegerCompressor / Decompressor      ← turns an int corrector into symbols + raw bits
        │  emits symbols/bits via
        ▼
ArithmeticEncoder / Decoder           ← the entropy coder itself
        │  drives
        ▼
ArithmeticModel / ArithmeticBitModel  ← adaptive probability tables
```

We'll go bottom-up.

---

## 4. The arithmetic coder

Files: `Ac.java`, `ArithmeticEncoder.java`, `ArithmeticDecoder.java`,
`ArithmeticModel.java`, `ArithmeticBitModel.java`.

This is the variant from **Amir Said's "Fast Arithmetic Coding"** paper, the same one
LASzip uses. You do not need to derive it from scratch, but here is the working mental
model.

### 4.1 How arithmetic coding works, briefly

Imagine the interval `[0, 1)`. Each symbol you encode narrows the interval to a
sub-range proportional to the symbol's probability. After encoding the whole message you
have a tiny interval; any number inside it identifies the message uniquely, and you emit
just enough bits to pin down such a number. Common symbols (big probability) shrink the
interval little → few bits; rare symbols shrink it a lot → many bits.

In practice you cannot use infinite-precision reals, so the coder keeps the interval as
two 32-bit integers and **renormalizes**: whenever the interval gets too narrow it shifts
the top byte out (to the output on encode, in from the input on decode) and scales back
up, keeping ≥ 24 bits of working precision. That is the `renorm()` you see in both
classes, gated on `AC__MinLength = 2^24` (`Ac.java`).

The encoder tracks `base` (the low end of the interval) and `length` (its width); the
decoder tracks `value` (the bits it has read) and `length`. Because adding to `base` can
overflow past 32 bits, the encoder has to **propagate a carry** back into bytes it has
already written — that's `propagateCarry()`. The comment in `ArithmeticEncoder`
explains the one deliberate divergence from laz-perf: it keeps the whole output buffer
rather than a circular double-buffer, which makes carries trivial to reason about and is
cheap because each chunk is small. The emitted bytes are identical.

> **Key invariant:** the encoder and decoder are mirror images running the *same model
> updates in the same order*. If they ever disagree about a probability, the stream
> desyncs and everything after is garbage. This is why so much of the code is about
> keeping model state in lock-step — see the "valid layer" discussion in §7.

All arithmetic is logically **unsigned 32-bit**, but Java has no unsigned `int`. The
code uses `Integer.compareUnsigned`, `Integer.divideUnsigned` and `>>>` everywhere it
matters. Get one of those wrong and you get subtle, data-dependent corruption.

### 4.2 Two kinds of model

The coder needs to know the probability of each symbol. Those live in *models*, and
they are **adaptive**: they start uniform and re-estimate themselves from the symbols
actually seen, so the coder tunes itself to this particular file with no side
information.

- **`ArithmeticBitModel`** — a single binary probability (probability that the next bit
  is 0). Used for yes/no decisions.
- **`ArithmeticModel`** — a distribution over `N` symbols (`N` from 2 up to 2048), stored
  as a cumulative distribution scaled to 15 bits.

Both share the same adaptation strategy (`update()`):

1. Keep integer *counts* of how often each symbol occurred.
2. Periodically rebuild the scaled probabilities from the counts.
3. When the counts grow past a cap, **halve them all**. This is the clever bit: halving
   keeps the *ratios* but lets recent symbols outweigh ancient ones, so the model tracks
   local statistics (a scan moving from ground to treetops) instead of averaging over the
   whole file.
4. Make updates progressively rarer (`updateCycle` grows). Early on the model is
   ignorant and wants to adapt fast; later it is well-estimated and re-estimating every
   symbol would just cost time.

The multi-symbol decoder has one extra wrinkle: to turn a coded value back into a symbol
it must find which cumulative bucket the value falls in. For small alphabets it does a
plain **bisection search** (`decodeSymbol`, the `else` branch). For larger alphabets it
builds a **`decoderTable`** — a coarse lookup that jumps you near the right bucket so the
bisection only has a couple of steps left. That is purely a speed optimization; the
encoder never needs it (it knows the symbol already).

### 4.3 Raw bits

Not everything is worth modelling. When a value's low bits are essentially uniform
random (e.g. the bottom bits of a large coordinate corrector), modelling them would
waste effort for no gain. So the coder also offers `writeBits`/`readBits`,
`writeByte`/`readByte`, etc., which push bits through the same interval machinery but
with a *flat* probability. You'll see the integer compressor split a value into
"modelled high bits + raw low bits" for exactly this reason.

---

## 5. From integers to symbols — `IntegerCompressor` / `IntegerDecompressor`

The arithmetic coder speaks "symbols". The point codec speaks "here is an integer
corrector, please store it". `IntegerCompressor` bridges the two. This is laz-perf's
`compressors::integer`, and it is used for almost every numeric field.

Given a corrector `c = real - pred`, it does the following (`writeCorrector`):

1. **Fold into range.** If the field is, say, 16-bit, a corrector can wrap; folding by
   `corrRange` keeps it in `[corrMin, corrMax]` so it always fits. (For 32-bit fields
   there is no folding.)

2. **Find `k`, the number of significant bits** of the corrector's magnitude. A
   corrector near zero has small `k`; a big jump has large `k`. Crucially, `k` is itself
   **entropy-coded** with a model (`mBits[context]`). Small `k` is common, so it costs
   almost nothing.

3. **Code the value within its `k`-bit bucket:**
   - `k == 0` → the value is 0 or 1: one modelled bit (`mCorrector0`).
   - small `k` (≤ 8) → the whole value goes through a per-`k` symbol model
     (`mCorrector[k-1]`). Different magnitudes have different shapes, so each `k` gets its
     own model.
   - large `k` → the **top 8 bits** go through a model and the remaining low bits are
     written **raw** (`writeBits`). The top bits carry structure worth modelling; the low
     bits are noise.

The `if (c < 0) … else …` lines map the signed corrector onto a contiguous non-negative
range so the bucket arithmetic is clean; the decompressor undoes exactly that mapping
(`readCorrector`, the `c >= (1 << (k-1))` branch).

### 5.1 Contexts

Notice `compress(enc, pred, real, context)` takes a **context** index, and the
constructor takes a `contexts` count. A context is just *which model to use*. The
encoder picks the context from something it already knows (and the decoder can recompute
identically). This is **context modelling**, and it is where a lot of LAZ's ratio comes
from: by splitting one stream of correctors into several models keyed on a correlated
signal, each model sees a more predictable sub-stream.

Concrete example from `Point14Compressor`: the Y corrector's context is derived from how
many bits the *X* corrector needed (`kbits = Math.min(c.dx.k, 20) & ~1`). Points with a
big X jump tend to have a big Y jump too, so conditioning Y's model on X's magnitude
makes Y cheaper. You'll see this pattern — feed one field's outcome into the next field's
context — all over the point codec.

The `k` left over after `compress`/`decompress` is stored on the object (`int k`) so the
caller can read it back for exactly this purpose.

---

## 6. Predictors — `StreamingMedian` and the per-field state

The other half of prediction is *choosing a good predictor*. Most fields just predict
"same as the previous point" (so the corrector is a simple delta). X and Y are smarter.

`StreamingMedian` maintains the **median of the last 5 deltas** in O(1) per insert
(it is a tiny hand-unrolled insertion structure — `values[2]` is always the median).
Instead of predicting "next dx = previous dx", X predicts "next dx = median of recent
dx", which is far more robust to the occasional outlier point. The decoder runs the same
median over the deltas it reconstructs, so both sides stay synchronized.

There are **12 medians** for X and 12 for Y per channel, indexed by a *context*
(`rmCtx`) computed from the return structure and whether GPS time changed. Different
parts of a multi-return pulse behave differently, so they get independent predictors.

---

## 7. The point codec — `Point14Compressor` / `Point14Decompressor`

This is where it all comes together, and it is the most intricate file in the package.
Read it with the encoder and decoder side by side: every `encodeSymbol` in one has a
matching `decodeSymbol` in the other, in the same order.

### 7.1 Layered sub-streams ("v3")

LASzip version 3 (the "layered" or "chunked" format, compressor id 3) splits each
**field into its own arithmetic stream**:

```
xy, z, classification, flags, intensity, scan-angle, user-data, point-source-id, gps-time
```

(nine of them; see the two `ArithmeticEncoder` blocks in `Point14Compressor`). Why
split? Two reasons:

1. **Selective decoding.** A reader that only wants X/Y/Z can decode just those layers
   and skip the rest — important for COPC viewers.
2. **Drop unchanged fields.** If *no point in the chunk* ever changed its classification,
   the whole classification layer can be omitted. That is the `valid` flag on
   `ArithmeticEncoder` and the `makeValid()` calls: a layer is written into on *every*
   point (to keep its model in lock-step), but if it was never "made valid" its bytes are
   thrown away and its size in the framing table is **zero**. On decode, a zero-size layer
   means "this field never changed; carry the value over from the previous point" — which
   is why `Point14Decompressor` guards each field with `if (classDec != null)` etc.

This is the subtle invariant from §4.1 made concrete: even a field you are going to
discard must be *encoded* symbol-for-symbol, because the act of encoding advances the
adaptive model, and the decoder's model must advance identically. You cannot simply skip
it.

### 7.2 Chunk layout

A chunk is one independently-decodable unit. Its on-disk layout (documented in the
`Point14Decompressor` header comment) is:

```
[raw first point: 30 bytes (+ extra bytes)]   ← stored verbatim, primes the predictors
[u32 point count]
[9 × u32 layer byte-sizes]  (+ one per extra byte)
[layer bytes, in order, zero-size layers omitted]
```

The **first point is stored raw**. It has no predecessor to predict from, so it seeds the
"last point" state for every channel. Every subsequent point is encoded as deltas/contexts
against that evolving state. `compress()` handles the first point specially
(`lastChannel == -1`), then deltas; `finish()` lays out the framing above; the decoder's
`readFraming()` reads it back and carves each layer into its own `ArithmeticDecoder`.

### 7.3 The "changed" bitmask

For each non-first point the encoder writes a single symbol — `changed` — into the XY
layer: a 7-bit mask saying which fields differ from the previous point and how the return
numbers moved (incremented / decremented / jumped). The decoder reads it first and uses
it to decide which fields to decode at all. This is a compact way to skip per-point what
the layer-dropping skips per-chunk. The bit assignments must match exactly between
encoder and decoder — compare the `changed = …` construction in the compressor with the
`(changed >> 6) & 1` unpacking in the decompressor.

### 7.4 Scanner channels and "last point" state

PDRF 6 has up to **4 scanner channels** (multi-beam sensors). Points from different
channels are interleaved but should be predicted from *their own* channel's history. So
all the per-point state — last point, last Z per return-level, medians, GPS-time
bookkeeping — lives in a `ChannelCtx`, and there are four of them. The `changed` mask's
top bit signals a channel switch; the decoder updates `lastChannel` accordingly. A
channel seen for the first time inherits the previous channel's last point so it has
something to predict from.

### 7.5 Per-field walk (the order is the wire format)

For each point, both sides process fields in this fixed order. Skim it once so the file
reads as a checklist rather than a wall:

1. **Return numbers** (`n` = number of returns, `r` = this return). Coded as
   increment/decrement/jump against the previous point, because a single laser pulse
   produces returns 1,2,3,… in sequence.
2. **X**, then **Y** — median predictor + `IntegerCompressor`, Y conditioned on X's `k`
   (§5.1).
3. **Z** — predicted from the last Z *at the same return level* (`lastZ[zctx]`), because
   the first and last returns of a pulse are at very different heights. Context blends
   X's and Y's bit-counts.
4. **Classification, flags, intensity, scan angle, user data, point source id** — each
   with its own model and a context derived from already-decoded fields. Each can be
   layer-dropped if constant.
5. **GPS time** — the most complex field, below.

### 7.6 GPS time: the multi-coder

`encodeGpsTime` / `decodeGpsTime` are the gnarliest code in the package. The problem:
timestamps in a scan increase at a roughly constant rate, but the file may *interleave*
points from several pulses/flightlines whose clocks differ, so a plain delta is poor.

The solution LASzip uses keeps the **last 4 timestamps and their last deltas** (a tiny
4-slot ring, `lastGpstime[]` / `lastGpstimeDiff[]`). For each new timestamp it asks: is
the gap a small integer **multiple** of the recent delta? If the rate is steady the
multiple is 1 and costs almost nothing; small multiples (skipped points) are cheap
symbols; and if nothing fits — a real discontinuity — it falls back to coding the raw
32-bit halves. The `multiExtremeCounter` logic resets the reference delta after a few
outliers so the model re-locks onto a new steady rate. The `findSeq` helper picks which
of the 4 slots the new time belongs to.

You do not need to memorize this. The thing to understand is *why* it is shaped this way:
it is a small state machine tuned to "mostly steady clock, occasionally jumps", encoded
so the steady case is nearly free. Encoder and decoder run the identical state machine.

---

## 8. The file container — `LasHeader` and `LazWriter`

### 8.1 Reading: `LasHeader`

`LasHeader.parse` reads just enough of the 375-byte public header to drive the codec:
version, point format and record length, point count, the scale/offset transform, and —
for a compressed file — the **LASzip VLR** (a "Variable Length Record", id 22204) which
carries the compressor version and chunk size. Note the LAS 1.4 detail: for PDRF > 5 the
legacy 32-bit point count is zero and the real count is an *extended* 64-bit field at
offset 247.

### 8.2 Chunks and the chunk table

A LAZ file's point data is a sequence of chunks preceded by an 8-byte pointer to a
**chunk table** at the end. The table lets a reader find and decode any chunk without
walking the file. Two flavours:

- **Fixed-size chunks** (plain LAZ): every chunk has the same point count (from the VLR),
  so the table stores only each chunk's *byte size*.
- **Variable-size chunks** (COPC needs these — one chunk per octree node, so counts
  differ): the table stores a *point count and* a byte size per chunk.

The table itself is **arithmetic-coded** (deltas between successive sizes/counts), via the
same `IntegerCompressor` — see `LazWriter.buildChunkTable` and the mirror `ChunkTable.read`
in `LazToCopc`. Reading the table up front is what lets `LazToCopc.decodeRecords` decode
each chunk independently from its byte offset, rather than parsing inline framing.

### 8.3 Writing: `LazWriter`

`LazWriter.write` assembles the output file: it copies the source header as a *template*
(so scale/offset, bounding box, GUID survive untouched) and patches only the
layout-dependent fields — point-data offset, VLR/EVLR counts, the extended point count,
and the compression bit (`header[104] |= 0x80`). Then it writes header → VLRs → the
chunk-pointer + chunks → chunk table → EVLRs. `laszipVlrDataPdrf6` hand-builds the
40-byte LASzip VLR that tells readers "this is layered-chunked (v3) PDRF 6 data".

---

## 9. COPC: reorganizing into an octree — `CopcWriter`

A plain LAZ file must be read start-to-finish. **COPC** (Cloud Optimized Point Cloud) is
a LAZ file whose chunks are arranged as an **octree** so a viewer can:

- fetch a coarse overview without reading everything, and
- fetch only the nodes overlapping the region/zoom it is displaying.

### 9.1 The octree and voxel acceptance

Start with one cube (the *root*) enclosing all points. `CopcWriter` uses **Entwine-style
voxel acceptance** (`build`, pass 2):

- Each node has a `SPAN × SPAN × SPAN` = 128³ virtual voxel grid.
- A point tries the root first. If its voxel cell is still free, the node **keeps** the
  point and marks the cell occupied. If the cell is already taken, the point **descends**
  into the child octant that contains it and tries again.
- This repeats down to `MAX_DEPTH`.

The effect: each node holds *at most one point per voxel*, so shallow nodes are a
**thinned, uniform overview** of the whole cloud and detail accumulates in deeper nodes.
That is exactly the level-of-detail structure a streaming viewer wants — render the root
when zoomed out, pull children as you zoom in.

`computeIfAbsent`, the `cell = (cx<<14)|(cy<<7)|cz` packing, and the child-octant
selection (`childX/childY/childZ`) implement this. A `NodeKey(depth, x, y, z)` identifies
each node; `Node` holds the occupied voxel set and the list of point indices assigned to it.

### 9.2 Collapsing the sparse tail

Pure voxel acceptance leaves a long tail of tiny deep nodes — often a handful of points
each. That is bad for LAZ, because **every chunk restarts the arithmetic models and
stores a raw first point + framing**; thousands of 5-point chunks bloat the file.
`collapseSmallSubtrees` fixes this: any whole subtree with ≤ `MIN_NODE_POINTS` (50,000)
points is folded into a single leaf. Well-filled upper nodes are untouched, so the LOD
structure survives; only the sparse tail is consolidated. This is a pragmatic
size/encoder-efficiency tradeoff, and the comment in the code spells out why it matters.

### 9.3 Assembling the COPC file

Each surviving node becomes one variable-size LAZ chunk (`Point14Compressor`). On top of
the normal LAZ structure COPC adds two records:

- the **COPC info VLR** (first VLR, by spec) — root cube center/half-size, point spacing,
  GPS-time range, and a pointer to the hierarchy;
- the **COPC hierarchy EVLR** — a flat table of every node: its key, the byte offset and
  size of its chunk, and its point count (32 bytes per node).

The slightly awkward two-phase layout in `build` (reserve a 160-byte info payload, lay
out the file to learn the hierarchy's offset, then fill the info in place) exists because
the info VLR has to point forward at the hierarchy, whose location isn't known until the
chunks are sized. `extractVlr` carries the source CRS (WKT) VLR through so the output is
still georeferenced.

---

## 10. Putting it together — `LazToCopc`

The CLI ties the whole package into one flow (`main`):

1. Read the file, `LasHeader.parse` it. Bail unless it's PDRF 6.
2. `decodeRecords` → a flat `count × 30` byte buffer. For uncompressed LAS this is a
   strided copy; for LAZ it reads the chunk table and runs `Point14Decompressor` per
   chunk.
3. `extractVlr` pulls the CRS WKT so it survives the round-trip.
4. `CopcWriter.build` reorganizes into the octree and re-encodes.
5. Write `<input>.copc.laz`.

Because the encoder is byte-exact against LASzip/PDAL, the output is a standard COPC file
any conforming reader (PDAL, QGIS, potree, …) can open.

---

## 11. Reading order for the code

If you want to walk the package in dependency order, bottom to top:

1. `Ac.java` — the constants and the one-paragraph model of the coder.
2. `ArithmeticBitModel` / `ArithmeticModel` — adaptive probabilities.
3. `ArithmeticEncoder` / `ArithmeticDecoder` — the entropy coder. Read `encodeBit` and
   `decodeBit` together, then `encodeSymbol`/`decodeSymbol`.
4. `IntegerCompressor` / `IntegerDecompressor` — int → symbols, contexts, the `k` trick.
5. `StreamingMedian`, `Point14`, `Point14Tables` — supporting pieces.
6. `Point14Compressor` / `Point14Decompressor` — the per-field prediction machine. Read
   them in parallel.
7. `LasHeader`, `ByteCursor`, `LazWriter` — the file container and chunk table.
8. `CopcWriter`, `LazToCopc` — the octree and the CLI.

The tests under `src/test/java/com/spotable/laz/` are the best executable documentation:
`CodecRoundTripTest`, `Point14DecodeTest`/`EncodeTest` (verified byte-identical to PDAL),
`LazWriterTest` and `CopcWriterTest` each pin down a layer of the stack.

---

## 12. Glossary

- **PDRF** — Point Data Record Format. This codec handles format 6 (30-byte core).
- **VLR / EVLR** — (Extended) Variable Length Record: typed metadata blocks in the header
  area / after the points. CRS, LASzip params, and COPC info/hierarchy all ride in these.
- **Chunk** — an independently-decodable run of compressed points. The unit of random
  access. In COPC, one chunk per octree node.
- **Layer / sub-stream** — in LASzip v3, one arithmetic stream per point field.
- **Context** — which adaptive model to use for a value, chosen from correlated
  already-known data. The main lever for compression ratio.
- **Corrector** — `real − predicted`; the small number the entropy coder actually stores.
- **Renormalization** — the coder shifting bytes in/out to keep ≥24 bits of working
  precision.
- **Octree / voxel acceptance** — COPC's spatial index; how points get sorted into a
  level-of-detail hierarchy.
- **COPC** — Cloud Optimized Point Cloud: a LAZ file whose chunks form a streamable octree.
