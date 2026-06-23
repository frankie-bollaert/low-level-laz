package com.spotable.laz;

import java.util.Arrays;

/**
 * A forward-only cursor over an in-memory byte array, used to read the main
 * (non-arithmetic) parts of a LAZ chunk: the raw first point, the point count, the
 * layer size table, and the layer byte slices. All multi-byte reads are
 * little-endian.
 */
final class ByteCursor {
    private final byte[] buf;
    private int pos;

    ByteCursor(byte[] buf, int pos) {
        this.buf = buf;
        this.pos = pos;
    }

    int position() {
        return pos;
    }

    boolean hasRemaining(int n) {
        return pos + n <= buf.length;
    }

    void getBytes(byte[] dst, int off, int len) {
        System.arraycopy(buf, pos, dst, off, len);
        pos += len;
    }

    /** Advances past {@code len} bytes without reading them. */
    void skip(int len) {
        pos += len;
    }

    /** Returns a fresh array of the next {@code len} bytes and advances past them. */
    byte[] take(int len) {
        byte[] s = Arrays.copyOfRange(buf, pos, pos + len);
        pos += len;
        return s;
    }

    int u32le() {
        int v = (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 3] & 0xFF) << 24);
        pos += 4;
        return v;
    }
}
