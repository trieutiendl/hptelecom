/* HP Map: map data and login stay separate from the server administration website. */
(() => {
  "use strict";
  const $ = id => document.getElementById(id);
  const esc = value => String(value == null ? "" : value).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const uid = prefix => prefix + "-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 9);
  const icons = {
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-2l2 3M4 16l2 3a7 7 0 0 0 12-2"/>',
    account: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    fit: '<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/><path d="m7 12 3-4 4 8 3-4"/>',
    locate: '<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3"/>',
    route: '<circle cx="6" cy="6" r="2"/><circle cx="18" cy="18" r="2"/><path d="M8 6h8a4 4 0 0 1 0 8H8a4 4 0 0 0 0 8"/>',
    layers: '<path d="m12 3 10 5-10 5L2 8l10-5Zm-10 9 10 5 10-5M2 16l10 5 10-5"/>',
    point: '<path d="M16 10a4 4 0 0 0-8 0c0 4 4 8 4 8s4-4 4-8Z"/><circle cx="12" cy="10" r="1"/><path d="M5 3v6M2 6h6"/>',
    line: '<path d="m3 18 6-11 7 7 5-10"/><circle cx="3" cy="18" r="1"/><circle cx="9" cy="7" r="1"/><circle cx="16" cy="14" r="1"/><circle cx="21" cy="4" r="1"/>',
    folder: '<path d="M3 6a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    down: '<path d="m6 9 6 6 6-6"/>',
    right: '<path d="m9 6 6 6-6 6"/>',
    back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
    search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/>',
    map: '<path d="m3 5 6-2 6 2 6-2v16l-6 2-6-2-6 2V5Zm6-2v16m6-14v16"/>',
    import: '<path d="M14 2H5v20h14V7l-5-5Zm0 0v5h5M12 10v8m-3-3 3 3 3-3"/>',
    export: '<path d="M14 2H5v20h14V7l-5-5Zm0 0v5h5M12 18v-8m-3 3 3-3 3 3"/>',
    eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
    eyeoff: '<path d="m3 3 18 18M9 5c7-2 13 7 13 7a24 24 0 0 1-4 4M5 6a24 24 0 0 0-3 6s4 7 10 7a11 11 0 0 0 4-1"/>',
    more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    undo: '<path d="M3 10h11a6 6 0 0 1 0 12M3 10l5-5m-5 5 5 5"/>',
    share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m9 11 6-4m-6 6 6 4"/>',
    check: '<path d="m5 12 4 4L20 5"/>',
    pin: '<path d="M19 9c0 6-7 13-7 13S5 15 5 9a7 7 0 0 1 14 0Z"/><circle cx="12" cy="9" r="2"/>'
  };
  const icon = name => '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (icons[name] || icons.map) + "</svg>";
  const iconIds = { refresh: "refresh", account: "account", fit: "fit", locate: "locate", edit: "route", layers: "layers", "add-point": "point", "add-line": "line", "open-folders": "folder", "close-sheet": "down", "close-modal": "close", "close-login": "back", "undo-drawing": "undo", "cancel-drawing": "close", "search-icon": "search" };
  Object.keys(iconIds).forEach(id => $(id).innerHTML = icon(iconIds[id]));
  ["map", "routes", "import", "export"].forEach((name, i) => $("nav-" + name).firstElementChild.innerHTML = icon(["map", "folder", "import", "export"][i]));

  const pending = new Map();
  let requestNo = 0;
  function native(action, args = {}) {
    return new Promise((resolve, reject) => {
      if (!window.HPMap || typeof window.HPMap.request !== "function") {
        if (action === "session") return resolve({ user: null, routes: [] });
        return reject(new Error("Chức năng này cần mở trong ứng dụng HP Map."));
      }
      const id = String(++requestNo);
      const timeout = setTimeout(() => {
        pending.delete(id);
        const error = new Error(action === "transferSharedFeatures" ? "Chưa xác nhận được kết quả. Hãy cập nhật bản đồ trước khi thao tác tiếp." : "Chưa nhận được phản hồi. Vui lòng thử lại.");
        error.transferUncertain = action === "transferSharedFeatures"; reject(error);
      }, ["import", "export", "serverLogin"].includes(action) ? 300000 : action === "transferSharedFeatures" ? 180000 : 60000);
      pending.set(id, { resolve, reject, timeout, action });
      try { window.HPMap.request(id, action, JSON.stringify(args)); }
      catch (error) { clearTimeout(timeout); pending.delete(id); reject(error); }
    });
  }
  window.HPMapResult = (id, payload) => {
    const call = pending.get(String(id));
    if (!call) return;
    pending.delete(String(id)); clearTimeout(call.timeout);
    try {
      const result = typeof payload === "string" ? JSON.parse(payload) : payload;
      if (result && (result.ok === false || result.error)) { const error = new Error(result.error || "Không thực hiện được thao tác."); error.status = result.status || result.code || 0; error.authExpired = result.authExpired || result.status === 401 || result.code === 401; error.transferUncertain = call.action === "transferSharedFeatures" && !error.status && !error.authExpired; call.reject(error); }
      else call.resolve(result || {});
    } catch (error) { if (call.action === "transferSharedFeatures") error.transferUncertain = true; call.reject(error); }
  };
  let toastTimer;
  function toast(message, duration = 4200) { $("toast").textContent = message; $("toast").hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $("toast").hidden = true, duration); }
  let state;
  try { state = JSON.parse(localStorage.getItem("hpmap-v2") || "null"); } catch (_) {}
  if (!state || !Array.isArray(state.folders) || !Array.isArray(state.features)) state = { folders: [{ id: "default", name: "Thư mục mặc định", parent: "", visible: true }], features: [], importedIds: [], activeFolder: "default", selected: [] };
  if (!state.folders.length) state.folders.push({ id: "default", name: "Thư mục mặc định", parent: "", visible: true });
  if (!Array.isArray(state.importedIds)) state.importedIds = [];
  if (!Array.isArray(state.dismissedImports)) state.dismissedImports = [];
  let routes = [], sharedLayers = [], user = null, selected = new Set(Array.isArray(state.selected) ? state.selected : []), sharedSelected = new Set(Array.isArray(state.sharedSelected) ? state.sharedSelected : []), loadedOnce = false, sharedLoaded = false, importsLoaded = false, activeRoute = "", drawing = null, locationLayer = null, modalCloseAction = null, lastFocus = null, refreshing = false, authExpired = false;
  let featureSelection = null, transferBusy = false, sharedTransferNeedsRefresh = false, sessionEpoch = 0;
  const openedFolders = new Set(), routeColors = ["#16b4c6", "#f6b44a", "#a18bea", "#e384a9", "#54bc87"];
  function isAdmin() { return !!user && user.role === "admin" && !authExpired; }
  function requireAdmin() { if (isAdmin()) return true; toast("Chỉ quản trị viên được thay đổi bản đồ."); return false; }
  function applyPermissions() {
    const admin = isAdmin();
    ["creation-tools", "edit", "nav-import", "folders-tab", "new-folder", "select-local-features"].forEach(id => $(id).hidden = !admin);
    $("close-login").hidden = !user; $("continue-local").hidden = !user;
    if (!admin) { featureSelection = null; renderSelectionBar(); cancelDrawing(); if (!$("modal-backdrop").hidden) closeModal(true); if (!$("folders-panel").hidden) changePanel("routes"); }
  }
  function persist() {
    state.selected = Array.from(selected);
    state.sharedSelected = Array.from(sharedSelected);
    try { localStorage.setItem("hpmap-v2", JSON.stringify(state)); }
    catch (_) { toast("Bộ nhớ thiết bị đã đầy. Hãy xuất KMZ để giữ bản đồ.", 6000); return false; }
    return true;
  }
  const map = L.map("map", { zoomControl: false, attributionControl: true }).setView([12.68, 108.05], 11);
  const baseMapChoices = [
    { id: "street", title: "Đường phố", source: "OpenStreetMap", icon: "map" },
    { id: "satellite", title: "Vệ tinh", source: "MapTiler", icon: "layers", requiresKey: true },
    { id: "hybrid", title: "Vệ tinh + đường phố", source: "MapTiler · Có tên đường", icon: "layers", requiresKey: true },
    { id: "terrain", title: "Địa hình", source: "OpenTopoMap", icon: "map" }
  ];
  let baseMapConfig = { provider: "maptiler", configured: false, key: "", satelliteAttribution: "", hybridAttribution: "" };
  let baseMapKey = "", baseMapLayer = null, baseMapGeneration = 0, baseMapTimer = null, configGeneration = 0, lastTileError = 0;
  if (!baseMapChoices.some(choice => choice.id === state.basemap)) state.basemap = "street";
  function tileSource(key) {
    const osm = '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>';
    if (key === "street") return { url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png", maxNativeZoom: 19, attribution: osm };
    if (key === "terrain") return { url: "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", maxNativeZoom: 17, attribution: osm + ', SRTM | © <a href="https://opentopomap.org">OpenTopoMap</a> (<a href="https://creativecommons.org/licenses/by-sa/3.0/">CC-BY-SA</a>)' };
    if (!baseMapConfig.configured || !baseMapConfig.key) return null;
    const style = key === "satellite" ? "satellite-v4" : key === "hybrid" ? "hybrid-v4" : "";
    if (!style) return null;
    return {
      url: "https://api.maptiler.com/maps/" + style + "/256/{z}/{x}/{y}." + (key === "satellite" ? "jpg" : "png") + "?key=" + encodeURIComponent(baseMapConfig.key),
      maxNativeZoom: 19,
      attribution: '© <a href="https://www.maptiler.com/copyright/">MapTiler</a>' + (baseMapConfig[key + "Attribution"] ? " | " + esc(baseMapConfig[key + "Attribution"]) : " | " + osm)
    };
  }
  function setBaseMap(key, options = {}) {
    const source = tileSource(key);
    if (!source) return false;
    if (baseMapKey === key && baseMapLayer && !options.reload) {
      if (options.persist !== false && state.basemap !== key) { state.basemap = key; persist(); }
      return true;
    }
    const generation = ++baseMapGeneration, previous = baseMapLayer;
    clearTimeout(baseMapTimer);
    let loaded = 0, failed = 0, previousRemoved = false;
    const next = L.tileLayer(source.url, { maxZoom: 19, maxNativeZoom: source.maxNativeZoom, attribution: source.attribution, zIndex: 1, keepBuffer: 2, updateWhenIdle: true });
    const removePrevious = () => { if (previous && !previousRemoved) { map.removeLayer(previous); previousRemoved = true; } };
    const notifyFailure = () => {
      if (generation !== baseMapGeneration) return;
      clearTimeout(baseMapTimer); removePrevious();
      if (key !== "street") {
        setBaseMap("street");
        toast("Chưa tải được lớp " + baseMapChoices.find(choice => choice.id === key).title + ". Đã chuyển về Đường phố; tuyến và điểm vẫn được giữ.", 6500);
      } else if (Date.now() - lastTileError > 30000) {
        lastTileError = Date.now();
        toast("Chưa tải được nền bản đồ. Kiểm tra kết nối mạng; tuyến và điểm vẫn được giữ.", 6000);
      }
    };
    next.on("tileload", () => {
      if (generation !== baseMapGeneration) return;
      loaded++; clearTimeout(baseMapTimer);
    });
    next.on("loading", () => { if (generation === baseMapGeneration) { loaded = 0; failed = 0; } });
    next.on("tileerror", () => { if (generation === baseMapGeneration) { failed++; if (!loaded && failed >= 3) notifyFailure(); } });
    next.on("load", () => {
      if (generation !== baseMapGeneration) return;
      if (loaded) {
        removePrevious();
        if (failed && Date.now() - lastTileError > 30000) { lastTileError = Date.now(); toast("Một phần nền bản đồ chưa tải được. Có thể chọn lại lớp hoặc kiểm tra mạng."); }
      } else if (failed) notifyFailure();
    });
    removePrevious();
    baseMapLayer = next; baseMapKey = key; next.addTo(map);
    if (options.persist !== false) { state.basemap = key; persist(); }
    $("layers").setAttribute("aria-label", "Lớp bản đồ: " + baseMapChoices.find(choice => choice.id === key).title);
    baseMapTimer = setTimeout(() => { if (!loaded) notifyFailure(); }, 16000);
    return true;
  }
  function acceptBaseMapConfig(result) {
    baseMapConfig = {
      provider: "maptiler",
      configured: !!result && result.provider === "maptiler" && result.configured === true && typeof result.key === "string" && !!result.key,
      key: result && typeof result.key === "string" ? result.key : "",
      satelliteAttribution: result && typeof result.satelliteAttribution === "string" ? result.satelliteAttribution.slice(0, 2048) : "",
      hybridAttribution: result && typeof result.hybridAttribution === "string" ? result.hybridAttribution.slice(0, 2048) : ""
    };
    if (!baseMapConfig.configured) baseMapConfig.key = "";
  }
  async function loadBaseMapConfig() {
    const generation = ++configGeneration;
    if (!user) return;
    try {
      const result = await native("basemapConfig");
      if (generation !== configGeneration || !user) return;
      const oldKey = baseMapConfig.key;
      acceptBaseMapConfig(result);
      if (!setBaseMap(state.basemap, { reload: oldKey !== baseMapConfig.key && ["satellite", "hybrid"].includes(state.basemap) })) setBaseMap("street", { persist: false });
    } catch (_) {
      if (generation === configGeneration && ["satellite", "hybrid"].includes(baseMapKey)) setBaseMap("street", { persist: false });
    }
  }
  setBaseMap(state.basemap === "terrain" ? "terrain" : "street", { persist: false });
  const serverLayers = L.featureGroup().addTo(map), localLayers = L.featureGroup().addTo(map), draftLayers = L.featureGroup().addTo(map);
  const visibleMarkers = route => route.markers.filter(point => point.display_on_map !== false);
  function ll(value) {
    if (!value) return null;
    const rawLat = Array.isArray(value) ? value[0] : (value.lat ?? value.latitude ?? value.latitude_value);
    const rawLon = Array.isArray(value) ? value[1] : (value.lon ?? value.lng ?? value.longitude ?? value.longitude_value);
    if (rawLat == null || rawLon == null || String(rawLat).trim() === "" || String(rawLon).trim() === "") return null;
    const lat = Number(rawLat), lon = Number(rawLon);
    return Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 ? [lat, lon] : null;
  }
  function normalizeRoute(route, index) {
    const key = String(route.key ?? route.route_key ?? route.id ?? route.name ?? index);
    let lines = route.lines || route.polylines || route.path || route.coordinates || [];
    if (lines.length && ll(lines[0])) lines = [lines];
    lines = (Array.isArray(lines) ? lines : []).map(line => (Array.isArray(line) ? line : line.coordinates || line.points || []).map(ll).filter(Boolean)).filter(line => line.length > 1);
    const markers = (route.markers || route.points || []).map(point => {
      const coords = ll(point); if (!coords) return null;
      return { ...point, coords, label: String(point.label || point.kind || point.type || point.name || "Điểm"), position: String(point.position || point.position_name || point.location || point.name || "") };
    }).filter(Boolean);
    return { ...route, key, name: String(route.name || route.route_name || route.tuyen || key), type: String(route.connection_type || route.connection || route.network_type || route.type || ""), capacity: String(route.capacity || route.cable_capacity || route.fiber_count || "").replace(/^(\d+)$/, "$1FO").toUpperCase(), lines, markers, color: routeColors[index % routeColors.length] };
  }
  function normalizeShared(layer, index) {
    const normalized = normalizeRoute(layer, index);
    normalized.shared = true;
    normalized.folderId = String(layer.folder_id || "");
    normalized.revision = Number.isInteger(layer.revision) ? layer.revision : null;
    normalized.lineFeatures = (Array.isArray(layer.lineFeatures) ? layer.lineFeatures : []).map(feature => ({
      ...feature, feature_id: String(feature.feature_id || ""),
      coords: (Array.isArray(feature.coords) ? feature.coords : []).map(ll).filter(Boolean)
    })).filter(feature => feature.coords.length > 1);
    normalized.parent = String(layer.parent_id || layer.parent || layer.folder_id || "");
    normalized.sourceType = String(layer.source_type || layer.source || "");
    const collection = layer.geojson || layer.data || layer.feature_collection;
    const features = Array.isArray(layer.features) ? layer.features : collection && Array.isArray(collection.features) ? collection.features : [];
    const convert = coords => ll(Array.isArray(coords) ? [coords[1], coords[0]] : null);
    features.forEach(feature => {
      const geometry = feature.geometry || {}, properties = feature.properties || {};
      if (geometry.type === "Point") { const coords = convert(geometry.coordinates); if (coords) normalized.markers.push({ coords, display_on_map: properties.display_on_map, label: String(properties.label || properties.kind || properties.name || "Điểm"), position: String(properties.name || properties.position || ""), note: String(properties.note || properties.description || "") }); }
      else if (geometry.type === "LineString") { const coords = (geometry.coordinates || []).map(convert).filter(Boolean); if (coords.length > 1) normalized.lines.push(coords); }
      else if (geometry.type === "MultiLineString") (geometry.coordinates || []).forEach(line => { const coords = line.map(convert).filter(Boolean); if (coords.length > 1) normalized.lines.push(coords); });
    });
    return normalized;
  }
  function receiveSession(result, fit = true) {
    const hadUser = !!user;
    const identity = account => account ? String(account.username || account.id || account.name || account.display_name || "") + "|" + String(account.role || "") : "";
    if (identity(user) !== identity(result.user)) sessionEpoch++;
    user = result.user || null;
    routes = (Array.isArray(result.routes) ? result.routes : []).map(normalizeRoute);
    if ((!loadedOnce || !hadUser) && selected.size === 0) routes.forEach(route => selected.add(route.key));
    if (Array.isArray(result.sharedLayers) || Array.isArray(result.shared_layers)) {
      sharedLayers = (result.sharedLayers || result.shared_layers).map(normalizeShared);
      if ((!sharedLoaded || !hadUser) && sharedSelected.size === 0) sharedLayers.forEach(layer => sharedSelected.add(layer.key));
      const validShared = new Set(sharedLayers.map(layer => layer.key)); sharedSelected.forEach(key => { if (!validShared.has(key)) sharedSelected.delete(key); });
      sharedLoaded = true;
    }
    if (!user) {
      featureSelection = null;
      selected.clear(); sharedSelected.clear(); sharedLayers = [];
      configGeneration++; acceptBaseMapConfig(null);
      if (["satellite", "hybrid"].includes(baseMapKey)) setBaseMap("street", { persist: false });
    }
    const validKeys = new Set(routes.map(route => route.key));
    selected.forEach(key => { if (!validKeys.has(key)) selected.delete(key); });
    loadedOnce = true;
    authExpired = false;
    if (featureSelection && featureSelection.source === "shared") {
      const current = sharedLayers.filter(layer => String(layer.team || "") === featureSelection.team);
      if (!current.length || current.some(layer => layer.revision !== featureSelection.revision)) {
        featureSelection = null;
        if (!transferBusy) toast("Bản đồ đã thay đổi. Hãy chọn lại đối tượng cần sao chép hoặc di chuyển.");
      }
    }
    applyPermissions();
    $("connection").textContent = user ? ("Đã kết nối · " + (user.display_name || user.name || user.username || "HP Telecom")) : "Bản đồ trên máy";
    $("server-warning").hidden = !result.warning;
    $("server-warning").textContent = result.warning || "";
    if (result.warning) toast(result.warning, 6500);
    updateFilters(); renderRoutes(); renderShared(); renderMap(); persist();
    if (result.maps && isAdmin()) importMaps(result.maps, false);
    if (isAdmin() && !importsLoaded && window.HPMap) { importsLoaded = true; native("imports").then(payload => { if (isAdmin()) importMaps(payload.maps || [], false); }).catch(() => { importsLoaded = false; toast("Chưa đọc được KMZ đã lưu trong ứng dụng."); }); }
    if (!user) importsLoaded = false;
    if (fit && (routes.length || sharedLayers.length)) fitMap();
    $("login").hidden = !!user; $("password").value = "";
    if (user) loadBaseMapConfig();
  }
  function updateFilters() {
    const setOptions = (id, values) => {
      const element = $(id), value = element.value;
      element.replaceChildren(new Option("Tất cả", ""));
      values.forEach(v => element.add(new Option(v, v)));
      if (values.includes(value)) element.value = value;
    };
    setOptions("type-filter", Array.from(new Set(routes.map(route => route.type).filter(Boolean))).sort());
    setOptions("capacity-filter", Array.from(new Set(routes.map(route => route.capacity).filter(Boolean))).sort((a, b) => (parseInt(a, 10) || 0) - (parseInt(b, 10) || 0)));
  }
  function filteredRoutes() {
    const query = $("search").value.trim().toLocaleLowerCase("vi");
    return routes.filter(route => (!query || route.name.toLocaleLowerCase("vi").includes(query)) && (!$("type-filter").value || route.type === $("type-filter").value) && (!$("capacity-filter").value || route.capacity === $("capacity-filter").value));
  }
  function renderRoutes() {
    const visible = filteredRoutes(), list = $("route-list");
    list.replaceChildren();
    let lastType = null;
    visible.slice().sort((a, b) => a.type.localeCompare(b.type) || a.name.localeCompare(b.name, "vi", { numeric: true })).forEach(route => {
      if (route.type !== lastType) { const heading = document.createElement("div"); heading.className = "route-group-title"; heading.innerHTML = icon("folder") + "<strong>" + esc(route.type || "Chưa phân loại") + "</strong>"; list.appendChild(heading); lastType = route.type; }
      const row = document.createElement("div"); row.className = "route-row" + (selected.has(route.key) ? " selected" : "");
      const wrap = document.createElement("label"); wrap.className = "check-wrap";
      const check = document.createElement("input"); check.type = "checkbox"; check.checked = selected.has(route.key); check.setAttribute("aria-label", "Hiện tuyến " + route.name);
      check.onchange = () => { check.checked ? selected.add(route.key) : selected.delete(route.key); persist(); renderRoutes(); renderMap(); };
      wrap.appendChild(check);
      const button = document.createElement("button"); button.className = "route-open";
      button.innerHTML = '<span class="route-line" style="background:' + route.color + '"></span><span class="route-copy"><strong>' + esc(route.name) + "</strong><small>" + esc([route.type, route.capacity, visibleMarkers(route).length + " điểm hiển thị"].filter(Boolean).join(" · ")) + "</small></span>" + icon("right");
      button.onclick = () => { activeRoute = route.key; if (!selected.has(route.key)) { selected.add(route.key); persist(); renderRoutes(); renderMap(); } fitRoute(route); updateCaption(route); collapseSheet(); };
      row.append(wrap, button); list.appendChild(row);
    });
    $("route-count").textContent = routes.length + " tuyến";
    $("selected-count").textContent = selected.size + " đã chọn";
    $("select-all").checked = visible.length > 0 && visible.every(route => selected.has(route.key));
    $("select-all").indeterminate = visible.some(route => selected.has(route.key)) && !$("select-all").checked;
    $("select-all").disabled = !visible.length;
    const empty = $("route-empty"); empty.hidden = visible.length > 0;
    empty.replaceChildren(document.createTextNode(user ? "Không có tuyến phù hợp hoặc chưa có tọa độ được cấp quyền." : "Đăng nhập HP Telecom để xem bản đồ được chia sẻ."));
    if (!user) { const login = document.createElement("button"); login.className = "secondary"; login.textContent = "Đăng nhập HP Telecom"; login.onclick = openLogin; empty.appendChild(login); }
  }
  function markerIcon(label, local = false, picked = false) {
    const text = String(label || "Điểm").slice(0, 24);
    return L.divIcon({ className: "hp-marker " + (picked ? "picked " : "") + (local ? "local" : /MX/i.test(text) ? "mx" : /DP|DỰ PHÒNG/i.test(text) ? "dp" : ""), html: "<span>" + esc(text) + "</span>", iconSize: null, iconAnchor: [14, 15] });
  }
  function updateCaption(route) {
    const current = route || routes.find(item => item.key === activeRoute && selected.has(item.key));
    const localCount = isAdmin() ? state.features.filter(feature => folderVisible(feature.folder) && feature.display_on_map !== false).length : 0;
    const totalSelected = selected.size + sharedSelected.size;
    $("caption").innerHTML = '<span class="dot"></span><strong>' + esc(current ? current.name : totalSelected ? totalSelected + " tuyến / lớp đã chọn" : "HP Map") + "</strong><small>" + esc(current ? [current.type, current.capacity].filter(Boolean).join(" · ") : localCount ? localCount + " đối tượng trên máy" : isAdmin() ? "Tạo điểm · Vẽ đường · Nhập KMZ" : "Bản đồ tuyến cáp") + "</small>";
  }
  function folderVisible(id, visited = new Set()) {
    if (visited.has(id)) return false; visited.add(id);
    const folder = state.folders.find(item => item.id === id);
    return !!folder && folder.visible !== false && (!folder.parent || folderVisible(folder.parent, visited));
  }
  function renderMap() {
    serverLayers.clearLayers(); localLayers.clearLayers();
    [...routes.filter(route => selected.has(route.key)), ...sharedLayers.filter(layer => sharedSelected.has(layer.key))].forEach(route => {
      route.lines.forEach((line, index) => {
        const feature = route.shared && route.lineFeatures && route.lineFeatures[index];
        const picked = feature && featurePicked("shared", feature.feature_id, route.team);
        L.polyline(line, { color: picked ? "#ffce56" : route.color, weight: picked ? 7 : 4 }).addTo(serverLayers).on("click", () => {
          if (feature && toggleFeatureSelection("shared", feature.feature_id, route.team)) return;
          activeRoute = route.key; updateCaption(route); showRoute(route);
        });
      });
      visibleMarkers(route).forEach(point => {
        const picked = route.shared && featurePicked("shared", point.feature_id, route.team);
        const marker = L.marker(point.coords, { icon: markerIcon(point.label, false, picked) }).addTo(serverLayers);
        marker.on("click", () => { if (route.shared && toggleFeatureSelection("shared", point.feature_id, route.team)) return; showServerPoint(route, point); });
      });
    });
    (isAdmin() ? state.features : []).filter(feature => folderVisible(feature.folder) && feature.display_on_map !== false).forEach(feature => {
      const picked = featurePicked("local", feature.id);
      if (feature.kind === "point" && ll(feature.coords)) {
        L.marker(feature.coords, { icon: markerIcon(feature.label || feature.name, true, picked) }).addTo(localLayers).on("click", () => { if (!toggleFeatureSelection("local", feature.id)) showFeature(feature); });
      } else if (feature.kind === "line" && feature.coords.length > 1) {
        L.polyline(feature.coords, { color: picked ? "#ffce56" : feature.color || "#9a73d3", weight: picked ? 7 : 4, dashArray: feature.sourceRoute ? "8 5" : null }).addTo(localLayers).on("click", () => { if (!toggleFeatureSelection("local", feature.id)) showFeature(feature); });
      }
    });
    updateCaption(); renderFolders(); renderSelectionBar();
  }
  function boundsFor(route) { const points = [...route.lines.flat(), ...visibleMarkers(route).map(point => point.coords)]; return points.length ? L.latLngBounds(points) : null; }
  function fitRoute(route) { const bounds = boundsFor(route); if (bounds && bounds.isValid()) map.fitBounds(bounds, { paddingTopLeft: [50, 70], paddingBottomRight: [55, 45], maxZoom: 17 }); else toast("Tuyến này chưa có tọa độ."); }
  function fitMap() {
    const bounds = L.latLngBounds([]);
    [serverLayers, localLayers].forEach(group => { const b = group.getBounds(); if (b.isValid()) bounds.extend(b); });
    if (bounds.isValid()) map.fitBounds(bounds, { paddingTopLeft: [50, 70], paddingBottomRight: [55, $("sheet").classList.contains("collapsed") ? 45 : $("sheet").clientHeight + 15], maxZoom: 17 });
    else toast("Chưa có điểm để xem. Hãy nhập KMZ hoặc thêm điểm.");
  }
  function collapseSheet() { $("sheet").classList.add("collapsed"); document.querySelector(".workspace").classList.remove("has-sheet"); $("nav-map").setAttribute("aria-current", "page"); $("nav-routes").removeAttribute("aria-current"); }
  function openSheet(panel = "routes") { if (drawing) cancelDrawing(); $("sheet").classList.remove("collapsed"); document.querySelector(".workspace").classList.add("has-sheet"); $("nav-routes").setAttribute("aria-current", "page"); $("nav-map").removeAttribute("aria-current"); changePanel(panel); }
  function changePanel(panel) {
    if (panel === "folders" && !isAdmin()) panel = "routes";
    const folders = panel === "folders", shared = panel === "shared";
    $("folders-panel").hidden = !folders; $("routes-panel").hidden = folders || shared; $("shared-panel").hidden = !shared;
    $("folders-tab").setAttribute("aria-selected", String(folders)); $("routes-tab").setAttribute("aria-selected", String(!folders && !shared)); $("shared-tab").setAttribute("aria-selected", String(shared));
    $("sheet-title").textContent = folders ? "Thư mục" : shared ? "Bản đồ quản trị" : "Tuyến cáp";
    $("route-count").textContent = folders ? state.folders.length + " thư mục" : shared ? sharedLayers.length + " lớp" : routes.length + " tuyến";
    renderFolders(); renderShared();
  }
  function sharedDescendants(layer) {
    const descendants = new Set([layer.key]); let added = true;
    while (added) { added = false; sharedLayers.forEach(item => { if (descendants.has(item.parent) && !descendants.has(item.key)) { descendants.add(item.key); added = true; } }); }
    return sharedLayers.filter(item => descendants.has(item.key));
  }
  function renderShared() {
    const list = $("shared-list"); list.replaceChildren();
    const visited = new Set(), valid = new Set(sharedLayers.map(layer => layer.key));
    const append = (layer, depth) => {
      if (visited.has(layer.key)) return; visited.add(layer.key);
      const descendants = sharedDescendants(layer), marked = descendants.filter(item => sharedSelected.has(item.key)).length;
      const row = document.createElement("div"); row.className = "route-row shared-row" + (marked ? " selected" : ""); row.style.marginLeft = Math.min(depth, 5) * 12 + "px";
      const label = document.createElement("label"); label.className = "check-wrap";
      const checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.checked = marked === descendants.length; checkbox.indeterminate = marked > 0 && marked < descendants.length; checkbox.setAttribute("aria-label", "Hiện " + layer.name);
      checkbox.onchange = () => { descendants.forEach(item => checkbox.checked ? sharedSelected.add(item.key) : sharedSelected.delete(item.key)); persist(); renderShared(); renderMap(); };
      label.appendChild(checkbox);
      const open = document.createElement("button"); open.className = "route-open";
      const sourceName = /kml|upload|import/i.test(layer.sourceType) ? "KMZ / KML" : /draw|manual/i.test(layer.sourceType) ? "Vẽ trên server" : "Được chia sẻ";
      open.innerHTML = icon("folder") + '<span class="route-copy"><strong>' + esc(layer.name) + "</strong><small>" + esc(sourceName) + " · " + visibleMarkers(layer).length + " điểm hiển thị · " + layer.lines.length + " đường</small></span>" + icon("right");
      open.onclick = () => {
        descendants.forEach(item => sharedSelected.add(item.key)); persist(); renderShared(); renderMap(); collapseSheet();
        const bounds = L.latLngBounds([]); descendants.forEach(item => { const b = boundsFor(item); if (b && b.isValid()) bounds.extend(b); }); if (bounds.isValid()) map.fitBounds(bounds, { padding: [55, 70], maxZoom: 17 }); else toast("Lớp này chưa có tọa độ.");
        updateCaption(layer);
      };
      row.append(label, open);
      if (isAdmin() && layer.folderId && layer.revision !== null) {
        const select = document.createElement("button"); select.className = "small-button select-shared-features"; select.textContent = "Chọn";
        select.setAttribute("aria-label", "Chọn đối tượng trong " + layer.name); select.onclick = () => beginFeatureSelection("shared", layer.key);
        row.appendChild(select);
      }
      list.appendChild(row);
      if (featureSelection && featureSelection.source === "shared" && featureSelection.team === String(layer.team || "")) {
        appendFeatureChoices(list, sharedFeatures(layer), "shared", layer.team, depth + 1);
      }
      sharedLayers.filter(item => item.parent === layer.key).forEach(item => append(item, depth + 1));
    };
    sharedLayers.filter(layer => !layer.parent || !valid.has(layer.parent)).forEach(layer => append(layer, 0));
    sharedLayers.forEach(layer => append(layer, 0));
    $("shared-empty").hidden = sharedLayers.length > 0;
    $("shared-empty").textContent = user ? "Chưa có bản đồ quản trị được chia sẻ." : "Đăng nhập HP Telecom để xem bản đồ quản trị.";
    $("shared-selected-count").textContent = sharedSelected.size + " đã chọn";
    $("shared-select-all").checked = !!sharedLayers.length && sharedLayers.every(layer => sharedSelected.has(layer.key));
    $("shared-select-all").indeterminate = sharedSelected.size > 0 && !$("shared-select-all").checked; $("shared-select-all").disabled = !sharedLayers.length;
  }
  function modal(title, html, setup, onClose) {
    clearTimeout(toastTimer); $("toast").hidden = true;
    lastFocus = document.activeElement; $("modal-title").textContent = title; $("modal-body").innerHTML = html; $("modal-backdrop").hidden = false; modalCloseAction = onClose || null;
    if (setup) setup(); $("close-modal").focus();
  }
  function closeModal(force = false) { if (transferBusy && force !== true) return; const action = modalCloseAction; modalCloseAction = null; $("modal-backdrop").hidden = true; if (action) action(); if (lastFocus && lastFocus.isConnected) lastFocus.focus(); }
  function folderOptions(value) {
    const path = folder => { const names = [folder.name]; let p = folder.parent, seen = new Set([folder.id]); while (p && !seen.has(p)) { seen.add(p); const parent = state.folders.find(item => item.id === p); if (!parent) break; names.unshift(parent.name); p = parent.parent; } return names.join(" / "); };
    return state.folders.map(folder => '<option value="' + esc(folder.id) + '"' + (folder.id === value ? " selected" : "") + ">" + esc(path(folder)) + "</option>").join("");
  }
  function renderFolders() {
    const container = $("folder-list"); container.replaceChildren();
    if (!isAdmin()) return;
    const render = (parent, depth, visited) => {
      state.folders.filter(folder => (folder.parent || "") === parent).forEach(folder => {
        if (visited.has(folder.id)) return; visited.add(folder.id);
        const count = state.features.filter(feature => feature.folder === folder.id).length;
        const row = document.createElement("div"); row.className = "folder-row" + (folder.id === state.activeFolder ? " active" : ""); row.style.marginLeft = Math.min(depth, 5) * 12 + "px";
        const visible = document.createElement("button"); visible.className = "icon-button"; visible.innerHTML = icon(folderVisible(folder.id) ? "eye" : "eyeoff"); visible.setAttribute("aria-label", (folder.visible === false ? "Hiện " : "Ẩn ") + folder.name); visible.onclick = () => { folder.visible = folder.visible === false; persist(); renderMap(); };
        const open = document.createElement("button"); open.className = "folder-open"; open.innerHTML = icon("folder") + "<span><strong>" + esc(folder.name) + "</strong><small>" + count + " đối tượng" + (folder.id === state.activeFolder ? " · Đang chọn" : "") + "</small></span>"; open.onclick = () => { state.activeFolder = folder.id; openedFolders.has(folder.id) ? openedFolders.delete(folder.id) : openedFolders.add(folder.id); persist(); renderFolders(); };
        const more = document.createElement("button"); more.className = "icon-button"; more.innerHTML = icon("more"); more.setAttribute("aria-label", "Tùy chọn thư mục " + folder.name); more.onclick = () => editFolder(folder);
        row.append(visible, open, more); container.appendChild(row);
        if (openedFolders.has(folder.id) && featureSelection && featureSelection.source === "local") {
          appendFeatureChoices(container, state.features.filter(feature => feature.folder === folder.id).map(feature => ({...feature, feature_id: feature.id})), "local", "", depth + 1);
        } else if (openedFolders.has(folder.id)) state.features.filter(feature => feature.folder === folder.id).forEach(feature => {
          const entry = document.createElement("div"); entry.className = "feature-row"; entry.style.marginLeft = Math.min(depth + 1, 6) * 12 + "px"; entry.innerHTML = icon(feature.kind === "point" ? "pin" : "line");
          const button = document.createElement("button"); button.textContent = feature.name; button.onclick = () => { collapseSheet(); feature.kind === "point" ? map.setView(feature.coords, Math.max(map.getZoom(), 17)) : map.fitBounds(feature.coords, { padding: [50, 70] }); showFeature(feature); }; entry.appendChild(button); container.appendChild(entry);
        });
        render(folder.id, depth + 1, visited);
      });
    };
    render("", 0, new Set());
  }
  function featurePicked(source, featureId, team = "") {
    return !!featureSelection && featureSelection.source === source && (source !== "shared" || featureSelection.team === String(team || "")) && featureSelection.ids.has(String(featureId || ""));
  }
  function sharedFeatures(layer) {
    return [
      ...layer.markers.filter(point => point.feature_id).map(point => ({ ...point, kind: "point", name: point.position || point.name || point.label })),
      ...(layer.lineFeatures || []).filter(line => line.feature_id).map(line => ({ ...line, kind: "line" }))
    ];
  }
  function selectedFeatureItems(selection = featureSelection) {
    if (!selection) return [];
    if (selection.source === "local") return state.features.filter(feature => selection.ids.has(feature.id));
    return sharedLayers.filter(layer => String(layer.team || "") === selection.team).flatMap(layer => sharedFeatures(layer)).filter(feature => selection.ids.has(feature.feature_id));
  }
  function beginFeatureSelection(source, folderKey, initialId) {
    if (!requireAdmin() || transferBusy) return;
    if (refreshing) { toast("Đang cập nhật bản đồ. Hãy chờ xong rồi chọn đối tượng."); return; }
    if (source === "shared" && sharedTransferNeedsRefresh) { toast("Cần bấm Cập nhật và tải lại bản đồ thành công trước khi sao chép hoặc di chuyển tiếp.", 7000); return; }
    cancelDrawing(); closeModal();
    if (source === "shared") {
      const layer = sharedLayers.find(item => item.key === folderKey);
      if (!layer || !layer.folderId || !Number.isInteger(layer.revision)) { toast("Cập nhật bản đồ trước khi chọn đối tượng."); return; }
      const team = String(layer.team || "");
      if (!featureSelection || featureSelection.source !== source || featureSelection.team !== team) featureSelection = { source, team, revision: layer.revision, ids: new Set() };
      sharedSelected.add(layer.key); persist();
    } else {
      if (!state.folders.some(folder => folder.id === folderKey)) { toast("Thư mục không còn tồn tại."); return; }
      if (!featureSelection || featureSelection.source !== "local") featureSelection = { source: "local", ids: new Set() };
      state.activeFolder = folderKey; openedFolders.add(folderKey);
    }
    if (initialId) featureSelection.ids.add(String(initialId));
    openSheet(source === "local" ? "folders" : "shared"); renderMap(); renderShared();
    toast("Chọn điểm/đường trên bản đồ hoặc danh sách.", 2200);
  }
  function cancelFeatureSelection() {
    if (transferBusy) return;
    featureSelection = null; renderSelectionBar(); renderMap(); renderShared();
  }
  function toggleFeatureSelection(source, featureId, team = "") {
    if (!featureSelection) return false;
    if (!requireAdmin() || transferBusy) return true;
    if (featureSelection.source !== source || (source === "shared" && featureSelection.team !== String(team || ""))) {
      toast("Hãy hoàn tất hoặc hủy lựa chọn hiện tại trước khi chọn nhóm dữ liệu khác."); return true;
    }
    const id = String(featureId || "");
    if (!id) { toast("Đối tượng này chưa hỗ trợ chọn để di chuyển."); return true; }
    featureSelection.ids.has(id) ? featureSelection.ids.delete(id) : featureSelection.ids.add(id);
    renderMap(); renderShared(); return true;
  }
  function renderSelectionBar() {
    const shown = isAdmin() && !!featureSelection;
    $("close-modal").disabled = transferBusy;
    $("selection-bar").hidden = !shown;
    document.querySelector(".workspace").classList.toggle("selecting-features", shown);
    if (!shown) return;
    const count = featureSelection.ids.size;
    $("selection-count").textContent = count + " đã chọn · " + (featureSelection.source === "local" ? "Trên máy" : featureSelection.team || "Dùng chung");
    ["selection-copy", "selection-move"].forEach(id => $(id).disabled = !count || transferBusy);
    $("selection-cancel").disabled = transferBusy; $("selection-list").disabled = transferBusy;
  }
  function appendFeatureChoices(container, features, source, team, depth) {
    if (!features.length) return;
    const all = document.createElement("label"); all.className = "feature-pick-all"; all.style.marginLeft = Math.min(depth, 6) * 12 + "px";
    const checkAll = document.createElement("input"); checkAll.type = "checkbox";
    const picked = features.filter(feature => featurePicked(source, feature.feature_id, team)).length;
    checkAll.checked = picked === features.length; checkAll.indeterminate = picked > 0 && !checkAll.checked; checkAll.disabled = transferBusy;
    checkAll.onchange = () => {
      if (!requireAdmin() || transferBusy || !featureSelection || featureSelection.source !== source || (source === "shared" && featureSelection.team !== String(team || ""))) return;
      features.forEach(feature => checkAll.checked ? featureSelection.ids.add(feature.feature_id) : featureSelection.ids.delete(feature.feature_id));
      renderMap(); renderShared();
    };
    all.append(checkAll, document.createTextNode("Chọn tất cả trong thư mục (" + features.length + ")")); container.appendChild(all);
    features.forEach(feature => {
      const row = document.createElement("label"); row.className = "feature-choice" + (featurePicked(source, feature.feature_id, team) ? " picked" : ""); row.style.marginLeft = Math.min(depth, 6) * 12 + "px";
      const checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.checked = featurePicked(source, feature.feature_id, team); checkbox.disabled = transferBusy; checkbox.setAttribute("aria-label", "Chọn " + (feature.name || "Đối tượng")); checkbox.dataset.featureId = String(feature.feature_id); checkbox.dataset.source = source;
      checkbox.onchange = () => toggleFeatureSelection(source, feature.feature_id, team);
      const title = document.createElement("span"); title.innerHTML = "<strong>" + esc(feature.name || feature.label || "Đối tượng") + "</strong><small>" + (feature.kind === "point" ? "Điểm" : "Đường") + (feature.display_on_map === false ? " · Đang ẩn trên bản đồ" : "") + "</small>";
      row.append(checkbox, title); container.appendChild(row);
    });
  }
  function sharedFolderPath(layer) {
    const names = [layer.name], seen = new Set([layer.key]); let parentKey = layer.parent;
    while (parentKey && !seen.has(parentKey)) { seen.add(parentKey); const parent = sharedLayers.find(item => item.key === parentKey && String(item.team || "") === String(layer.team || "")); if (!parent) break; names.unshift(parent.name); parentKey = parent.parent; }
    return names.join(" / ");
  }
  function localTransferSnapshot(current, ids, targetId, mode, newId, now) {
    if (!["copy", "move"].includes(mode) || !current.folders.some(folder => folder.id === targetId)) throw new Error("Thư mục đích không còn tồn tại.");
    const wanted = new Set(ids), originals = current.features.filter(feature => wanted.has(feature.id));
    if (!wanted.size || originals.length !== wanted.size) throw new Error("Một số đối tượng đã thay đổi. Hãy chọn lại.");
    const next = JSON.parse(JSON.stringify(current));
    let count = 0;
    if (mode === "move") {
      next.features.forEach(feature => { if (wanted.has(feature.id) && feature.folder !== targetId) { feature.folder = targetId; feature.updatedAt = now; count++; } });
    } else {
      if (next.features.length + originals.length > 10000) throw new Error("Bản đồ trên máy tối đa 10.000 đối tượng. Hãy giảm số lượng sao chép.");
      const usedIds = new Set(next.features.map(feature => feature.id));
      originals.forEach(feature => {
        const copy = JSON.parse(JSON.stringify(feature));
        copy.id = newId(feature.kind || "feature");
        if (!copy.id || usedIds.has(copy.id)) throw new Error("Không tạo được mã bản sao. Hãy thử lại.");
        usedIds.add(copy.id); copy.folder = targetId; copy.updatedAt = now;
        const stem = copy.name || "Đối tượng"; let name = stem, suffix = 1;
        while (next.features.some(item => item.folder === targetId && item.name === name)) { suffix++; name = stem + " · Bản sao" + (suffix > 2 ? " " + (suffix - 1) : ""); }
        copy.name = name; next.features.push(copy); count++;
      });
    }
    next.activeFolder = targetId;
    const seen = new Set(); let currentId = targetId;
    while (currentId && !seen.has(currentId)) { seen.add(currentId); const folder = next.folders.find(item => item.id === currentId); if (!folder) break; folder.visible = true; currentId = folder.parent; }
    return { state: next, count };
  }
  function openTransfer(mode) {
    if (!requireAdmin() || transferBusy || !featureSelection || !featureSelection.ids.size) return;
    const selection = { ...featureSelection, ids: new Set(featureSelection.ids) }, startingSession = sessionEpoch;
    const count = selection.ids.size, local = selection.source === "local";
    const targets = local ? state.folders : sharedLayers.filter(layer => String(layer.team || "") === selection.team && layer.folderId);
    if (!targets.length) { toast("Chưa có thư mục đích."); return; }
    const options = local ? folderOptions(state.activeFolder) : targets.map(layer => '<option value="' + esc(layer.folderId) + '">' + esc(sharedFolderPath(layer)) + "</option>").join("");
    const action = mode === "copy" ? "Sao chép" : "Di chuyển";
    const originals = selectedFeatureItems(selection).map(item => JSON.stringify(item));
    modal(action + " " + count + " đối tượng", '<label>Thư mục đích<select id="transfer-target">' + options + '</select></label><p class="note">' + (local ? "Lưu trên thiết bị này." : "Cập nhật bản đồ quản trị trên server trong nhóm " + esc(selection.team || "Dùng chung") + ".") + " " + (mode === "copy" ? "Giữ nguyên bản gốc." : "Đối tượng được chuyển khỏi thư mục cũ; giữ nguyên tọa độ và nội dung.") + '</p><p id="transfer-error" class="form-error" role="alert" hidden></p><div class="actions"><button id="transfer-back" class="secondary">Quay lại</button><button id="transfer-confirm" class="primary">' + action + "</button></div>", () => {
      $("transfer-back").onclick = closeModal;
      $("transfer-confirm").onclick = async () => {
        if (!requireAdmin() || transferBusy) return;
        if (!featureSelection || featureSelection.source !== selection.source || featureSelection.team !== selection.team || featureSelection.ids.size !== count || Array.from(selection.ids).some(id => !featureSelection.ids.has(id))) { $("transfer-error").textContent = "Lựa chọn đã thay đổi. Hãy mở lại thao tác."; $("transfer-error").hidden = false; return; }
        const target = $("transfer-target").value, errorBox = $("transfer-error"), confirm = $("transfer-confirm"), back = $("transfer-back"), targetSelect = $("transfer-target");
        errorBox.hidden = true; transferBusy = true; renderSelectionBar(); confirm.disabled = back.disabled = targetSelect.disabled = true;
        try {
          if (local) {
            const current = selectedFeatureItems(selection).map(item => JSON.stringify(item));
            if (current.length !== originals.length || current.some((value, index) => value !== originals[index])) throw new Error("Đối tượng đã thay đổi. Hãy chọn lại.");
            const result = localTransferSnapshot(state, Array.from(selection.ids), target, mode, uid, new Date().toISOString());
            result.state.selected = Array.from(selected); result.state.sharedSelected = Array.from(sharedSelected);
            // Commit storage before changing any live object; quota errors preserve the source.
            localStorage.setItem("hpmap-v2", JSON.stringify(result.state));
            state = result.state; featureSelection = null; openedFolders.add(target); closeModal(true); renderMap(); openSheet("folders");
            toast(result.count ? "Đã " + action.toLocaleLowerCase("vi") + " " + result.count + " đối tượng." : "Các đối tượng đã nằm trong thư mục này.");
          } else {
            const layer = sharedLayers.find(item => String(item.team || "") === selection.team && item.folderId === target);
            if (!layer || layer.revision !== selection.revision) throw new Error("Bản đồ đã thay đổi. Hãy cập nhật rồi chọn lại.");
            const result = await native("transferSharedFeatures", { team: selection.team, featureIds: Array.from(selection.ids), targetFolderId: target, mode, revision: selection.revision });
            if (!result.transfer || (result.transfer.saved !== true && !result.transfer.uncertain)) { const error = new Error("Phản hồi lưu chưa rõ kết quả."); error.transferUncertain = true; throw error; }
            featureSelection = null; closeModal(true);
            if (sessionEpoch !== startingSession || !isAdmin()) { sharedTransferNeedsRefresh = true; toast("Phiên đăng nhập đã thay đổi. Bấm Cập nhật để xem kết quả.", 7000); return; }
            if (result.refreshRequired) {
              sharedTransferNeedsRefresh = true;
              toast(result.warning || "Đã gửi thao tác. Đang tải lại bản đồ để xác nhận.", 8000);
              renderMap(); renderShared();
              try { const fresh = await native("refresh"); if (sessionEpoch === startingSession) { receiveSession(fresh, false); sharedTransferNeedsRefresh = false; } } catch (_) { toast(result.warning || "Hãy bấm Cập nhật để xác nhận kết quả; không lặp lại sao chép ngay.", 8000); }
            } else {
              receiveSession(result, false);
              const destination = sharedLayers.find(item => String(item.team || "") === selection.team && item.folderId === target);
              if (destination) sharedSelected.add(destination.key);
              persist(); renderMap(); renderShared(); openSheet("shared");
              toast(result.transfer && result.transfer.noop ? "Các đối tượng đã nằm trong thư mục này." : "Đã " + action.toLocaleLowerCase("vi") + " trên server.");
            }
          }
        } catch (error) {
          if (error.authExpired || error.status === 403) { authExpired = true; featureSelection = null; applyPermissions(); renderMap(); toast("Quyền đăng nhập đã thay đổi. Hãy đăng nhập lại.", 6000); }
          else if (error.transferUncertain || error.status === 409) {
            sharedTransferNeedsRefresh = true;
            featureSelection = null; closeModal(true); renderMap(); renderShared();
            const warning = error.transferUncertain ? "Chưa xác nhận được kết quả. Đang cập nhật bản đồ; không lặp lại sao chép ngay." : "Bản đồ trên server đã thay đổi. Đang cập nhật; hãy chọn lại đối tượng.";
            toast(warning, 8000);
            try { const fresh = await native("refresh"); if (sessionEpoch === startingSession) { receiveSession(fresh, false); sharedTransferNeedsRefresh = false; } } catch (_) { toast(warning + " Bấm Cập nhật để thử tải lại.", 8000); }
          }
          else if (errorBox.isConnected) { errorBox.textContent = error.name === "QuotaExceededError" ? "Bộ nhớ thiết bị đã đầy. Dữ liệu gốc vẫn được giữ." : error.message || "Chưa lưu được. Dữ liệu gốc vẫn được giữ."; errorBox.hidden = false; }
          else toast(error.message || "Chưa thực hiện được thao tác.", 6000);
        } finally {
          transferBusy = false; if (confirm.isConnected) confirm.disabled = false; if (back.isConnected) back.disabled = false; if (targetSelect.isConnected) targetSelect.disabled = false;
          renderSelectionBar(); renderFolders(); renderShared();
        }
      };
    });
  }
  function createFolder(parent = "") {
    if (!requireAdmin()) return;
    modal("Tạo thư mục", '<label>Tên thư mục<input id="folder-name" maxlength="80" placeholder="Ví dụ: Khảo sát mới"></label><label>Thư mục cha<select id="folder-parent"><option value="">Không có</option>' + folderOptions(parent) + '</select></label><div class="actions"><button class="secondary" id="cancel-folder">Hủy</button><button class="primary" id="save-folder">Tạo thư mục</button></div>', () => {
      $("cancel-folder").onclick = closeModal;
      $("save-folder").onclick = () => {
        if (!requireAdmin()) return;
        const name = $("folder-name").value.trim(); if (!name) { $("folder-name").focus(); return; }
        const parentId = $("folder-parent").value;
        if (state.folders.some(folder => folder.name.toLocaleLowerCase() === name.toLocaleLowerCase() && (folder.parent || "") === parentId)) { toast("Thư mục này đã có trong cùng thư mục cha."); return; }
        const folder = { id: uid("folder"), name, parent: parentId, visible: true }; state.folders.push(folder); state.activeFolder = folder.id; persist(); renderFolders(); closeModal(); openSheet("folders");
      };
    });
  }
  function editFolder(folder) {
    if (!requireAdmin()) return;
    modal(folder.name, '<label>Tên thư mục<input id="rename-folder" maxlength="80" value="' + esc(folder.name) + '"></label><div class="actions"><button id="save-folder-name" class="primary">Lưu tên</button><button id="add-child" class="secondary">Thư mục con</button></div><button id="delete-folder" class="text-button danger wide-button">Xóa thư mục và đối tượng bên trong</button>', () => {
      $("save-folder-name").onclick = () => { if (!requireAdmin()) return; const name = $("rename-folder").value.trim(); if (!name) return; folder.name = name; persist(); renderFolders(); closeModal(); };
      $("add-child").onclick = () => createFolder(folder.id);
      $("delete-folder").onclick = () => {
        modal("Xóa thư mục?", "<p>Xóa “" + esc(folder.name) + "” cùng các thư mục con và điểm, đường đã lưu trên máy. Bạn có thể xuất KMZ trước khi xóa.</p><div class=\"actions\"><button id=\"keep-folder\" class=\"secondary\">Giữ lại</button><button id=\"confirm-delete-folder\" class=\"secondary danger\">Xóa</button></div>", () => {
          $("keep-folder").onclick = closeModal; $("confirm-delete-folder").onclick = () => {
            if (!requireAdmin()) return;
            const ids = new Set([folder.id]); let added = true; while (added) { added = false; state.folders.forEach(item => { if (ids.has(item.parent) && !ids.has(item.id)) { ids.add(item.id); added = true; } }); }
            state.folders = state.folders.filter(item => !ids.has(item.id)); state.features = state.features.filter(feature => !ids.has(feature.folder));
            const removedImports = state.importedIds.filter(id => !state.features.some(feature => feature.importId === id));
            state.dismissedImports = Array.from(new Set([...state.dismissedImports, ...removedImports]));
            state.importedIds = state.importedIds.filter(id => !removedImports.includes(id));
            if (!state.folders.length) state.folders.push({ id: uid("folder"), name: "Thư mục mặc định", parent: "", visible: true });
            if (ids.has(state.activeFolder)) state.activeFolder = state.folders[0].id;
            persist(); renderMap(); closeModal();
            removedImports.forEach(id => native("removeImport", { id }).catch(() => toast("Đã xóa khỏi bản đồ; chưa xóa được tệp KMZ gốc trong bộ nhớ ứng dụng.", 6000)));
          };
        });
      };
    });
  }
  function startPoint() { if (!requireAdmin() || transferBusy) return; cancelFeatureSelection(); collapseSheet(); closeModal(); cancelDrawing(); drawing = { mode: "point", coords: [] }; $("drawing-bar").hidden = false; $("drawing-help").textContent = "Chạm bản đồ để đặt điểm"; $("finish-drawing").hidden = true; $("undo-drawing").hidden = true; $("add-point").classList.add("active"); }
  function startLine(feature) {
    if (!requireAdmin() || transferBusy) return; cancelFeatureSelection();
    collapseSheet(); closeModal(); cancelDrawing(); drawing = { mode: "line", coords: feature ? feature.coords.map(coords => coords.slice()) : [], featureId: feature ? feature.id : null };
    $("drawing-bar").hidden = false; $("finish-drawing").hidden = false; $("undo-drawing").hidden = false; $("add-line").classList.add("active"); renderDraft();
  }
  function cancelDrawing() { drawing = null; draftLayers.clearLayers(); $("drawing-bar").hidden = true; $("add-point").classList.remove("active"); $("add-line").classList.remove("active"); }
  function renderDraft() {
    draftLayers.clearLayers(); if (!drawing || drawing.mode !== "line") return;
    if (drawing.coords.length > 1) L.polyline(drawing.coords, { color: "#e95f9e", weight: 5, dashArray: "7 4" }).addTo(draftLayers);
    drawing.coords.forEach((coords, index) => {
      const marker = L.marker(coords, { draggable: true, icon: L.divIcon({ className: "hp-marker local", html: "<span>" + (index + 1) + "</span>", iconSize: null, iconAnchor: [13, 13] }) }).addTo(draftLayers);
      marker.on("dragend", () => { if (drawing) { const pos = marker.getLatLng(); drawing.coords[index] = [pos.lat, pos.lng]; renderDraft(); } });
    });
    $("drawing-help").textContent = drawing.coords.length + " điểm · Chạm để thêm, kéo điểm để chỉnh";
    $("finish-drawing").disabled = drawing.coords.length < 2; $("undo-drawing").disabled = drawing.coords.length === 0;
  }
  map.on("click", event => {
    if (!drawing || !isAdmin()) return;
    const coords = [event.latlng.lat, event.latlng.lng];
    if (drawing.mode === "point") { cancelDrawing(); editFeature({ id: uid("point"), kind: "point", name: "Điểm mới", label: "MX", coords, folder: state.activeFolder }); }
    else { drawing.coords.push(coords); renderDraft(); }
  });
  function finishLine() {
    if (!requireAdmin()) return;
    if (!drawing || drawing.coords.length < 2) return;
    const existing = state.features.find(feature => feature.id === drawing.featureId);
    const feature = { ...(existing || {}), id: existing ? existing.id : uid("line"), kind: "line", name: existing ? existing.name : "Đường mới", folder: existing ? existing.folder : state.activeFolder, coords: drawing.coords.map(coords => coords.slice()) };
    cancelDrawing(); editFeature(feature);
  }
  function editFeature(feature) {
    if (!requireAdmin()) return;
    const existing = state.features.find(item => item.id === feature.id), isPoint = feature.kind === "point";
    const html = '<label>Tên ' + (isPoint ? "điểm" : "đường") + '<input id="feature-name" maxlength="120" value="' + esc(feature.name) + '"></label><label>Thư mục<select id="feature-folder">' + folderOptions(feature.folder || state.activeFolder) + "</select></label>" +
      (isPoint ? '<label>Ký hiệu<select id="feature-label"><option value="MX">MX · Măng xông</option><option value="DP">DP · Dự phòng</option><option value="Điểm">Điểm khác</option></select></label><div class="coordinates"><label>Vĩ độ<input id="point-lat" inputmode="decimal" value="' + Number(feature.coords[0]).toFixed(7) + '"></label><label>Kinh độ<input id="point-lon" inputmode="decimal" value="' + Number(feature.coords[1]).toFixed(7) + '"></label></div>' : "<p>" + feature.coords.length + " đỉnh · " + formatDistance(lineLength(feature.coords)) + "</p>") +
      '<label>Ghi chú<textarea id="feature-note" rows="2" maxlength="2000">' + esc(feature.note || "") + '</textarea></label><p class="note">Lưu trên thiết bị · Xuất KMZ để chia sẻ. Chưa thay đổi dữ liệu server.</p><p id="feature-error" class="form-error" hidden></p><div class="actions"><button id="cancel-feature" class="secondary">Hủy</button><button id="save-feature" class="primary">Lưu</button></div>';
    modal(existing ? "Chỉnh " + (isPoint ? "điểm" : "đường") : "Thêm " + (isPoint ? "điểm" : "đường"), html, () => {
      if (isPoint) $("feature-label").value = ["MX", "DP"].includes(feature.label) ? feature.label : "Điểm";
      $("cancel-feature").onclick = closeModal;
      $("save-feature").onclick = () => {
        if (!requireAdmin()) return;
        const name = $("feature-name").value.trim(), folder = $("feature-folder").value;
        const coords = isPoint ? ll([$("point-lat").value.replace(",", "."), $("point-lon").value.replace(",", ".")]) : feature.coords;
        if (!name || !coords || !state.folders.some(item => item.id === folder)) { $("feature-error").textContent = "Nhập tên, thư mục và tọa độ hợp lệ."; $("feature-error").hidden = false; return; }
        const label = isPoint ? $("feature-label").value : "";
        if (isPoint && label === "MX") {
          const duplicate = state.features.find(item => item.id !== feature.id && item.folder === folder && item.kind === "point" && item.label === "MX" && map.distance(item.coords, coords) <= 20);
          if (duplicate) { $("feature-error").textContent = "Đã có MX “" + duplicate.name + "” trong 20 m ở thư mục này. Chỉnh điểm đã có để tránh trùng."; $("feature-error").hidden = false; return; }
        }
        const saved = { ...feature, name, folder, coords, label, note: $("feature-note").value.trim(), updatedAt: new Date().toISOString() };
        const index = state.features.findIndex(item => item.id === saved.id); index >= 0 ? state.features.splice(index, 1, saved) : state.features.push(saved);
        state.activeFolder = folder; const targetFolder = state.folders.find(item => item.id === folder); if (targetFolder) targetFolder.visible = true;
        persist(); renderMap(); closeModal(); toast("Đã lưu trên thiết bị.");
      };
    });
  }
  function formatDistance(meters) { return meters >= 1000 ? (meters / 1000).toFixed(2) + " km" : Math.round(meters) + " m"; }
  function lineLength(points) { return points.reduce((total, coords, index) => total + (index ? map.distance(points[index - 1], coords) : 0), 0); }
  function showFeature(feature) {
    if (!requireAdmin()) return;
    const isPoint = feature.kind === "point", folder = state.folders.find(item => item.id === feature.folder);
    modal(feature.name, '<p>' + esc(folder ? folder.name : "") + ' <span class="badge">Trên máy</span></p><p>' + (isPoint ? feature.coords.map(n => Number(n).toFixed(7)).join(", ") : feature.coords.length + " điểm · " + formatDistance(lineLength(feature.coords))) + "</p>" + (feature.note ? "<p>" + esc(feature.note) + "</p>" : "") + (isPoint ? '<button id="point-directions" class="primary wide-button">' + icon("route") + 'Chỉ đường</button>' : "") + '<button id="select-feature" class="secondary wide-button">Chọn để sao chép / di chuyển</button><div class="actions"><button id="edit-feature" class="secondary">Chỉnh sửa</button><button id="move-feature" class="secondary">' + (isPoint ? "Đổi vị trí" : "Chỉnh đường") + '</button></div><button id="delete-feature" class="text-button danger wide-button">Xóa trên máy</button>', () => {
      if (isPoint) $("point-directions").onclick = event => requestDirections(feature.coords, event.currentTarget);
      $("select-feature").onclick = () => beginFeatureSelection("local", feature.folder, feature.id);
      $("edit-feature").onclick = () => editFeature({ ...feature });
      $("move-feature").onclick = () => {
        if (!isPoint) { startLine(feature); return; }
        closeModal(); collapseSheet(); map.setView(feature.coords, Math.max(map.getZoom(), 17)); toast("Kéo điểm tím đến vị trí mới rồi bấm Lưu.", 6000);
        draftLayers.clearLayers(); const marker = L.marker(feature.coords, { icon: markerIcon(feature.label || feature.name, true), draggable: true }).addTo(draftLayers);
        drawing = { mode: "move", featureId: feature.id };
        $("drawing-bar").hidden = false; $("drawing-help").textContent = "Kéo điểm tím để đổi vị trí"; $("undo-drawing").hidden = true; $("finish-drawing").hidden = false; $("finish-drawing").disabled = false;
        drawing.save = () => { const pos = marker.getLatLng(); cancelDrawing(); editFeature({ ...feature, coords: [pos.lat, pos.lng] }); };
      };
      $("delete-feature").onclick = () => modal("Xóa đối tượng?", "<p>Xóa “" + esc(feature.name) + "” khỏi bản đồ trên máy?</p><div class=\"actions\"><button id=\"keep-feature\" class=\"secondary\">Giữ lại</button><button id=\"confirm-delete-feature\" class=\"secondary danger\">Xóa</button></div>", () => { $("keep-feature").onclick = closeModal; $("confirm-delete-feature").onclick = () => { if (!requireAdmin()) return; state.features = state.features.filter(item => item.id !== feature.id); persist(); renderMap(); closeModal(); }; });
    });
  }
  function showRoute(route) {
    modal(route.name, "<p>" + esc([route.type, route.capacity].filter(Boolean).join(" · ")) + '</p><p>' + visibleMarkers(route).length + " điểm hiển thị · " + route.lines.length + ' đường</p><p class="note">' + (isAdmin() ? "Dữ liệu server. Tạo bản sao trên máy để chỉnh tuyến và xuất KMZ." : "Dữ liệu từ HP Telecom Server · Chỉ xem") + '</p><div class="actions"><button id="view-route" class="secondary">Xem tuyến</button>' + (isAdmin() ? '<button id="copy-route" class="primary">Tạo bản sao</button>' : "") + "</div>", () => {
      $("view-route").onclick = () => { closeModal(); collapseSheet(); fitRoute(route); };
      if (isAdmin()) $("copy-route").onclick = () => copyRoute(route);
    });
  }
  function copyRoute(route) {
    if (!requireAdmin()) return;
    if (!route.lines.length && !route.markers.length) { toast("Tuyến chưa có tọa độ để sao chép."); return; }
    const folder = { id: uid("folder"), name: route.name + " · Bản chỉnh", parent: "", visible: true }; state.folders.push(folder);
    route.lines.forEach((coords, index) => state.features.push({ id: uid("line"), kind: "line", name: route.name + (route.lines.length > 1 ? " " + (index + 1) : ""), coords: coords.map(point => point.slice()), folder: folder.id, sourceRoute: route.key, color: route.color }));
    route.markers.forEach(point => state.features.push({ id: uid("point"), kind: "point", name: point.position || point.label, label: point.label, display_on_map: point.display_on_map, coords: point.coords.slice(), folder: folder.id, sourceRoute: route.key }));
    state.activeFolder = folder.id; route.shared ? sharedSelected.delete(route.key) : selected.delete(route.key); openedFolders.add(folder.id); persist(); renderRoutes(); renderShared(); renderMap(); closeModal(); openSheet("folders"); toast("Đã tạo bản sao. Chọn đường hoặc điểm để chỉnh.");
  }
  function showServerPoint(route, point) {
    const history = Array.isArray(point.history) ? point.history : Array.isArray(point.records) ? point.records : [];
    modal(point.label, "<p><strong>" + esc(route.name) + "</strong></p>" + (point.position ? "<p>" + esc(point.position) + "</p>" : "") + (point.note ? "<p>" + esc(point.note) + "</p>" : "") + "<p>" + point.coords.map(n => n.toFixed(7)).join(", ") + '</p><p class="note">' + (route.shared ? "Bản đồ quản trị" + (isAdmin() ? "" : " · Chỉ xem") : "Dữ liệu từ HP Telecom Server") + "</p>" +
      (history.length ? '<ul class="detail-list">' + history.slice(0, 30).map(item => "<li>" + esc(item.type || item.category || "Ghi nhận") + "<small>" + esc(item.date || item.created_at || "") + "</small></li>").join("") + "</ul>" : "") +
      '<button id="point-directions" class="primary wide-button">' + icon("route") + 'Chỉ đường</button>' +
      (isAdmin() && route.shared && point.feature_id ? '<button id="select-server-feature" class="secondary wide-button">Chọn để sao chép / di chuyển</button>' : "") +
      (isAdmin() ? '<button id="copy-server-point" class="secondary wide-button">Sao chép điểm vào thư mục trên máy</button>' : ""), () => {
        $("point-directions").onclick = event => requestDirections(point.coords, event.currentTarget);
        if (isAdmin() && route.shared && point.feature_id) $("select-server-feature").onclick = () => beginFeatureSelection("shared", route.key, point.feature_id);
        if (isAdmin()) $("copy-server-point").onclick = () => editFeature({ id: uid("point"), kind: "point", name: point.position || point.label, label: point.label, coords: point.coords.slice(), folder: state.activeFolder, sourceRoute: route.key });
      });
  }
  async function requestDirections(coords, button) {
    const point = ll(coords);
    if (!point) { toast("Điểm chưa có tọa độ hợp lệ để chỉ đường."); return; }
    if (button) button.disabled = true;
    try { await native("directions", { lat: point[0], lon: point[1] }); }
    catch (error) { toast(error.message || "Chưa mở được ứng dụng chỉ đường."); }
    finally { if (button && button.isConnected) button.disabled = false; }
  }
  function openEditor() {
    if (!requireAdmin()) return;
    const route = routes.find(item => item.key === activeRoute) || routes.find(item => selected.has(item.key));
    modal("Thêm vị trí · Chỉnh tuyến", '<button id="editor-point" class="option">' + icon("point") + '<span>Thêm điểm<small>Chọn trên bản đồ, nhập tọa độ</small></span></button><button id="editor-line" class="option">' + icon("line") + '<span>Vẽ đường<small>Chạm để thêm đỉnh, kéo để chỉnh</small></span></button><button id="editor-folder" class="option">' + icon("folder") + '<span>Tạo thư mục<small>Nhóm điểm và đường theo tuyến</small></span></button>' + (route ? '<button id="editor-copy" class="option">' + icon("route") + '<span>Chỉnh tuyến server<small>Tạo bản sao của ' + esc(route.name) + "</small></span></button>" : "") + '<p class="note">Điểm và đường mới được lưu trên máy; có thể xuất hoặc chia sẻ KMZ.</p>', () => {
      $("editor-point").onclick = startPoint; $("editor-line").onclick = () => startLine(); $("editor-folder").onclick = () => createFolder();
      if (route) $("editor-copy").onclick = () => copyRoute(route);
    });
  }
  function openLayers() {
    modal("Lớp bản đồ", baseMapChoices.map(choice => {
      const available = !choice.requiresKey || baseMapConfig.configured;
      return '<button id="basemap-' + choice.id + '" class="option layer-option' + (baseMapKey === choice.id ? " selected" : "") + '" aria-pressed="' + (baseMapKey === choice.id) + '">' + icon(choice.icon) + "<span>" + esc(choice.title) + "<small>" + esc(available ? choice.source : isAdmin() ? "Cần kết nối MapTiler · Chạm để cấu hình" : "Quản trị chưa kết nối nguồn vệ tinh") + "</small></span>" + (baseMapKey === choice.id ? icon("check") : "") + "</button>";
    }).join("") + (isAdmin() ? '<button id="configure-basemap" class="text-button wide-button">' + (baseMapConfig.configured ? "Quản lý nguồn vệ tinh" : "Kết nối nguồn vệ tinh") + "</button>" : "") + '<p class="note">Các điểm GPS dọc tuyến được ẩn; đường cáp, MX, DP và tên hai đầu tuyến vẫn hiển thị. KMZ giữ nguyên toàn bộ tọa độ.</p><p class="note">Đổi lớp nền không thay đổi dữ liệu tuyến. Nền bản đồ cần internet.</p>', () => {
      baseMapChoices.forEach(choice => $("basemap-" + choice.id).onclick = () => {
        if (choice.requiresKey && !baseMapConfig.configured) {
          if (isAdmin()) configureBaseMap();
          else toast("Quản trị cần kết nối nguồn vệ tinh để sử dụng lớp này.");
          return;
        }
        if (setBaseMap(choice.id)) closeModal();
      });
      if (isAdmin()) $("configure-basemap").onclick = configureBaseMap;
    });
  }
  function configureBaseMap() {
    if (!requireAdmin()) return;
    modal("Kết nối nguồn vệ tinh", '<p>Nhập khóa MapTiler có quyền sử dụng cho HP Telecom để mở Vệ tinh và Vệ tinh + đường phố trên thiết bị này.</p><label>Khóa MapTiler<input id="basemap-key" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" maxlength="256" placeholder="' + (baseMapConfig.configured ? "Nhập khóa mới để thay thế" : "Nhập khóa MapTiler") + '"></label><p id="basemap-config-error" class="form-error" role="alert" hidden></p><div class="actions"><button id="cancel-basemap-config" class="secondary">Quay lại</button><button id="save-basemap-config" class="primary">Kiểm tra & lưu</button></div>' + (baseMapConfig.configured ? '<button id="clear-basemap-config" class="text-button danger wide-button">Ngắt nguồn vệ tinh trên máy</button>' : "") + '<p class="note">Dùng tài khoản dịch vụ bản đồ đã có. Ứng dụng không đăng ký hay mua gói tự động.</p>', () => {
      $("cancel-basemap-config").onclick = openLayers;
      const save = async key => {
        if (!requireAdmin()) return;
        const input = $("basemap-key"), saveButton = $("save-basemap-config"), clearButton = $("clear-basemap-config"), errorBox = $("basemap-config-error");
        input.disabled = true; saveButton.disabled = true; if (clearButton) clearButton.disabled = true; errorBox.hidden = true;
        try {
          const result = await native("saveBasemapConfig", { key });
          if (!isAdmin()) return;
          acceptBaseMapConfig(result);
          if (["satellite", "hybrid"].includes(baseMapKey)) setBaseMap(baseMapConfig.configured ? baseMapKey : "street", { reload: true });
          if (saveButton.isConnected) { input.value = ""; openLayers(); }
          toast(baseMapConfig.configured ? "Đã kết nối nguồn vệ tinh. Chọn lớp để xem." : "Đã ngắt nguồn vệ tinh trên máy.");
        } catch (error) {
          if (errorBox.isConnected) { errorBox.textContent = error.message || "Chưa kiểm tra được khóa. Vui lòng thử lại."; errorBox.hidden = false; }
        } finally {
          if (input.isConnected) input.disabled = false;
          if (saveButton.isConnected) saveButton.disabled = false;
          if (clearButton && clearButton.isConnected) clearButton.disabled = false;
        }
      };
      $("save-basemap-config").onclick = () => {
        const key = $("basemap-key").value.trim();
        if (!key) { $("basemap-config-error").textContent = "Hãy nhập khóa MapTiler."; $("basemap-config-error").hidden = false; $("basemap-key").focus(); return; }
        save(key);
      };
      if ($("clear-basemap-config")) $("clear-basemap-config").onclick = () => save("");
    });
  }
  function openLogin() { $("login-error").hidden = true; $("login").hidden = false; $("password").value = ""; $("close-login").hidden = !user; $("continue-local").hidden = !user; }
  function account() {
    if (!user || authExpired) { openLogin(); return; }
    modal("Tài khoản", "<p><strong>" + esc(user.display_name || user.name || user.username || "HP Telecom") + '</strong></p><p>Đang kết nối HP Telecom Server.</p><button id="logout" class="secondary wide-button">Đăng xuất</button>', () => {
      $("logout").onclick = async () => { try { await native("logout"); receiveSession({ user: null, routes: [] }); closeModal(); toast("Đã đăng xuất. Bản đồ trên máy vẫn được giữ."); } catch (error) { toast(error.message); } };
    });
  }
  async function refresh(background = false) {
    if (refreshing || transferBusy) return;
    refreshing = true;
    $("refresh").disabled = true;
    try { receiveSession(await native("refresh"), !background); sharedTransferNeedsRefresh = false; if (!background) toast(user ? "Đã cập nhật tuyến từ server." : "Đăng nhập để xem tuyến server."); }
    catch (error) {
      authExpired = !!error.authExpired || /hết hạn|đăng nhập lại|unauthorized/i.test(error.message);
      applyPermissions(); renderMap();
      const warning = authExpired ? "Phiên đăng nhập đã hết hạn. Bấm Tài khoản để đăng nhập lại." : "Chưa cập nhật được. Đang giữ dữ liệu nhận trước đó. " + error.message;
      $("connection").textContent = authExpired ? "Cần đăng nhập lại" : "Dữ liệu chưa cập nhật";
      $("server-warning").textContent = warning; $("server-warning").hidden = false;
      if (!background) toast(warning, 6500);
    }
    finally { refreshing = false; $("refresh").disabled = false; }
  }
  async function locate() {
    $("locate").disabled = true;
    try {
      const result = await native("location"), coords = ll(result);
      if (!coords) throw new Error("Chưa lấy được tọa độ hợp lệ.");
      if (locationLayer) locationLayer.remove();
      locationLayer = L.featureGroup([L.circleMarker(coords, { radius: 7, color: "#fff", fillColor: "#188bff", weight: 3, fillOpacity: 1 }), L.circle(coords, { radius: Math.max(Number(result.accuracy) || 0, 1), color: "#188bff", fillOpacity: 0.1, weight: 1 })]).addTo(map);
      collapseSheet(); map.setView(coords, Math.max(map.getZoom(), 17)); toast("Vị trí của bạn" + (result.accuracy ? " · Sai số " + Math.round(result.accuracy) + " m" : ""));
    } catch (error) { toast(error.message, 6000); }
    finally { $("locate").disabled = false; }
  }
  function parseKml(kml, mapName, mapId) {
    if (typeof kml !== "string" || kml.length > 8 * 1024 * 1024 || /<!\s*(DOCTYPE|ENTITY)\b/i.test(kml)) throw new Error("Tệp KML không hợp lệ hoặc quá lớn.");
    const doc = new DOMParser().parseFromString(kml, "application/xml");
    const namespaces = new Set(["", "http://www.opengis.net/kml/2.2", "http://earth.google.com/kml/2.2", "http://earth.google.com/kml/2.1", "http://earth.google.com/kml/2.0"]);
    if (!doc.documentElement || doc.getElementsByTagNameNS("*", "parsererror").length || doc.documentElement.localName.toLowerCase() !== "kml" || !namespaces.has(doc.documentElement.namespaceURI || "")) throw new Error("Không đọc được nội dung KML.");
    if (doc.getElementsByTagName("*").length > 100000) throw new Error("Tệp có quá nhiều thành phần.");
    const child = (element, name) => Array.from(element.children).find(item => item.localName.toLowerCase() === name);
    const childText = (element, name) => { const item = child(element, name); return item ? item.textContent.trim() : ""; };
    let coordinateCount = 0;
    const coordsOf = element => {
      const coordinates = Array.from(element.getElementsByTagNameNS("*", "coordinates"))[0];
      if (!coordinates) return [];
      const tokens = coordinates.textContent.trim().split(/\s+/);
      coordinateCount += tokens.length;
      if (coordinateCount > 50000) throw new Error("KML vượt 50.000 tọa độ. Hãy chia nhỏ tệp.");
      return tokens.map(value => {
        const pieces = value.split(","), coords = pieces.length >= 2 && pieces.length <= 3 ? ll([pieces[1], pieces[0]]) : null;
        if (!coords || (pieces.length === 3 && (pieces[2].trim() === "" || !Number.isFinite(Number(pieces[2]))))) throw new Error("Tệp có tọa độ không hợp lệ.");
        return coords;
      });
    };
    const top = { id: uid("import"), name: mapName.replace(/\.(kmz|kml)$/i, ""), parent: "", visible: true, importId: mapId };
    const folders = [top], features = [];
    function visit(node, folderId, depth) {
      if (depth > 24 || features.length >= 5000) throw new Error("Tệp có quá nhiều đối tượng hoặc thư mục lồng nhau.");
      for (const element of Array.from(node.children)) {
        if (!namespaces.has(element.namespaceURI || "")) continue;
        const name = element.localName.toLowerCase();
        if (name === "folder") {
          const folder = { id: uid("folder"), name: childText(element, "name") || "Thư mục", parent: folderId, visible: childText(element, "visibility") !== "0" };
          folders.push(folder); visit(element, folder.id, depth + 1);
        } else if (name === "document" || name === "kml") visit(element, folderId, depth + 1);
        else if (name === "placemark") {
          const featureName = childText(element, "name") || "Điểm nhập", note = childText(element, "description").slice(0, 2000);
          Array.from(element.getElementsByTagNameNS("*", "Point")).forEach(point => { const points = coordsOf(point); if (points.length !== 1) throw new Error("Một điểm KML cần đúng một tọa độ."); const coords = points[0]; features.push({ id: uid("point"), kind: "point", name: featureName, label: /(^|\s)MX(\s|$)/i.test(featureName) ? "MX" : /(^|\s)DP(\s|$)/i.test(featureName) ? "DP" : featureName.slice(0, 24), coords, folder: folderId, note, importId: mapId }); });
          Array.from(element.getElementsByTagNameNS("*", "LineString")).forEach(line => { const coords = coordsOf(line); if (coords.length > 1) features.push({ id: uid("line"), kind: "line", name: featureName, coords, folder: folderId, note, importId: mapId }); });
        }
      }
    }
    visit(doc.documentElement, top.id, 0);
    if (!features.length) throw new Error("Tệp không có điểm hoặc đường với tọa độ hợp lệ.");
    if (features.length > 5000) throw new Error("KML vượt 5.000 điểm hoặc đường. Hãy chia nhỏ tệp.");
    return { folders, features, top };
  }
  function importMaps(maps, showMessage = true) {
    if (!isAdmin()) return;
    let added = 0, duplicates = 0, errors = [];
    (Array.isArray(maps) ? maps : []).forEach(item => {
      const id = String(item.id || item.name);
      if (state.importedIds.includes(id) || (!showMessage && state.dismissedImports.includes(id))) { duplicates++; return; }
      try {
        const result = parseKml(item.kml, item.name || "KMZ đã nhập", id);
        state.folders.push(...result.folders); state.features.push(...result.features); state.importedIds.push(id); state.dismissedImports = state.dismissedImports.filter(item => item !== id); state.activeFolder = result.top.id; added += result.features.length;
      } catch (error) { errors.push((item.name || "Tệp") + ": " + error.message); }
    });
    if (added) { persist(); renderMap(); openSheet("folders"); fitMap(); }
    if (showMessage) toast(errors.length ? errors.join("\n") : added ? "Đã nhập " + added + " điểm, đường. Tệp được lưu trên máy." : duplicates ? "Tệp này đã được nhập, không tạo bản trùng." : "Chưa chọn tệp.", 6000);
  }
  async function importFile() {
    if (!requireAdmin()) return;
    try { const result = await native("import"); if (!result.cancelled) importMaps(result.maps || (result.kml ? [result] : [])); }
    catch (error) { toast(error.message, 6000); }
  }
  const xml = esc;
  function kmlFeature(name, kind, coords, note = "") {
    return "<Placemark><name>" + xml(name) + "</name>" + (note ? "<description>" + xml(note) + "</description>" : "") +
      (kind === "point" ? "<Point><coordinates>" + coords[1] + "," + coords[0] + ",0</coordinates></Point>" : "<styleUrl>#cable</styleUrl><LineString><tessellate>1</tessellate><coordinates>" + coords.map(point => point[1] + "," + point[0] + ",0").join(" ") + "</coordinates></LineString>") + "</Placemark>";
  }
  function makeKml(includeLocal = true) {
    if (!user) throw new Error("Đăng nhập để xuất bản đồ.");
    includeLocal = includeLocal && isAdmin();
    const sharedExport = sharedLayers.filter(layer => sharedSelected.has(layer.key));
    const serverCount = [...routes.filter(route => selected.has(route.key)), ...sharedExport].reduce((count, route) => count + route.lines.length + route.markers.length, 0);
    const localCount = includeLocal ? state.features.filter(feature => folderVisible(feature.folder)).length : 0;
    if (!serverCount && !localCount) throw new Error("Chọn tuyến hoặc hiện thư mục có điểm, đường trước khi xuất.");
    let content = "";
    routes.filter(route => selected.has(route.key)).forEach(route => {
      let items = route.lines.map((line, index) => kmlFeature(route.name + (route.lines.length > 1 ? " " + (index + 1) : ""), "line", line)).join("");
      items += route.markers.map(point => kmlFeature(point.label + (point.position ? " · " + point.position : ""), "point", point.coords, route.name)).join("");
      if (items) content += "<Folder><name>" + xml(route.name) + "</name>" + items + "</Folder>";
    });
    if (sharedExport.length) {
      const written = new Set(), selectedIds = new Set(sharedExport.map(layer => layer.key));
      const sharedFolder = layer => {
        if (written.has(layer.key)) return ""; written.add(layer.key);
        let items = layer.lines.map((line, index) => kmlFeature(layer.name + (layer.lines.length > 1 ? " " + (index + 1) : ""), "line", line)).join("");
        items += layer.markers.map(point => kmlFeature(point.label + (point.position ? " · " + point.position : ""), "point", point.coords, point.note || layer.name)).join("");
        items += sharedExport.filter(item => item.parent === layer.key).map(sharedFolder).join("");
        return "<Folder><name>" + xml(layer.name) + "</name>" + items + "</Folder>";
      };
      let sharedContent = sharedExport.filter(layer => !selectedIds.has(layer.parent)).map(sharedFolder).join("");
      sharedContent += sharedExport.map(sharedFolder).join("");
      content += "<Folder><name>Bản đồ quản trị</name>" + sharedContent + "</Folder>";
    }
    if (includeLocal) {
      const foldersKml = (parent, visited) => state.folders.filter(folder => (folder.parent || "") === parent && folderVisible(folder.id)).map(folder => {
        if (visited.has(folder.id)) return ""; visited.add(folder.id);
        const features = state.features.filter(feature => feature.folder === folder.id).map(feature => kmlFeature(feature.name, feature.kind, feature.coords, feature.note)).join("");
        return "<Folder><name>" + xml(folder.name) + "</name>" + features + foldersKml(folder.id, visited) + "</Folder>";
      }).join("");
      content += foldersKml("", new Set());
    }
    if (!content) throw new Error("Chọn tuyến hoặc hiện thư mục có điểm, đường trước khi xuất.");
    return '<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>HP Map</name><Style id="cable"><LineStyle><color>ffc6b416</color><width>4</width></LineStyle></Style>' + content + "</Document></kml>";
  }
  function openExport() {
    if (!user) { openLogin(); return; }
    const active = routes.filter(route => selected.has(route.key)), count = isAdmin() ? state.features.filter(feature => folderVisible(feature.folder)).length : 0;
    modal("Xuất & chia sẻ KMZ", "<p>" + active.length + " tuyến server · " + sharedSelected.size + " lớp quản trị đã chọn" + (isAdmin() ? " · " + count + " đối tượng trên máy đang hiện" : "") + '</p><label class="inline-check"' + (!isAdmin() ? " hidden" : "") + '><input id="include-local" type="checkbox"' + (isAdmin() ? " checked" : "") + '>Gồm thư mục trên máy đang hiện</label><p class="note">Mỗi tuyến một thư mục. File KMZ là bản sao tại thời điểm xuất.</p><div class="actions"><button id="save-kmz" class="primary">' + icon("export") + 'Lưu KMZ</button><button id="share-kmz" class="secondary">' + icon("share") + 'Chia sẻ</button></div><p class="note">Chia sẻ qua Zalo, Viber, email hoặc ứng dụng đã cài.</p>', () => {
      const run = async share => {
        $("save-kmz").disabled = true; $("share-kmz").disabled = true;
        try {
          const kml = makeKml($("include-local").checked), name = "HP-Map-" + new Date().toISOString().slice(0, 10) + ".kmz";
          const result = await native("export", { kml, name, share });
          if (!result.cancelled) { closeModal(); toast(share ? "Đã mở lựa chọn chia sẻ KMZ." : "Đã lưu KMZ."); }
        } catch (error) { toast(error.message, 6000); }
        finally { if ($("save-kmz")) { $("save-kmz").disabled = false; $("share-kmz").disabled = false; } }
      };
      $("save-kmz").onclick = () => run(false); $("share-kmz").onclick = () => run(true);
    });
  }
  $("refresh").onclick = () => refresh(false); $("account").onclick = account; $("fit").onclick = () => { collapseSheet(); fitMap(); }; $("locate").onclick = locate; $("edit").onclick = openEditor; $("layers").onclick = openLayers;
  $("add-point").onclick = startPoint; $("add-line").onclick = () => startLine(); $("open-folders").onclick = () => openSheet("folders"); $("new-folder").onclick = () => createFolder();
  $("nav-map").onclick = collapseSheet; $("nav-routes").onclick = () => openSheet("routes"); $("nav-import").onclick = importFile; $("nav-export").onclick = openExport;
  $("sheet-handle").onclick = collapseSheet; $("close-sheet").onclick = collapseSheet; $("routes-tab").onclick = () => changePanel("routes"); $("folders-tab").onclick = () => changePanel("folders"); $("shared-tab").onclick = () => changePanel("shared");
  $("select-local-features").onclick = () => beginFeatureSelection("local", state.activeFolder);
  $("selection-cancel").onclick = cancelFeatureSelection;
  $("selection-copy").onclick = () => openTransfer("copy");
  $("selection-move").onclick = () => openTransfer("move");
  $("selection-list").onclick = () => { if (featureSelection) openSheet(featureSelection.source === "local" ? "folders" : "shared"); };
  $("shared-select-all").onchange = () => { const checked = $("shared-select-all").checked; sharedLayers.forEach(layer => checked ? sharedSelected.add(layer.key) : sharedSelected.delete(layer.key)); persist(); renderShared(); renderMap(); };
  $("search").oninput = renderRoutes; $("type-filter").onchange = renderRoutes; $("capacity-filter").onchange = renderRoutes;
  $("select-all").onchange = () => { const checked = $("select-all").checked; filteredRoutes().forEach(route => checked ? selected.add(route.key) : selected.delete(route.key)); persist(); renderRoutes(); renderMap(); };
  $("close-modal").onclick = closeModal; $("modal-backdrop").onclick = event => { if (event.target === $("modal-backdrop")) closeModal(); };
  $("cancel-drawing").onclick = cancelDrawing; $("undo-drawing").onclick = () => { if (drawing && drawing.mode === "line") { drawing.coords.pop(); renderDraft(); } };
  $("finish-drawing").onclick = () => drawing && drawing.save ? drawing.save() : finishLine();
  $("close-login").onclick = $("continue-local").onclick = () => { if (user) $("login").hidden = true; };
  $("show-password").onclick = () => { const show = $("password").type === "password"; $("password").type = show ? "text" : "password"; $("show-password").textContent = show ? "Ẩn" : "Hiện"; };
  $("login-form").onsubmit = async event => {
    event.preventDefault(); $("login-submit").disabled = true; $("login-error").hidden = true; $("login-submit").textContent = "Đang đăng nhập…";
    try { receiveSession(await native("login", { username: $("username").value.trim(), password: $("password").value })); openSheet("routes"); toast("Đã kết nối HP Telecom."); }
    catch (error) { $("login-error").textContent = error.message; $("login-error").hidden = false; }
    finally { $("login-submit").disabled = false; $("login-submit").textContent = "Đăng nhập"; }
  };
  $("server-login").onclick = async () => {
    $("server-login").disabled = true; $("login-error").hidden = true;
    try { const result = await native("serverLogin"); if (!result.cancelled) { receiveSession(result); openSheet("routes"); } }
    catch (error) { $("login-error").textContent = error.message; $("login-error").hidden = false; }
    finally { $("server-login").disabled = false; }
  };
  function back() {
    if (!$("login").hidden) { if (!user) return false; $("login").hidden = true; return true; }
    if (!$("modal-backdrop").hidden) { closeModal(); return true; }
    if (drawing) { cancelDrawing(); return true; }
    if (featureSelection && !transferBusy) { cancelFeatureSelection(); return true; }
    if (!$("sheet").classList.contains("collapsed")) { collapseSheet(); return true; }
    return false;
  }
  document.addEventListener("keydown", event => { if (event.key === "Escape") back(); if (event.key === "Tab" && !$("modal-backdrop").hidden) { const items = Array.from($("modal").querySelectorAll("button:not([disabled]),input,select,textarea")).filter(item => item.offsetParent !== null); if (!items.length) return; if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items[items.length - 1].focus(); } else if (!event.shiftKey && document.activeElement === items[items.length - 1]) { event.preventDefault(); items[0].focus(); } } });
  window.HPMapApp = { receiveSession, back, parseKml, makeKml, getBasemap: () => baseMapKey };
  applyPermissions(); openSheet("routes"); renderRoutes(); renderMap(); openLogin();
  native("session").then(receiveSession).catch(error => { $("connection").textContent = "Chưa kết nối server"; toast(error.message, 6000); });
  setInterval(() => { if (user && !authExpired && !drawing && !featureSelection && !transferBusy && $("login").hidden && $("modal-backdrop").hidden && document.visibilityState !== "hidden") refresh(true); }, 60000);
  window.addEventListener("resize", () => map.invalidateSize());
})();
