package com.spotable;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/**
 * Builds a minimal classic GeoTIFF in memory and drives it through the real
 * {@link DtmBounds} header parser (no I/O, no external fixtures).
 */
public class DtmBoundsTest {

    private static DtmBounds readBytes(byte[] file) throws Exception {
        DtmBounds.Source src = new DtmBounds.Source() {
            public String label() { return "test.tif"; }
            public byte[] read(long offset, int len) {
                if (offset >= file.length) return new byte[0];
                int from = (int) offset;
                int to = (int) Math.min(file.length, offset + len);
                return Arrays.copyOfRange(file, from, to);
            }
        };
        Method m = DtmBounds.class.getDeclaredMethod("read", DtmBounds.Source.class);
        m.setAccessible(true);
        return (DtmBounds) m.invoke(null, src);
    }

    /**
     * Layout: 8-byte header, IFD at offset 8 (8 entries), then out-of-line payloads
     * for pixel scale, tiepoint and the GeoKey directory.
     */
    private static byte[] sampleGeoTiff() {
        int width = 2000, height = 1500;
        double sx = 2.5, sy = 2.5;
        double originX = 2015000.0, originY = 358703.62;

        int nEntries = 8;
        int ifdOffset = 8;
        int ifdSize = 2 + nEntries * 12 + 4;     // count + entries + next-offset
        int dataStart = ifdOffset + ifdSize;

        int scaleOff = dataStart;                 // 3 doubles = 24 bytes
        int tieOff = scaleOff + 24;               // 6 doubles = 48 bytes
        int geoKeyOff = tieOff + 48;              // 16 shorts = 32 bytes
        int total = geoKeyOff + 32;

        byte[] f = new byte[total];
        ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
        f[0] = 'I'; f[1] = 'I';
        b.putShort(2, (short) 42);
        b.putInt(4, ifdOffset);

        b.putShort(ifdOffset, (short) nEntries);
        int e = ifdOffset + 2;
        e = entry(b, e, 256, 4, 1, width);        // ImageWidth (LONG, inline)
        e = entry(b, e, 257, 4, 1, height);       // ImageLength (LONG, inline)
        e = entry(b, e, 258, 3, 1, 32);           // BitsPerSample (SHORT, inline)
        e = entry(b, e, 339, 3, 1, 3);            // SampleFormat = float (SHORT, inline)
        e = entry(b, e, 33550, 12, 3, scaleOff);  // ModelPixelScale (3 doubles, offset)
        e = entry(b, e, 33922, 12, 6, tieOff);    // ModelTiepoint (6 doubles, offset)
        e = entry(b, e, 34735, 3, 16, geoKeyOff); // GeoKeyDirectory (16 shorts, offset)
        e = entry(b, e, 42113, 2, 4, packAscii());// GDAL_NODATA "-99\0" (4 bytes, inline)
        b.putInt(e, 0);                            // next IFD = none

        b.putDouble(scaleOff, sx);
        b.putDouble(scaleOff + 8, sy);
        b.putDouble(scaleOff + 16, 0.0);

        b.putDouble(tieOff, 0.0);                  // i
        b.putDouble(tieOff + 8, 0.0);              // j
        b.putDouble(tieOff + 16, 0.0);             // k
        b.putDouble(tieOff + 24, originX);         // x
        b.putDouble(tieOff + 32, originY);         // y
        b.putDouble(tieOff + 40, 0.0);             // z

        short[] gk = {1, 1, 0, 3,                  // version, revision, minor, numKeys
                1024, 0, 1, 1,                     // GTModelType = projected
                1025, 0, 1, 1,                     // GTRasterType
                3072, 0, 1, 6441};                 // ProjectedCSType = EPSG:6441
        for (int i = 0; i < gk.length; i++) b.putShort(geoKeyOff + i * 2, gk[i]);
        return f;
    }

    private static int entry(ByteBuffer b, int pos, int tag, int type, int count, int valueOrOffset) {
        b.putShort(pos, (short) tag);
        b.putShort(pos + 2, (short) type);
        b.putInt(pos + 4, count);
        b.putInt(pos + 8, valueOrOffset);
        return pos + 12;
    }

    // "-99\0" packed little-endian into the 4-byte inline value field.
    private static int packAscii() {
        byte[] s = "-99\0".getBytes(StandardCharsets.US_ASCII);
        return (s[0] & 0xFF) | ((s[1] & 0xFF) << 8) | ((s[2] & 0xFF) << 16) | ((s[3] & 0xFF) << 24);
    }

    @Test
    public void parsesBoundsAndCrs() throws Exception {
        DtmBounds d = readBytes(sampleGeoTiff());
        assertEquals(2015000.0, d.minX, 1e-6);
        assertEquals(2015000.0 + 2000 * 2.5, d.maxX, 1e-6);
        assertEquals(358703.62, d.maxY, 1e-6);
        assertEquals(358703.62 - 1500 * 2.5, d.minY, 1e-6);
        assertEquals(Integer.valueOf(6441), d.epsg);
    }

    /** Output mirrors LazBinaryReader: a bare bbox WKT carrying the SRID. */
    @Test
    public void emitsEwktGeometry() throws Exception {
        DtmBounds d = readBytes(sampleGeoTiff());
        assertEquals(
            "SRID=6441;POLYGON ((2015000 354953.62, 2020000 354953.62, "
            + "2020000 358703.62, 2015000 358703.62, 2015000 354953.62))",
            d.toWkt());
    }
}
