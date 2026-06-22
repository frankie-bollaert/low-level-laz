package com.spotable.laz;

import static com.spotable.laz.Ac.AC__MinLength;
import static com.spotable.laz.Ac.AC__MaxLength;
import static com.spotable.laz.Ac.BM__LengthShift;
import static com.spotable.laz.Ac.DM__LengthShift;

/**
 * Arithmetic decoder (Amir Said's fast coder, as used by LASzip), reading from a
 * byte array starting at a given offset. The matching counterpart of
 * {@link ArithmeticEncoder}; the two must use identically-configured models.
 * <p>
 * Reads past the end of the backing array yield zero bytes, matching the trailing
 * zero padding the encoder writes.
 */
final class ArithmeticDecoder {
    private final byte[] buf;
    private int pos;
    private int value;
    private int length = AC__MaxLength;

    ArithmeticDecoder(byte[] buf, int offset) {
        this.buf = buf;
        this.pos = offset;
        value = (getByte() << 24) | (getByte() << 16) | (getByte() << 8) | getByte();
    }

    /** Position just past the last byte consumed (useful for laying out sub-streams). */
    int position() {
        return pos;
    }

    int decodeBit(ArithmeticBitModel m) {
        int x = m.bit0Prob * (length >>> BM__LengthShift);
        int sym = Integer.compareUnsigned(value, x) >= 0 ? 1 : 0;
        if (sym == 0) {
            length = x;
            ++m.bit0Count;
        } else {
            value -= x;
            length -= x;
        }
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        if (--m.bitsUntilUpdate == 0) m.update();
        return sym;
    }

    int decodeSymbol(ArithmeticModel m) {
        int n, sym, x, y = length;
        if (m.decoderTable != null) {
            int dv = Integer.divideUnsigned(value, (length >>>= DM__LengthShift));
            int t = dv >>> m.tableShift;
            sym = m.decoderTable[t];                 // table look-up gives a starting bracket
            n = m.decoderTable[t + 1] + 1;
            while (n > sym + 1) {                    // narrow it by bisection
                int k = (sym + n) >>> 1;
                if (Integer.compareUnsigned(m.distribution[k], dv) > 0) n = k; else sym = k;
            }
            x = m.distribution[sym] * length;
            if (sym != m.lastSymbol) y = m.distribution[sym + 1] * length;
        } else {
            x = 0;
            sym = 0;
            length >>>= DM__LengthShift;
            n = m.symbols;
            int k = n >>> 1;
            do {                                     // pure bisection search
                int z = length * m.distribution[k];
                if (Integer.compareUnsigned(z, value) > 0) {
                    n = k;
                    y = z;
                } else {
                    sym = k;
                    x = z;
                }
            } while ((k = (sym + n) >>> 1) != sym);
        }
        value -= x;
        length = y - x;
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        ++m.symbolCount[sym];
        if (--m.symbolsUntilUpdate == 0) m.update();
        return sym;
    }

    int readBit() {
        int sym = Integer.divideUnsigned(value, (length >>>= 1));
        value -= length * sym;
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        return sym;
    }

    int readBits(int bits) {
        if (bits > 19) {
            int lower = readShort();
            int upper = readBits(bits - 16) << 16;
            return upper | lower;
        }
        int sym = Integer.divideUnsigned(value, (length >>>= bits));
        value -= length * sym;
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        return sym;
    }

    int readByte() {
        int sym = Integer.divideUnsigned(value, (length >>>= 8));
        value -= length * sym;
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        return sym & 0xFF;
    }

    int readShort() {
        int sym = Integer.divideUnsigned(value, (length >>>= 16));
        value -= length * sym;
        if (Integer.compareUnsigned(length, AC__MinLength) < 0) renorm();
        return sym & 0xFFFF;
    }

    int readInt() {
        int lower = readShort();
        int upper = readShort();
        return (upper << 16) | lower;
    }

    private void renorm() {
        do {
            value = (value << 8) | getByte();
        } while (Integer.compareUnsigned(length <<= 8, AC__MinLength) < 0);
    }

    private int getByte() {
        int b = pos < buf.length ? (buf[pos] & 0xFF) : 0;
        pos++;
        return b;
    }
}
