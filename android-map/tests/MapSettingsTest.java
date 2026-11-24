package vn.hptelecom.map;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

/** Exercises native validation without a device, network, account or real API key. */
public final class MapSettingsTest {
    private static final String TEST_KEY = "test-only-map-key-12345";
    private static final String OLD_KEY = "previous-map-key-12345";
    private static final String VALID_METADATA =
            "{\"tiles\":[\"https://api.maptiler.com/maps/test/256/{z}/{x}/{y}.png\"],"
            + "\"attribution\":\"<a href='https://example.invalid'>Map source</a>\"}";
    private static final ArrayDeque<Response> responses = new ArrayDeque<>();
    private static final List<FakeConnection> connections = new ArrayList<>();

    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static String rejection(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IOException error) {
            String message = error.getMessage();
            check(message != null && !message.isEmpty(), "A readable error is required");
            check(!message.contains(TEST_KEY) && !message.contains(OLD_KEY), "API key leaked into error");
            check(!message.contains("https://") && !message.contains("key="), "Provider request leaked into error");
            return message;
        }
        throw new AssertionError("Expected the invalid request to be rejected");
    }
    private static void reset(Response... planned) {
        responses.clear(); connections.clear();
        for (Response item : planned) responses.add(item);
    }

    private static void directionsAcceptValidCoordinatesAndRejectInvalidValues() throws Exception {
        String expected = "https://www.google.com/maps/dir/?api=1&destination=12.642969%2C108.015392";
        check(expected.equals(MapDirections.url(12.642969, 108.015392)), "Directions changed coordinate order/precision");
        check(MapDirections.url(-90, -180).endsWith("-90.0%2C-180.0"), "Boundary coordinates must be valid");
        check(MapDirections.url(90, 180).endsWith("90.0%2C180.0"), "Boundary coordinates must be valid");
        check(MapDirections.url(0, 0).endsWith("0.0%2C0.0"), "Zero coordinates are valid");
        for (double[] pair : new double[][]{{90.00001, 0}, {-90.00001, 0}, {0, 180.00001}, {0, -180.00001},
                {Double.NaN, 108}, {12, Double.NaN}, {Double.POSITIVE_INFINITY, 0}, {0, Double.NEGATIVE_INFINITY}}) {
            rejection(() -> MapDirections.url(pair[0], pair[1]));
        }
    }
    private static void keyValidationIsBoundedAndDoesNotEchoInput() throws Exception {
        check("".equals(BasemapSettings.validateKey(null)), "Null should remove optional provider");
        check("".equals(BasemapSettings.validateKey(" \n ")), "Blank should remove optional provider");
        check(TEST_KEY.equals(BasemapSettings.validateKey("  " + TEST_KEY + "  ")), "Trim surrounding whitespace");
        check(BasemapSettings.validateKey("a".repeat(160)).length() == 160, "Maximum key should be accepted");
        for (String value : new String[]{"short", "a".repeat(161), "key/with/slashes", "key?query=true",
                "key with spaces", "https://api.maptiler.com/" + TEST_KEY, TEST_KEY + "\nappend"}) {
            rejection(() -> BasemapSettings.validateKey(value));
        }
    }
    private static MemoryPreferences previousSettings() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("maptiler-key", OLD_KEY);
        prefs.values.put("satellite-attribution", "Old satellite");
        prefs.values.put("hybrid-attribution", "Old hybrid");
        return prefs;
    }
    private static BasemapSettings settings(MemoryPreferences prefs) {
        return new BasemapSettings(new Context() {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                check("hp-map-basemaps".equals(name), "Unexpected preference namespace");
                return prefs;
            }
        });
    }
    private static void validStylesAreCheckedBeforeAtomicCommit() throws Exception {
        reset(new Response(200, VALID_METADATA), new Response(200, VALID_METADATA));
        MemoryPreferences prefs = previousSettings();
        JSONObject saved = settings(prefs).save(TEST_KEY);
        check(saved.getBoolean("configured"), "Valid provider should be configured");
        check(TEST_KEY.equals(saved.getString("key")), "Successful save should replace key");
        check("Map source".equals(saved.getString("satelliteAttribution")), "Remote attribution HTML must be stripped");
        check("Map source".equals(saved.getString("hybridAttribution")), "Hybrid attribution must be preserved as text");
        check(prefs.commits == 1, "Both style validations must finish before the single commit");
        check(connections.size() == 2, "Both style permissions must be validated");
        check(connections.get(0).getURL().getPath().equals("/maps/satellite-v4/256/tiles.json"), "Wrong satellite endpoint");
        check(connections.get(1).getURL().getPath().equals("/maps/hybrid-v4/256/tiles.json"), "Wrong hybrid endpoint");
        for (FakeConnection connection : connections) {
            check(connection.getURL().getHost().equals("api.maptiler.com"), "Provider origin must be fixed");
            check(connection.getURL().getQuery().equals("key=" + TEST_KEY), "Unexpected provider query");
            check(!connection.getInstanceFollowRedirects(), "Provider redirect must not be followed");
            check(connection.getConnectTimeout() > 0 && connection.getReadTimeout() > 0, "Network calls must time out");
            check(connection.getRequestMethod().equals("GET"), "Provider validation must be read-only");
            check(connection.getRequestProperty("Authorization") == null, "HP credentials must not reach map provider");
            check(connection.getRequestProperty("Cookie") == null, "HP session must not reach map provider");
            check(connection.disconnected, "Provider connection must be closed");
        }
    }
    private static void networkAndInvalidMetadataNeverReplaceWorkingSettingsOrLeakKey() throws Exception {
        List<Response[]> failures = List.of(
                new Response[]{new Response(403, TEST_KEY)},
                new Response[]{new Response(200, VALID_METADATA), new Response(429, TEST_KEY)},
                new Response[]{new Response(302, TEST_KEY)},
                new Response[]{new Response(200, VALID_METADATA), new Response(new IOException(
                        "https://api.maptiler.com/maps/hybrid-v4/256/tiles.json?key=" + TEST_KEY))},
                new Response[]{new Response(200, "{\"tiles\":[]}")},
                new Response[]{new Response(200, "invalid JSON " + TEST_KEY)},
                new Response[]{new Response(200, "x".repeat(128 * 1024 + 1))}
        );
        for (Response[] planned : failures) {
            reset(planned);
            MemoryPreferences prefs = previousSettings();
            BasemapSettings settings = settings(prefs);
            rejection(() -> settings.save(TEST_KEY));
            JSONObject retained = settings.read();
            check(OLD_KEY.equals(retained.getString("key")), "Failed validation overwrote working key");
            check("Old satellite".equals(retained.getString("satelliteAttribution")), "Old attribution was replaced");
            check("Old hybrid".equals(retained.getString("hybridAttribution")), "Old hybrid attribution was replaced");
            check(prefs.commits == 0, "Validation failure must never write preferences");
            for (FakeConnection connection : connections) check(connection.disconnected, "Failed connection left open");
        }
    }
    private static void invalidAndEmptyKeysDoNotMakeNetworkRequests() throws Exception {
        reset();
        MemoryPreferences prefs = previousSettings();
        BasemapSettings settings = settings(prefs);
        rejection(() -> settings.save("not a valid key"));
        check(prefs.commits == 0 && connections.isEmpty(), "Invalid key must not reach provider or storage");
        JSONObject cleared = settings.save("");
        check(!cleared.getBoolean("configured") && cleared.getString("key").isEmpty(), "Clearing optional key should work offline");
        check(connections.isEmpty() && prefs.commits == 1, "Clearing key must not make a network request");
    }
    private static void failedDiskCommitRestoresPreviousInMemorySettings() throws Exception {
        // Android updates its in-memory preferences before disk commit returns.
        // Model both an initial disk failure and a still-full disk on rollback.
        for (int failures : new int[]{1, 2}) for (String replacement : new String[]{TEST_KEY, ""}) {
            reset(new Response(200, VALID_METADATA), new Response(200, VALID_METADATA));
            MemoryPreferences prefs = previousSettings();
            prefs.failCommits = failures;
            BasemapSettings settings = settings(prefs);
            rejection(() -> settings.save(replacement));
            JSONObject retained = settings.read();
            check(OLD_KEY.equals(retained.getString("key")), "Failed disk write changed active key");
            check("Old satellite".equals(retained.getString("satelliteAttribution")), "Failed disk write changed satellite credits");
            check("Old hybrid".equals(retained.getString("hybridAttribution")), "Failed disk write changed hybrid credits");
            check(prefs.commits == 2, "Failed commit must attempt restoring old settings");
        }
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                if (responses.isEmpty()) throw new AssertionError("Unexpected network request: no test response queued");
                FakeConnection connection = new FakeConnection(url, responses.remove());
                connections.add(connection);
                return connection;
            }
        } : null);
        directionsAcceptValidCoordinatesAndRejectInvalidValues();
        keyValidationIsBoundedAndDoesNotEchoInput();
        validStylesAreCheckedBeforeAtomicCommit();
        networkAndInvalidMetadataNeverReplaceWorkingSettingsOrLeakKey();
        invalidAndEmptyKeysDoNotMakeNetworkRequests();
        failedDiskCommitRestoresPreviousInMemorySettings();
        System.out.println("PASS: fixed directions URL and coordinate bounds; MapTiler key validation, no secret errors/HP auth, no redirects, bounded metadata, and working settings retained on network/validation/disk failure.");
    }

    private static final class Response {
        final int status; final String body; final IOException failure;
        Response(int status, String body) { this.status = status; this.body = body; this.failure = null; }
        Response(IOException failure) { this.status = 0; this.body = ""; this.failure = failure; }
    }
    private static final class FakeConnection extends HttpsURLConnection {
        final Response response; boolean disconnected;
        FakeConnection(URL url, Response response) { super(url); this.response = response; }
        @Override public int getResponseCode() throws IOException { if (response.failure != null) throw response.failure; return response.status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response.body.getBytes(StandardCharsets.UTF_8)); }
        @Override public void disconnect() { disconnected = true; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "test"; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>(); int commits, failCommits;
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
        @Override public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
        @Override public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
        @Override public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() { return new MemoryEditor(); }
        private final class MemoryEditor implements Editor {
            final Map<String, Object> updates = new HashMap<>(); final Set<String> removals = new HashSet<>(); boolean clear;
            @Override public Editor putString(String key, String value) { updates.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) { updates.put(key, value); return this; }
            @Override public Editor putInt(String key, int value) { updates.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { updates.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { updates.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { updates.put(key, value); return this; }
            @Override public Editor remove(String key) { removals.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                commits++;
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(updates);
                if (failCommits > 0) { failCommits--; return false; }
                return true;
            }
            @Override public void apply() { commit(); }
        }
    }
}
