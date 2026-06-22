package com.spotable.laz;

import static com.spotable.laz.Ac.BM__LengthShift;
import static com.spotable.laz.Ac.BM__MaxCount;

/**
 * Adaptive binary model: tracks the probability of bit 0 ({@link #bit0Prob}, scaled
 * to {@code 2^BM__LengthShift}) and re-estimates it periodically from observed
 * counts. A direct port of laz-perf's {@code models::arithmetic_bit}.
 */
final class ArithmeticBitModel {
    int updateCycle, bitsUntilUpdate;
    int bit0Prob, bit0Count, bitCount;

    ArithmeticBitModel() {
        init();
    }

    /** Resets to the equiprobable model and a fast initial update cadence. */
    void init() {
        bit0Count = 1;
        bitCount = 2;
        bit0Prob = 1 << (BM__LengthShift - 1);
        updateCycle = bitsUntilUpdate = 4;
    }

    /** Re-estimates {@link #bit0Prob} from the running counts; called periodically. */
    void update() {
        // Halve the counts once they grow past the cap, so the model keeps adapting.
        if ((bitCount += updateCycle) > BM__MaxCount) {
            bitCount = (bitCount + 1) >> 1;
            bit0Count = (bit0Count + 1) >> 1;
            if (bit0Count == bitCount) ++bitCount;
        }
        // Scaled probability of a 0 bit.
        int scale = Integer.divideUnsigned(0x80000000, bitCount);
        bit0Prob = (bit0Count * scale) >>> (31 - BM__LengthShift);
        // Updates get progressively rarer (up to every 64 bits).
        updateCycle = (5 * updateCycle) >> 2;
        if (updateCycle > 64) updateCycle = 64;
        bitsUntilUpdate = updateCycle;
    }
}
