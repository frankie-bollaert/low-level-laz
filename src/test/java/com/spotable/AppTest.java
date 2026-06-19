package com.spotable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

/**
 * Unit test for simple App.
 */
public class AppTest
{
    /**
     * Rigorous Test :-)
     */
    @Test
    public void shouldAnswerWithTrue()
    {
        assertTrue( true );
    }

    private static Integer wktEpsg(String wkt) {
        return GeoCrs.horizontalEpsg(wkt);
    }

    /**
     * A compound CRS pairs a horizontal CRS with a vertical (height) one. The
     * horizontal EPSG must win — not the vertical EPSG that appears last in the WKT.
     * This is the real shape of the USGS Hurricane Michael 2020 tiles: Florida North
     * (ftUS) horizontal + NAVD88 height (ftUS) vertical.
     */
    @Test
    public void compoundCrsPrefersHorizontalNotVertical() throws Exception {
        String wkt =
            "COMPD_CS[\"NAD83(2011) / Florida North (ftUS) + NAVD88 height (ftUS)\","
            + "PROJCS[\"NAD83(2011) / Florida North (ftUS)\","
            + "GEOGCS[\"NAD83(2011)\",DATUM[\"NAD83_National_Spatial_Reference_System_2011\","
            + "SPHEROID[\"GRS 1980\",6378137,298.257222101,AUTHORITY[\"EPSG\",\"7019\"]],"
            + "AUTHORITY[\"EPSG\",\"1116\"]],AUTHORITY[\"EPSG\",\"6318\"]],"
            + "AUTHORITY[\"EPSG\",\"6441\"]],"
            + "VERT_CS[\"NAVD88 height (ftUS)\",AUTHORITY[\"EPSG\",\"6360\"]]]";
        assertEquals(Integer.valueOf(6441), wktEpsg(wkt));
    }

    /** A plain projected CRS still returns its own (last) EPSG code. */
    @Test
    public void plainProjectedCrsReturnsItsCode() throws Exception {
        String wkt =
            "PROJCS[\"NAD83 / UTM zone 17N\","
            + "GEOGCS[\"NAD83\",AUTHORITY[\"EPSG\",\"4269\"]],"
            + "AUTHORITY[\"EPSG\",\"26917\"]]";
        assertEquals(Integer.valueOf(26917), wktEpsg(wkt));
    }

    /** WKT2 keywords (PROJCRS/GEOGCRS, ID[...]) are handled too. */
    @Test
    public void wkt2ProjectedCrs() throws Exception {
        String wkt =
            "PROJCRS[\"NAD83(2011) / Florida North (ftUS)\","
            + "BASEGEOGCRS[\"NAD83(2011)\",ID[\"EPSG\",6318]],"
            + "ID[\"EPSG\",6441]]";
        assertEquals(Integer.valueOf(6441), wktEpsg(wkt));
    }

    /** A geographic-only CRS falls through to the GEOGCS code. */
    @Test
    public void geographicOnlyCrs() throws Exception {
        String wkt = "GEOGCS[\"WGS 84\",AUTHORITY[\"EPSG\",\"4326\"]]";
        assertEquals(Integer.valueOf(4326), wktEpsg(wkt));
    }

    /** Fields are double-quoted; the comma-laden WKT stays a single field. */
    @Test
    public void csvRowQuotesFields() {
        assertEquals(
            "\"s3://bucket/tile.laz\",\"SRID=6441;POLYGON ((1 2, 3 4))\"",
            LazBinaryReader.csvRow("s3://bucket/tile.laz", "SRID=6441;POLYGON ((1 2, 3 4))"));
    }

    /** Embedded double quotes are doubled per RFC 4180. */
    @Test
    public void csvRowEscapesEmbeddedQuotes() {
        assertEquals("\"a \"\"b\"\" c\"", LazBinaryReader.csvRow("a \"b\" c"));
    }
}
