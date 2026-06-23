package com.spotable.laz;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds a COPC (Cloud Optimized Point Cloud) file from PDRF&nbsp;6 point records.
 * <p>
 * Points are organized into a clustered octree using Entwine-style voxel acceptance:
 * starting at the root cube, a point is kept by the shallowest node whose
 * {@value #SPAN}&times;{@value #SPAN}&times;{@value #SPAN} voxel grid still has its
 * cell free, otherwise it descends to the child octant. Coarse nodes therefore form a
 * thinned overview and deeper nodes carry the detail. Each populated node becomes one
 * independently-decodable LASzip chunk (via {@link Point14Compressor}).
 * <p>
 * The file is assembled by {@link LazWriter}: the mandatory COPC info VLR (first),
 * the variable-chunk LASzip VLR, an optional CRS WKT VLR, the per-node chunks, the
 * compressed chunk table, and a single COPC hierarchy EVLR listing every node.
 * Targets PDRF&nbsp;6 (30-byte records).
 */
final class CopcWriter {

    /** Voxel grid resolution per node edge (points-per-node cap is SPAN^3). */
    private static final int SPAN = 128;
    /** Maximum octree depth; at this depth points are accepted without the voxel test. */
    private static final int MAX_DEPTH = 18;
    /**
     * Any octree subtree holding at most this many points is collapsed into a single
     * leaf node. Without this, dense data leaves a long tail of tiny deep nodes (one
     * chunk each), and LASzip v3 compresses small chunks poorly — every chunk restarts
     * the arithmetic models and stores a raw first point plus framing. Collapsing folds
     * that tail into fuller leaves, cutting chunk count and file size dramatically.
     */
    private static final int MIN_NODE_POINTS = 50_000;

    private CopcWriter() {}

    private record NodeKey(int d, int x, int y, int z) {}

    private static final class Node {
        final HashSet<Integer> occupied = new HashSet<>();
        int[] pts = new int[8];
        int n = 0;

        void add(int idx) {
            if (n == pts.length) {
                int[] g = new int[pts.length * 2];
                System.arraycopy(pts, 0, g, 0, n);
                pts = g;
            }
            pts[n++] = idx;
        }
    }

    /**
     * Builds the COPC file bytes.
     *
     * @param headerTemplate source 375-byte LAS 1.4 header (scale/offset/GUID copied through)
     * @param records        concatenated 30-byte PDRF 6 records
     * @param count          number of records
     * @param wkt            optional CRS WKT VLR (record 2112) to carry through, or null
     */
    static byte[] build(byte[] headerTemplate, byte[] records, int count, LazWriter.Vlr wkt) {
        LasHeader h = LasHeader.parse(headerTemplate);

        // Pass 1: real-world bounds and GPS-time range.
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        double gpsMin = Double.POSITIVE_INFINITY, gpsMax = Double.NEGATIVE_INFINITY;
        Point14 p = new Point14();
        for (int i = 0; i < count; i++) {
            p.unpack(records, i * Point14.SIZE);
            double rx = p.x * h.scaleX + h.offsetX;
            double ry = p.y * h.scaleY + h.offsetY;
            double rz = p.z * h.scaleZ + h.offsetZ;
            if (rx < minX) minX = rx; if (rx > maxX) maxX = rx;
            if (ry < minY) minY = ry; if (ry > maxY) maxY = ry;
            if (rz < minZ) minZ = rz; if (rz > maxZ) maxZ = rz;
            double g = Double.longBitsToDouble(p.gpstime);
            if (g < gpsMin) gpsMin = g; if (g > gpsMax) gpsMax = g;
        }
        if (count == 0) { gpsMin = 0; gpsMax = 0; minX = minY = minZ = maxX = maxY = maxZ = 0; }

        double centerX = (minX + maxX) / 2, centerY = (minY + maxY) / 2, centerZ = (minZ + maxZ) / 2;
        double halfsize = Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) / 2;
        if (halfsize <= 0) halfsize = 1; // degenerate / single point
        double cube = 2 * halfsize;
        double rootMinX = centerX - halfsize, rootMinY = centerY - halfsize, rootMinZ = centerZ - halfsize;

        // Pass 2: assign every point to a node.
        Map<NodeKey, Node> nodes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            p.unpack(records, i * Point14.SIZE);
            double rx = p.x * h.scaleX + h.offsetX;
            double ry = p.y * h.scaleY + h.offsetY;
            double rz = p.z * h.scaleZ + h.offsetZ;
            int d = 0, nx = 0, ny = 0, nz = 0;
            double nMinX = rootMinX, nMinY = rootMinY, nMinZ = rootMinZ, nSize = cube;
            while (true) {
                Node node = nodes.computeIfAbsent(new NodeKey(d, nx, ny, nz), k -> new Node());
                double cs = nSize / SPAN;
                int cx = clamp((int) ((rx - nMinX) / cs), SPAN);
                int cy = clamp((int) ((ry - nMinY) / cs), SPAN);
                int cz = clamp((int) ((rz - nMinZ) / cs), SPAN);
                int cell = (cx << 14) | (cy << 7) | cz;
                if (d >= MAX_DEPTH || node.occupied.add(cell)) {
                    node.add(i);
                    break;
                }
                double half = nSize / 2;
                int childX = rx >= nMinX + half ? 1 : 0;
                int childY = ry >= nMinY + half ? 1 : 0;
                int childZ = rz >= nMinZ + half ? 1 : 0;
                nMinX += childX * half; nMinY += childY * half; nMinZ += childZ * half;
                nx = nx * 2 + childX; ny = ny * 2 + childY; nz = nz * 2 + childZ;
                nSize = half;
                d++;
            }
        }

        // Fold the long tail of tiny deep nodes into fuller leaves before chunking.
        collapseSmallSubtrees(nodes);

        // Stable node order: shallow-to-deep, then by key. Root (0,0,0,0) comes first.
        List<Map.Entry<NodeKey, Node>> ordered = new ArrayList<>(nodes.entrySet());
        ordered.sort(Comparator
                .<Map.Entry<NodeKey, Node>>comparingInt(e -> e.getKey().d())
                .thenComparingInt(e -> e.getKey().x())
                .thenComparingInt(e -> e.getKey().y())
                .thenComparingInt(e -> e.getKey().z()));

        // Encode one chunk per node.
        List<byte[]> chunks = new ArrayList<>(ordered.size());
        int[] chunkCounts = new int[ordered.size()];
        for (int ci = 0; ci < ordered.size(); ci++) {
            Node node = ordered.get(ci).getValue();
            Point14Compressor enc = new Point14Compressor();
            for (int j = 0; j < node.n; j++) enc.compress(records, node.pts[j] * Point14.SIZE);
            chunks.add(enc.finish());
            chunkCounts[ci] = node.n;
        }

        // ---- compute the file layout so the COPC info VLR can point at the hierarchy ----
        List<LazWriter.Vlr> vlrs = new ArrayList<>();
        byte[] copcInfoData = new byte[160];                 // filled in below
        vlrs.add(new LazWriter.Vlr("copc", 1, "", copcInfoData));
        vlrs.add(new LazWriter.Vlr("laszip encoded", 22204, "http://laszip.org",
                LazWriter.laszipVlrDataPdrf6(LasHeader.VARIABLE_CHUNK_SIZE)));
        if (wkt != null) vlrs.add(wkt);

        int vlrTotal = 0;
        for (LazWriter.Vlr v : vlrs) vlrTotal += v.totalSize();
        long pointOffset = 375 + vlrTotal;

        long[] chunkOffsets = new long[chunks.size()];
        long cursor = pointOffset + 8;
        long chunkBytes = 0;
        for (int i = 0; i < chunks.size(); i++) {
            chunkOffsets[i] = cursor;
            cursor += chunks.get(i).length;
            chunkBytes += chunks.get(i).length;
        }
        byte[] chunkTable = LazWriter.buildChunkTable(chunks, chunkCounts, true);
        long chunkTableOffset = pointOffset + 8 + chunkBytes;
        long evlrOffset = chunkTableOffset + chunkTable.length;
        long rootHierOffset = evlrOffset + 60;               // past the EVLR header
        long rootHierSize = (long) ordered.size() * 32;

        // COPC hierarchy EVLR: one page listing every node.
        byte[] hier = new byte[ordered.size() * 32];
        for (int i = 0; i < ordered.size(); i++) {
            NodeKey k = ordered.get(i).getKey();
            int o = i * 32;
            i32(hier, o, k.d());
            i32(hier, o + 4, k.x());
            i32(hier, o + 8, k.y());
            i32(hier, o + 12, k.z());
            i64(hier, o + 16, chunkOffsets[i]);
            i32(hier, o + 24, chunks.get(i).length);
            i32(hier, o + 28, chunkCounts[i]);
        }
        List<LazWriter.Evlr> evlrs = List.of(new LazWriter.Evlr("copc", 1000, "", hier));

        // Now that the hierarchy location is known, fill the COPC info payload.
        fillCopcInfo(copcInfoData, centerX, centerY, centerZ, halfsize, cube / SPAN,
                rootHierOffset, rootHierSize, gpsMin, gpsMax);

        return LazWriter.write(headerTemplate, vlrs, chunks, chunkCounts, evlrs, true);
    }

    /**
     * Collapses every octree subtree whose total point count is at most
     * {@link #MIN_NODE_POINTS} into a single leaf node, absorbing its descendants'
     * points and removing them from the map. Well-filled upper-level nodes are left
     * intact, so the level-of-detail structure is preserved; only the sparse deep tail
     * is folded up. The absorbed points stay within the collapsed node's bounds, so the
     * result is still a valid COPC octree.
     */
    private static void collapseSmallSubtrees(Map<NodeKey, Node> nodes) {
        // Subtree totals, deepest nodes first so children are summed before their parent.
        List<NodeKey> deepFirst = new ArrayList<>(nodes.keySet());
        deepFirst.sort(Comparator.comparingInt(NodeKey::d).reversed());
        Map<NodeKey, Integer> subtree = new HashMap<>();
        for (NodeKey k : deepFirst) {
            int total = nodes.get(k).n;
            for (int i = 0; i < 8; i++) {
                Integer cs = subtree.get(child(k, i));
                if (cs != null) total += cs;
            }
            subtree.put(k, total);
        }
        // Shallowest first: collapse the highest node whose whole subtree is small enough;
        // its descendants are then removed and skipped.
        List<NodeKey> shallowFirst = new ArrayList<>(subtree.keySet());
        shallowFirst.sort(Comparator.comparingInt(NodeKey::d));
        for (NodeKey k : shallowFirst) {
            Node target = nodes.get(k);
            if (target == null) continue;                  // already absorbed by an ancestor
            if (subtree.get(k) <= MIN_NODE_POINTS) collapseInto(nodes, k, target);
        }
    }

    /** Pulls every descendant node's points into {@code target} and drops the descendants. */
    private static void collapseInto(Map<NodeKey, Node> nodes, NodeKey root, Node target) {
        Deque<NodeKey> stack = new ArrayDeque<>();
        for (int i = 0; i < 8; i++) stack.push(child(root, i));
        while (!stack.isEmpty()) {
            NodeKey c = stack.pop();
            Node cn = nodes.remove(c);
            if (cn == null) continue;                       // no node here ⇒ no descendants either
            for (int j = 0; j < cn.n; j++) target.add(cn.pts[j]);
            for (int i = 0; i < 8; i++) stack.push(child(c, i));
        }
    }

    private static NodeKey child(NodeKey k, int octant) {
        return new NodeKey(k.d() + 1, k.x() * 2 + ((octant >> 2) & 1),
                k.y() * 2 + ((octant >> 1) & 1), k.z() * 2 + (octant & 1));
    }

    private static void fillCopcInfo(byte[] d, double cx, double cy, double cz, double half,
            double spacing, long rootHierOffset, long rootHierSize, double gpsMin, double gpsMax) {
        f64(d, 0, cx);
        f64(d, 8, cy);
        f64(d, 16, cz);
        f64(d, 24, half);
        f64(d, 32, spacing);
        i64(d, 40, rootHierOffset);
        i64(d, 48, rootHierSize);
        f64(d, 56, gpsMin);
        f64(d, 64, gpsMax);
        // bytes 72..160 are reserved (left zero)
    }

    /** Extracts a VLR's payload from a parsed LAS/LAZ file, or null if not present. */
    static LazWriter.Vlr extractVlr(byte[] file, String userId, int recordId) {
        LasHeader h = LasHeader.parse(file);
        int pos = h.headerSize;
        int end = (int) Math.min(h.pointOffset, file.length);
        for (long i = 0; i < h.vlrCount && pos + 54 <= end; i++) {
            String uid = cstr(file, pos + 2, 16);
            int rid = u16(file, pos + 18);
            int len = u16(file, pos + 20);
            String desc = cstr(file, pos + 22, 32);
            int data = pos + 54;
            if (uid.equals(userId) && rid == recordId) {
                byte[] payload = new byte[len];
                System.arraycopy(file, data, payload, 0, len);
                return new LazWriter.Vlr(uid, rid, desc, payload);
            }
            pos = data + len;
        }
        return null;
    }

    private static int clamp(int v, int span) {
        if (v < 0) return 0;
        if (v >= span) return span - 1;
        return v;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static String cstr(byte[] b, int off, int len) {
        int e = off, max = off + len;
        while (e < max && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.US_ASCII);
    }

    private static void i32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    private static void i64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) ((v >>> (8 * i)) & 0xFF);
    }

    private static void f64(byte[] b, int o, double v) {
        i64(b, o, Double.doubleToRawLongBits(v));
    }
}
