package vn.hptelecom.map;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.net.ssl.HttpsURLConnection;

/**
 * Fixed-origin, read-only map API client. Tokens stay in private app storage and
 * never enter the map WebView. Call on a worker thread.
 */
public final class MapApiClient {
    private static final String ORIGIN = "https://hptelecom.id.vn";
    private static final int MAX_RESPONSE = 32 * 1024 * 1024;
    private static final double MX_RADIUS = 20.0;
    private final SharedPreferences preferences;
    private String verifiedRole = "";

    public MapApiClient(Context context) {
        preferences = context.getSharedPreferences("hp-map-server-session", Context.MODE_PRIVATE);
    }

    public synchronized JSONObject session() throws IOException {
        if (!hasSession()) return emptySession();
        return refresh();
    }

    /** Role originates only from an authenticated /api/me response. */
    public synchronized boolean isAdmin() {
        return hasSession() && "admin".equals(verifiedRole);
    }

    public synchronized JSONObject login(String username, String password) throws IOException {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty())
            throw new IOException("Vui lòng nhập tên đăng nhập và mật khẩu HP Telecom.");
        if (username.length() > 200 || password.length() > 200)
            throw new IOException("Thông tin đăng nhập quá dài.");
        try {
            JSONObject credentials = new JSONObject().put("username", username.trim())
                    .put("password", password).put("client_type", "web");
            JSONObject result = request("/api/login", "POST", credentials, "");
            saveSession(result.optString("token"), result.optLong("expires_at"));
            return refresh();
        } catch (JSONException error) {
            throw invalidResponse();
        }
    }

    /**
     * The official web login uses sessionStorage, not cookies. The isolated login
     * activity may transfer that token once; /me validates it before it is kept.
     */
    public synchronized JSONObject importSession(String token, long expiresAt) throws IOException {
        validateSession(token, expiresAt);
        request("/api/me", "GET", null, token);
        saveSession(token, expiresAt);
        return refresh();
    }

    public synchronized JSONObject refresh() throws IOException {
        if (!hasSession()) return emptySession();
        String token = preferences.getString("token", "");
        try {
            JSONObject user = request("/api/me", "GET", null, token);
            verifiedRole = user.optString("role", "");
            JSONArray tasks = requiredArray(request("/api/mobile/cable-tasks", "GET", null, token), "tasks");
            List<JSONObject> maintenance = new ArrayList<>();
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task != null && "maintenance".equals(task.optString("work_type"))
                        && !task.optString("id").isEmpty()) maintenance.add(task);
            }
            JSONObject catalog = new JSONObject();
            String warning = "";
            try {
                catalog = request("/api/mobile/route-catalog", "GET", null, token);
            } catch (ApiException error) {
                if (error.status == 401) throw error;
                warning = "Chưa tải được loại kết nối và dung lượng từ danh sách tuyến.";
            } catch (IOException error) {
                warning = "Chưa tải được loại kết nối và dung lượng từ danh sách tuyến.";
            }
            JSONArray maps = new JSONArray();
            long updated = System.currentTimeMillis() / 1000;
            for (int offset = 0; offset < maintenance.size(); offset += 500) {
                JSONArray ids = new JSONArray();
                for (int i = offset; i < Math.min(offset + 500, maintenance.size()); i++)
                    ids.put(maintenance.get(i).getString("id"));
                JSONObject result = request("/api/mobile/cable-tasks/map", "POST",
                        new JSONObject().put("task_ids", ids), token);
                JSONArray routes = requiredArray(result, "routes");
                updated = result.optLong("updated", updated);
                for (int i = 0; i < routes.length(); i++) {
                    JSONObject route = routes.optJSONObject(i);
                    if (route != null) maps.put(route);
                }
            }
            JSONArray sharedLayers = new JSONArray();
            try {
                sharedLayers = normalizeSharedLayers(request("/api/map-layers", "GET", null, token));
            } catch (ApiException error) {
                if (error.status == 401) throw error;
                warning += (warning.isEmpty() ? "" : " ")
                        + (error.status == 404 ? "Server chưa cập nhật thư mục bản đồ của quản trị."
                        : "Chưa tải được thư mục bản đồ của quản trị.");
            } catch (IOException error) {
                warning += (warning.isEmpty() ? "" : " ") + "Chưa tải được thư mục bản đồ của quản trị.";
            }
            return new JSONObject().put("user", safeUser(user))
                    .put("routes", normalizeRoutes(maps, catalog.optJSONArray("rows")))
                    .put("sharedLayers", sharedLayers)
                    .put("updated", updated).put("warning", warning);
        } catch (JSONException error) {
            throw invalidResponse();
        }
    }

    public synchronized void logout() {
        String token = preferences.getString("token", "");
        // Always discard the local credential, including while offline.
        verifiedRole = "";
        preferences.edit().clear().commit();
        if (!token.isEmpty()) {
            try { request("/api/logout", "POST", new JSONObject(), token); }
            catch (IOException ignored) { /* Local logout already completed. */ }
        }
    }

    private boolean hasSession() {
        String token = preferences.getString("token", "");
        long expires = preferences.getLong("expires_at", 0);
        if (token.isEmpty() || expires <= System.currentTimeMillis() / 1000) {
            verifiedRole = "";
            preferences.edit().clear().commit();
            return false;
        }
        return true;
    }

    private void saveSession(String token, long expiresAt) throws IOException {
        validateSession(token, expiresAt);
        verifiedRole = "";
        if (!preferences.edit().putString("token", token).putLong("expires_at", expiresAt).commit())
            throw new IOException("Không lưu được phiên đăng nhập trên máy.");
    }

    private void validateSession(String token, long expiresAt) throws IOException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,200}")
                || expiresAt <= System.currentTimeMillis() / 1000)
            throw new IOException("Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
    }

    private JSONObject emptySession() throws IOException {
        try { return new JSONObject().put("user", JSONObject.NULL).put("routes", new JSONArray()); }
        catch (JSONException error) { throw invalidResponse(); }
    }

    private static JSONArray requiredArray(JSONObject object, String key) throws IOException {
        JSONArray array = object.optJSONArray(key);
        if (array == null) throw invalidResponse();
        return array;
    }

    private static JSONObject safeUser(JSONObject user) throws JSONException {
        JSONObject safe = new JSONObject();
        for (String key : new String[]{"id", "username", "display_name", "role", "team"})
            safe.put(key, text(user, key, 200));
        return safe;
    }

    private JSONObject request(String path, String method, JSONObject body, String token) throws IOException {
        // No arbitrary URLs, paths, redirects, or caller-supplied headers.
        if (!("/api/login".equals(path) || "/api/logout".equals(path) || "/api/me".equals(path)
                || "/api/mobile/cable-tasks".equals(path)
                || "/api/mobile/cable-tasks/map".equals(path)
                || "/api/mobile/route-catalog".equals(path)
                || "/api/map-layers".equals(path))) throw new IOException("Yêu cầu không được hỗ trợ.");
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(ORIGIN + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "HP-Map-Android/1.0");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status == 401 && !"/api/login".equals(path)) {
                verifiedRole = "";
                preferences.edit().clear().commit();
            }
            if (status >= 300 && status < 400)
                throw new ApiException(status, "Máy chủ chuyển hướng bất thường. Vui lòng thử lại sau.");
            InputStream source = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(source);
            JSONObject object;
            try { object = new JSONObject(response); }
            catch (JSONException error) {
                if (status == 401) throw new ApiException(status, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.");
                if (status >= 500) throw new ApiException(status, "Máy chủ HP Telecom chưa sẵn sàng. Vui lòng thử lại.");
                throw new ApiException(status, "Không đọc được phản hồi từ máy chủ HP Telecom.");
            }
            if (status < 200 || status >= 300) {
                String message = text(object, "error", 350);
                // Display only the server's dedicated public error field.
                if (message.isEmpty()) message = status == 401
                        ? "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."
                        : "Không tải được dữ liệu từ máy chủ HP Telecom.";
                throw new ApiException(status, message);
            }
            return object;
        } catch (ApiException error) {
            throw error;
        } catch (javax.net.ssl.SSLException error) {
            throw new IOException("Không xác minh được kết nối bảo mật tới HP Telecom.");
        } catch (java.net.SocketTimeoutException error) {
            throw new IOException("Kết nối HP Telecom quá thời gian chờ. Hãy thử lại.");
        } catch (IOException error) {
            throw new IOException("Không kết nối được HP Telecom. Kiểm tra mạng rồi thử lại.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBounded(InputStream source) throws IOException {
        if (source == null) return "{}";
        try (InputStream input = source; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[16384];
            int length, total = 0;
            while ((length = input.read(bytes)) != -1) {
                total += length;
                if (total > MAX_RESPONSE) throw new IOException("Dữ liệu bản đồ quá lớn.");
                output.write(bytes, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static IOException invalidResponse() {
        return new IOException("Dữ liệu bản đồ từ HP Telecom không hợp lệ.");
    }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    private static String text(JSONObject object, String key, int limit) {
        Object raw = object.opt(key);
        if (!(raw instanceof String)) return "";
        String value = ((String) raw).replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "").trim();
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    private static String routeKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)
                .replaceAll("[‐‑‒–—−]", "-").replaceAll("\\s+", "");
    }

    private static final class Route {
        final String key, name;
        final JSONArray lines = new JSONArray();
        final List<Observation> observations = new ArrayList<>();
        final Set<String> reports = new HashSet<>();
        final Set<String> observationsSeen = new HashSet<>();
        String status = "not_started";
        Route(String key, String name) { this.key = key; this.name = name; }
    }

    private static final class Observation {
        final double lat, lon;
        final String kind, name, note, source, author, report, endpointName;
        final long updated;
        final boolean endpoint;
        Observation(JSONObject point, JSONObject member, String source, String report, boolean endpoint) {
            lat = point.optDouble("lat", Double.NaN); lon = point.optDouble("lon", Double.NaN);
            name = text(point, "name", 120); note = text(point, "note", 300);
            this.source = source; this.report = report; this.endpoint = endpoint;
            String sequence = point.isNull("seq") ? "" : String.valueOf(point.opt("seq")).trim();
            endpointName = !name.isEmpty() ? name : sequence.isEmpty() ? "Vị trí" : "Vị trí " + sequence;
            author = text(member, "member_name", 120); updated = member.optLong("updated");
            String match = Normalizer.normalize(name + " " + note, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
            String provided = point.optString("kind");
            kind = "MX".equals(provided) || match.matches("(?s).*(mang xong|\\bmx\\b).*") ? "MX"
                    : "DP".equals(provided) || match.matches("(?s).*(du phong|\\b(dp|db)\\b).*") ? "DP" : "GPS";
        }
        boolean valid() { return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180; }
    }

    /**
     * Only authorized API data enters this normalization. Each report keeps its
     * own patrol polyline; monthly tasks share one route folder. Incident and
     * relocation MX observations never become cable lines.
     */
    static JSONArray normalizeRoutes(JSONArray rawRoutes, JSONArray catalogRows) throws JSONException {
        Map<String, Route> routes = new LinkedHashMap<>();
        for (int i = 0; i < rawRoutes.length(); i++) {
            JSONObject raw = rawRoutes.optJSONObject(i);
            if (raw == null) continue;
            String name = text(raw, "task_name", 200);
            String key = text(raw, "route_key", 500);
            if (key.isEmpty()) key = text(raw, "task_id", 100);
            if (key.isEmpty()) continue;
            Route route = routes.get(key);
            if (route == null) { route = new Route(key, name); routes.put(key, route); }
            String status = raw.optString("task_status", "not_started");
            if ("completed".equals(status) || ("in_progress".equals(status) && !"completed".equals(route.status)))
                route.status = status;
            appendReports(route, raw.optJSONArray("members"), "patrol");
            appendReports(route, raw.optJSONArray("overlays"), "");
        }
        Map<String, JSONObject> metadata = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        if (catalogRows != null) for (int i = 0; i < catalogRows.length(); i++) {
            JSONObject row = catalogRows.optJSONObject(i);
            if (row == null) continue;
            String key = routeKey(text(row, "name", 200));
            if (key.isEmpty()) continue;
            JSONObject prior = metadata.get(key);
            if (prior != null && (!text(prior, "link", 100).equalsIgnoreCase(text(row, "link", 100))
                    || !text(prior, "capacity", 100).equalsIgnoreCase(text(row, "capacity", 100)))) ambiguous.add(key);
            else metadata.put(key, row);
        }
        Map<String, Integer> sameNames = new HashMap<>();
        for (Route route : routes.values()) {
            String key = routeKey(route.name);
            sameNames.put(key, sameNames.getOrDefault(key, 0) + 1);
        }
        JSONArray result = new JSONArray();
        for (Route route : routes.values()) {
            String nameKey = routeKey(route.name);
            JSONObject meta = !ambiguous.contains(nameKey) && sameNames.get(nameKey) == 1 ? metadata.get(nameKey) : null;
            result.put(new JSONObject().put("key", route.key).put("name", route.name)
                    .put("connection_type", meta == null ? "" : text(meta, "link", 100))
                    .put("capacity", meta == null ? "" : text(meta, "capacity", 100))
                    .put("length_km", meta == null ? JSONObject.NULL : meta.opt("length"))
                    .put("status", route.status).put("lines", route.lines)
                    .put("markers", markers(route.observations)));
        }
        return result;
    }

    static JSONArray normalizeSharedLayers(JSONObject response) throws JSONException, IOException {
        JSONArray result = new JSONArray(), layers = requiredArray(response, "layers");
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            if (layer == null) continue;
            String team = text(layer, "team", 100);
            JSONArray folders = layer.optJSONArray("folders"), features = layer.optJSONArray("features");
            if (folders == null || features == null) throw invalidResponse();
            Map<String, JSONObject> indexed = new LinkedHashMap<>();
            for (int f = 0; f < folders.length(); f++) {
                JSONObject folder = folders.optJSONObject(f);
                if (folder == null || folder.optString("id").isEmpty()) continue;
                String id = folder.optString("id"), prefix = "server:" + team + ":";
                Object parent = folder.isNull("parent_id") ? JSONObject.NULL : prefix + folder.optString("parent_id");
                JSONObject item = new JSONObject().put("key", prefix + id).put("id", prefix + id)
                        .put("name", text(folder, "name", 200)).put("parent_id", parent)
                        .put("source_type", "server").put("team", team)
                        .put("color", text(folder, "color", 20)).put("connection_type", "").put("capacity", "")
                        .put("lines", new JSONArray()).put("markers", new JSONArray());
                indexed.put(id, item);
                result.put(item);
            }
            for (int f = 0; f < features.length(); f++) {
                JSONObject feature = features.optJSONObject(f);
                if (feature == null) continue;
                JSONObject target = indexed.get(feature.optString("folder_id"));
                JSONObject geometry = feature.optJSONObject("geometry");
                if (target == null || geometry == null) continue;
                JSONArray coordinates = geometry.optJSONArray("coordinates");
                if (coordinates == null) continue;
                if ("Point".equals(geometry.optString("type"))) {
                    JSONArray point = mapCoordinate(coordinates);
                    if (point == null) continue;
                    String name = text(feature, "name", 200);
                    String kind = name.matches("(?is).*\\bMX\\b.*") ? "MX" : name.matches("(?is).*\\bDP\\b.*") ? "DP" : "GPS";
                    target.getJSONArray("markers").put(new JSONObject().put("lat", point.getDouble(0))
                            .put("lon", point.getDouble(1)).put("label", "GPS".equals(kind) ? (name.isEmpty() ? "Điểm" : name) : kind)
                            .put("position", name).put("name", name).put("note", text(feature, "note", 2000))
                            .put("kind", kind).put("color", text(feature, "color", 20)).put("source", "server")
                            .put("display_on_map", true).put("is_endpoint", false)
                            .put("id", feature.optString("id")));
                } else if ("LineString".equals(geometry.optString("type"))) {
                    JSONArray line = new JSONArray();
                    boolean valid = true;
                    for (int p = 0; p < coordinates.length(); p++) {
                        JSONArray coordinate = coordinates.optJSONArray(p);
                        JSONArray point = coordinate == null ? null : mapCoordinate(coordinate);
                        if (point == null) { valid = false; break; }
                        line.put(point);
                    }
                    if (valid && line.length() >= 2) target.getJSONArray("lines").put(line);
                }
            }
        }
        return result;
    }

    private static JSONArray mapCoordinate(JSONArray pair) throws JSONException {
        if (pair.length() != 2) return null;
        double lon = pair.optDouble(0, Double.NaN), lat = pair.optDouble(1, Double.NaN);
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) return null;
        return new JSONArray().put(lat).put(lon);
    }

    private static void appendReports(Route route, JSONArray reports, String fixedSource) throws JSONException {
        if (reports == null) return;
        for (int i = 0; i < reports.length(); i++) {
            JSONObject member = reports.optJSONObject(i);
            if (member == null) continue;
            String source = fixedSource.isEmpty() ? member.optString("source") : fixedSource;
            if (!("patrol".equals(source) || "incident".equals(source) || "relocation".equals(source))) continue;
            String report = source + "|" + member.optString("report_id",
                    member.optString("member_name") + "|" + member.optLong("updated"));
            // A report may be attached to multiple monthly tasks in this route.
            if (!route.reports.add(report)) continue;
            JSONArray raw = member.optJSONArray("points");
            if (raw == null) continue;
            List<JSONObject> points = new ArrayList<>();
            for (int p = 0; p < raw.length(); p++) {
                JSONObject point = raw.optJSONObject(p);
                // Invalid coordinates must not take the first/last valid point's
                // endpoint role. The same valid points still form the full line.
                if (point != null && new Observation(point, member, source, report, false).valid()) points.add(point);
            }
            if ("patrol".equals(source)) Collections.sort(points, Comparator.comparingDouble(p -> p.optDouble("seq", 0)));
            JSONArray line = new JSONArray();
            for (int p = 0; p < points.size(); p++) {
                Observation observation = new Observation(points.get(p), member, source, report,
                        "patrol".equals(source) && (p == 0 || p == points.size() - 1));
                if (!observation.valid()) continue;
                if ("patrol".equals(source)) line.put(new JSONArray().put(observation.lat).put(observation.lon));
                String signature = report + "|" + observation.lat + "|" + observation.lon + "|"
                        + points.get(p).opt("seq") + "|" + observation.name + "|" + observation.note;
                if (route.observationsSeen.add(signature)) route.observations.add(observation);
            }
            if (line.length() >= 2) route.lines.put(line);
        }
    }

    private static JSONArray markers(List<Observation> observations) throws JSONException {
        observations.sort(Comparator.comparingLong((Observation item) -> item.updated).reversed()
                .thenComparing(item -> item.report).thenComparingDouble(item -> item.lat).thenComparingDouble(item -> item.lon));
        List<List<Observation>> clusters = new ArrayList<>();
        Map<String, List<Integer>> cells = new HashMap<>();
        Map<String, List<Observation>> ordinary = new LinkedHashMap<>();
        for (Observation item : observations) {
            if (!"MX".equals(item.kind)) {
                String key = item.kind + "|" + Math.round(item.lat * 1e6) + "|" + Math.round(item.lon * 1e6);
                ordinary.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
                continue;
            }
            List<Observation> target = null;
            int[] cell = pointCell(item);
            Set<Integer> candidates = new TreeSet<>();
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                List<Integer> nearby = cells.get((cell[0] + x) + "|" + (cell[1] + y) + "|" + (cell[2] + z));
                if (nearby != null) candidates.addAll(nearby);
            }
            for (int index : candidates) {
                List<Observation> cluster = clusters.get(index);
                boolean within = true;
                for (Observation other : cluster) if (distanceMeters(item, other) > MX_RADIUS + 1e-7) {
                    within = false; break;
                }
                if (within) { target = cluster; break; }
            }
            if (target == null) {
                target = new ArrayList<>();
                String key = cell[0] + "|" + cell[1] + "|" + cell[2];
                cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(clusters.size());
                clusters.add(target);
            }
            target.add(item);
        }
        clusters.addAll(ordinary.values());
        JSONArray result = new JSONArray();
        for (List<Observation> cluster : clusters) {
            Observation item = cluster.get(0); Set<String> reports = new HashSet<>();
            String position = "";
            boolean endpoint = false;
            for (Observation part : cluster) {
                reports.add(part.report);
                if (part.endpoint) {
                    endpoint = true;
                    if (position.isEmpty()) position = part.endpointName;
                }
            }
            String label = "GPS".equals(item.kind) ? position : item.kind;
            result.put(new JSONObject().put("lat", item.lat).put("lon", item.lon).put("label", label)
                    .put("position", position).put("name", item.name).put("note", item.note)
                    .put("kind", item.kind).put("source", item.source).put("member_name", item.author)
                    // Hide only intermediate patrol GPS badges. Keep coordinates
                    // for the cable line, details and KMZ export; named admin/import
                    // features are normalized separately and remain visible.
                    .put("display_on_map", !"GPS".equals(item.kind) || endpoint).put("is_endpoint", endpoint)
                    .put("updated", item.updated).put("history_count", reports.size()));
        }
        return result;
    }

    private static double distanceMeters(Observation a, Observation b) {
        double lat = Math.toRadians(b.lat - a.lat), lon = Math.toRadians(b.lon - a.lon);
        double value = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(a.lat))
                * Math.cos(Math.toRadians(b.lat)) * Math.pow(Math.sin(lon / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, value))));
    }

    private static int[] pointCell(Observation point) {
        double lat = Math.toRadians(point.lat), lon = Math.toRadians(point.lon), scale = 6371008.8 / MX_RADIUS;
        return new int[]{(int) Math.floor(Math.cos(lat) * Math.cos(lon) * scale),
                (int) Math.floor(Math.cos(lat) * Math.sin(lon) * scale), (int) Math.floor(Math.sin(lat) * scale)};
    }
}
