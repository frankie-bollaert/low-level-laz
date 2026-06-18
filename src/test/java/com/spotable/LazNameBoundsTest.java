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

    // ---- projections / Florida State Plane LID tiles ----

    @Test
    public void generalTmInverseMatchesUtmForAUtmPoint() {
        // The generic inverse-TM with UTM parameters must equal DtmNameBounds' UTM inverse.
        double[] a = LazNameBounds.Proj.utm(17).lonLat(507000.0, 3_051_000.0);
        double[] b = DtmNameBounds.Utm.toLonLat(507000.0, 3_051_000.0, 17);
        assertEquals(b[0], a[0], 1e-9);
        assertEquals(b[1], a[1], 1e-9);
    }

    @Test
    public void decodesPeninsularLidTileToMetadataLocation() {
        // 447196_W: idx 447196, West C=549701 -> k=102505 -> row=342, col=95
        //        -> SW (475000 ft, 1710000 ft) State Plane West -> ~(-82.5669, 29.0365).
        var m = LazNameBounds.parseLid(
                "FL_Peninsular_2018_D18/FL_Peninsular_Citrus_2018/LAZ/"
                + "USGS_LPC_FL_Peninsular_2018_D18_LID2019_447196_W.laz");
        assertEquals("FL_Peninsular_Citrus_2018", m.project());   // group = sub-project dir
        double[] sw = m.proj().lonLat(m.swE(), m.swN());
        assertEquals(-82.56694, sw[0], 5e-4);   // metadata W -82.567014 (within the tile collar)
        assertEquals(29.03651, sw[1], 5e-4);    // metadata S  29.036510

        // An East-zone tile from the same family.
        var e = LazNameBounds.parseLid("FL_Peninsular_FDEM_2018_D19_DRRA/x/LAZ/"
                + "USGS_LPC_FL_Peninsular_FDEM_2018_D19_DRRA_LID2019_252706_E.laz");
        double[] esw = e.proj().lonLat(e.swE(), e.swN());
        assertEquals(-80.87876, esw[0], 5e-4);
        assertEquals(28.79012, esw[1], 5e-4);
    }

    @Test
    public void northZoneDecodesViaLambert() {
        // HurricaneMichael 651011_N (State Plane North, EPSG:2238) -> ~(-84.083, 30.360).
        var m = LazNameBounds.parseLid("FL_HurricaneMichael_2020_D20/x/LAZ/"
                + "USGS_LPC_FL_HurricaneMichael_2020_D20_LID2019_651011_N.laz");
        assertTrue(m.proj() instanceof LazNameBounds.LccProj);
        double[] sw = m.proj().lonLat(m.swE(), m.swN());
        assertEquals(-84.08306, sw[0], 5e-4);     // metadata W -84.083056
        assertEquals(30.36040, sw[1], 5e-4);      // metadata S  30.360398
    }

    @Test
    public void osceolaQuadrantDecodes() {
        // 060759_E_A: East C=149767 -> parent SW (460000,1485000) ft, quad A = NW (+0,+2500)
        //          -> 2500-ft tile SW (460000,1487500) ft -> ~(-81.6103, 28.4244).
        var m = LazNameBounds.parseLid("FL_Osceola_2015/FL_Osceola_2015/LAZ/"
                + "USGS_LPC_FL_Osceola_2015_LID2015_060759_E_A.laz");
        assertEquals("FL_Osceola_2015", m.project());
        assertEquals(2500 * 1200.0 / 3937.0, m.fixedTile(), 1e-6);   // 2500-ft tiles
        double[] sw = m.proj().lonLat(m.swE(), m.swN());
        assertEquals(-81.610308, sw[0], 2e-4);
        assertEquals(28.424397, sw[1], 2e-4);
    }

    @Test
    public void suwannee5DigitNorthDecodes() {
        // 64019_N: North C=104051, factor 540 -> SW (2340000,375000) ft -> ~(-83.3260, 30.0259).
        var m = LazNameBounds.parseLid("FL_Suwannee_River_FL_QL2_LiDAR_FY14_14/x/LAZ/"
                + "USGS_LPC_FL_Suwannee_River_FL_QL2_LiDAR_FY14_14_LID2014_64019_N.laz");
        assertTrue(m.proj() instanceof LazNameBounds.LccProj);
        double[] sw = m.proj().lonLat(m.swE(), m.swN());
        assertEquals(-83.326046, sw[0], 2e-4);
        assertEquals(30.025889, sw[1], 2e-4);
    }

    @Test
    public void miamiDadeNoSuffixDecodes() {
        // 317533 (no E/W/N suffix, trailing _0901): East C=349767 -> SW (830000,540000) ft.
        var m = LazNameBounds.parseLid("FL_MiamiDade_D23/x/LAZ/"
                + "USGS_LPC_FL_MiamiDade_D23_LID2024_317533_0901.laz");
        assertTrue(m.proj() instanceof LazNameBounds.TmProj);
        double[] sw = m.proj().lonLat(m.swE(), m.swN());
        assertEquals(-80.471561, sw[0], 2e-4);
        assertEquals(25.818298, sw[1], 2e-4);
    }

    @Test
    public void groupKeyPicksSubProjectDirFromS3Path() {
        String s3 = "s3://spotable-geo-us-west-2/point-cloud/us/FL_Peninsular_FDEM_2018_D19_DRRA/"
                + "FL_Peninsular_FDEM_Alachua_2018/LAZ/"
                + "USGS_LPC_FL_Peninsular_FDEM_2018_D19_DRRA_LID2019_667309_N.laz";
        assertEquals("FL_Peninsular_FDEM_Alachua_2018", LazNameBounds.groupKey(s3));
        // groupDir keeps the full path up to (not including) the LAZ folder.
        assertEquals("s3://spotable-geo-us-west-2/point-cloud/us/FL_Peninsular_FDEM_2018_D19_DRRA/"
                + "FL_Peninsular_FDEM_Alachua_2018", LazNameBounds.groupDir(s3));
    }

    @Test
    public void projectionsReportNativeHorizontalCrs() {
        assertEquals("EPSG:26917", LazNameBounds.Proj.utm(17).epsg());
        assertEquals("NAD83 / UTM zone 17N", LazNameBounds.Proj.utm(17).crs());
        // State Plane North LID tile -> Florida North.
        var n = LazNameBounds.parseLid("FL_HurricaneMichael_2020_D20/x/LAZ/"
                + "USGS_LPC_FL_HurricaneMichael_2020_D20_LID2019_651011_N.laz");
        assertEquals("EPSG:2238", n.proj().epsg());
        assertEquals("NAD83 / Florida North ftUS", n.proj().crs());
        // State Plane West LID tile -> Florida West.
        var w = LazNameBounds.parseLid("FL_Peninsular_2018_D18/x/LAZ/"
                + "USGS_LPC_FL_Peninsular_2018_D18_LID2019_447196_W.laz");
        assertEquals("EPSG:2237", w.proj().epsg());
        assertEquals("NAD83 / Florida West ftUS", w.proj().crs());
    }

    @Test
    public void lidDecodeGatedToConfirmedProjectsAndForms() {
        // Project not on the confirmed-origin allowlist -> not decoded.
        assertNull(LazNameBounds.parseLid("FL_Made_Up_2099/x/LAZ/"
                + "USGS_LPC_FL_Made_Up_2099_LID2019_447196_W.laz"));
        // A Suwannee collection we have not verified -> not decoded.
        assertNull(LazNameBounds.parseLid("FL_Suwannee_River_Lidar_2016_B16/x/LAZ/"
                + "USGS_LPC_FL_Suwannee_River_Lidar_2016_B16_568390_N.laz"));
        // MGRS names are not LID.
        assertNull(LazNameBounds.parseLid("x/USGS_LPC_FL_2017FortDrum_C22_17RNL070510.laz"));
    }
}
