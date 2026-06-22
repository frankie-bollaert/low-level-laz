package com.spotable.laz;

import static org.junit.Assert.assertEquals;

import java.util.Random;
import org.junit.Test;

/**
 * Round-trip tests for the entropy layer ({@link ArithmeticEncoder}/{@link ArithmeticDecoder}
 * with both model kinds and raw bit reads) and the {@link IntegerCompressor}/{@link
 * IntegerDecompressor}. These prove the encoder and decoder are mutually consistent;
 * bit-compatibility with reference LASzip is verified separately by decoding real files.
 */
public class CodecRoundTripTest {

    @Test
    public void bitModelRoundTrip() {
        Random rnd = new Random(1);
        int[] bits = new int[5000];
        for (int i = 0; i < bits.length; i++) bits[i] = rnd.nextInt(100) < 30 ? 1 : 0; // skewed

        ArithmeticEncoder enc = new ArithmeticEncoder();
        ArithmeticBitModel em = new ArithmeticBitModel();
        for (int b : bits) enc.encodeBit(em, b);
        byte[] data = enc.done();

        ArithmeticDecoder dec = new ArithmeticDecoder(data, 0);
        ArithmeticBitModel dm = new ArithmeticBitModel();
        for (int i = 0; i < bits.length; i++) assertEquals("bit " + i, bits[i], dec.decodeBit(dm));
    }

    @Test
    public void symbolModelRoundTrip() {
        for (int symbols : new int[] {2, 7, 16, 17, 64, 256, 2048}) {
            Random rnd = new Random(symbols);
            int[] syms = new int[8000];
            for (int i = 0; i < syms.length; i++) syms[i] = rnd.nextInt(symbols);

            ArithmeticEncoder enc = new ArithmeticEncoder();
            ArithmeticModel em = new ArithmeticModel(symbols, true);
            for (int s : syms) enc.encodeSymbol(em, s);
            byte[] data = enc.done();

            ArithmeticDecoder dec = new ArithmeticDecoder(data, 0);
            ArithmeticModel dm = new ArithmeticModel(symbols, false);
            for (int i = 0; i < syms.length; i++) {
                assertEquals("symbols=" + symbols + " idx " + i, syms[i], dec.decodeSymbol(dm));
            }
        }
    }

    @Test
    public void rawBitsRoundTrip() {
        Random rnd = new Random(42);
        int n = 4000;
        int[] widths = new int[n];
        int[] vals = new int[n];
        for (int i = 0; i < n; i++) {
            int w = 1 + rnd.nextInt(32);
            widths[i] = w;
            vals[i] = w == 32 ? rnd.nextInt() : (rnd.nextInt() & ((1 << w) - 1));
        }
        ArithmeticEncoder enc = new ArithmeticEncoder();
        for (int i = 0; i < n; i++) enc.writeBits(widths[i], vals[i]);
        byte[] data = enc.done();

        ArithmeticDecoder dec = new ArithmeticDecoder(data, 0);
        for (int i = 0; i < n; i++) assertEquals("raw " + i, vals[i], dec.readBits(widths[i]));
    }

    @Test
    public void integerRoundTrip() {
        for (int bits : new int[] {8, 16, 18, 32}) {
            for (int contexts : new int[] {1, 4}) {
                Random rnd = new Random(bits * 31L + contexts);
                int n = 6000;
                int[] preds = new int[n], reals = new int[n], ctxs = new int[n];
                for (int i = 0; i < n; i++) {
                    ctxs[i] = rnd.nextInt(contexts);
                    if (bits == 32) {
                        preds[i] = rnd.nextInt();
                        reals[i] = rnd.nextInt();
                    } else {
                        int range = 1 << bits;
                        preds[i] = rnd.nextInt(range);
                        reals[i] = rnd.nextInt(range);
                    }
                }
                ArithmeticEncoder enc = new ArithmeticEncoder();
                IntegerCompressor ic = new IntegerCompressor(bits, contexts);
                ic.init();
                for (int i = 0; i < n; i++) ic.compress(enc, preds[i], reals[i], ctxs[i]);
                byte[] data = enc.done();

                ArithmeticDecoder dec = new ArithmeticDecoder(data, 0);
                IntegerDecompressor id = new IntegerDecompressor(bits, contexts);
                id.init();
                for (int i = 0; i < n; i++) {
                    assertEquals("bits=" + bits + " ctx=" + contexts + " idx " + i,
                            reals[i], id.decompress(dec, preds[i], ctxs[i]));
                }
            }
        }
    }
}
