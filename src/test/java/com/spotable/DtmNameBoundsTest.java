package com.spotable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DtmNameBoundsTest {

    @Test
    public void parsesModernNameWithExplicitZone() {
        var t = DtmNameBounds.parse(
                "FL_WestEvergladesNP_2018_B18/TIFF/USGS_1M_17_x44y286_FL_WestEvergladesNP_2018_B18.tif");
        assertEquals(17, t.zone());
        assertEquals(44, t.xi());
        assertEquals(286, t.yi());
    }

    @Test
    public void resolvesLegacyZoneFromProjectMap() {
        // USGS_one_meter_ / USGS_1m_ omit the zone; it comes from the project's CRS map.
        var osceola = DtmNameBounds.parse(
                "FL_Osceola_2015/TIFF/USGS_one_meter_x43y313_FL_Osceola_2015.tif");
        assertEquals(17, osceola.zone());
        assertEquals(43, osceola.xi());
        assertEquals(313, osceola.yi());

        var choctaw = DtmNameBounds.parse(
                "FL_Lower_Choctawhatchee_2017/TIFF/USGS_1m_x60y337_FL_Lower_Choctawhatchee_2017.tif");
        assertEquals(16, choctaw.zone());
    }

    @Test
    public void returnsNullForUnknownName() {
        assertNull(DtmNameBounds.parse("something/random/file.tif"));
        assertNull(DtmNameBounds.parse("UNMAPPED_Project/TIFF/USGS_one_meter_x10y20_UNMAPPED_Project.tif"));
    }

    /**
     * Inverse UTM must reproduce the authoritative GeoTIFF corner. gdalinfo on the real
     * Osceola tile x43y313 (NAD83 / UTM 17N) reports the lower-left (SW) corner at
     * 429994 E, 3119994 N -> 81d42'48.03"W, 28d12'13.98"N. We feed the nominal SW corner
     * (430000, 3120000) and expect a match to within the ~6 m collar + NAD83/WGS84 offset.
     */
    @Test
    public void inverseUtmMatchesGdalCorner() {
        double[] ll = DtmNameBounds.Utm.toLonLat(430000.0, 3120000.0, 17);
        double expectedLon = -(81 + 42.0 / 60 + 48.03 / 3600);  // ~ -81.713342
        double expectedLat = 28 + 12.0 / 60 + 13.98 / 3600;     //  ~ 28.203883
        // 6 m collar -> < 1e-4 deg; allow a small tolerance for that plus datum.
        assertEquals(expectedLon, ll[0], 2e-4);
        assertEquals(expectedLat, ll[1], 2e-4);
    }

    @Test
    public void emitsWgs84EwktPolygon() {
        var t = DtmNameBounds.parse(
                "FL_Osceola_2015/TIFF/USGS_one_meter_x43y313_FL_Osceola_2015.tif");
        String wkt = DtmNameBounds.toWgs84Wkt(t);
        assertTrue(wkt, wkt.startsWith("SRID=4326;POLYGON (("));
        assertTrue(wkt, wkt.endsWith("))"));
        // Closed ring: 5 coordinate pairs (SW, SE, NE, NW, SW).
        int pairs = wkt.split(",").length;
        assertEquals(5, pairs);
        // Footprint sits in SW Florida longitudes/latitudes.
        assertTrue(wkt, wkt.contains("-81.7"));
        assertTrue(wkt, wkt.contains("28.2"));
    }
}
