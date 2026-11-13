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
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final int PICK_MAP = 101, SAVE_KMZ = 102, LOCATION_PERMISSION = 103;
    private static final int CHUNK = 192 * 1024;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;
    private ProgressBar progress;
    private LinearLayout errorPanel;
    private TextView errorText;
    private Button loginButton;
    private ImportedMapStore imports;
    private String plugin = "", pendingImport = "", pendingSaveName = "";
    private byte[] pendingSave;
    private boolean destroyed, operationBusy;
    private int pageGeneration;
    private GeolocationPermissions.Callback locationCallback;
    private String locationOrigin;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 43, 57));
        getWindow().setNavigationBarColor(Color.rgb(8, 43, 57));
        imports = new ImportedMapStore(new File(getFilesDir(), "imported-maps"));
        try (InputStream input = getAssets().open("map-app.js")) {
            plugin = new String(BridgePolicy.readBounded(input, 2 * 1024 * 1024), StandardCharsets.UTF_8);
        } catch (IOException ignored) { /* A readable error is shown after the shell loads. */ }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(8, 43, 57));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        // API 26-29 use the older inset accessors.
        if (android.os.Build.VERSION.SDK_INT < 30) root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(4), 0);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.hp_icon);
        logo.setContentDescription("Logo HP Telecom"); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        logoParams.setMarginEnd(dp(9)); toolbar.addView(logo, logoParams);
        TextView title = new TextView(this); title.setText("HP Map"); title.setTextSize(17); title.setTextColor(Color.WHITE);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1)); title.setGravity(Gravity.CENTER_VERTICAL);
        loginButton = tool("Đăng nhập", "Đăng nhập qua HP Telecom Server", () -> web.loadUrl(BridgePolicy.LOGIN_URL));
        loginButton.setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(48)));
        loginButton.setTextSize(12); loginButton.setVisibility(View.GONE); toolbar.addView(loginButton);
        toolbar.addView(tool("＋", "Nhập KMZ hoặc KML", () -> chooseMap("native-" + System.currentTimeMillis())));
        toolbar.addView(tool("↗", "Chia sẻ các tuyến đã chọn", () -> shareSelected(false)));
        Button more = tool("⋮", "Tùy chọn", () -> {}); more.setOnClickListener(view -> showMenu(more)); toolbar.addView(more);
        root.addView(toolbar);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        FrameLayout content = new FrameLayout(this);
        web = new WebView(this); web.setBackgroundColor(Color.rgb(8, 43, 57));
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSaveFormData(false); settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUserAgentString(settings.getUserAgentString() + " HPMapAndroid/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new NativeBridge(), "HPTelecomMapNative");
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        errorPanel = new LinearLayout(this); errorPanel.setOrientation(LinearLayout.VERTICAL); errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(24), dp(24), dp(24), dp(24)); errorPanel.setBackgroundColor(Color.rgb(8, 43, 57));
        errorText = new TextView(this); errorText.setTextColor(Color.WHITE); errorText.setTextSize(17); errorText.setGravity(Gravity.CENTER);
        errorPanel.addView(errorText); Button retry = new Button(this); retry.setText("Thử lại"); retry.setOnClickListener(view -> reloadMap()); errorPanel.addView(retry);
        content.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1)); errorPanel.setVisibility(View.GONE);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (BridgePolicy.trustedUrl(url)) return false;
                if (request.isForMainFrame() && request.hasGesture() && "https".equalsIgnoreCase(request.getUrl().getScheme())) {
                    new AlertDialog.Builder(MainActivity.this).setMessage("Mở liên kết này trong trình duyệt?")
                        .setNegativeButton("Hủy", null).setPositiveButton("Mở", (dialog, which) -> {
                            try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); } catch (Exception ignored) { toast("Không tìm thấy trình duyệt."); }
                        }).show();
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loginButton.setVisibility(BridgePolicy.isLoginUrl(url) ? View.VISIBLE : View.GONE);
                pageGeneration++; progress.setVisibility(View.VISIBLE); errorPanel.setVisibility(View.GONE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE); CookieManager.getInstance().flush();
                if (!BridgePolicy.trustedUrl(url) || !trusted()) return;
                if (plugin.isEmpty()) showError("Thiếu phần giao diện bản đồ. Hãy cài lại bản APK đầy đủ.");
                else web.evaluateJavascript(plugin, null);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showError("Chưa kết nối được HP Telecom. Hãy kiểm tra mạng rồi thử lại.");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    if (response.getStatusCode() == 401 || response.getStatusCode() == 403) loginButton.setVisibility(View.VISIBLE);
                    showError("Máy chủ chưa phản hồi được yêu cầu (" + response.getStatusCode() + "). Hãy thử lại.");
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); showError("Chưa xác minh được kết nối an toàn đến máy chủ.");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!trusted() || !BridgePolicy.trustedUrl(origin)) { callback.invoke(origin, false, false); return; }
                if (locationGranted()) { callback.invoke(origin, true, false); return; }
                cancelLocation(); locationCallback = callback; locationOrigin = origin;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            }
            @Override public void onGeolocationPermissionsHidePrompt() { cancelLocation(); }
        });
        if (state == null || web.restoreState(state) == null) web.loadUrl(BridgePolicy.START_URL);
    }

    private Button tool(String text, String description, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(24); button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT); button.setContentDescription(description);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(0, 0, 0, 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
        button.setOnClickListener(view -> action.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean trusted() { return !destroyed && web != null && BridgePolicy.trustedUrl(web.getUrl()); }
    private void toast(String message) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void showError(String text) { if (!destroyed) { errorText.setText(text); errorPanel.setVisibility(View.VISIBLE); progress.setVisibility(View.GONE); } }
    private void reloadMap() { if (!destroyed) { errorPanel.setVisibility(View.GONE); if (trusted()) web.reload(); else web.loadUrl(BridgePolicy.START_URL); } }
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Làm mới bản đồ").setOnMenuItemClickListener(item -> { reloadMap(); return true; });
        menu.getMenu().add("Lưu KMZ các tuyến đã chọn").setOnMenuItemClickListener(item -> { shareSelected(true); return true; });
        menu.getMenu().add("Bản đồ đã nhập").setOnMenuItemClickListener(item -> { manageImports(); return true; });
        menu.getMenu().add("Phiên bản " + BuildConfig.VERSION_NAME).setEnabled(false); menu.show();
    }
    private void shareSelected(boolean save) {
        if (!trusted()) { toast("Hãy mở bản đồ và đăng nhập trước."); return; }
        web.evaluateJavascript("(function(){if(window.HPTelecomMapBridge&&typeof HPTelecomMapBridge.shareSelected==='function'){HPTelecomMapBridge.shareSelected(" + (save ? "'save'" : "") + ");return true;}return false;})()", result -> {
            if (!"true".equals(result)) toast("Bản đồ đang tải. Hãy thử lại sau khi danh sách tuyến hiện ra.");
        });
    }
    private void chooseMap(String id) {
        if (operationBusy) { result(error(id, "import", "Đang xử lý một tệp khác. Hãy thử lại sau.")); return; }
        pendingImport = BridgePolicy.requestId(id); operationBusy = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.google-earth.kmz", "application/vnd.google-earth.kml+xml", "application/zip", "application/octet-stream", "application/xml", "text/xml"});
        try { startActivityForResult(intent, PICK_MAP); }
        catch (Exception failure) { operationBusy = false; result(error(pendingImport, "import", "Không mở được trình chọn tệp.")); }
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "Ban-do-da-nhap";
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == PICK_MAP) {
            String id = pendingImport; pendingImport = "";
            if (code != RESULT_OK || data == null || data.getData() == null) {
                operationBusy = false; result(json("requestId", id, "type", "import", "ok", false, "cancelled", true, "error", "Đã hủy chọn tệp.")); return;
            }
            Uri uri = data.getData();
            io.execute(() -> {
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    ImportedMapStore.MapFile map = imports.add(displayName(uri), BridgePolicy.readBounded(stream, BridgePolicy.MAX_FILE));
                    runOnUiThread(() -> { operationBusy = false; toast(map.duplicate ? "Bản đồ này đã có, không tạo bản trùng." : "Đã lưu bản đồ trên điện thoại."); result(json("requestId", id, "type", "import", "ok", true, "map", mapJson(map), "duplicate", map.duplicate)); });
                } catch (Exception failure) { runOnUiThread(() -> { operationBusy = false; toast(message(failure)); result(error(id, "import", message(failure))); }); }
            });
        } else if (request == SAVE_KMZ) {
            byte[] bytes = pendingSave; String name = pendingSaveName; pendingSave = null; pendingSaveName = "";
            if (code != RESULT_OK || data == null || data.getData() == null || bytes == null) {
                operationBusy = false; result(json("type", "save", "ok", false, "cancelled", true, "error", "Đã hủy lưu tệp.")); return;
            }
            Uri uri = data.getData();
            io.execute(() -> {
                try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                    if (stream == null) throw new IOException("Không mở được nơi lưu tệp.");
                    stream.write(bytes); stream.flush();
                    runOnUiThread(() -> { operationBusy = false; toast("Đã lưu " + name); result(json("type", "save", "ok", true, "name", name)); });
                } catch (Exception failure) { runOnUiThread(() -> { operationBusy = false; toast(message(failure)); result(error("native", "save", message(failure))); }); }
            });
        }
    }
    private void listMaps(String id) {
        io.execute(() -> {
            try {
                JSONArray maps = new JSONArray(); for (ImportedMapStore.MapFile map : imports.list()) maps.put(mapJson(map));
                runOnUiThread(() -> result(json("requestId", BridgePolicy.requestId(id), "type", "list", "ok", true, "maps", maps)));
            } catch (Exception failure) { runOnUiThread(() -> result(error(id, "list", message(failure)))); }
        });
    }
    private void manageImports() {
        if (operationBusy) { toast("Đang xử lý tệp. Hãy thử lại sau."); return; }
        io.execute(() -> {
            try {
                List<ImportedMapStore.MapFile> maps = imports.list();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (maps.isEmpty()) { toast("Chưa có bản đồ nhập trên điện thoại."); return; }
                    String[] names = new String[maps.size()]; for (int i = 0; i < names.length; i++) names[i] = maps.get(i).name;
                    new AlertDialog.Builder(this).setTitle("Bản đồ đã nhập · chạm để xóa").setItems(names, (dialog, index) -> {
                        ImportedMapStore.MapFile map = maps.get(index);
                        new AlertDialog.Builder(this).setMessage("Xóa bản sao “" + map.name + "” khỏi ứng dụng? Tệp gốc vẫn giữ nguyên.")
                            .setNegativeButton("Giữ lại", null).setPositiveButton("Xóa bản sao", (confirmation, which) -> io.execute(() -> {
                                try { imports.remove(map.id); runOnUiThread(() -> { toast("Đã xóa bản sao trong ứng dụng."); if (!destroyed) listMaps("native-list"); }); }
                                catch (IOException failure) { runOnUiThread(() -> toast(message(failure))); }
                            })).show();
                    }).setNegativeButton("Đóng", null).show();
                });
            } catch (Exception failure) { runOnUiThread(() -> toast(message(failure))); }
        });
    }
    private void exportKmz(String base64, String suppliedName, boolean save) {
        if (operationBusy) { toast("Đang xử lý một tệp khác. Hãy thử lại sau."); return; }
        operationBusy = true;
        io.execute(() -> {
            try {
                byte[] bytes = BridgePolicy.exportedKmz(base64); ImportedMapStore.extractKml(bytes);
                String name = BridgePolicy.fileName(suppliedName, ".kmz");
                if (save) {
                    runOnUiThread(() -> {
                        if (destroyed) return; pendingSave = bytes; pendingSaveName = name;
                        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/vnd.google-earth.kmz").putExtra(Intent.EXTRA_TITLE, name), SAVE_KMZ); }
                        catch (Exception failure) { pendingSave = null; operationBusy = false; result(error("native", "save", "Không mở được nơi lưu tệp.")); }
                    });
                } else {
                    String id = UUID.randomUUID().toString(); File directory = new File(new File(getCacheDir(), "exports"), id);
                    if (!directory.mkdirs()) throw new IOException("Không tạo được tệp chia sẻ.");
                    File file = new File(directory, name);
                    try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); stream.flush(); }
                    Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".files").appendPath("exports").appendPath(id).appendPath(name).build();
                    runOnUiThread(() -> {
                        operationBusy = false; if (destroyed) return;
                        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/vnd.google-earth.kmz").putExtra(Intent.EXTRA_STREAM, uri);
                        intent.setClipData(ClipData.newUri(getContentResolver(), name, uri));
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try { startActivity(Intent.createChooser(intent, "Chia sẻ KMZ qua ứng dụng")); result(json("type", "share", "ok", true, "name", name, "chooserOpened", true)); }
                        catch (Exception failure) { result(error("native", "share", "Không mở được bảng chia sẻ.")); }
                    });
                }
            } catch (Exception failure) { runOnUiThread(() -> { operationBusy = false; toast(message(failure)); result(error("native", save ? "save" : "share", message(failure))); }); }
        });
    }
    private static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception failure) { throw new IllegalArgumentException(failure); }
        return result;
    }
    private static JSONObject error(String id, String type, String text) { return json("requestId", BridgePolicy.requestId(id), "type", type, "ok", false, "error", text); }
    private static String message(Exception error) { return error.getMessage() != null && !error.getMessage().isEmpty() ? error.getMessage() : "Không xử lý được tệp. Hãy kiểm tra rồi thử lại."; }
    private static JSONObject mapJson(ImportedMapStore.MapFile map) { return json("id", map.id, "name", map.name, "kmlBase64", Base64.encodeToString(map.kml, Base64.NO_WRAP)); }
    private void result(JSONObject payload) {
        if (!trusted()) return;
        String text = payload.toString();
        if (text.length() < CHUNK) {
            web.evaluateJavascript("if(window.HPTelecomMapBridge&&HPTelecomMapBridge.onNativeResult)HPTelecomMapBridge.onNativeResult(" + text + ");", null); return;
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > 24 * 1024 * 1024) { toast("Dữ liệu bản đồ vượt giới hạn truyền an toàn."); return; }
        String encoded = Base64.encodeToString(utf8, Base64.NO_WRAP);
        chunks(UUID.randomUUID().toString(), encoded, 0, pageGeneration);
    }
    private void chunks(String id, String encoded, int index, int generation) {
        if (!trusted() || generation != pageGeneration) return;
        int total = (encoded.length() + CHUNK - 1) / CHUNK;
        if (index >= total) return;
        JSONObject chunk = json("id", id, "index", index, "total", total, "data", encoded.substring(index * CHUNK, Math.min(encoded.length(), (index + 1) * CHUNK)));
        web.evaluateJavascript("if(window.HPTelecomMapBridge&&HPTelecomMapBridge.receiveChunk)HPTelecomMapBridge.receiveChunk(" + chunk + ");", ignored -> chunks(id, encoded, index + 1, generation));
    }
    private boolean locationGranted() { return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    private void cancelLocation() { if (locationCallback != null) locationCallback.invoke(locationOrigin, false, false); locationCallback = null; locationOrigin = null; }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LOCATION_PERMISSION && locationCallback != null) {
            locationCallback.invoke(locationOrigin, trusted() && locationGranted(), false); locationCallback = null; locationOrigin = null;
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { if (web != null) web.saveState(state); super.onSaveInstanceState(state); }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onPause() { if (web != null) web.onPause(); CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        destroyed = true; cancelLocation(); pendingSave = null; io.shutdown();
        if (web != null) { web.removeJavascriptInterface("HPTelecomMapNative"); web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }
    private final class NativeBridge {
        @JavascriptInterface public String getVersion() { return json("version", BuildConfig.VERSION_NAME, "platform", "android", "persistentImports", true, "maxImportBytes", BridgePolicy.MAX_FILE, "maxKmlBytes", BridgePolicy.MAX_KML, "maxMaps", BridgePolicy.MAX_MAPS).toString(); }
        @JavascriptInterface public void pickMapFile(String id) { runOnUiThread(() -> { if (trusted()) chooseMap(id); }); }
        @JavascriptInterface public void listMapFiles(String id) { runOnUiThread(() -> { if (trusted()) listMaps(id); }); }
        @JavascriptInterface public void shareKmz(String data, String name) { runOnUiThread(() -> { if (trusted()) exportKmz(data, name, false); }); }
        @JavascriptInterface public void saveKmz(String data, String name) { runOnUiThread(() -> { if (trusted()) exportKmz(data, name, true); }); }
    }
}
