package com.spotable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import org.junit.Test;

/**
 * Exercises the EWKT &rarr; GeoJSON conversion in {@link GeoJson} (geometry parsing, property
 * typing, and the FeatureCollection envelope) with no I/O.
 */
public class GeoJsonTest {

    @Test
    public void multiPolygonGeometryDropsSridAndNestsCorrectly() {
        String ewkt = "SRID=4326;MULTIPOLYGON (((-80.1 27.4, -80.2 27.5, -80.3 27.6, -80.1 27.4)))";
        assertEquals(
                "{\"type\": \"MultiPolygon\", \"coordinates\": "
                        + "[[[[-80.1,27.4],[-80.2,27.5],[-80.3,27.6],[-80.1,27.4]]]]}",
                GeoJson.geometry(ewkt));
    }

    @Test
    public void polygonWithHoleKeepsBothRings() {
        String ewkt = "POLYGON ((0 0, 4 0, 4 4, 0 4, 0 0), (1 1, 2 1, 2 2, 1 1))";
        assertEquals(
                "{\"type\": \"Polygon\", \"coordinates\": "
                        + "[[[0,0],[4,0],[4,4],[0,4],[0,0]],[[1,1],[2,1],[2,2],[1,1]]]}",
                GeoJson.geometry(ewkt));
    }

    @Test
    public void emptyOrUnsupportedGeometryIsJsonNull() {
        assertEquals("null", GeoJson.geometry(""));
        assertEquals("null", GeoJson.geometry(null));
        assertEquals("null", GeoJson.geometry("SRID=4326;POINT (1 2)"));
    }

    @Test
    public void featureCollectionTypesPropertiesAndCarriesGeometry() throws IOException {
        List<String> columns = List.of("project", "files", "resolution_m", "vertical_epsg", "geometry");
        List<List<String>> rows = List.of(
                List.of("FL_Test", "9", "1.0000", "", "SRID=4326;MULTIPOLYGON (((0 0, 1 0, 1 1, 0 0)))"));
        StringWriter sw = new StringWriter();
        GeoJson.writeFeatureCollection(columns, rows, sw);
        String out = sw.toString();

        assertTrue(out.startsWith("{\n\"type\": \"FeatureCollection\",\n\"features\": [\n"));
        assertTrue(out.trim().endsWith("]\n}"));
        // Strings quoted, numbers bare, empty -> null, geometry not a property.
        assertTrue(out.contains("\"project\": \"FL_Test\""));
        assertTrue(out.contains("\"files\": 9"));
        assertTrue(out.contains("\"resolution_m\": 1.0000"));
        assertTrue(out.contains("\"vertical_epsg\": null"));
        assertTrue(out.contains("\"geometry\": {\"type\": \"MultiPolygon\""));
    }

    @Test
    public void epsgCodeStaysAStringNotANumber() {
        // "EPSG:26917" must not be mistaken for a number.
        List<String> columns = List.of("horizontal_epsg", "geometry");
        List<List<String>> rows = List.of(List.of("EPSG:26917", ""));
        StringWriter sw = new StringWriter();
        try {
            GeoJson.writeFeatureCollection(columns, rows, sw);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertTrue(sw.toString().contains("\"horizontal_epsg\": \"EPSG:26917\""));
        assertTrue(sw.toString().contains("\"geometry\": null"));
    }
}
