package vn.hptelecom.map;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Optional, device-local imagery configuration. Never receives HP Telecom credentials. */
final class BasemapSettings {
    private final SharedPreferences preferences;

    BasemapSettings(Context context) {
        preferences = context.getSharedPreferences("hp-map-basemaps", Context.MODE_PRIVATE);
    }

    JSONObject read() throws Exception {
        String key = preferences.getString("maptiler-key", "");
        return new JSONObject().put("provider", "maptiler").put("configured", !key.isEmpty())
                .put("key", key)
                .put("satelliteAttribution", preferences.getString("satellite-attribution", ""))
                .put("hybridAttribution", preferences.getString("hybrid-attribution", ""));
    }

    /** Validate both styles before replacing working settings; errors must never echo the key. */
    JSONObject save(String suppliedKey) throws Exception {
        String key = validateKey(suppliedKey);
        if (key.isEmpty()) {
            persist("", "", "");
            return read();
        }
        String satellite = validateStyle("satellite-v4", key);
        String hybrid = validateStyle("hybrid-v4", key);
        persist(key, satellite, hybrid);
        return read();
    }

    private void persist(String key, String satellite, String hybrid) throws IOException {
        String previousKey = preferences.getString("maptiler-key", "");
        String previousSatellite = preferences.getString("satellite-attribution", "");
        String previousHybrid = preferences.getString("hybrid-attribution", "");
        if (!preferences.edit().putString("maptiler-key", key)
                .putString("satellite-attribution", satellite)
                .putString("hybrid-attribution", hybrid).commit()) {
            // commit may update the in-memory map even when disk persistence fails.
            preferences.edit().putString("maptiler-key", previousKey)
                    .putString("satellite-attribution", previousSatellite)
                    .putString("hybrid-attribution", previousHybrid).commit();
            throw new IOException("Không lưu được cấu hình bản đồ.");
        }
    }

    static String validateKey(String suppliedKey) throws IOException {
        String key = suppliedKey == null ? "" : suppliedKey.trim();
        if (!key.isEmpty() && !key.matches("[A-Za-z0-9_-]{12,160}"))
            throw new IOException("Khóa MapTiler không đúng định dạng.");
        return key;
    }

    private String validateStyle(String style, String key) throws IOException {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(
                    "https://api.maptiler.com/maps/" + style + "/256/tiles.json?key=" + key).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "HPMapAndroid/" + BuildConfig.VERSION_NAME);
            int status = connection.getResponseCode();
            if (status == 401 || status == 403)
                throw new IOException("Khóa MapTiler chưa được phép dùng lớp vệ tinh. Kiểm tra quyền và gói dịch vụ.");
            if (status == 429)
                throw new IOException("Nguồn bản đồ đã đạt giới hạn sử dụng. Vui lòng kiểm tra tài khoản MapTiler.");
            if (status != 200)
                throw new IOException("Chưa xác nhận được lớp vệ tinh. Vui lòng thử lại.");
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 128 * 1024) throw new IOException("Phản hồi nguồn bản đồ không hợp lệ.");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            JSONObject metadata = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (metadata.optJSONArray("tiles") == null || metadata.getJSONArray("tiles").length() == 0)
                throw new IOException("Nguồn bản đồ chưa cung cấp ảnh nền.");
            // Keep credit text, never pass remote HTML into the privileged packaged UI.
            return metadata.optString("attribution").replaceAll("<[^>]*>", "").trim();
        } catch (IOException failure) {
            String message = failure.getMessage();
            if (message != null && (message.startsWith("Khóa MapTiler") || message.startsWith("Nguồn bản đồ")
                    || message.startsWith("Chưa xác nhận") || message.startsWith("Phản hồi nguồn")))
                throw failure;
            throw new IOException("Không kết nối được MapTiler. Kiểm tra mạng và thử lại.");
        } catch (Exception failure) {
            throw new IOException("Phản hồi nguồn bản đồ không hợp lệ.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
