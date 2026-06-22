package com.spotable.laz;

/**
 * Compresses an integer as a correction relative to a predictor (port of laz-perf's
 * {@code compressors::integer}). The corrector {@code real - pred} is folded into the
 * model's range, the number of significant bits {@code k} is entropy-coded, and the
 * value within the {@code k}-bit interval is coded with a per-{@code k} model (its
 * top bits) plus raw bits for the remainder.
 */
final class IntegerCompressor {
    private static final int BITS_HIGH = 8;

    final int bits;
    final int contexts;
    final int corrBits;
    final int corrRange;     // 0 when bits >= 32 (no folding)
    final int corrMin, corrMax;
    int k;

    private ArithmeticModel[] mBits;
    private final ArithmeticBitModel mCorrector0 = new ArithmeticBitModel();
    private ArithmeticModel[] mCorrector;  // mCorrector[i-1] models k == i, for i in 1..corrBits

    IntegerCompressor(int bits, int contexts) {
        this.bits = bits;
        this.contexts = contexts;
        if (bits != 0 && bits < 32) {
            corrBits = bits;
            corrRange = 1 << bits;
            corrMin = -(corrRange >>> 1);
            corrMax = corrMin + corrRange - 1;
        } else {
            corrBits = 32;
            corrRange = 0;
            corrMin = Integer.MIN_VALUE;
            corrMax = Integer.MAX_VALUE;
        }
        k = 0;
    }

    void init() {
        if (mBits == null) {
            mBits = new ArithmeticModel[contexts];
            for (int i = 0; i < contexts; i++) mBits[i] = new ArithmeticModel(corrBits + 1, true);
            mCorrector = new ArithmeticModel[corrBits];
            for (int i = 1; i <= corrBits; i++) {
                int v = i <= BITS_HIGH ? (1 << i) : (1 << BITS_HIGH);
                mCorrector[i - 1] = new ArithmeticModel(v, true);
            }
        }
    }

    void compress(ArithmeticEncoder enc, int pred, int real, int context) {
        int corr = real - pred;
        if (corr < corrMin) corr += corrRange;
        else if (corr > corrMax) corr -= corrRange;
        writeCorrector(enc, corr, mBits[context]);
    }

    private void writeCorrector(ArithmeticEncoder enc, int c, ArithmeticModel mBitsModel) {
        // k = number of significant bits of the (sign-adjusted) corrector magnitude.
        int c1 = (c <= 0 ? -c : c - 1);
        for (k = 0; c1 != 0; k++) c1 >>>= 1;
        enc.encodeSymbol(mBitsModel, k);

        if (k != 0) {                       // c is < 0 or > 1
            if (k == 32) return;            // all info is in k (corrector was INT_MIN)
            if (c < 0) c += (1 << k) - 1;   // map [-(2^k-1) .. -2^(k-1)] -> [0 .. 2^(k-1)-1]
            else c -= 1;                    // map [2^(k-1)+1 .. 2^k] -> [2^(k-1) .. 2^k-1]
            if (k <= BITS_HIGH) {
                enc.encodeSymbol(mCorrector[k - 1], c);
            } else {
                int k1 = k - BITS_HIGH;
                int low = c & ((1 << k1) - 1);
                int high = c >>> k1;
                enc.encodeSymbol(mCorrector[k - 1], high);
                enc.writeBits(k1, low);
            }
        } else {                            // c is 0 or 1
            enc.encodeBit(mCorrector0, c);
        }
    }
}
