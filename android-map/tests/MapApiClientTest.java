package vn.hptelecom.map;

import org.json.JSONArray;
import org.json.JSONObject;

/** Regressions for map presentation; no login or production server requests. */
public class MapApiClientTest {
    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }
    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; received " + actual);
    }
    private static void assertEquals(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) throw new AssertionError("Unexpected coordinate");
    }
    private JSONObject point(double lat, double lon, String name, int seq) throws Exception {
        return new JSONObject().put("lat", lat).put("lon", lon).put("name", name).put("seq", seq);
    }

    private JSONObject report(String id, long updated, JSONArray points) throws Exception {
        return new JSONObject().put("report_id", id).put("updated", updated)
                .put("member_name", "Worker").put("points", points);
    }

    private JSONObject route(String task, String key, String status, JSONArray members, JSONArray overlays) throws Exception {
        return new JSONObject().put("task_id", task).put("route_key", key).put("task_name", "A-B")
                .put("task_status", status).put("members", members).put("overlays", overlays);
    }

    private JSONArray catalog() throws Exception {
        return new JSONArray().put(new JSONObject().put("name", "A-B").put("link", "DWDM")
                .put("capacity", "8FO").put("length", 1.2));
    }

    public void monthlyRoutesRetainCompletedHistoryAndEmptyRoutes() throws Exception {
        JSONObject oldReport = report("september-report", 1,
                new JSONArray().put(point(12, 108, "Trụ B", 2)).put(point(12.001, 108, "Trụ A", 1)));
        JSONObject currentReport = report("october-report", 2,
                new JSONArray().put(point(12.002, 108, "Trụ C", 1)).put(point(12.003, 108, "Trụ D", 2)));
        JSONObject old = route("september-task", "A-B|West", "completed", new JSONArray().put(oldReport), new JSONArray());
        JSONObject current = route("october-task", "A-B|West", "in_progress", new JSONArray().put(currentReport), new JSONArray());
        JSONObject empty = route("empty-task", "C-D|West", "not_started", new JSONArray(), new JSONArray()).put("task_name", "C-D");
        JSONArray result = MapApiClient.normalizeRoutes(new JSONArray().put(old).put(current).put(empty), catalog());
        assertEquals(2, result.length());
        JSONObject combined = result.getJSONObject(0);
        assertEquals(2, combined.getJSONArray("lines").length());
        assertEquals(12.001, combined.getJSONArray("lines").getJSONArray(0).getJSONArray(0).getDouble(0), 0);
        assertEquals("DWDM", combined.getString("connection_type"));
        assertEquals("8FO", combined.getString("capacity"));
        assertEquals(0, result.getJSONObject(1).getJSONArray("lines").length());
        assertEquals("C-D", result.getJSONObject(1).getString("name"));
    }

    public void repeatedMonthlyOverlayIsOneObservationAndMxDoesNotChainBeyondTwentyMetres() throws Exception {
        JSONObject newest = report("incident1", 3, new JSONArray().put(point(12.002, 108, "MX", 1))).put("source", "incident");
        JSONObject near = report("incident2", 2, new JSONArray().put(point(12.0021, 108, "MX", 1))).put("source", "incident");
        JSONObject farther = report("incident3", 1, new JSONArray().put(point(12.0022, 108, "MX", 1))).put("source", "incident");
        JSONArray overlays = new JSONArray().put(newest).put(near).put(farther);
        JSONObject first = route("first-month", "A-B|West", "completed", new JSONArray(), overlays);
        JSONObject second = route("second-month", "A-B|West", "completed", new JSONArray(), overlays);
        JSONObject result = MapApiClient.normalizeRoutes(new JSONArray().put(first).put(second), catalog()).getJSONObject(0);
        JSONArray markers = result.getJSONArray("markers");
        assertEquals(2, markers.length()); // Neighbours 11m apart, endpoints 22m apart.
        int reports = 0;
        for (int i = 0; i < markers.length(); i++) {
            assertEquals("MX", markers.getJSONObject(i).getString("label"));
            reports += markers.getJSONObject(i).getInt("history_count");
        }
        assertEquals(3, reports);
        assertEquals(0, result.getJSONArray("lines").length()); // Incident observations are not patrol lines.
    }

    public void sameNameAcrossTeamsNeverMergesOrGuessesCatalogMetadata() throws Exception {
        JSONObject member = report("a", 1,
                new JSONArray().put(point(12, 108, "Vị trí A", 1)).put(point(12.01, 108, "Vị trí B", 2)));
        JSONObject west = route("west", "A-B|West", "completed", new JSONArray().put(member), new JSONArray());
        JSONObject east = route("east", "A-B|East", "completed", new JSONArray().put(member), new JSONArray());
        JSONArray result = MapApiClient.normalizeRoutes(new JSONArray().put(west).put(east), catalog());
        assertEquals(2, result.length());
        for (int i = 0; i < result.length(); i++) {
            assertTrue(result.getJSONObject(i).getString("connection_type").isEmpty());
            assertTrue(result.getJSONObject(i).getString("capacity").isEmpty());
            JSONArray markers = result.getJSONObject(i).getJSONArray("markers");
            assertEquals(2, markers.length());
            assertTrue(markers.getJSONObject(0).getString("label").startsWith("Vị trí"));
            assertTrue(markers.getJSONObject(1).getString("label").startsWith("Vị trí"));
        }
    }

    public void emptyServerDoesNotInventExampleRoutes() throws Exception {
        assertEquals(0, MapApiClient.normalizeRoutes(new JSONArray(), catalog()).length());
    }
    public void patrolGpsBadgesAreHiddenWithoutDroppingGeometryOrExportPoints() throws Exception {
        // The mobile map API sends seq/name/note for every patrol photo. A named
        // intermediate photo is still a route sample, not an admin-created point.
        JSONArray raw = new JSONArray()
                .put(point(999, 108, "Invalid start", 0))
                .put(point(12, 108, "ODF A", 1))
                .put(point(12.001, 108, "", 2))
                .put(point(12.002, 108, "Trụ 17", 3))
                .put(point(12.003, 108, "Dự phòng", 4))
                .put(point(12.004, 108, "Măng xông", 5))
                .put(point(12.005, 108, "", 6))
                .put(point(999, 108, "Invalid end", 7));
        JSONObject result = MapApiClient.normalizeRoutes(new JSONArray().put(route("task", "A-B|West",
                "completed", new JSONArray().put(report("patrol", 1, raw)), new JSONArray())), catalog()).getJSONObject(0);
        JSONArray markers = result.getJSONArray("markers"), line = result.getJSONArray("lines").getJSONArray(0);
        assertEquals(6, line.length());
        assertEquals(6, markers.length()); // Hidden markers remain available to KMZ export.
        int visible = 0, endpoints = 0, hidden = 0;
        for (int i = 0; i < markers.length(); i++) {
            JSONObject marker = markers.getJSONObject(i);
            if (marker.getBoolean("display_on_map")) visible++; else hidden++;
            if (marker.getBoolean("is_endpoint")) {
                endpoints++;
                assertTrue(marker.getBoolean("display_on_map"));
                assertTrue(!marker.getString("label").isEmpty());
            }
            if (marker.getString("name").equals("Trụ 17")) assertTrue(!marker.getBoolean("display_on_map"));
            if (marker.getString("kind").equals("MX") || marker.getString("kind").equals("DP"))
                assertTrue(marker.getBoolean("display_on_map"));
            if (marker.getDouble("lat") == 12.005) assertEquals("Vị trí 6", marker.getString("label"));
        }
        assertEquals(4, visible);
        assertEquals(2, hidden);
        assertEquals(2, endpoints);
    }
    public void olderEndpointRetainsItsLabelWhenLatestReportPassesThroughThatPoint() throws Exception {
        JSONObject old = report("old", 1, new JSONArray()
                .put(point(12.001, 108, "ODF A", 1)).put(point(12.01, 108, "ODF B", 2)));
        JSONObject latest = report("latest", 2, new JSONArray()
                .put(point(12, 108, "ODF C", 1)).put(point(12.001, 108, "", 2)).put(point(12.02, 108, "ODF D", 3)));
        JSONArray markers = MapApiClient.normalizeRoutes(new JSONArray().put(route("task", "A-B|West",
                "completed", new JSONArray().put(old).put(latest), new JSONArray())), catalog())
                .getJSONObject(0).getJSONArray("markers");
        boolean found = false;
        for (int i = 0; i < markers.length(); i++) {
            JSONObject marker = markers.getJSONObject(i);
            if (marker.getDouble("lat") == 12.001) {
                found = true;
                assertTrue(marker.getBoolean("display_on_map"));
                assertTrue(marker.getBoolean("is_endpoint"));
                assertEquals("ODF A", marker.getString("label"));
                assertEquals(2, marker.getInt("history_count"));
            }
        }
        assertTrue(found);
    }
    public void sharedFoldersKeepHierarchyAndConvertGeoJsonCoordinateOrder() throws Exception {
        JSONObject source = new JSONObject().put("layers", new JSONArray().put(new JSONObject()
                .put("team", "West").put("revision", 2)
                .put("folders", new JSONArray()
                        .put(new JSONObject().put("id", "root").put("name", "Root").put("parent_id", JSONObject.NULL))
                        .put(new JSONObject().put("id", "child").put("name", "MX").put("parent_id", "root")))
                .put("features", new JSONArray()
                        .put(new JSONObject().put("id", "p").put("folder_id", "child").put("name", "MX 1")
                                .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(108).put(12))))
                        .put(new JSONObject().put("id", "l").put("folder_id", "root").put("name", "Cable")
                                .put("geometry", new JSONObject().put("type", "LineString").put("coordinates",
                                        new JSONArray().put(new JSONArray().put(108).put(12)).put(new JSONArray().put(108.1).put(12.1))))))));
        JSONArray result = MapApiClient.normalizeSharedLayers(source);
        assertEquals(2, result.length());
        assertEquals("server:West:root", result.getJSONObject(1).getString("parent_id"));
        assertEquals("server", result.getJSONObject(0).getString("source_type"));
        assertEquals(12.0, result.getJSONObject(0).getJSONArray("lines").getJSONArray(0).getJSONArray(0).getDouble(0), 0);
        assertEquals(108.0, result.getJSONObject(1).getJSONArray("markers").getJSONObject(0).getDouble("lon"), 0);
        assertEquals("MX", result.getJSONObject(1).getJSONArray("markers").getJSONObject(0).getString("label"));
        assertTrue(result.getJSONObject(1).getJSONArray("markers").getJSONObject(0).getBoolean("display_on_map"));
    }
    public void adminCreatedGenericAndNamedPointsRemainVisible() throws Exception {
        JSONArray features = new JSONArray();
        for (String name : new String[]{"GPS", "Tủ cáp mới", ""}) {
            features.put(new JSONObject().put("id", "p" + features.length()).put("folder_id", "root").put("name", name)
                    .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(108).put(12))));
        }
        JSONObject response = new JSONObject().put("layers", new JSONArray().put(new JSONObject()
                .put("team", "West")
                .put("folders", new JSONArray().put(new JSONObject().put("id", "root").put("name", "Imported KML").put("parent_id", JSONObject.NULL)))
                .put("features", features)));
        JSONArray markers = MapApiClient.normalizeSharedLayers(response).getJSONObject(0).getJSONArray("markers");
        assertEquals(3, markers.length());
        for (int i = 0; i < markers.length(); i++) {
            assertTrue(markers.getJSONObject(i).getBoolean("display_on_map"));
            assertTrue(!markers.getJSONObject(i).getBoolean("is_endpoint"));
            assertTrue(!markers.getJSONObject(i).getString("label").isEmpty());
        }
        assertEquals("Tủ cáp mới", markers.getJSONObject(1).getString("label"));
    }
    public static void main(String[] args) throws Exception {
        MapApiClientTest test = new MapApiClientTest();
        test.monthlyRoutesRetainCompletedHistoryAndEmptyRoutes();
        test.repeatedMonthlyOverlayIsOneObservationAndMxDoesNotChainBeyondTwentyMetres();
        test.sameNameAcrossTeamsNeverMergesOrGuessesCatalogMetadata();
        test.emptyServerDoesNotInventExampleRoutes();
        test.patrolGpsBadgesAreHiddenWithoutDroppingGeometryOrExportPoints();
        test.olderEndpointRetainsItsLabelWhenLatestReportPassesThroughThatPoint();
        test.sharedFoldersKeepHierarchyAndConvertGeoJsonCoordinateOrder();
        test.adminCreatedGenericAndNamedPointsRemainVisible();
        System.out.println("PASS: monthly history, catalog, named endpoints, hidden intermediate GPS with intact geometry/export points, admin point visibility, 20m MX, team isolation and shared server geometry.");
    }
}
