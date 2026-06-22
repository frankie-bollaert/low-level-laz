package com.spotable.laz;

import static com.spotable.laz.Ac.AC__MinLength;
import static com.spotable.laz.Ac.AC__MaxLength;
import static com.spotable.laz.Ac.BM__LengthShift;
import static com.spotable.laz.Ac.DM__LengthShift;

import java.util.Arrays;

/**
 * Arithmetic encoder (Amir Said's fast coder, as used by LASzip), writing into a
 * growable in-memory byte buffer. {@link #done()} finalizes the interval and returns
 * the encoded bytes.
 * <p>
 * Unlike laz-perf's circular double-buffer — an optimization that bounds how far a
 * carry can propagate — this keeps every emitted byte and propagates carries across
 * the whole buffer. The resulting byte stream is identical (the decoder is oblivious
 * to how bytes were buffered) and the carry logic is simpler to verify. Each
 * compressed chunk is small, so retaining the full buffer is cheap.
 */
final class ArithmeticEncoder {
    private byte[] buf = new byte[256];
    private int count = 0;
    private int base = 0;
    private int length = AC__MaxLength;

    /** Encodes one bit under an adaptive binary model. */
    void encodeBit(ArithmeticBitModel m, int sym) {
        int x = m.bit0Prob * (length >>> BM__LengthShift);   // product length * P(0)
        if (sym == 0) {
            length = x;
            ++m.bit0Count;
        } else {
            int initBase = base;
            base += x;
            length -= x;
            if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        }
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        if (--m.bitsUntilUpdate == 0) m.update();
    }

    /** Encodes one symbol under an adaptive multi-symbol model. */
    void encodeSymbol(ArithmeticModel m, int sym) {
        int initBase = base, x;
        if (sym == m.lastSymbol) {
            x = m.distribution[sym] * (length >>> DM__LengthShift);
            base += x;
            length -= x;                                     // last symbol: no upper bound product
        } else {
            x = m.distribution[sym] * (length >>>= DM__LengthShift);
            base += x;
            length = m.distribution[sym + 1] * length - x;
        }
        if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        ++m.symbolCount[sym];
        if (--m.symbolsUntilUpdate == 0) m.update();
    }

    /** Writes a single bit with a flat (50/50) model. */
    void writeBit(int sym) {
        int initBase = base;
        base += sym * (length >>>= 1);
        if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
    }

    /** Writes {@code bits} raw (uniformly distributed) low bits of {@code sym}. */
    void writeBits(int bits, int sym) {
        if (bits > 19) {
            writeShort(sym & 0xFFFF);
            sym >>>= 16;
            bits -= 16;
        }
        int initBase = base;
        base += sym * (length >>>= bits);
        if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
    }

    void writeByte(int sym) {
        int initBase = base;
        base += (sym & 0xFF) * (length >>>= 8);
        if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
    }

    void writeShort(int sym) {
        int initBase = base;
        base += (sym & 0xFFFF) * (length >>>= 16);
        if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
    }

    void writeInt(int sym) {
        writeShort(sym & 0xFFFF);
        writeShort(sym >>> 16);
    }

    /** Finalizes the stream and returns the encoded bytes. */
    byte[] done() {
        int initBase = base;
        boolean anotherByte = true;
        if (Integer.compareUnsigned(length, 2 * AC__MinLength) > 0) {
            base += AC__MinLength;
            length = AC__MinLength >>> 1;            // one more output byte
        } else {
            base += AC__MinLength >>> 1;
            length = AC__MinLength >>> 9;            // two more output bytes
            anotherByte = false;
        }
        if (Integer.compareUnsigned(initBase, base) > 0) propagateCarry();
        renorm();
        // Trailing zero bytes keep the decoder's look-ahead reads in sync.
        append(0);
        append(0);
        if (anotherByte) append(0);
        return Arrays.copyOf(buf, count);
    }

    private void renorm() {
        do {
            append((base >>> 24) & 0xFF);
            base <<= 8;
        } while (Integer.compareUnsigned(length <<= 8, AC__MinLength) < 0);
    }

    private void propagateCarry() {
        int p = count - 1;
        while (p >= 0 && (buf[p] & 0xFF) == 0xFF) {
            buf[p] = 0;
            p--;
        }
        // For a well-formed stream a carry always lands within already-emitted bytes.
        buf[p]++;
    }

    private void append(int b) {
        if (count == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[count++] = (byte) b;
    }
}
