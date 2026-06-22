package com.spotable.laz;

/**
 * Shared constants for the LASzip arithmetic coder (Amir Said's "fast arithmetic
 * coding" variant, as used by LASzip / laz-perf).
 * <p>
 * The coder keeps a 32-bit {@code base}/{@code value} and a 32-bit {@code length}
 * (the current interval width). Whenever {@code length} drops below
 * {@link #AC__MinLength} the top byte is shifted out (renormalization), keeping at
 * least 24 bits of precision. All of these values are logically <em>unsigned</em>
 * 32-bit integers; the Java code stores them in {@code int} and uses
 * {@link Integer#compareUnsigned}, {@link Integer#divideUnsigned} and {@code >>>}
 * wherever unsigned semantics are required.
 */
final class Ac {
    private Ac() {}

    /** Renormalization threshold: keep at least 2^24 of interval width. */
    static final int AC__MinLength = 0x01000000;
    /** Maximum interval width = 2^32 - 1 (stored as the int with all bits set). */
    static final int AC__MaxLength = 0xFFFFFFFF;

    // Binary (bit) model: probability is kept scaled to 13 bits.
    static final int BM__LengthShift = 13;
    static final int BM__MaxCount = 1 << BM__LengthShift;

    // Symbol model: cumulative distribution is kept scaled to 15 bits.
    static final int DM__LengthShift = 15;
    static final int DM__MaxCount = 1 << DM__LengthShift;
}
