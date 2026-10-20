package vn.hptelecom.map;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Native HP Map screen. Route content is clearly marked as a local sample until a server API exists. */
public final class MapHomeActivity extends Activity {
    private static final int PICK_MAP = 210, LOCATION_PERMISSION = 211;
    private static final int BG = Color.rgb(8, 43, 57), PANEL = Color.rgb(12, 58, 72), ACCENT = Color.rgb(25, 200, 218), INK = Color.rgb(236, 248, 250), MUTED = Color.rgb(160, 194, 202);
    private final List<Route> routes = new ArrayList<>();
    private final List<String> folders = new ArrayList<>();
    private final List<PointF> points = new ArrayList<>(), drawnLine = new ArrayList<>();
    private final List<List<PointF>> localLines = new ArrayList<>();
    private final List<PointF> routePoints = new ArrayList<>();
    private LinearLayout list, root;
    private MapCanvas map;
    private TextView selectedRouteLabel, sampleLabel, modeLabel;
    private EditText search;
    private Spinner typeFilter, capacityFilter;
    private Route selectedRoute;
    private String mode = "";
    private String basemap = "Đường phố";
    private ImportedMapStore imports;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        folders.add("Mặc định");
        seedRoutes(); imports = new ImportedMapStore(new java.io.File(getFilesDir(), "imported-maps")); loadLocalMap();
        buildScreen(); renderRoutes();
    }

    private void seedRoutes() {
        routes.add(new Route("DLA01-DLA02", "DWDM", "24FO"));
        routes.add(new Route("DLB01-DLB07", "CSG", "24FO"));
        routes.add(new Route("DLC03-DLC09", "Lastmile", "12FO"));
        routes.add(new Route("DLD02-DLD04", "Lastmile", "12FO"));
        routes.add(new Route("DLE06-DLE11", "AGG", "48FO"));
        routes.add(new Route("Tuyến mẫu khu vực A", "FTTH", "4FO"));
        selectedRoute = routes.get(2);
    }

    private void buildScreen() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.addView(buildToolbar());
        FrameLayout mapFrame = new FrameLayout(this);
        map = new MapCanvas(); mapFrame.addView(map, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout mapTop = new LinearLayout(this); mapTop.setGravity(Gravity.CENTER_VERTICAL); mapTop.setPadding(dp(12), dp(8), dp(12), dp(8));
        mapTop.setBackgroundColor(0xCC08303E);
        selectedRouteLabel = label(selectedRoute.name, 15, true, INK); mapTop.addView(selectedRouteLabel, new LinearLayout.LayoutParams(0, dp(38), 1));
        Button edit = chip("✎"); edit.setContentDescription("Chỉnh tuyến"); edit.setOnClickListener(v -> editRoute()); mapTop.addView(edit);
        Button layer = chip("▤  " + basemap); layer.setOnClickListener(v -> chooseLayer()); mapTop.addView(layer);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP); mapFrame.addView(mapTop, topParams);
        LinearLayout mapTools = new LinearLayout(this); mapTools.setOrientation(LinearLayout.VERTICAL); mapTools.setPadding(dp(8), dp(8), dp(8), dp(8));
        mapTools.addView(tool("＋", "Thêm điểm", () -> setMode("point")));
        mapTools.addView(tool("／", "Vẽ đường", () -> setMode("line")));
        mapTools.addView(tool("✓", "Hoàn tất vẽ đường", this::finishLine));
        mapTools.addView(tool("▰", "Thư mục", this::showFolders));
        mapTools.addView(tool("◎", "Vị trí của tôi", this::locate));
        FrameLayout.LayoutParams toolsParams = new FrameLayout.LayoutParams(dp(56), -2, Gravity.START | Gravity.CENTER_VERTICAL); mapFrame.addView(mapTools, toolsParams);
        LinearLayout mapCaption = new LinearLayout(this); mapCaption.setGravity(Gravity.CENTER_VERTICAL); mapCaption.setPadding(dp(13), dp(5), dp(13), dp(5)); mapCaption.setBackgroundColor(0xDD08303E);
        TextView dot = label("●", 13, true, ACCENT); mapCaption.addView(dot); TextView name = label("  " + selectedRoute.name, 13, true, INK); mapCaption.addView(name);
        TextView type = label("  " + selectedRoute.type, 11, false, MUTED); mapCaption.addView(type); 
        FrameLayout.LayoutParams capParams = new FrameLayout.LayoutParams(-2, dp(34), Gravity.BOTTOM | Gravity.START); capParams.setMargins(dp(12), 0, 0, dp(12)); mapFrame.addView(mapCaption, capParams);
        root.addView(mapFrame, new LinearLayout.LayoutParams(-1, 0, 1.12f));

        LinearLayout sheet = new LinearLayout(this); sheet.setOrientation(LinearLayout.VERTICAL); sheet.setPadding(dp(14), dp(8), dp(14), dp(7)); sheet.setBackgroundColor(PANEL);
        LinearLayout handleRow = new LinearLayout(this); handleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = label("Tuyến cáp", 18, true, INK); handleRow.addView(heading, new LinearLayout.LayoutParams(0, dp(32), 1));
        sampleLabel = label("DỮ LIỆU MẪU", 10, true, ACCENT); handleRow.addView(sampleLabel);
        sheet.addView(handleRow);
        search = new EditText(this); search.setSingleLine(true); search.setTextColor(INK); search.setHintTextColor(MUTED); search.setHint("⌕  Tìm tên tuyến…"); search.setTextSize(14); search.setPadding(dp(12), 0, dp(12), 0); search.setBackground(round(0xFF0A3442, 12, 0xFF286171));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(43)); searchParams.topMargin = dp(5); sheet.addView(search, searchParams);
        LinearLayout filters = new LinearLayout(this); filters.setGravity(Gravity.CENTER_VERTICAL); filters.setWeightSum(2);
        typeFilter = spinner(new String[]{"Loại: Tất cả", "DWDM", "CSG", "AGG", "Lastmile", "FTTH"}); capacityFilter = spinner(new String[]{"Cáp: Tất cả", "4FO", "6FO", "8FO", "12FO", "24FO", "48FO"});
        filters.addView(typeFilter, new LinearLayout.LayoutParams(0, dp(40), 1)); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(40), 1); cp.leftMargin = dp(8); filters.addView(capacityFilter, cp); sheet.addView(filters);
        LinearLayout selectRow = new LinearLayout(this); selectRow.setGravity(Gravity.CENTER_VERTICAL); Button all = chip("Chọn tất cả"); all.setOnClickListener(v -> { for (Route route : routes) route.checked = true; renderRoutes(); }); selectRow.addView(all);
        TextView count = label("  6 tuyến", 12, false, MUTED); selectRow.addView(count); Button importButton = chip("＋ KMZ"); importButton.setOnClickListener(v -> pickMap()); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, dp(38)); ip.leftMargin = dp(8); selectRow.addView(importButton, ip); Button share = chip("Chia sẻ"); share.setOnClickListener(v -> shareMap()); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(38)); sp.leftMargin = dp(6); selectRow.addView(share, sp); sheet.addView(selectRow);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list); sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        modeLabel = label("Danh sách tuyến theo dữ liệu minh họa · Chưa đồng bộ server", 10, false, MUTED); modeLabel.setGravity(Gravity.CENTER); sheet.addView(modeLabel, new LinearLayout.LayoutParams(-1, dp(25)));
        root.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        search.addTextChangedListener(watcher()); typeFilter.setOnItemSelectedListener(filterListener()); capacityFilter.setOnItemSelectedListener(filterListener());
    }

    private View buildToolbar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12), 0, dp(8), 0); bar.setBackgroundColor(BG);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.hp_icon); logo.setScaleType(ImageView.ScaleType.FIT_CENTER); logo.setContentDescription("HP");
        LinearLayout.LayoutParams logoP = new LinearLayout.LayoutParams(dp(36), dp(36)); logoP.setMarginEnd(dp(9)); bar.addView(logo, logoP);
        LinearLayout title = new LinearLayout(this); title.setOrientation(LinearLayout.VERTICAL); TextView name = label("HP Map", 17, true, INK); TextView subtitle = label("Bản đồ tuyến cáp", 10, false, MUTED); title.addView(name); title.addView(subtitle); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button login = chip("Đăng nhập server"); login.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class))); bar.addView(login);
        Button share = tool("↗", "Chia sẻ", this::shareMap); bar.addView(share);
        return bar;
    }

    private void renderRoutes() {
        if (list == null) return; list.removeAllViews(); String query = search == null ? "" : search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        String type = typeFilter == null || typeFilter.getSelectedItemPosition() == 0 ? "" : String.valueOf(typeFilter.getSelectedItem());
        String cap = capacityFilter == null || capacityFilter.getSelectedItemPosition() == 0 ? "" : String.valueOf(capacityFilter.getSelectedItem());
        int total = 0, checked = 0;
        for (Route route : routes) {
            if (!route.name.toLowerCase(java.util.Locale.ROOT).contains(query) || !type.isEmpty() && !route.type.equals(type) || !cap.isEmpty() && !route.capacity.equals(cap)) continue;
            total++; if (route.checked) checked++;
            LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(11), dp(6), dp(10), dp(6)); card.setBackground(round(route == selectedRoute ? 0xFF104858 : 0xFF0A3442, 11, route == selectedRoute ? ACCENT : 0xFF225362));
            LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); TextView routeName = label(route.name, 14, true, INK); TextView sub = label(route.type + "  ·  " + route.capacity, 11, false, MUTED); words.addView(routeName); words.addView(sub); card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            TextView status = label(route.checked ? "●" : "○", 17, true, route.checked ? ACCENT : MUTED); card.addView(status);
            card.setOnClickListener(v -> { selectedRoute = route; selectedRouteLabel.setText(route.name); map.invalidate(); renderRoutes(); });
            LinearLayout.LayoutParams cardP = new LinearLayout.LayoutParams(-1, dp(56)); cardP.topMargin = dp(5); list.addView(card, cardP);
        }
        if (total == 0) { TextView empty = label("Không có tuyến phù hợp", 12, false, MUTED); empty.setGravity(Gravity.CENTER); list.addView(empty, new LinearLayout.LayoutParams(-1, dp(55))); }
    }

    private void setMode(String next) {
        mode = mode.equals(next) ? "" : next;
        if (mode.equals("point")) toast("Chạm vào bản đồ để thêm điểm.");
        else if (mode.equals("line")) toast("Chạm liên tiếp để vẽ đường; nhấn dấu ✓ để lưu.");
        else if (mode.equals("adjust")) toast("Chạm vào bản đồ để thêm một điểm vào tuyến.");
        modeLabel.setText(mode.isEmpty() ? "Danh sách tuyến mẫu · Chưa đồng bộ server" : mode.equals("point") ? "Chế độ thêm điểm · Chạm vào bản đồ" : mode.equals("adjust") ? "Chỉnh tuyến · Chạm để thêm điểm" : "Chế độ vẽ đường · Chạm để đặt các điểm");
        map.invalidate();
    }

    private void editRoute() {
        String[] choices = {"Thêm vị trí", "Đổi tuyến"};
        new AlertDialog.Builder(this).setTitle("Chỉnh tuyến · " + selectedRoute.name).setItems(choices, (d, which) -> setMode(which == 0 ? "point" : "adjust")).setNegativeButton("Đóng", null).show();
    }

    private void finishLine() {
        if (!mode.equals("line") || drawnLine.size() < 2) { toast("Cần ít nhất 2 điểm để lưu đường."); return; }
        localLines.add(new ArrayList<>(drawnLine)); drawnLine.clear(); mode = ""; persistLocalMap(); map.invalidate();
        modeLabel.setText("Đường đã lưu trên thiết bị · Chưa đồng bộ server"); toast("Đã lưu đường trên thiết bị.");
    }

    private void createFolder() {
        EditText input = new EditText(this); input.setHint("Tên thư mục"); input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Tạo thư mục").setView(input).setNegativeButton("Hủy", null).setPositiveButton("Tạo", (d, w) -> {
            String value = input.getText().toString().trim(); if (value.isEmpty()) { toast("Nhập tên thư mục."); return; }
            if (!folders.contains(value)) folders.add(value); persistLocalMap(); toast("Đã tạo thư mục " + value + " trên thiết bị.");
        }).show();
    }

    private void showFolders() {
        String[] items = folders.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Thư mục bản đồ").setItems(items, (dialog, which) -> toast(folders.get(which)))
            .setPositiveButton("＋ Tạo thư mục", (dialog, which) -> createFolder()).setNegativeButton("Đóng", null).show();
    }

    private void chooseLayer() {
        String[] layers = {"Đường phố", "Vệ tinh", "Vệ tinh + đường phố", "Địa hình"};
        new AlertDialog.Builder(this).setTitle("Lớp bản đồ").setSingleChoiceItems(layers, java.util.Arrays.asList(layers).indexOf(basemap), (d, which) -> { basemap = layers[which]; map.invalidate(); d.dismiss(); toast("Đang xem lớp: " + basemap + " (bản đồ mẫu)."); }).setNegativeButton("Đóng", null).show();
    }

    private void pickMap() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/vnd.google-earth.kmz", "application/vnd.google-earth.kml+xml", "application/zip", "application/xml"});
        startActivityForResult(intent, PICK_MAP);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MAP || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] bytes = BridgePolicy.readBounded(in, BridgePolicy.MAX_FILE);
                String name = "Bản đồ đã nhập";
                try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (c != null && c.moveToFirst()) name = c.getString(0); }
                ImportedMapStore.MapFile file = imports.add(name, bytes); String shown = file.name;
                runOnUiThread(() -> toast(file.duplicate ? "Bản đồ này đã được nhập." : "Đã nhập " + shown + " vào thiết bị."));
            } catch (Exception e) { runOnUiThread(() -> toast(e.getMessage() == null ? "Không đọc được KMZ/KML." : e.getMessage())); }
        }).start();
    }

    private void shareMap() {
        String text = "HP Map · " + selectedRoute.name + "\nLoại: " + selectedRoute.type + " · Cáp: " + selectedRoute.capacity + "\nBản đồ tuyến cáp mẫu.";
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Chia sẻ bản đồ HP Map"));
    }

    private void loadLocalMap() {
        android.content.SharedPreferences prefs = getSharedPreferences("hp-map-local", MODE_PRIVATE);
        try {
            org.json.JSONArray names = new org.json.JSONArray(prefs.getString("folders", "[]"));
            for (int i=0;i<names.length() && folders.size()<50;i++) { String name=names.optString(i, ""); if(!name.isEmpty()&&!folders.contains(name))folders.add(name); }
            readPoints(new org.json.JSONArray(prefs.getString("points", "[]")), points);
            readPoints(new org.json.JSONArray(prefs.getString("route", "[]")), routePoints);
            org.json.JSONArray lines = new org.json.JSONArray(prefs.getString("lines", "[]"));
            for (int i=0;i<lines.length() && localLines.size()<100;i++) { ArrayList<PointF> line=new ArrayList<>(); readPoints(lines.optJSONArray(i),line); if(line.size()>1)localLines.add(line); }
        } catch (Exception ignored) { points.clear(); localLines.clear(); routePoints.clear(); }
        if (routePoints.size()<2) { routePoints.add(new PointF(.08f,.78f)); routePoints.add(new PointF(.24f,.66f)); routePoints.add(new PointF(.43f,.61f)); routePoints.add(new PointF(.62f,.48f)); routePoints.add(new PointF(.92f,.22f)); }
    }
    private void readPoints(org.json.JSONArray array, List<PointF> into) {
        if(array==null)return; for(int i=0;i<array.length()&&into.size()<300;i++){org.json.JSONArray pair=array.optJSONArray(i);if(pair!=null&&pair.length()==2){double x=pair.optDouble(0,-1),y=pair.optDouble(1,-1);if(x>=0&&x<=1&&y>=0&&y<=1)into.add(new PointF((float)x,(float)y));}}
    }
    private org.json.JSONArray pointsJson(List<PointF> list) { org.json.JSONArray out=new org.json.JSONArray();for(PointF point:list){try{org.json.JSONArray pair=new org.json.JSONArray();pair.put(point.x);pair.put(point.y);out.put(pair);}catch(org.json.JSONException ignored){}}return out; }
    private void persistLocalMap() {
        try { org.json.JSONArray names=new org.json.JSONArray();for(String name:folders)names.put(name);org.json.JSONArray lines=new org.json.JSONArray();for(List<PointF> line:localLines)lines.put(pointsJson(line));getSharedPreferences("hp-map-local",MODE_PRIVATE).edit().putString("folders",names.toString()).putString("points",pointsJson(points).toString()).putString("route",pointsJson(routePoints).toString()).putString("lines",lines.toString()).apply(); } catch (Exception ignored) { }
    }

    private void locate() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION); return;
        }
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE); Location found = null;
            for (String provider : manager.getProviders(true)) { Location loc = manager.getLastKnownLocation(provider); if (loc != null && (found == null || loc.getTime() > found.getTime())) found = loc; }
            if (found == null) toast("Chưa có vị trí GPS gần đây."); else toast(String.format(java.util.Locale.US, "Vị trí: %.6f, %.6f", found.getLatitude(), found.getLongitude()));
        } catch (SecurityException e) { toast("Chưa được cấp quyền vị trí."); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) { super.onRequestPermissionsResult(request, permissions, results); if (request == LOCATION_PERMISSION) { if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) locate(); else toast("Chưa cấp quyền vị trí."); } }

    private Button tool(String text, String description, Runnable action) { Button b = new Button(this); b.setText(text); b.setTextSize(21); b.setTextColor(Color.rgb(31, 54, 60)); b.setContentDescription(description); b.setBackground(round(0xFFF7F3FA, 28, 0xFFE9F7FA)); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(0, 0, 0, 0); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44)); p.bottomMargin = dp(8); b.setLayoutParams(p); b.setOnClickListener(v -> action.run()); return b; }
    private Button chip(String text) { Button b = new Button(this); b.setText(text); b.setTextSize(11); b.setTextColor(INK); b.setAllCaps(false); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(8), 0, dp(8), 0); b.setBackground(round(0xFF174959, 9, 0xFF2B6676)); b.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(38))); return b; }
    private TextView label(String text, int size, boolean bold, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private android.graphics.drawable.GradientDrawable round(int color, int radius, int stroke) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private Spinner spinner(String[] values) { Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values)); s.setBackground(round(0xFF0A3442, 8, 0xFF286171)); return s; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private android.text.TextWatcher watcher() { return new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){renderRoutes();} public void afterTextChanged(android.text.Editable e){} }; }
    private android.widget.AdapterView.OnItemSelectedListener filterListener() { return new android.widget.AdapterView.OnItemSelectedListener() { public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id){renderRoutes();} }; }

    private final class MapCanvas extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path route = new Path();
        MapCanvas() { super(MapHomeActivity.this); setBackgroundColor(Color.rgb(240, 238, 230)); setOnTouchListener((v, e) -> { if (e.getAction() != MotionEvent.ACTION_UP || mode.isEmpty()) return true; float x=e.getX()/Math.max(1,getWidth()), y=e.getY()/Math.max(1,getHeight()); if (mode.equals("point")) { points.add(new PointF(x,y)); mode=""; persistLocalMap(); modeLabel.setText("Đã thêm điểm trên thiết bị · Chưa đồng bộ"); } else if(mode.equals("adjust")){int index=Math.max(1,Math.min(routePoints.size()-1,routePoints.size()/2));routePoints.add(index,new PointF(x,y));mode="";persistLocalMap();modeLabel.setText("Tuyến đã đổi trên thiết bị · Chưa đồng bộ server");} else { drawnLine.add(new PointF(x,y)); modeLabel.setText("Đang vẽ · " + drawnLine.size() + " điểm. Chạm tiếp hoặc nhấn ✓ để lưu."); } invalidate(); return true; }); }
        @Override protected void onDraw(Canvas c) { super.onDraw(c); float w=getWidth(),h=getHeight(); p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(240,238,230)); c.drawRect(0,0,w,h,p);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(23)); p.setColor(Color.rgb(215,229,211)); c.drawLine(-20,h*.88f,w*.9f,h*.05f,p); c.drawLine(w*.25f,h*.2f,w*.62f,h*1.2f,p);
            p.setStrokeWidth(dp(10)); p.setColor(Color.WHITE); c.drawLine(-20,h*.88f,w*.9f,h*.05f,p); c.drawLine(w*.25f,h*.2f,w*.62f,h*1.2f,p); c.drawLine(w*.05f,h*.35f,w*1.1f,h*.62f,p);
            p.setStrokeWidth(dp(2)); p.setColor(Color.rgb(224,174,100)); c.drawLine(-20,h*.88f,w*.9f,h*.05f,p); c.drawLine(w*.25f,h*.2f,w*.62f,h*1.2f,p);
            p.setStrokeWidth(dp(2)); p.setColor(Color.rgb(25,174,195)); route.reset(); route.moveTo(routePoints.get(0).x*w,routePoints.get(0).y*h); for(int i=1;i<routePoints.size();i++)route.lineTo(routePoints.get(i).x*w,routePoints.get(i).y*h); c.drawPath(route,p);
            marker(c,w*.16f,h*.72f,"MX",Color.rgb(4,54,68)); marker(c,w*.46f,h*.56f,"DP",Color.rgb(4,54,68)); marker(c,w*.78f,h*.38f,"MX",Color.rgb(4,54,68));
            for(PointF point:points){p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(110,185,0));c.drawCircle(point.x*w,point.y*h,dp(6),p);p.setColor(Color.WHITE);p.setTextSize(dp(11));c.drawText("MX",point.x*w+dp(7),point.y*h,p);}
            for(List<PointF> line:localLines)drawLocalLine(c,w,h,line,Color.rgb(239,106,138));
            if(!drawnLine.isEmpty())drawLocalLine(c,w,h,drawnLine,Color.rgb(236,169,28));
            p.setStyle(Paint.Style.FILL);p.setColor(0xAAFFFFFF);p.setTextSize(dp(12));c.drawText("Buôn Ma Thuột",w*.32f,h*.38f,p);p.setTextSize(dp(10));p.setColor(0xFF65716D);c.drawText("Nền bản đồ minh họa · "+basemap,dp(12),h-dp(12),p);
        }
        private void drawLocalLine(Canvas c,float w,float h,List<PointF> line,int color){if(line.size()<2)return;p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(color);Path user=new Path();user.moveTo(line.get(0).x*w,line.get(0).y*h);for(int i=1;i<line.size();i++)user.lineTo(line.get(i).x*w,line.get(i).y*h);c.drawPath(user,p);}
        private void marker(Canvas c,float x,float y,String text,int color){p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawCircle(x,y,dp(12),p);p.setColor(color);c.drawRoundRect(x-dp(17),y-dp(31),x+dp(17),y-dp(5),dp(4),dp(4),p);p.setColor(Color.WHITE);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(11));c.drawText(text,x,y-dp(13),p);p.setTextAlign(Paint.Align.LEFT);}
    }

    private static final class Route { final String name,type,capacity; boolean checked=true; Route(String n,String t,String c){name=n;type=t;capacity=c;} }
}

