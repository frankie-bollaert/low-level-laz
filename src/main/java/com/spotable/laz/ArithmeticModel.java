package com.spotable.laz;

import static com.spotable.laz.Ac.DM__LengthShift;
import static com.spotable.laz.Ac.DM__MaxCount;

/**
 * Adaptive multi-symbol model: keeps a cumulative {@link #distribution} over
 * {@code symbols} symbols (scaled to {@code 2^DM__LengthShift}), updated periodically
 * from observed {@link #symbolCount}s. A direct port of laz-perf's
 * {@code models::arithmetic}.
 * <p>
 * For larger decode alphabets ({@code symbols > 16} and not in compress mode) a
 * {@link #decoderTable} accelerates the symbol search; encode-side models and small
 * alphabets leave it null and the decoder falls back to a bisection search.
 */
final class ArithmeticModel {
    final int symbols;
    final boolean compress;
    final int[] distribution;
    final int[] symbolCount;
    final int[] decoderTable; // null unless a lookup table is built (see above)
    final int lastSymbol;
    final int tableSize, tableShift;
    int totalCount, updateCycle, symbolsUntilUpdate;

    ArithmeticModel(int symbols, boolean compress) {
        this(symbols, compress, null);
    }

    ArithmeticModel(int symbols, boolean compress, int[] initTable) {
        if (symbols < 2 || symbols > (1 << 11)) {
            throw new IllegalArgumentException("invalid number of symbols: " + symbols);
        }
        this.symbols = symbols;
        this.compress = compress;
        this.lastSymbol = symbols - 1;
        if (!compress && symbols > 16) {
            int tableBits = 3;
            while (symbols > (1 << (tableBits + 2))) tableBits++;
            this.tableSize = 1 << tableBits;
            this.tableShift = DM__LengthShift - tableBits;
            this.decoderTable = new int[tableSize + 2];
        } else {
            this.decoderTable = null;
            this.tableSize = 0;
            this.tableShift = 0;
        }
        this.distribution = new int[symbols];
        this.symbolCount = new int[symbols];
        this.totalCount = 0;
        this.updateCycle = symbols;
        for (int k = 0; k < symbols; k++) symbolCount[k] = initTable != null ? initTable[k] : 1;
        update();
        symbolsUntilUpdate = updateCycle = (symbols + 6) >> 1;
    }

    /** Recomputes the cumulative distribution (and decoder table) from the counts. */
    void update() {
        // Halve the counts once they grow past the cap, so the model keeps adapting.
        if ((totalCount += updateCycle) > DM__MaxCount) {
            totalCount = 0;
            for (int n = 0; n < symbols; n++) {
                totalCount += (symbolCount[n] = (symbolCount[n] + 1) >> 1);
            }
        }
        int sum = 0, s = 0;
        int scale = Integer.divideUnsigned(0x80000000, totalCount);
        if (compress || tableSize == 0) {
            for (int k = 0; k < symbols; k++) {
                distribution[k] = (scale * sum) >>> (31 - DM__LengthShift);
                sum += symbolCount[k];
            }
        } else {
            for (int k = 0; k < symbols; k++) {
                distribution[k] = (scale * sum) >>> (31 - DM__LengthShift);
                sum += symbolCount[k];
                int w = distribution[k] >>> tableShift;
                while (s < w) decoderTable[++s] = k - 1;
            }
            decoderTable[0] = 0;
            while (s <= tableSize) decoderTable[++s] = symbols - 1;
        }
        // Updates get progressively rarer, capped relative to the alphabet size.
        updateCycle = (5 * updateCycle) >> 2;
        int maxCycle = (symbols + 6) << 3;
        if (updateCycle > maxCycle) updateCycle = maxCycle;
        symbolsUntilUpdate = updateCycle;
    }
}
