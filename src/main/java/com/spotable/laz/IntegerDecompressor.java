package com.spotable.laz;

/**
 * Inverse of {@link IntegerCompressor} (port of laz-perf's {@code decompressors::integer}).
 * Decodes the bit count {@code k}, then the value within the {@code k}-bit interval,
 * and adds it to the predictor — unfolding the range so the original integer is
 * recovered exactly.
 */
final class IntegerDecompressor {
    final int bits;
    final int contexts;
    final int bitsHigh;
    final int range;
    final int corrBits;
    final int corrRange;
    final int corrMin, corrMax;
    int k;

    private ArithmeticModel[] mBits;
    private final ArithmeticBitModel mCorrector0 = new ArithmeticBitModel();
    private ArithmeticModel[] mCorrector;  // mCorrector[i-1] models k == i, for i in 1..corrBits

    IntegerDecompressor(int bits, int contexts) {
        this(bits, contexts, 8, 0);
    }

    IntegerDecompressor(int bits, int contexts, int bitsHigh, int range) {
        this.bits = bits;
        this.contexts = contexts;
        this.bitsHigh = bitsHigh;
        this.range = range;
        int cBits, cRange, cMin, cMax;
        if (range != 0) {
            int r = range, cb = 0;
            while (r != 0) {
                r >>>= 1;
                cb++;
            }
            if (range == (1 << (cb - 1))) cb--;
            cBits = cb;
            cRange = range;
            cMin = -(cRange >>> 1);
            cMax = cMin + cRange - 1;
        } else if (bits != 0 && bits < 32) {
            cBits = bits;
            cRange = 1 << bits;
            cMin = -(cRange >>> 1);
            cMax = cMin + cRange - 1;
        } else {
            cBits = 32;
            cRange = 0;
            cMin = Integer.MIN_VALUE;
            cMax = Integer.MAX_VALUE;
        }
        corrBits = cBits;
        corrRange = cRange;
        corrMin = cMin;
        corrMax = cMax;
        k = 0;
    }

    void init() {
        if (mBits == null) {
            mBits = new ArithmeticModel[contexts];
            for (int i = 0; i < contexts; i++) mBits[i] = new ArithmeticModel(corrBits + 1, false);
            mCorrector = new ArithmeticModel[corrBits];
            for (int i = 1; i <= corrBits; i++) {
                int v = i <= bitsHigh ? (1 << i) : (1 << bitsHigh);
                mCorrector[i - 1] = new ArithmeticModel(v, false);
            }
        }
    }

    int decompress(ArithmeticDecoder dec, int pred, int context) {
        int real = pred + readCorrector(dec, mBits[context]);
        if (real < 0) real += corrRange;
        else if (Integer.compareUnsigned(real, corrRange) >= 0) real -= corrRange;
        return real;
    }

    private int readCorrector(ArithmeticDecoder dec, ArithmeticModel mBitsModel) {
        int c;
        k = dec.decodeSymbol(mBitsModel);
        if (k != 0) {                       // c is < 0 or > 1
            if (k < 32) {
                if (k <= bitsHigh) {
                    c = dec.decodeSymbol(mCorrector[k - 1]);
                } else {
                    int k1 = k - bitsHigh;
                    c = dec.decodeSymbol(mCorrector[k - 1]);
                    int low = dec.readBits(k1);
                    c = (c << k1) | low;
                }
                if (c >= (1 << (k - 1))) c += 1;            // undo the encoder's interval mapping
                else c -= (1 << k) - 1;
            } else {
                c = corrMin;
            }
        } else {                            // c is 0 or 1
            c = dec.decodeBit(mCorrector0);
        }
        return c;
    }
}
