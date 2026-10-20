package vn.hptelecom.map;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packaged HP Map UI. The server supplies data, never the main app's interface. */
@SuppressWarnings("deprecation")
public final class MapHomeActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START = ORIGIN + "/hpmap/index.html";
    private static final int PICK = 301, SAVE = 302, GPS = 303;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web, authWeb;
    private MapApiClient api;
    private ImportedMapStore imports;
    private boolean destroyed, fileBusy;
    private String fileRequest, gpsRequest;
    private byte[] exportBytes;
    private LocationListener locationListener;
    private Runnable locationTimeout;
    private AlertDialog authDialog;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        api = new MapApiClient(this);
        imports = new ImportedMapStore(new File(getFilesDir(), "imported-maps"));
        getWindow().setStatusBarColor(Color.rgb(7, 28, 38));
        getWindow().setNavigationBarColor(Color.rgb(7, 28, 38));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 28, 38));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        web = new WebView(this);
        configure(web);
        web.setBackgroundColor(Color.rgb(7, 28, 38));
        WebView.setWebContentsDebuggingEnabled(false);
        web.addJavascriptInterface(new AppBridge(), "HPMap");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !START.equals(request.getUrl().toString());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (!"GET".equals(request.getMethod()) || path == null || path.contains("..")
                            || !path.matches("/(?:hpmap|vendor)/[A-Za-z0-9_./-]+")) return blocked();
                    try {
                        String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css" :
                            path.endsWith(".js") ? "application/javascript" : path.endsWith(".svg") ? "image/svg+xml" : "image/png";
                        HashMap<String,String> headers = new HashMap<>();
                        headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org https://*.tile.openstreetmap.org https://*.tile.opentopomap.org; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
                        headers.put("X-Content-Type-Options", "nosniff");
                        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(path.substring(1)));
                    } catch (Exception failure) { return blocked(); }
                }
                String host = uri.getHost();
                if (!request.isForMainFrame() && "https".equals(uri.getScheme()) && host != null
                    && (host.equals("tile.openstreetmap.org") || host.endsWith(".tile.openstreetmap.org")
                        || host.endsWith(".tile.opentopomap.org"))) return null;
                return blocked();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        web.loadUrl(START);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUserAgentString(settings.getUserAgentString() + " HPMapAndroid/" + BuildConfig.VERSION_NAME);
    }
    private WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    private final class AppBridge {
        @JavascriptInterface public void request(String id, String action, String payload) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}") || payload == null || payload.length() > BridgePolicy.MAX_KML) return;
            ui.post(() -> {
                if (destroyed || !START.equals(web.getUrl())) return;
                try {
                    JSONObject args = new JSONObject(payload);
                    switch (action) {
                        case "import":
                            worker.execute(() -> {
                                if (api.isAdmin()) ui.post(() -> chooseFile(id));
                                else result(id, json("ok", false, "error", "Chỉ quản trị viên được nhập bản đồ."));
                            }); return;
                        case "export": export(id, args); return;
                        case "location": locate(id); return;
                        case "serverLogin": serverLogin(id); return;
                        case "session": case "refresh": case "login": case "logout": case "imports": case "removeImport":
                            worker.execute(() -> {
                                try {
                                    JSONObject value;
                                    switch (action) {
                                        case "login": value = api.login(args.optString("username"), args.optString("password")); break;
                                        case "logout": api.logout(); value = json("ok", true, "user", JSONObject.NULL, "routes", new JSONArray()); break;
                                        case "refresh": value = api.refresh(); break;
                                        case "imports":
                                            value = api.isAdmin() ? importedMaps() : json("ok", true, "maps", new JSONArray()); break;
                                        case "removeImport":
                                            if (!api.isAdmin()) throw new java.io.IOException("Chỉ quản trị viên được xóa bản đồ.");
                                            imports.remove(args.optString("id")); value = json("ok", true); break;
                                        default: value = api.session();
                                    }
                                    result(id, value);
                                } catch (Exception failure) { fail(id, failure); }
                            }); return;
                        default: result(id, json("ok", false, "error", "Thao tác chưa được hỗ trợ."));
                    }
                } catch (Exception failure) { fail(id, failure); }
            });
        }
    }
    private void result(String id, JSONObject value) {
        ui.post(() -> {
            if (destroyed || !START.equals(web.getUrl())) return;
            String encoded = android.util.Base64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            web.evaluateJavascript("window.__hpNativeChunks=window.__hpNativeChunks||{};window.__hpNativeChunks[" + JSONObject.quote(id) + "]='';", null);
            for (int offset = 0; offset < encoded.length(); offset += 48000) {
                String chunk = encoded.substring(offset, Math.min(encoded.length(), offset + 48000));
                web.evaluateJavascript("window.__hpNativeChunks[" + JSONObject.quote(id) + "]+=" + JSONObject.quote(chunk) + ";", null);
            }
            web.evaluateJavascript("(function(){var k=" + JSONObject.quote(id) + ",b=atob(window.__hpNativeChunks[k]);delete window.__hpNativeChunks[k];"
                + "var a=Uint8Array.from(b,function(c){return c.charCodeAt(0)});window.HPMapResult(k,JSON.parse(new TextDecoder().decode(a)));})()", null);
        });
    }
    private void fail(String id, Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) message = "Không thực hiện được. Hãy thử lại.";
        int status = failure instanceof MapApiClient.ApiException ? ((MapApiClient.ApiException)failure).status : 0;
        result(id, json("ok", false, "error", message.substring(0, Math.min(350, message.length())),
            "status", status, "authExpired", status == 401));
    }
    private static JSONObject json(Object... pairs) {
        JSONObject obj = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) obj.put((String)pairs[i], pairs[i+1]); } catch (Exception ignored) { }
        return obj;
    }
    private JSONObject importedMaps() throws Exception {
        JSONArray maps = new JSONArray();
        for (ImportedMapStore.MapFile file : imports.list()) maps.put(json("id", file.id, "name", file.name, "kml", new String(file.kml, StandardCharsets.UTF_8)));
        return json("ok", true, "maps", maps);
    }
    private void chooseFile(String id) {
        if (fileBusy) { result(id, json("ok", false, "error", "Đang xử lý một tệp khác.")); return; }
        fileBusy = true; fileRequest = id;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.google-earth.kmz", "application/vnd.google-earth.kml+xml", "application/zip", "application/octet-stream", "application/xml", "text/xml"});
            startActivityForResult(intent, PICK);
        } catch (Exception error) { fileBusy = false; fail(id, error); }
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "Ban-do.kmz";
    }
    private void export(String id, JSONObject args) {
        if (fileBusy) { result(id, json("ok", false, "error", "Đang xử lý một tệp khác.")); return; }
        fileBusy = true; fileRequest = id;
        worker.execute(() -> {
            try {
                byte[] kml = args.optString("kml").getBytes(StandardCharsets.UTF_8);
                BridgePolicy.utf8Kml(kml);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("doc.kml")); zip.write(kml); zip.closeEntry(); }
                String name = BridgePolicy.fileName(args.optString("name", "HP-Map"), ".kmz");
                if (args.optBoolean("share")) {
                    String key = UUID.randomUUID().toString();
                    File directory = new File(getCacheDir(), "exports/" + key);
                    if (!directory.mkdirs()) throw new java.io.IOException("Không tạo được tệp chia sẻ.");
                    try (FileOutputStream output = new FileOutputStream(new File(directory, name))) { bytes.writeTo(output); }
                    Uri uri = new Uri.Builder().scheme("content").authority(getPackageName()+".files").appendPath("exports").appendPath(key).appendPath(name).build();
                    ui.post(() -> {
                        fileBusy = false;
                        try {
                            Intent share = new Intent(Intent.ACTION_SEND).setType("application/vnd.google-earth.kmz").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            share.setClipData(ClipData.newRawUri("HP Map", uri));
                            startActivity(Intent.createChooser(share, "Chia sẻ KMZ"));
                            result(id, json("ok", true));
                        } catch (Exception failure) { fail(id, failure); }
                    });
                } else {
                    exportBytes = bytes.toByteArray();
                    ui.post(() -> {
                        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/vnd.google-earth.kmz").putExtra(Intent.EXTRA_TITLE, name), SAVE); }
                        catch (Exception failure) { exportBytes = null; fileBusy = false; fail(id, failure); }
                    });
                }
            } catch (Exception error) { ui.post(() -> fileBusy = false); fail(id, error); }
        });
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != PICK && request != SAVE) return;
        String id = fileRequest;
        if (code != RESULT_OK || data == null || data.getData() == null) {
            fileBusy = false; exportBytes = null;
            result(id, json("ok", false, "cancelled", true, "error", "Đã hủy chọn tệp.")); return;
        }
        Uri uri = data.getData(); byte[] saving = exportBytes; exportBytes = null;
        worker.execute(() -> {
            try {
                if (request == PICK) {
                    ImportedMapStore.MapFile file;
                    try (InputStream input = getContentResolver().openInputStream(uri)) { file = imports.add(displayName(uri), BridgePolicy.readBounded(input, BridgePolicy.MAX_FILE)); }
                    result(id, json("ok", true, "maps", new JSONArray().put(json("id", file.id, "name", file.name,
                        "kml", new String(file.kml, StandardCharsets.UTF_8)))));
                } else {
                    if (saving == null) throw new java.io.IOException("Hãy xuất lại tệp KMZ.");
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new java.io.IOException("Không mở được nơi lưu.");
                        output.write(saving); output.flush();
                    }
                    result(id, json("ok", true));
                }
            } catch (Exception error) { fail(id, error); }
            finally { ui.post(() -> fileBusy = false); }
        });
    }
    private void locate(String id) {
        if (gpsRequest != null) { result(id, json("ok", false, "error", "Đang lấy vị trí.")); return; }
        gpsRequest = id;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, GPS);
        } else beginLocation();
    }
    @SuppressLint("MissingPermission")
    private void beginLocation() {
        final LocationManager manager = (LocationManager)getSystemService(LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                String id = gpsRequest; stopLocation();
                if (id != null) result(id, json("lat", location.getLatitude(), "lon", location.getLongitude(), "accuracy", location.getAccuracy()));
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        boolean started = false;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try { if (manager.isProviderEnabled(provider)) { manager.requestLocationUpdates(provider, 1000, 0, locationListener); started = true; } }
            catch (Exception ignored) { }
        }
        if (!started) { String id = gpsRequest; stopLocation(); result(id, json("ok", false, "error", "Hãy bật Vị trí trên điện thoại.")); return; }
        locationTimeout = () -> {
            String id = gpsRequest; stopLocation();
            if (id != null) result(id, json("ok", false, "error", "Chưa lấy được GPS. Hãy ra nơi thoáng rồi thử lại."));
        };
        ui.postDelayed(locationTimeout, 20000);
    }
    private void stopLocation() {
        if (locationListener != null) {
            try { ((LocationManager)getSystemService(LOCATION_SERVICE)).removeUpdates(locationListener); } catch (Exception ignored) { }
        }
        if (locationTimeout != null) ui.removeCallbacks(locationTimeout);
        locationListener = null; locationTimeout = null; gpsRequest = null;
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != GPS || gpsRequest == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) beginLocation();
        else { String id = gpsRequest; gpsRequest = null; result(id, json("ok", false, "error", "Chưa được cấp quyền vị trí.")); }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void serverLogin(String id) {
        if (authDialog != null) return;
        authWeb = new WebView(this); configure(authWeb);
        final boolean[] importing = {false};
        authWeb.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !BridgePolicy.trustedUrl(request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!BridgePolicy.trustedUrl(url) || BridgePolicy.isLoginUrl(url) || importing[0]) return;
                view.evaluateJavascript("sessionStorage.getItem('hp-server-session')", encoded -> {
                    try {
                        Object decoded = new JSONTokener(encoded).nextValue();
                        if (!(decoded instanceof String)) return;
                        JSONObject session = new JSONObject((String)decoded);
                        String token = session.optString("token");
                        if (token.isEmpty()) return;
                        importing[0] = true;
                        view.setVisibility(android.view.View.INVISIBLE);
                        worker.execute(() -> {
                            try { result(id, api.importSession(token, session.optLong("expires_at", 0))); }
                            catch (Exception failure) { fail(id, failure); }
                            finally { ui.post(() -> closeAuth()); }
                        });
                    } catch (Exception ignored) { }
                });
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.cancel(); }
        });
        authDialog = new AlertDialog.Builder(this).setTitle("Đăng nhập HP Telecom Server").setView(authWeb)
            .setNegativeButton("Quay lại HP Map", (dialog, which) -> {}).create();
        authDialog.setOnDismissListener(dialog -> {
            if (!importing[0]) result(id, json("ok", false, "cancelled", true, "error", "Đã quay lại HP Map."));
            if (authWeb != null) { authWeb.stopLoading(); authWeb.destroy(); authWeb = null; }
            authDialog = null;
        });
        authDialog.show();
        authDialog.getWindow().setLayout(-1, (int)(getResources().getDisplayMetrics().heightPixels * 0.88));
        authWeb.loadUrl(BridgePolicy.LOGIN_URL);
    }
    private void closeAuth() { if (authDialog != null) authDialog.dismiss(); }
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.HPMapApp&&window.HPMapApp.back?window.HPMapApp.back():false", value -> {
            if (!"true".equals(value)) new AlertDialog.Builder(this).setMessage("Thoát HP Map?")
                .setNegativeButton("Ở lại", null).setPositiveButton("Thoát", (dialog, which) -> finish()).show();
        });
    }
    @Override protected void onDestroy() {
        destroyed = true; stopLocation(); closeAuth(); worker.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("HPMap"); web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
