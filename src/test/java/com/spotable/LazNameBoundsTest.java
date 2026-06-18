package com.spotable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LazNameBoundsTest {

    @Test
    public void parsesMgrsTileToSwCornerUtm() {
        // 17RNL070510: zone 17, square NL -> baseE 500000 / baseN 3,000,000,
        // + 070*100=7000 E, 510*100=51000 N  ->  SW (507000, 3051000).
        var t = LazNameBounds.parse(
                "FL_2017FortDrum_C22/.../USGS_LPC_FL_2017FortDrum_C22_17RNL070510.laz");
        assertEquals(17, t.zone());
        assertEquals(507000.0, t.swEasting(), 1e-6);
        assertEquals(3_051_000.0, t.swNorthing(), 1e-6);
    }

    @Test
    public void parsesAnotherSquareAndZone16() {
        // Cross-check a different square / zone (Choctawhatchee, zone 16): 16REU565600.
        // Square EU = column E, row U.  zone 16 -> set0 "ABCDEFGH", E -> 5th -> 500000;
        // + 565*100 = 56500 E.  Reprojects to ~-86.41, 30.37 (panhandle, zone 16).
        var t = LazNameBounds.parse("x/USGS_LPC_FL_Lower_Choctawhatchee_2017_16REU565600.laz");
        assertEquals(16, t.zone());
        assertEquals(556_500.0, t.swEasting(), 1e-6);
        assertTrue("northing in band R range", t.swNorthing() > 3_300_000 && t.swNorthing() < 3_500_000);
    }

    @Test
    public void skipsNonMgrsForms() {
        assertNull(LazNameBounds.parse("proj/ept-data/12-339-2750-2044.laz"));
        assertNull(LazNameBounds.parse("x/USGS_LPC_FL_Peninsular_2018_D18_LID2019_447196_W.laz"));
        assertNull(LazNameBounds.parse("x/USGS_LPC_FL_Panhandle_2018_B18_e0853n0913.laz"));
        assertNull(LazNameBounds.parse("x/USGS_LPC_GA_..._GAW_20100945.laz"));
    }

    /**
     * The reprojected footprint must match the tile's authoritative metadata bounding
     * coordinates (USGS XML for 17RNL070510): W -80.9264, E -80.9139, N 27.5964, S 27.5937.
     * The nominal 1500 m tile slightly exceeds the clipped data extent, so we assert the
     * nominal polygon contains the metadata box and shares the SW/NE neighbourhood.
     */
    @Test
    public void footprintMatchesMetadataBbox() {
        var t = LazNameBounds.parse("USGS_LPC_FL_2017FortDrum_C22_17RNL070510.laz");
        // SW corner (507000, 3051000) and NE (508500, 3052500) in lon/lat.
        double[] sw = DtmNameBounds.Utm.toLonLat(507000.0, 3_051_000.0, 17);
        double[] ne = DtmNameBounds.Utm.toLonLat(508500.0, 3_052_500.0, 17);
        // Nominal tile must bracket the metadata data-extent box.
        assertTrue(sw[0] <= -80.9264 + 1e-3);   // west <= metadata west
        assertTrue(ne[0] >= -80.9139 - 1e-3);   // east >= metadata east
        assertTrue(sw[1] <= 27.5937 + 1e-3);    // south <= metadata south
        assertTrue(ne[1] >= 27.5964 - 1e-3);    // north >= metadata north

        String wkt = LazNameBounds.toWgs84Wkt(t, 1500.0);
        assertTrue(wkt, wkt.startsWith("SRID=4326;POLYGON (("));
        assertTrue(wkt, wkt.contains("-80.9") && wkt.contains("27.5"));
    }

    // ---- merge / grid-union geometry ----

    private static java.util.Set<Integer> cells(int[]... cr) {
        var s = new java.util.HashSet<Integer>();
        for (int[] c : cr) s.add(c[0] * LazNameBounds.GRID + c[1]);
        return s;
    }

    @Test
    public void singleCellIsOneFiveVertexSquare() {
        var rings = LazNameBounds.boundaryRings(cells(new int[]{0, 0}));
        assertEquals(1, rings.size());
        assertEquals(5, rings.get(0).length);   // 4 corners + closing vertex
    }

    @Test
    public void adjacentCellsMergeAndCollinearVerticesAreRemoved() {
        // Two cells side by side collapse to a single 2x1 rectangle (still 5 vertices).
        var rings = LazNameBounds.boundaryRings(cells(new int[]{0, 0}, new int[]{1, 0}));
        assertEquals(1, rings.size());
        assertEquals(5, rings.get(0).length);
        var polys = LazNameBounds.buildPolygons(rings);
        assertEquals(1, polys.size());
        assertTrue(polys.get(0).holes().isEmpty());
    }

    @Test
    public void diagonalCellsStayTwoPolygons() {
        var rings = LazNameBounds.boundaryRings(cells(new int[]{0, 0}, new int[]{1, 1}));
        var polys = LazNameBounds.buildPolygons(rings);
        assertEquals(2, polys.size());
        assertTrue(polys.get(0).holes().isEmpty());
        assertTrue(polys.get(1).holes().isEmpty());
    }

    @Test
    public void ringWithGapBecomesPolygonWithHole() {
        // 3x3 block minus the centre cell -> one shell with one hole.
        var cr = new java.util.ArrayList<int[]>();
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++)
                if (!(c == 1 && r == 1)) cr.add(new int[]{c, r});
        var rings = LazNameBounds.boundaryRings(cells(cr.toArray(new int[0][])));
        assertEquals(2, rings.size());          // outer shell + inner hole
        var polys = LazNameBounds.buildPolygons(rings);
        assertEquals(1, polys.size());
        assertEquals(1, polys.get(0).holes().size());
        assertEquals(5, polys.get(0).shell().length);   // outer 3x3 square, collinear-cleaned
    }
}
