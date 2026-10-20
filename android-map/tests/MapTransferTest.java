package vn.hptelecom.map;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

/** Offline tests only: fake HTTPS responses, fictional folders, no account or live writes. */
public final class MapTransferTest {
    private static final String TOKEN = "test-session-token-not-a-real-user";
    private static final ArrayDeque<Response> responses = new ArrayDeque<>();
    private static final List<FakeConnection> connections = new ArrayList<>();
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static IOException rejection(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IOException error) {
            check(error.getMessage() != null && !error.getMessage().isEmpty(), "Readable rejection required");
            check(!error.getMessage().contains(TOKEN), "Credentials leaked into error");
            return error;
        }
        throw new AssertionError("Expected rejection");
    }
    private static void status(int expected, Attempt attempt) throws Exception {
        IOException error = rejection(attempt);
        check(error instanceof MapApiClient.ApiException && ((MapApiClient.ApiException)error).status == expected,
                "Expected HTTP status " + expected + ", received " + error);
    }
    private static void reset(Response... planned) {
        responses.clear(); connections.clear();
        for (Response response : planned) responses.add(response);
    }
    private static JSONObject data() throws Exception {
        JSONArray folders = new JSONArray()
                .put(new JSONObject().put("id", "source").put("name", "Nguồn").put("parent_id", JSONObject.NULL).put("color", "#008080"))
                .put(new JSONObject().put("id", "target").put("name", "Đích").put("parent_id", "source").put("color", "#123456"));
        JSONArray features = new JSONArray()
                .put(new JSONObject().put("id", "point").put("folder_id", "source").put("name", "MX 17").put("note", "Ghi chú điểm").put("color", "#123456")
                        .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(108.123).put(12.456))))
                .put(new JSONObject().put("id", "line").put("folder_id", "source").put("name", "Cáp 6FO").put("note", "Ghi chú tuyến").put("color", "#ff0000")
                        .put("geometry", new JSONObject().put("type", "LineString").put("coordinates", new JSONArray()
                                .put(new JSONArray().put(108.123).put(12.456)).put(new JSONArray().put(108.234).put(12.567)))))
                .put(new JSONObject().put("id", "unselected").put("folder_id", "source").put("name", "Không chọn")
                        .put("geometry", new JSONObject().put("type", "Point").put("coordinates", new JSONArray().put(108.9).put(12.9))));
        JSONObject west = new JSONObject().put("team", "West").put("revision", 7).put("folders", folders).put("features", features);
        // Same IDs in another team must never be copied into the selected scope.
        JSONObject east = new JSONObject(west.toString()).put("team", "East").put("revision", 2);
        east.getJSONArray("features").getJSONObject(0).put("name", "Other team private marker");
        return new JSONObject().put("layers", new JSONArray().put(west).put(east));
    }
    private static JSONObject args(String mode) throws Exception {
        return new JSONObject().put("team", "West").put("featureIds", new JSONArray().put("point").put("line"))
                .put("targetFolderId", "target").put("mode", mode).put("revision", 7);
    }
    private static JSONObject feature(JSONArray features, String id) throws Exception {
        for (int i = 0; i < features.length(); i++) if (id.equals(features.getJSONObject(i).getString("id"))) return features.getJSONObject(i);
        throw new AssertionError("Missing feature " + id);
    }
    private static void pureTransformsPreserveSelectionGeometryAndScope() throws Exception {
        JSONObject source = data(); String before = source.toString();
        JSONObject copy = MapApiClient.prepareSharedTransfer(source, args("copy"));
        JSONObject body = copy.getJSONObject("body"); JSONArray originals = source.getJSONArray("layers").getJSONObject(0).getJSONArray("features");
        JSONArray copied = body.getJSONArray("features"), ids = copy.getJSONArray("resultIds");
        check(copied.length() == 5 && ids.length() == 2, "Copy must add only selected point and line");
        check(body.getString("team").equals("West") && body.getLong("revision") == 7, "Scope/revision changed");
        check(body.getJSONArray("folders").toString().equals(source.getJSONArray("layers").getJSONObject(0).getJSONArray("folders").toString()), "Folder hierarchy changed");
        for (int i = 0; i < 3; i++) check(copied.getJSONObject(i).toString().equals(originals.getJSONObject(i).toString()), "Copy mutated original");
        Set<String> allIds = new HashSet<>();
        for (int i = 0; i < copied.length(); i++) check(allIds.add(copied.getJSONObject(i).getString("id")), "Copied IDs collided");
        for (int i = 0; i < 2; i++) {
            JSONObject changed = feature(copied, ids.getString(i)), original = originals.getJSONObject(i);
            check(changed.getString("folder_id").equals("target"), "Wrong target");
            check(changed.getString("id").matches("[0-9a-f-]{36}"), "Copy needs a new UUID");
            for (String key : new String[]{"name", "note", "color", "geometry"}) check(changed.get(key).toString().equals(original.get(key).toString()), "Copy lost " + key);
        }
        JSONObject move = MapApiClient.prepareSharedTransfer(source, args("move"));
        JSONArray moved = move.getJSONObject("body").getJSONArray("features");
        check(moved.length() == 3 && move.getJSONObject("transfer").getInt("count") == 2, "Move count incorrect");
        for (String id : new String[]{"point", "line"}) {
            JSONObject changed = feature(moved, id), original = feature(originals, id);
            check(changed.getString("folder_id").equals("target"), "Move did not preserve IDs into target");
            for (String key : new String[]{"name", "note", "color", "geometry"}) check(changed.get(key).toString().equals(original.get(key).toString()), "Move lost " + key);
        }
        check(feature(moved, "unselected").toString().equals(feature(originals, "unselected").toString()), "Move changed unselected object");
        check(source.toString().equals(before), "Pure transform mutated source");
        JSONObject noop = MapApiClient.prepareSharedTransfer(source, args("move").put("targetFolderId", "source"));
        check(noop.getJSONObject("transfer").getBoolean("noop") && noop.getJSONObject("transfer").getInt("count") == 0, "Same-folder move must be no-op");
    }
    private static void invalidSelectionAndStaleDataAreRejectedBeforeSave() throws Exception {
        status(409, () -> MapApiClient.prepareSharedTransfer(data(), args("copy").put("revision", 6)));
        status(409, () -> MapApiClient.prepareSharedTransfer(data(), args("copy").put("team", "Missing")));
        status(409, () -> MapApiClient.prepareSharedTransfer(data(), args("copy").put("targetFolderId", "deleted")));
        status(409, () -> MapApiClient.prepareSharedTransfer(data(), args("copy").put("featureIds", new JSONArray().put("deleted"))));
        for (JSONObject bad : new JSONObject[]{
                args("copy").put("featureIds", new JSONArray()), args("copy").put("featureIds", new JSONArray().put("point").put("point")),
                args("copy").put("featureIds", new JSONArray().put("https://outside.invalid/point")),
                args("copy").put("revision", "7"), args("copy").put("revision", -1), args("copy").put("revision", 7.5),
                args("delete"), args("copy").put("targetFolderId", "server:West:target"), args("copy").put("team", " West ")})
            rejection(() -> MapApiClient.prepareSharedTransfer(data(), bad));
        JSONObject full = data(); JSONArray features = full.getJSONArray("layers").getJSONObject(0).getJSONArray("features");
        for (int i = features.length(); i < 10000; i++) features.put(new JSONObject(features.getJSONObject(0).toString()).put("id", "p" + i));
        rejection(() -> MapApiClient.prepareSharedTransfer(full, args("copy")));
    }
    private static MapApiClient client() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("token", TOKEN); prefs.values.put("expires_at", System.currentTimeMillis() / 1000 + 3600);
        return new MapApiClient(new Context() {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                check("hp-map-server-session".equals(name), "Wrong session namespace"); return prefs;
            }
        });
    }
    private static Response me(String role) { return new Response("/api/me", 200, "{\"role\":\"" + role + "\",\"team\":\"West\",\"username\":\"test\"}"); }
    private static void queueRefresh(String role, JSONObject data) {
        responses.add(me(role)); responses.add(new Response("/api/mobile/cable-tasks", 200, "{\"tasks\":[]}"));
        responses.add(new Response("/api/mobile/route-catalog", 200, "{\"rows\":[]}"));
        responses.add(new Response("/api/map-layers", 200, data.toString()));
    }
    private static MapApiClient adminClient() throws Exception {
        reset(); MapApiClient client = client(); queueRefresh("admin", data()); client.session();
        check(client.isAdmin() && responses.isEmpty(), "Admin must come from verified /me"); connections.clear(); return client;
    }
    private static void permissionsAreCheckedAgainAndRevisionConflictsNeverWrite() throws Exception {
        reset(); MapApiClient unverified = client(); status(403, () -> unverified.transferSharedFeatures(args("copy")));
        check(connections.isEmpty(), "Unverified cached session wrote data");
        MapApiClient demoted = adminClient(); responses.add(me("staff"));
        status(403, () -> demoted.transferSharedFeatures(args("copy")));
        check(connections.size() == 1 && !demoted.isAdmin(), "Demotion was not revalidated before write");
        MapApiClient expired = adminClient(); responses.add(new Response("/api/me", 401, "{\"error\":\"Session expired\"}"));
        status(401, () -> expired.transferSharedFeatures(args("move")));
        check(!expired.isAdmin() && expired.session().isNull("user"), "Expired session was retained");
        MapApiClient stale = adminClient(); responses.add(me("admin")); responses.add(new Response("/api/map-layers", 200, data().toString()));
        status(409, () -> stale.transferSharedFeatures(args("copy").put("revision", 6)));
        check(connections.size() == 2, "Stale selection reached POST");
        MapApiClient conflict = adminClient(); responses.add(me("admin")); responses.add(new Response("/api/map-layers", 200, data().toString()));
        responses.add(new Response("/api/map-layers/save", 409, "{\"error\":\"Changed concurrently\"}"));
        status(409, () -> conflict.transferSharedFeatures(args("move")));
        check(connections.size() == 3 && posts() == 1, "Conflict must not retry");
    }
    private static int posts() { int n = 0; for (FakeConnection c : connections) if ("POST".equals(c.getRequestMethod())) n++; return n; }
    private static JSONObject savedData(JSONObject body) throws Exception {
        return new JSONObject().put("layers", new JSONArray().put(new JSONObject(body.toString()).put("revision", body.getLong("revision") + 1)));
    }
    private static void successfulMoveReturnsFreshSessionAndNoopDoesNotWrite() throws Exception {
        MapApiClient client = adminClient(); JSONObject body = MapApiClient.prepareSharedTransfer(data(), args("move")).getJSONObject("body");
        JSONObject saved = savedData(body);
        responses.add(me("admin")); responses.add(new Response("/api/map-layers", 200, data().toString()));
        responses.add(new Response("/api/map-layers/save", 200, saved.toString())); queueRefresh("admin", saved);
        JSONObject result = client.transferSharedFeatures(args("move"));
        check(result.getJSONObject("transfer").getBoolean("saved") && result.getJSONObject("transfer").getInt("count") == 2, "Save result missing");
        check(result.getJSONArray("sharedLayers").getJSONObject(0).getLong("revision") == 8, "Returned map is not refreshed");
        check(posts() == 1 && responses.isEmpty(), "Unexpected retries/network requests");
        JSONObject submitted = new JSONObject(connections.get(2).output.toString(StandardCharsets.UTF_8.name()));
        check(submitted.toString().equals(body.toString()), "Submitted payload changed beyond pure transform");
        for (FakeConnection c : connections) {
            check(c.getURL().getHost().equals("hptelecom.id.vn") && c.getURL().getQuery() == null, "Arbitrary origin/query used");
            check(!c.getInstanceFollowRedirects() && c.getConnectTimeout() > 0 && c.getReadTimeout() > 0, "Unsafe network policy");
            check(("Bearer " + TOKEN).equals(c.getRequestProperty("Authorization")), "Missing authenticated request");
            check(c.disconnected, "Connection leaked");
        }
        client = adminClient(); responses.add(me("admin")); responses.add(new Response("/api/map-layers", 200, data().toString())); queueRefresh("admin", data());
        result = client.transferSharedFeatures(args("move").put("targetFolderId", "source"));
        check(result.getJSONObject("transfer").getBoolean("noop") && posts() == 0 && responses.isEmpty(), "Same-folder move unnecessarily wrote");
    }
    private static void lostOrInvalidSaveResponseNeverInvitesBlindCopyRetry() throws Exception {
        for (Response failure : new Response[]{new Response("/api/map-layers/save", new IOException("lost response")),
                new Response("/api/map-layers/save", 500, "{}"), new Response("/api/map-layers/save", 200, "not json"),
                new Response("/api/map-layers/save", 200, "{\"layers\":[]}"), new Response("/api/map-layers/save", 200, "{}")}) {
            MapApiClient client = adminClient(); responses.add(me("admin")); responses.add(new Response("/api/map-layers", 200, data().toString())); responses.add(failure);
            JSONObject result = client.transferSharedFeatures(args("copy"));
            check(result.getBoolean("ok") && result.getBoolean("refreshRequired"), "Ambiguous commit must require refresh");
            JSONObject transfer = result.getJSONObject("transfer");
            check(transfer.isNull("saved") && transfer.getBoolean("uncertain"), "Ambiguous commit claimed success/failure");
            check(result.getString("warning").contains("kiểm tra thư mục đích"), "Must explain inspecting destination before retry");
            check(posts() == 1 && responses.isEmpty(), "Ambiguous save auto-retried");
        }
    }
    private static void confirmedSaveSurvivesRefreshFailureWithoutDuplicateRetry() throws Exception {
        MapApiClient client = adminClient(); JSONObject body = MapApiClient.prepareSharedTransfer(data(), args("move")).getJSONObject("body");
        responses.add(me("admin")); responses.add(new Response("/api/map-layers", 200, data().toString()));
        responses.add(new Response("/api/map-layers/save", 200, savedData(body).toString())); responses.add(new Response("/api/me", new IOException("offline after save")));
        JSONObject result = client.transferSharedFeatures(args("move"));
        check(result.getBoolean("refreshRequired") && result.getJSONObject("transfer").getBoolean("saved"), "Confirmed save was lost after refresh failure");
        check(!result.getJSONObject("transfer").getBoolean("uncertain") && result.getString("warning").contains("không thực hiện lại"), "User must not repeat confirmed transfer");
        check(posts() == 1 && responses.isEmpty(), "Refresh failure repeated save");
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                if (responses.isEmpty()) throw new AssertionError("Unexpected network request " + url.getPath());
                Response response = responses.remove(); check(url.getPath().equals(response.path), "Expected " + response.path + ", got " + url.getPath());
                FakeConnection connection = new FakeConnection(url, response); connections.add(connection); return connection;
            }
        } : null);
        pureTransformsPreserveSelectionGeometryAndScope(); invalidSelectionAndStaleDataAreRejectedBeforeSave();
        permissionsAreCheckedAgainAndRevisionConflictsNeverWrite(); successfulMoveReturnsFreshSessionAndNoopDoesNotWrite();
        lostOrInvalidSaveResponseNeverInvitesBlindCopyRetry(); confirmedSaveSurvivesRefreshFailureWithoutDuplicateRetry();
        System.out.println("PASS: shared transfer selection/metadata/geometry preservation, fresh admin checks, scope/revision conflicts, 10k bound, no-op move, fixed origin and no blind retry after uncertain or confirmed save.");
    }
    private static final class Response {
        final String path, body; final int status; final IOException failure;
        Response(String path, int status, String body) { this.path = path; this.status = status; this.body = body; this.failure = null; }
        Response(String path, IOException failure) { this.path = path; this.status = 0; this.body = ""; this.failure = failure; }
    }
    private static final class FakeConnection extends HttpsURLConnection {
        final Response response; final ByteArrayOutputStream output = new ByteArrayOutputStream(); boolean disconnected;
        FakeConnection(URL url, Response response) { super(url); this.response = response; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public int getResponseCode() throws IOException { if (response.failure != null) throw response.failure; return response.status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public void disconnect() { disconnected = true; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "test"; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return (String)values.getOrDefault(key, fallback); }
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>)values.getOrDefault(key, fallback); }
        @Override public int getInt(String key, int fallback) { return (Integer)values.getOrDefault(key, fallback); }
        @Override public long getLong(String key, long fallback) { return (Long)values.getOrDefault(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return (Float)values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean)values.getOrDefault(key, fallback); }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() { return new Editor() {
            final Map<String, Object> updates = new HashMap<>(); final Set<String> removals = new HashSet<>(); boolean clear;
            @Override public Editor putString(String key, String value) { updates.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { updates.put(key, value); return this; }
            @Override public Editor putInt(String key, int value) { updates.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { updates.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { updates.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { updates.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() { if (clear) values.clear(); for (String key : removals) values.remove(key); values.putAll(updates); return true; }
            @Override public void apply() { commit(); }
        }; }
    }
}
