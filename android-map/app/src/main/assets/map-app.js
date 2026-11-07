(function (global) {
  'use strict';

  const VERSION = '1.1.0';
  const MAX_KML_BYTES = 8 * 1024 * 1024;
  const MAX_FEATURES = 5000;
  const MAX_COORDINATES = 50000;
  const MAX_NATIVE_BYTES = 16 * 1024 * 1024;
  const KML_NAMESPACES = new Set(['', 'http://www.opengis.net/kml/2.2', 'http://earth.google.com/kml/2.2', 'http://earth.google.com/kml/2.1', 'http://earth.google.com/kml/2.0']);

  function plain(value, limit = 512) {
    return String(value == null ? '' : value).replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').trim().slice(0, limit);
  }

  function decodeBase64(value, limit, environment) {
    if (typeof value !== 'string' || value.length > Math.ceil(limit / 3) * 4 || value.length % 4 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
      throw new Error('Dữ liệu tệp không hợp lệ hoặc vượt giới hạn dung lượng.');
    }
    const binary = environment.atob(value);
    if (binary.length > limit) throw new Error('Tệp vượt giới hạn dung lượng.');
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  function encodeBase64(bytes, environment) {
    const chunks = [];
    for (let offset = 0; offset < bytes.length; offset += 24576) {
      const end = Math.min(offset + 24576, bytes.length);
      let binary = '';
      for (let index = offset; index < end; index++) binary += String.fromCharCode(bytes[index]);
      chunks.push(environment.btoa(binary));
    }
    return chunks.join('');
  }

  function elements(node) {
    return Array.from(node.childNodes || []).filter(child => child.nodeType === 1);
  }

  function kmlName(node) {
    return KML_NAMESPACES.has(node.namespaceURI || '') ? (node.localName || node.nodeName).replace(/^.*:/, '') : '';
  }

  function childText(node, name) {
    const found = elements(node).find(child => kmlName(child) === name);
    return found ? found.textContent || '' : '';
  }

  // Only local, explicitly supported geometries are read. Styles, images,
  // overlays, HTML and NetworkLinks never become DOM content or network loads.
  function parseKml(text, metadata, DOMParserClass) {
    if (typeof text !== 'string' || text.length > MAX_KML_BYTES || /<!\s*(?:DOCTYPE|ENTITY)\b/i.test(text)) {
      throw new Error('KML không hợp lệ: không hỗ trợ khai báo DOCTYPE hoặc ENTITY.');
    }
    const document = new DOMParserClass().parseFromString(text, 'application/xml');
    const root = document.documentElement;
    if (!root || kmlName(root) !== 'kml' || document.getElementsByTagName('parsererror').length ||
        Array.from(document.getElementsByTagName('*')).some(node => (node.localName || node.nodeName) === 'parsererror')) {
      throw new Error('Không đọc được cấu trúc KML.');
    }
    if (document.getElementsByTagName('*').length > 100000) throw new Error('KML có quá nhiều thành phần.');
    const id = plain(metadata.id, 128), filename = plain(metadata.name) || 'Bản đồ nhập';
    const routes = new Map();
    let featureCount = 0, coordinateCount = 0, folderSequence = 0, ignoredLinks = 0;

    function coordinates(node) {
      const value = childText(node, 'coordinates').trim();
      if (!value) throw new Error('Một vị trí trong KML chưa có tọa độ.');
      const tokens = value.split(/\s+/);
      coordinateCount += tokens.length;
      if (coordinateCount > MAX_COORDINATES) throw new Error('KML vượt 50.000 tọa độ. Hãy chia thành tệp nhỏ hơn.');
      return tokens.map(token => {
        const parts = token.split(',');
        if (parts.length < 2 || parts.length > 3 || parts.some(part => !part.trim()) ||
            parts.some(part => !Number.isFinite(Number(part)))) throw new Error('KML có tọa độ không hợp lệ.');
        const lon = Number(parts[0]), lat = Number(parts[1]);
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new Error('Tọa độ KML nằm ngoài phạm vi bản đồ.');
        return [lat, lon];
      });
    }

    function geometry(node, route, name, note, depth) {
      if (depth > 24) throw new Error('KML lồng quá nhiều nhóm.');
      const kind = kmlName(node);
      if (kind === 'MultiGeometry') {
        elements(node).forEach(child => geometry(child, route, name, note, depth + 1));
        return;
      }
      if (kind !== 'Point' && kind !== 'LineString') return;
      if (++featureCount > MAX_FEATURES) throw new Error('KML vượt 5.000 điểm hoặc đường. Hãy chia thành tệp nhỏ hơn.');
      const points = coordinates(node);
      if (kind === 'Point') {
        if (points.length !== 1) throw new Error('Một điểm KML phải có đúng một tọa độ.');
        const markerKind = /^(MX|DP)$/i.test(name) ? name.toUpperCase() : 'GPS';
        route.markers.push({lat: points[0][0], lon: points[0][1], label: markerKind === 'GPS' ? name : markerKind,
          kind: markerKind, name, note, history: []});
      } else {
        if (points.length < 2) throw new Error('Đường KML cần ít nhất hai tọa độ.');
        route.lines.push(points);
      }
    }

    function visit(node, folder, depth) {
      if (depth > 24) throw new Error('KML lồng quá nhiều thư mục.');
      const kind = kmlName(node);
      if (kind === 'NetworkLink') { ignoredLinks++;return; }
      if (kind === 'Folder') {
        const name = plain(childText(node, 'name')) || 'Thư mục';
        const next = {key: id + '/folder-' + (++folderSequence), name: folder.name + ' / ' + name};
        elements(node).forEach(child => visit(child, next, depth + 1));
      } else if (kind === 'kml' || kind === 'Document') {
        elements(node).forEach(child => visit(child, folder, depth + 1));
      } else if (kind === 'Placemark') {
        let route = routes.get(folder.key);
        if (!route) { route = {key: 'local:' + folder.key, name: folder.name, lines: [], markers: []};routes.set(folder.key, route); }
        const name = plain(childText(node, 'name'));
        // The description stays plain text even when it contains CDATA HTML.
        const note = plain(childText(node, 'description'), 2000);
        elements(node).forEach(child => geometry(child, route, name, note, 0));
      }
    }

    visit(root, {key: id + '/root', name: filename.replace(/\.(kmz|kml)$/i, '')}, 0);
    const result = [...routes.values()].filter(route => route.lines.length || route.markers.length);
    if (!result.length) throw new Error('Tệp chưa có điểm hoặc đường KML được hỗ trợ.');
    return {id, name: filename, routes: result, featureCount, coordinateCount, ignoredLinks};
  }

  function start(environment) {
    const native = environment.HPTelecomMapNative;
    if (!environment.location || environment.location.origin !== 'https://hptelecom.id.vn' || !native ||
        typeof native.listMapFiles !== 'function') return null;
    if (environment.HPTelecomMapBridge && environment.HPTelecomMapBridge.version === VERSION) {
      environment.HPTelecomMapBridge.attach();
      return environment.HPTelecomMapBridge;
    }

    const document = environment.document;
    const localFiles = new Map(), localLayers = new Map(), transport = new Map(), annotationFolders = new Map();
    let active = null, unsubscribe = null, localRoot = null, drawTools = null, sharing = false, revision = 0, messageTimer = null;
    let selectedServerRouteCount = 0, serverBusy = false, requestSequence = 0;
    const visibilityKey = 'hp-map-local-visibility-v1';
    const annotationsKey = 'hp-map-annotations-v1';
    let selectedFolderId = '', drawMode = null, draftName = '', lineDraft = [], draftLayer = null;
    let visibility = {};
    try {
      const value = JSON.parse(environment.localStorage.getItem(visibilityKey) || '{}');
      if (value && typeof value === 'object' && !Array.isArray(value)) visibility = value;
    } catch (_) {}

    try {
      const saved = JSON.parse(environment.localStorage.getItem(annotationsKey) || '[]');
      if (Array.isArray(saved)) for (const folder of saved.slice(0, 100)) {
        if (!folder || typeof folder.id !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(folder.id) || !Array.isArray(folder.features)) continue;
        const features = folder.features.slice(0, 5000).filter(feature => {
          if (!feature || !['point', 'line'].includes(feature.type)) return false;
          if (feature.type === 'point') return Number.isFinite(feature.lat) && Math.abs(feature.lat) <= 90 && Number.isFinite(feature.lon) && Math.abs(feature.lon) <= 180;
          return Array.isArray(feature.points) && feature.points.length >= 2 && feature.points.length <= 5000 &&
            feature.points.every(point => Array.isArray(point) && point.length === 2 && Number.isFinite(point[0]) && Math.abs(point[0]) <= 90 && Number.isFinite(point[1]) && Math.abs(point[1]) <= 180);
        }).map(feature => feature.type === 'point'
          ? {type: 'point', name: plain(feature.name, 100) || 'Điểm', lat: feature.lat, lon: feature.lon}
          : {type: 'line', name: plain(feature.name, 100) || 'Đường', points: feature.points.map(point => [point[0], point[1]])});
        annotationFolders.set(folder.id, {id: folder.id, name: plain(folder.name, 100) || 'Thư mục', visible: folder.visible !== false, features});
      }
    } catch (_) {}
    if (!annotationFolders.size) annotationFolders.set('local-default', {id: 'local-default', name: 'Default folder', visible: true, features: []});

    function node(tag, text, className) {
      const element = document.createElement(tag);
      if (text != null) element.textContent = String(text);
      if (className) element.className = className;
      return element;
    }

    const style = node('style');
    style.id = 'hp-map-native-style';
    style.textContent = '.hp-map-local{border-top:1px solid #39727f;margin-top:12px;padding-top:8px}.hp-map-local h3{font-size:15px;margin:5px 0 9px}.hp-map-local>p{font-size:12px;color:#a9cbd5;margin:5px 0}.hp-map-local-row{display:flex;align-items:center;gap:8px;margin:7px 0;padding:8px;background:#123f4c;border-radius:8px}.hp-map-local-row label{display:flex;align-items:center;gap:8px;flex:1;min-width:0;font-size:13px;overflow-wrap:anywhere}.hp-map-local-row input{width:19px;height:19px;min-height:19px;flex-shrink:0;accent-color:#21c9dc}.hp-map-local-row button{width:auto;min-height:40px;margin:0;padding:4px 10px;font-size:12px}.hp-map-local-row small{display:block;font-size:11px;color:#a9cbd5;margin-top:4px}.hp-map-local>button{width:auto;min-height:42px;margin:7px 0;font-size:13px}.hp-map-native-message{position:fixed;z-index:10000;bottom:12px;left:12px;right:12px;padding:12px 14px;background:#103b48;color:#f2fbff;border:1px solid #4ea5b6;border-radius:10px;font:14px/1.4 system-ui,sans-serif;box-shadow:0 4px 18px #0005}.hp-map-native-message[hidden]{display:none}body.hp-map-native-active #server-sidebar,body.hp-map-native-active #sidebar-backdrop,body.hp-map-native-active .sidebar-toggle,body.hp-map-native-active .main-column>footer,body.hp-map-native-active .topbar-heading small{display:none!important}body.hp-map-native-active .server-shell{display:block}body.hp-map-native-active .main-column{width:100%;margin:0}body.hp-map-native-active .topbar{position:static;padding:8px 12px;min-height:44px}body.hp-map-native-active main{padding:10px}body.hp-map-native-active .patrol-map-panel{padding:12px}body.hp-map-native-active .patrol-map-panel>h1{font-size:19px;margin:4px 0}body.hp-map-native-active .patrol-map-controls{gap:7px}body.hp-map-native-active .patrol-map-controls button{min-height:42px;padding:6px 9px;font-size:12px}body.hp-map-native-active .patrol-map-layout{display:flex;flex-direction:column}body.hp-map-native-active .patrol-live-map{order:0;height:52vh;min-height:300px}body.hp-map-native-active .patrol-map-sidebar{order:1;max-height:none}body.hp-map-native-active .patrol-map-panel>.hint{font-size:12px;line-height:1.4}';
    style.textContent += '.hp-map-edit{border-top:1px solid #39727f;margin-top:14px;padding-top:10px}.hp-map-edit h3{font-size:15px;margin:5px 0 8px}.hp-map-edit>p{font-size:12px;color:#a9cbd5;margin:6px 0}.hp-map-edit input,.hp-map-edit select{display:block;width:100%;min-height:42px;margin:7px 0;padding:7px 10px;border:1px solid #39727f;border-radius:8px;background:#0c3441;color:#f2fbff}.hp-map-edit>button{width:auto;min-height:42px;margin:5px 6px 5px 0;padding:6px 12px;font-size:12px}.hp-map-edit .hp-map-line-finish{background:#087f8d}.hp-map-edit .hp-map-draw-cancel{background:#583b43}';
    style.textContent += '.hp-map-draw-tools{position:absolute;z-index:1100;top:74px;left:10px;display:flex;flex-direction:column;gap:10px}.hp-map-draw-tool{width:46px!important;height:46px!important;min-height:46px!important;margin:0!important;padding:0!important;border:0!important;border-radius:50%!important;background:#f8f4fb!important;color:#21363c!important;font-size:24px!important;font-weight:700!important;box-shadow:0 2px 10px #00182066!important;touch-action:manipulation}';
    if (new URLSearchParams(environment.location.search).get('mapapp') === '1' && document.querySelector('.patrol-map-panel')) {
      document.body.classList.add('hp-map-native-shell');
      style.textContent += [
        'html,body{height:100%;overflow:hidden!important}',
        'body.hp-map-native-shell #server-sidebar,body.hp-map-native-shell #sidebar-backdrop,body.hp-map-native-shell .sidebar-toggle,body.hp-map-native-shell .topbar,body.hp-map-native-shell .main-column>footer{display:none!important}',
        'body.hp-map-native-shell .server-shell{display:block!important;height:100dvh;overflow:hidden}',
        'body.hp-map-native-shell .main-column{display:block!important;width:100%!important;height:100dvh;margin:0!important;overflow:hidden}',
        'body.hp-map-native-shell main{position:relative;height:100dvh;padding:0!important;overflow:hidden}',
        'body.hp-map-native-shell main>button{display:none!important}',
        'body.hp-map-native-shell .patrol-map-panel{position:relative!important;width:100%!important;height:100dvh!important;min-height:100dvh!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important;box-shadow:none!important;overflow:hidden!important}',
        'body.hp-map-native-shell .patrol-map-panel>h1,body.hp-map-native-shell .patrol-map-panel>p,body.hp-map-native-shell .patrol-map-panel>.hint{display:none!important}',
        'body.hp-map-native-shell .patrol-map-controls{position:absolute!important;z-index:900;top:8px;left:10px;right:10px;display:flex!important;flex-wrap:nowrap!important;gap:7px;overflow-x:auto;white-space:nowrap;padding:5px;background:#082b39d9;border:1px solid #377482;border-radius:14px;scrollbar-width:none}',
        'body.hp-map-native-shell .patrol-map-controls::-webkit-scrollbar{display:none}',
        'body.hp-map-native-shell .patrol-map-controls button{flex:0 0 auto;min-height:42px;padding:6px 10px;font-size:12px}',
        'body.hp-map-native-shell .patrol-map-layout{position:absolute!important;inset:0!important;display:block!important;margin:0!important}',
        'body.hp-map-native-shell .patrol-live-map{position:absolute!important;z-index:1;inset:0!important;width:100%!important;height:100dvh!important;min-height:100dvh!important;border-radius:0!important}',
        'body.hp-map-native-shell .patrol-map-sidebar{position:absolute!important;z-index:800;left:8px!important;right:8px!important;bottom:0!important;width:auto!important;height:43dvh!important;max-height:43dvh!important;margin:0!important;padding:12px!important;overflow:auto!important;background:#083447f5!important;border:1px solid #63b5c5!important;border-bottom:0!important;border-radius:20px 20px 0 0!important;box-shadow:0 -8px 28px #00182066!important;overscroll-behavior:contain}',
        'body.hp-map-native-shell .patrol-map-sidebar h2,body.hp-map-native-shell .patrol-map-sidebar h3{margin-top:4px}',
        'body.hp-map-native-shell .patrol-map-sidebar input,body.hp-map-native-shell .patrol-map-sidebar select{min-height:42px}',
        'body.hp-map-native-shell .patrol-map-sidebar button{min-height:42px}',
        'body.hp-map-native-shell .patrol-map-sidebar .hp-map-local{padding-bottom:8px}'
      ].join('');
    }
    (document.head || document.documentElement).appendChild(style);
    const message = node('div', '', 'hp-map-native-message');message.hidden = true;message.setAttribute('role', 'status');
    message.setAttribute('aria-live', 'polite');document.body.appendChild(message);

    function tell(text) {
      environment.clearTimeout(messageTimer);message.textContent = plain(text, 1000);message.hidden = false;
      messageTimer = environment.setTimeout(() => { message.hidden = true; }, 6500);
    }

    function persistVisibility() {
      visibility = Object.fromEntries([...localFiles].map(([id, file]) => [id, file.visible]));
      try { environment.localStorage.setItem(visibilityKey, JSON.stringify(visibility)); } catch (_) {}
    }

    function persistAnnotations() {
      try {
        const value = JSON.stringify([...annotationFolders.values()]);
        if (value.length > 2 * 1024 * 1024) { tell('Bản vẽ vượt dung lượng lưu trên máy. Hãy xuất KMZ rồi xóa bớt dữ liệu.'); return false; }
        environment.localStorage.setItem(annotationsKey, value);revision++;return true;
      } catch (_) { tell('Không lưu được bản vẽ vào bộ nhớ điện thoại. Hãy xuất KMZ để giữ bản sao.');return false; }
    }

    function newId() { return 'hp-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9); }

    function selectedFolder() {
      return annotationFolders.get(selectedFolderId) || annotationFolders.values().next().value || null;
    }

    function clearDraft() {
      if (draftLayer) { try { draftLayer.remove(); } catch (_) {} draftLayer = null; }
      lineDraft = [];
    }

    function updateEditorStatus() {
      if (!localRoot) return;
      const status = localRoot.querySelector('.hp-map-edit-status');
      if (!status) return;
      status.textContent = drawMode === 'point' ? 'Chạm bản đồ để đặt điểm.' : drawMode === 'line'
        ? 'Chạm bản đồ để thêm điểm cho đường (' + lineDraft.length + '). Chọn Hoàn tất khi xong.'
        : 'Bản vẽ lưu trên điện thoại. Xuất KMZ để chia sẻ.';
      const finish = localRoot.querySelector('.hp-map-line-finish'), cancel = localRoot.querySelector('.hp-map-draw-cancel');
      if (finish) finish.hidden = drawMode !== 'line';
      if (cancel) cancel.hidden = !drawMode;
    }

    function renderAnnotations() {
      if (!active) return;
      const {L, map} = active;
      for (const [id, folder] of annotationFolders) {
        if (!folder.visible || !folder.features.length) continue;
        const layer = L.layerGroup();
        for (const feature of folder.features) {
          if (feature.type === 'point') {
            L.circleMarker([feature.lat, feature.lon], {radius: 7, color: '#ffffff', weight: 1.5, fillColor: '#16aeca', fillOpacity: 1})
              .bindPopup(popup(feature.name, folder.name)).addTo(layer);
          } else {
            L.polyline(feature.points, {color: '#16aeca', weight: 4, opacity: 0.95})
              .bindPopup(popup(feature.name, folder.name)).addTo(layer);
          }
        }
        layer.addTo(map);localLayers.set('annotation:' + id, layer);
      }
    }

    function handleMapClick(event) {
      if (!drawMode || !active || sharing) return;
      const folder = selectedFolder();
      if (!folder) { drawMode = null;updateEditorStatus();tell('Hãy tạo thư mục trước.');return; }
      const point = [Number(event.latlng.lat), Number(event.latlng.lng)];
      if (!point.every(Number.isFinite)) return;
      const featureCount = [...annotationFolders.values()].reduce((count, item) => count + item.features.length, 0);
      if (featureCount >= 5000) { drawMode = null;clearDraft();updateEditorStatus();tell('Đã đạt giới hạn 5.000 điểm và đường trên thiết bị.');return; }
      if (drawMode === 'point') {
        folder.features.push({type: 'point', name: draftName, lat: point[0], lon: point[1]});
        drawMode = null;persistAnnotations();drawFiles();renderFiles();tell('Đã thêm điểm vào “' + folder.name + '”. Bản vẽ chỉ lưu trên điện thoại.');
      } else {
        if (lineDraft.length >= 5000) { tell('Đường đã đạt giới hạn 5.000 điểm. Hoàn tất đường hoặc hủy.');return; }
        lineDraft.push(point);
        if (draftLayer) draftLayer.remove();
        draftLayer = active.L.layerGroup();
        active.L.polyline(lineDraft, {color: '#f5a623', weight: 4, dashArray: '7 6'}).addTo(draftLayer);
        for (const latlng of lineDraft) active.L.circleMarker(latlng, {radius: 5, color: '#ffffff', fillColor: '#f5a623', fillOpacity: 1}).addTo(draftLayer);
        draftLayer.addTo(active.map);updateEditorStatus();
      }
    }

    function startPoint() {
      const folder = selectedFolder(), input = localRoot && localRoot.querySelector('.hp-map-point-name');
      const name = plain(input && input.value || 'Điểm mới', 100);
      if (!folder) { tell('Tạo thư mục ở bảng dưới trước khi thêm điểm.');return; }
      if (!name) { tell('Nhập tên điểm trước.');return; }
      draftName = name;clearDraft();drawMode = 'point';updateEditorStatus();tell('Chạm bản đồ để đặt “' + name + '”.');
    }

    function startLine() {
      const folder = selectedFolder(), input = localRoot && localRoot.querySelector('.hp-map-line-name');
      const name = plain(input && input.value || 'Đường mới', 100);
      if (!folder) { tell('Tạo thư mục ở bảng dưới trước khi vẽ đường.');return; }
      if (!name) { tell('Nhập tên đường trước.');return; }
      if ([...annotationFolders.values()].reduce((count, item) => count + item.features.length, 0) >= 5000) { tell('Đã đạt giới hạn 5.000 điểm và đường trên thiết bị.');return; }
      draftName = name;clearDraft();drawMode = 'line';updateEditorStatus();tell('Chạm bản đồ để thêm các điểm của đường.');
    }

    function makeDrawTools() {
      const tools = node('div', null, 'hp-map-draw-tools');
      const point = node('button', '＋', 'hp-map-draw-tool');point.type = 'button';point.title = 'Thêm điểm';point.setAttribute('aria-label', 'Thêm điểm');point.addEventListener('click', () => startPoint());
      const line = node('button', '／', 'hp-map-draw-tool');line.type = 'button';line.title = 'Vẽ đường';line.setAttribute('aria-label', 'Vẽ đường');line.addEventListener('click', () => startLine());
      const folders = node('button', '▰', 'hp-map-draw-tool');folders.type = 'button';folders.title = 'Thư mục';folders.setAttribute('aria-label', 'Mở phần thư mục và bản vẽ');folders.addEventListener('click', () => {
        if (localRoot) { localRoot.scrollIntoView({behavior: 'smooth', block: 'nearest'});tell('Quản lý thư mục và đặt tên ở bảng bên dưới.'); }
      });
      for (const button of [point, line, folders]) {
        button.addEventListener('pointerdown', event => event.stopPropagation());
        button.addEventListener('click', event => event.stopPropagation());
      }
      tools.append(point, line, folders);return tools;
    }

    function renderAnnotationTools() {
      const section = node('section', null, 'hp-map-edit');
      section.setAttribute('aria-label', 'Thư mục, điểm và đường cục bộ');
      section.appendChild(node('h3', 'Thư mục & bản vẽ'));
      section.appendChild(node('p', 'Tạo thư mục, điểm và đường. Lưu trên điện thoại; xuất KMZ để chuyển sang thiết bị khác.'));
      const folderName = node('input');folderName.type = 'text';folderName.maxLength = 100;folderName.placeholder = 'Tên thư mục mới';folderName.setAttribute('aria-label', 'Tên thư mục mới');folderName.className = 'hp-map-folder-name';section.appendChild(folderName);
      const create = node('button', '＋ Tạo thư mục');create.type = 'button';create.addEventListener('click', () => {
        const name = plain(folderName.value, 100);if (!name) { tell('Nhập tên thư mục trước.');folderName.focus();return; }
        if (annotationFolders.size >= 100) { tell('Đã đạt giới hạn 100 thư mục trên thiết bị.');return; }
        const id = newId();annotationFolders.set(id, {id, name, visible: true, features: []});selectedFolderId = id;folderName.value = '';
        persistAnnotations();drawFiles();renderFiles();tell('Đã tạo thư mục “' + name + '” trên điện thoại.');
      });section.appendChild(create);
      const chooser = node('select');chooser.className = 'hp-map-folder-select';chooser.setAttribute('aria-label', 'Thư mục lưu điểm và đường');
      if (!annotationFolders.size) { const option = node('option', 'Tạo thư mục để bắt đầu');option.value = '';chooser.appendChild(option);chooser.disabled = true; }
      else {
        for (const folder of annotationFolders.values()) { const option = node('option', folder.name);option.value = folder.id;option.selected = folder.id === selectedFolderId;chooser.appendChild(option); }
        if (!annotationFolders.has(selectedFolderId)) { selectedFolderId = annotationFolders.keys().next().value;chooser.value = selectedFolderId; }
        chooser.addEventListener('change', () => { selectedFolderId = chooser.value; });
      }
      section.appendChild(chooser);
      const pointName = node('input');pointName.type = 'text';pointName.maxLength = 100;pointName.placeholder = 'Tên điểm';pointName.value = 'Điểm mới';pointName.setAttribute('aria-label', 'Tên điểm mới');pointName.className = 'hp-map-point-name';section.appendChild(pointName);
      const addPoint = node('button', '＋ Tạo điểm');addPoint.type = 'button';addPoint.disabled = !annotationFolders.size;addPoint.addEventListener('click', () => {
        startPoint();
      });section.appendChild(addPoint);
      const lineName = node('input');lineName.type = 'text';lineName.maxLength = 100;lineName.placeholder = 'Tên đường';lineName.value = 'Đường mới';lineName.setAttribute('aria-label', 'Tên đường mới');lineName.className = 'hp-map-line-name';section.appendChild(lineName);
      const drawLine = node('button', '／ Vẽ đường');drawLine.type = 'button';drawLine.disabled = !annotationFolders.size;drawLine.addEventListener('click', () => {
        startLine();
      });section.appendChild(drawLine);
      const finish = node('button', 'Hoàn tất đường');finish.type = 'button';finish.className = 'hp-map-line-finish';finish.hidden = true;finish.addEventListener('click', () => {
        const folder = selectedFolder();
        if (!folder || lineDraft.length < 2) { tell('Vẽ ít nhất hai điểm để tạo đường.');return; }
        folder.features.push({type: 'line', name: draftName, points: lineDraft.map(point => point.slice())});clearDraft();drawMode = null;persistAnnotations();drawFiles();renderFiles();tell('Đã lưu đường “' + draftName + '” vào thư mục.');
      });section.appendChild(finish);
      const cancel = node('button', 'Hủy thao tác');cancel.type = 'button';cancel.className = 'hp-map-draw-cancel';cancel.hidden = true;cancel.addEventListener('click', () => { clearDraft();drawMode = null;updateEditorStatus();tell('Đã hủy thao tác vẽ.'); });section.appendChild(cancel);
      const status = node('p', 'Bản vẽ lưu trên điện thoại. Xuất KMZ để chia sẻ.', 'hp-map-edit-status');status.setAttribute('aria-live', 'polite');section.appendChild(status);
      for (const folder of annotationFolders.values()) {
        const row = node('div', null, 'hp-map-local-row'), label = node('label'), checkbox = node('input');
        checkbox.type = 'checkbox';checkbox.checked = folder.visible;checkbox.setAttribute('aria-label', 'Hiện thư mục ' + folder.name);
        checkbox.addEventListener('change', () => { folder.visible = checkbox.checked;persistAnnotations();drawFiles();renderFiles(); });
        label.append(checkbox, node('span', folder.name + ' · ' + folder.features.length + ' mục'));row.appendChild(label);section.appendChild(row);
      }
      updateEditorStatus();return section;
    }

    function popup(title, note) {
      const box = node('div');box.appendChild(node('strong', title || 'Vị trí'));
      if (note) box.appendChild(node('p', note));
      box.appendChild(node('small', 'KMZ trên máy'));
      return box;
    }

    function removeLayers() {
      for (const layer of localLayers.values()) { try { layer.remove(); } catch (_) {} }
      localLayers.clear();
    }

    function detach() {
      const oldUnsubscribe = unsubscribe;unsubscribe = null;
      if (oldUnsubscribe) oldUnsubscribe();
      if (active && active.map && typeof active.map.off === 'function') active.map.off('click', handleMapClick);
      clearDraft();drawMode = null;
      if (drawTools) drawTools.remove();drawTools = null;
      removeLayers();if (localRoot) localRoot.remove();localRoot = null;active = null;
      selectedServerRouteCount = 0;serverBusy = false;document.body.classList.remove('hp-map-native-active');
    }

    function fitFile(file) {
      if (!active) return;
      const points = file.routes.flatMap(route => route.lines.flatMap(line => line).concat(route.markers.map(marker => [marker.lat, marker.lon])));
      if (points.length) active.map.fitBounds(active.L.latLngBounds(points), {padding: [24, 24], maxZoom: 17});
    }

    function drawFiles() {
      removeLayers();if (!active) return;
      for (const [id, file] of localFiles) {
        if (!file.visible) continue;
        const {L, map} = active;
        const layer = L.layerGroup();
        for (const route of file.routes) {
          for (const line of route.lines) L.polyline(line, {color: '#b59aff', weight: 4, opacity: 0.9}).bindPopup(popup(route.name)).addTo(layer);
          for (const marker of route.markers) {
            const point = L.circleMarker([marker.lat, marker.lon], {radius: 6, color: '#ffffff', weight: 1.5, fillColor: '#8759cf', fillOpacity: 0.95});
            point.bindPopup(popup(marker.name || marker.label, marker.note));
            if (marker.label) point.bindTooltip(node('span', marker.label), {direction: 'top'});
            point.addTo(layer);
          }
        }
        layer.addTo(map);localLayers.set(id, layer);
      }
      renderAnnotations();
    }

    function updateDisabled() {
      if (!localRoot) return;
      for (const input of localRoot.querySelectorAll('input,button')) input.disabled = sharing;
      if (drawTools) for (const button of drawTools.querySelectorAll('button')) button.disabled = sharing;
    }

    function renderFiles() {
      if (!localRoot) return;
      localRoot.replaceChildren();localRoot.appendChild(node('h3', 'KMZ trên máy'));
      localRoot.appendChild(node('p', localFiles.size ? [...localFiles.values()].filter(file => file.visible).length + ' / ' + localFiles.size + ' tệp đang chọn · Chỉ lưu trên máy' : 'Nhập KMZ/KML để xem cùng tuyến trên server.'));
      for (const file of localFiles.values()) {
        const row = node('div', null, 'hp-map-local-row'), label = node('label'), checkbox = node('input');
        checkbox.type = 'checkbox';checkbox.checked = file.visible;checkbox.setAttribute('aria-label', 'Hiện ' + file.name);
        checkbox.addEventListener('change', () => { if (sharing) { checkbox.checked = file.visible;return; }file.visible = checkbox.checked;revision++;persistVisibility();drawFiles();renderFiles(); });
        const title = node('span', file.name);title.appendChild(node('small', file.featureCount + ' điểm / đường'));
        label.append(checkbox, title);row.appendChild(label);
        const view = node('button', 'Xem');view.type = 'button';view.addEventListener('click', () => {
          if (sharing) return;file.visible = true;revision++;persistVisibility();drawFiles();renderFiles();fitFile(file);
        });row.appendChild(view);localRoot.appendChild(row);
      }
      const pick = node('button', 'Nhập KMZ');pick.type = 'button';pick.addEventListener('click', pickFile);localRoot.appendChild(pick);updateDisabled();
      localRoot.appendChild(renderAnnotationTools());updateDisabled();
    }

    function attach() {
      const adapter = environment.HPPatrolLiveMap && environment.HPPatrolLiveMap.getActive && environment.HPPatrolLiveMap.getActive();
      if (!adapter || !adapter.map || !adapter.L || !adapter.sidebar) { if (active) detach();return; }
      if (active === adapter) return;
      detach();active = adapter;localRoot = node('section', null, 'hp-map-local');localRoot.setAttribute('aria-label', 'Tệp KMZ trên máy');adapter.sidebar.appendChild(localRoot);
      if (typeof adapter.map.on === 'function') adapter.map.on('click', handleMapClick);
      drawTools = makeDrawTools();
      const mapContainer = adapter.map.getContainer && adapter.map.getContainer();if (mapContainer) mapContainer.appendChild(drawTools);
      document.body.classList.add('hp-map-native-active');
      unsubscribe = adapter.subscribe(state => {
        if (active !== adapter) return;
        if (state.disposed) { detach();return; }
        selectedServerRouteCount = Number(state.selectedServerRouteCount) || 0;serverBusy = !!state.busy;updateDisabled();
      });
      drawFiles();renderFiles();
      environment.setTimeout(() => { if (active === adapter) adapter.map.invalidateSize(); }, 0);
    }

    function parseNativeMap(value) {
      if (!value || typeof value !== 'object' || !/^[A-Za-z0-9_.-]{1,128}$/.test(value.id || '')) throw new Error('Thông tin tệp nhập không hợp lệ.');
      const bytes = decodeBase64(value.kmlBase64, MAX_KML_BYTES, environment);
      const text = new environment.TextDecoder('utf-8', {fatal: true}).decode(bytes);
      const parsed = parseKml(text, {id: value.id, name: value.name}, environment.DOMParser);
      return {...parsed, byteLength: bytes.length, visible: visibility[value.id] !== false};
    }

    function validateTotals(files) {
      if (files.length > 10 || files.reduce((n, file) => n + file.byteLength, 0) > MAX_NATIVE_BYTES ||
          files.reduce((n, file) => n + file.featureCount, 0) > MAX_FEATURES || files.reduce((n, file) => n + file.coordinateCount, 0) > MAX_COORDINATES) {
        throw new Error('Bản đồ trên máy vượt giới hạn 10 tệp, 5.000 điểm/đường hoặc 50.000 tọa độ. Hãy bớt tệp trước khi nhập.');
      }
    }

    function onNativeResult(result) {
      if (!result || typeof result !== 'object' || Array.isArray(result)) return;
      if (!result.ok) { if (!result.cancelled) tell(result.error || 'Không thực hiện được thao tác với tệp.');return; }
      try {
        if (result.type === 'import') {
          const file = parseNativeMap(result.map);file.visible = true;
          validateTotals([...localFiles.values()].filter(previous => previous.id !== file.id).concat(file));
          localFiles.set(file.id, file);revision++;persistVisibility();attach();drawFiles();renderFiles();fitFile(file);
          tell((result.duplicate ? 'Tệp đã có; đã bật lại lớp bản đồ.' : 'Đã nhập ' + file.name + '. Chỉ lưu trên máy.') + (file.ignoredLinks ? ' Liên kết bản đồ bên ngoài được bỏ qua.' : ''));
        } else if (result.type === 'list') {
          if (!Array.isArray(result.maps) || result.maps.length > 10) throw new Error('Danh sách tệp trên máy không hợp lệ.');
          const files = result.maps.map(parseNativeMap);validateTotals(files);
          if (new Set(files.map(file => file.id)).size !== files.length) throw new Error('Danh sách tệp bị trùng mã.');
          localFiles.clear();files.forEach(file => localFiles.set(file.id, file));revision++;persistVisibility();attach();drawFiles();renderFiles();
        } else if (result.type === 'share') tell('Đã mở menu chia sẻ Android. Bạn chọn ứng dụng, người nhận và tự bấm Gửi.');
        else if (result.type === 'save') tell('Đã lưu tệp KMZ.');
      } catch (error) { tell(error.message || 'Không đọc được tệp bản đồ.'); }
    }

    function receiveChunk(chunk) {
      if (!chunk || typeof chunk !== 'object' || !/^[A-Za-z0-9_.-]{1,128}$/.test(chunk.id || '')) return;
      let entry = transport.get(chunk.id);
      try {
        if (!Number.isInteger(chunk.index) || !Number.isInteger(chunk.total) || chunk.total < 1 || chunk.total > 171 ||
            chunk.index < 0 || chunk.index >= chunk.total || typeof chunk.data !== 'string' || chunk.data.length > 192 * 1024 || !/^[A-Za-z0-9+/=]*$/.test(chunk.data)) throw new Error('Phần dữ liệu tệp không hợp lệ.');
        if (!entry) {
          if (chunk.index !== 0 || transport.size >= 2) throw new Error('Thứ tự nhận tệp không hợp lệ.');
          entry = {parts: [], total: chunk.total, size: 0, timer: environment.setTimeout(() => { transport.delete(chunk.id);tell('Nhận tệp quá lâu. Hãy thử nhập lại.'); }, 60000)};
          transport.set(chunk.id, entry);
        }
        if (entry.total !== chunk.total || chunk.index !== entry.parts.length) throw new Error('Các phần của tệp không đúng thứ tự.');
        entry.size += chunk.data.length;if (entry.size > 32 * 1024 * 1024) throw new Error('Dữ liệu nhận vượt giới hạn.');
        entry.parts.push(chunk.data);
        if (entry.parts.length === entry.total) {
          environment.clearTimeout(entry.timer);transport.delete(chunk.id);
          const bytes = decodeBase64(entry.parts.join(''), 24 * 1024 * 1024, environment);
          onNativeResult(JSON.parse(new environment.TextDecoder('utf-8', {fatal: true}).decode(bytes)));
        }
      } catch (error) { if (entry) environment.clearTimeout(entry.timer);transport.delete(chunk.id);tell(error.message); }
    }

    function pickFile() {
      if (sharing) return;
      try { native.pickMapFile('web-import-' + (++requestSequence)); } catch (_) { tell('Chưa mở được trình chọn tệp.'); }
    }

    async function shareSelected(mode) {
      if (sharing) return;
      attach();
      if (!active) { tell('Hãy mở Bản đồ và đăng nhập để chọn tuyến cần xuất.');return; }
      const adapter = active;
      const selectedLocal = [...localFiles.values()].filter(file => file.visible);
      if (!selectedServerRouteCount && !selectedLocal.length) { tell('Hãy chọn ít nhất một tuyến hoặc tệp KMZ.');return; }
      if (serverBusy) { tell('Bản đồ đang cập nhật. Hãy thử lại sau vài giây.');return; }
      sharing = true;const capturedRevision = revision;updateDisabled();tell('Đang chuẩn bị KMZ các tuyến đã chọn…');
      try {
        const snapshot = await adapter.snapshotSelected();
        if (active !== adapter || capturedRevision !== revision) throw new Error('Danh sách bản đồ vừa thay đổi. Hãy chọn và chia sẻ lại.');
        if (!snapshot || !Array.isArray(snapshot.routes)) throw new Error('Chưa đọc được bản đồ mới nhất từ server.');
        const drawnRoutes = [...annotationFolders.values()].filter(folder => folder.visible && folder.features.length).map(folder => ({
          key: 'local-drawing:' + folder.id, name: folder.name,
          lines: folder.features.filter(feature => feature.type === 'line').map(feature => feature.points),
          markers: folder.features.filter(feature => feature.type === 'point').map(feature => ({lat: feature.lat, lon: feature.lon, name: feature.name, label: 'GPS', kind: 'GPS', note: folder.name, history: []}))
        }));
        const routes = snapshot.routes.concat(selectedLocal.flatMap(file => file.routes), drawnRoutes);
        if (!routes.length) throw new Error('Các tuyến đã chọn chưa có tọa độ để xuất.');
        const exporter = environment.HPPatrolMapExport;
        if (!exporter || typeof exporter.kmz !== 'function') throw new Error('Chưa tải được công cụ KMZ. Hãy mở lại bản đồ.');
        const bytes = exporter.kmz({name: 'Bản đồ tuyến cáp', routes});
        if (!bytes || !bytes.length || bytes.length > MAX_NATIVE_BYTES) throw new Error('KMZ vượt 16 MB. Hãy chọn ít tuyến hơn.');
        const payload = encodeBase64(bytes, environment);
        const filename = 'Ban-do-' + routes.length + '-tuyen.kmz';
        if (mode === 'save') native.saveKmz(payload, filename);
        else native.shareKmz(payload, filename);
      } catch (error) { tell(error.message || 'Chưa tạo được tệp KMZ.'); }
      finally { sharing = false;updateDisabled(); }
    }

    const bridge = Object.freeze({version: VERSION, attach, onNativeResult, receiveChunk, shareSelected, pickFile});
    environment.HPTelecomMapBridge = bridge;
    environment.addEventListener('hp-map-ready', attach);
    environment.addEventListener('hp-map-disposed', () => { if (active) detach(); });
    attach();
    try { native.listMapFiles('web-list-' + (++requestSequence)); } catch (_) { tell('Chưa đọc được danh sách KMZ trên máy.'); }
    return bridge;
  }

  if (typeof module === 'object' && module.exports) module.exports = {parseKml, decodeBase64, encodeBase64, start};
  else start(global);
})(typeof window !== 'undefined' ? window : globalThis);
