"""Explicit, append-only photo collaboration for incident and relocation folders."""
import hashlib
import json
import math
import re

MODULES = {'incident', 'relocation'}
STANDARD_GROUPS = {
    'incident': {'Hiện trường sự cố', 'Đầu số cáp', 'Chủng loại cáp', 'Vật tư',
                 'Kết quả đo kiểm', 'Trong thi công', 'Măng xông (MX)'},
    'relocation': {'Trước di dời', 'Trong thi công', 'Sau hoàn thành', 'Vật tư',
                   'Măng xông (MX)', 'Đầu số cáp', 'Kết quả đo kiểm'},
}


def schema(d):
    d.executescript('''
CREATE TABLE IF NOT EXISTS report_collaboration_state(
 fid TEXT PRIMARY KEY REFERENCES mobile_folders(id) ON DELETE CASCADE,
 revision INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS report_collaborators(
 fid TEXT NOT NULL REFERENCES mobile_folders(id) ON DELETE CASCADE,
 user TEXT NOT NULL REFERENCES users(id),
 PRIMARY KEY(fid,user));
CREATE INDEX IF NOT EXISTS report_collaborator_user ON report_collaborators(user,fid);
CREATE TABLE IF NOT EXISTS report_asset_bindings(
 fid TEXT NOT NULL REFERENCES mobile_folders(id) ON DELETE CASCADE,
 user TEXT NOT NULL REFERENCES users(id),
 asset TEXT NOT NULL REFERENCES mobile_assets(id) ON DELETE CASCADE,
 created INTEGER NOT NULL,
 consumed INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(fid,user,asset));
CREATE INDEX IF NOT EXISTS report_asset_pending ON report_asset_bindings(asset,consumed);
CREATE TABLE IF NOT EXISTS report_contribution_receipts(
 fid TEXT NOT NULL REFERENCES mobile_folders(id) ON DELETE CASCADE,
 user TEXT NOT NULL REFERENCES users(id),
 operation TEXT NOT NULL,
 digest TEXT NOT NULL,
 revision INTEGER NOT NULL,
 PRIMARY KEY(fid,user,operation));
''')


def eligible(person, folder):
    if not person or folder['module'] not in MODULES:
        return False
    if not person['enabled'] or person['deleted_at'] or person['role'] == 'viewer':
        return False
    if person['team'] != folder['team']:
        return False
    modules = person['modules']
    if isinstance(modules, str):
        modules = json.loads(modules)
    return person['role'] in ('admin', 'dispatcher') or folder['module'] in modules


def is_member(d, u, folder):
    if folder['owner'] == u['id'] or folder['module'] not in MODULES:
        return False
    if not d.execute('SELECT 1 FROM report_collaborators WHERE fid=? AND user=?',
                     (folder['id'], u['id'])).fetchone():
        return False
    return eligible(d.execute('SELECT * FROM users WHERE id=?', (u['id'],)).fetchone(), folder)


def members(d, folder):
    return [{'id': p['id'], 'display_name': p['display_name']}
            for p in d.execute('SELECT u.* FROM users u JOIN report_collaborators c ON c.user=u.id '
                               'WHERE c.fid=? ORDER BY u.display_name,u.id', (folder['id'],))
            if eligible(p, folder)]


def view(d, folder):
    state = d.execute('SELECT revision FROM report_collaboration_state WHERE fid=?',
                      (folder['id'],)).fetchone()
    candidates = [{'id': p['id'], 'display_name': p['display_name']}
                  for p in d.execute('SELECT * FROM users WHERE team=? AND id!=? '
                                     'ORDER BY display_name,id', (folder['team'], folder['owner']))
                  if eligible(p, folder)]
    return {'revision': state['revision'] if state else 0,
            'members': members(d, folder), 'candidates': candidates}


def folder(d, fid, Error):
    if not isinstance(fid, str) or not re.fullmatch('[a-f0-9-]{32,36}', fid):
        raise Error(400, 'Mã báo cáo không hợp lệ.')
    row = d.execute('SELECT * FROM mobile_folders WHERE id=?', (fid,)).fetchone()
    if not row:
        raise Error(404, 'Không tìm thấy báo cáo.')
    return row


def manager(store, u, row, Error):
    if row['owner'] != u['id'] and u['role'] != 'admin':
        raise Error(403, 'Chỉ người lập báo cáo hoặc quản trị được chọn người cùng làm.')
    store.module(u, row['module'])
    if row['module'] not in MODULES:
        raise Error(400, 'Người cùng làm chỉ áp dụng cho Sự cố và Di dời.')


def allow_upload(store, d, u, row, Error):
    store.module(u, row['module'])
    if row['module'] not in MODULES:
        # Preserve the existing cable administrator/support upload workflow.
        if row['owner'] != u['id']:
            store.manage(u)
        if not store.team_visible(u, row['team']):
            raise Error(403, 'Không có quyền gửi ảnh vào báo cáo này.')
        return
    if row['owner'] != u['id'] and u['role'] != 'admin' and not is_member(d, u, row):
        raise Error(403, 'Bạn chưa được chọn làm cùng báo cáo này.')
    payload = json.loads(row['payload'])
    if row['owner'] != u['id'] and u['role'] != 'admin' and (payload.get('completed') or (payload.get('report') or {}).get('completed')):
        raise Error(409, 'Báo cáo đã hoàn thành. Người lập cần mở lại trước khi thêm ảnh.')


def bind_asset(d, row, user, asset, now):
    if row['module'] in MODULES:
        # An upload grants only this actor a pending reference within this folder.
        d.execute('INSERT INTO report_asset_bindings(fid,user,asset,created) VALUES(?,?,?,?) '
                  'ON CONFLICT(fid,user,asset) DO UPDATE SET created=excluded.created,consumed=0',
                  (row['id'], user, asset, now))


def accept_assets(d, fid, user, assets):
    d.executemany('UPDATE report_asset_bindings SET consumed=1 WHERE fid=? AND user=? AND asset=?',
                  ((fid, user, aid) for aid in assets))


def can_read_asset(d, u, asset, refs):
    for row in d.execute('SELECT f.* FROM mobile_folders f JOIN report_collaborators c ON c.fid=f.id '
                         'WHERE c.user=? AND f.owner=? AND f.module=?',
                         (u['id'], asset['owner'], asset['module'])):
        if not is_member(d, u, row):
            continue
        if asset['id'] in refs(json.loads(row['payload'])):
            return True
        if d.execute('SELECT 1 FROM report_asset_bindings WHERE fid=? AND user=? AND asset=? AND consumed=0',
                     (row['id'], u['id'], asset['id'])).fetchone():
            return True
    return False


def pending_assets(d, owner, module):
    return {r['asset'] for r in d.execute(
        'SELECT b.asset FROM report_asset_bindings b JOIN mobile_folders f ON f.id=b.fid '
        'WHERE f.owner=? AND f.module=? AND b.consumed=0', (owner, module))}


def bound_assets(d, fid):
    return {r['asset'] for r in d.execute('SELECT asset FROM report_asset_bindings WHERE fid=?', (fid,))}


def safe_image(value, Error):
    if isinstance(value, dict):
        if any(k in value for k in ('path', 'pendingPhoto', 'pendingOriginal', 'pendingCapture', 'routes', 'raw')):
            raise Error(400, 'Ảnh gửi lên phải là ảnh đã lưu, không chứa đường dẫn trên điện thoại.')
        for child in value.values():
            safe_image(child, Error)
    elif isinstance(value, list):
        for child in value:
            safe_image(child, Error)
    elif isinstance(value, float) and not math.isfinite(value):
        raise Error(400, 'Thông tin ảnh không hợp lệ.')


def dispatch(store, d, u, method, fid, action, b, now, Error):
    if action == 'collaborators':
        if method not in ('GET', 'PUT'):
            raise Error(405, 'Thao tác người cùng làm không hợp lệ.')
        if method == 'PUT':
            d.execute('BEGIN IMMEDIATE')
        row = folder(d, fid, Error)
        manager(store, u, row, Error)
        current = view(d, row)
        if method == 'GET':
            return current
        if type(b.get('revision')) is not int or b['revision'] != current['revision']:
            raise Error(409, 'Danh sách người cùng làm đã thay đổi. Hãy tải lại.')
        users = b.get('users')
        if not isinstance(users, list) or len(users) > 100 or any(not isinstance(x, str) for x in users) or len(set(users)) != len(users):
            raise Error(400, 'Danh sách người cùng làm không hợp lệ.')
        available = {p['id'] for p in current['candidates']}
        if any(uid not in available for uid in users):
            raise Error(400, 'Người cùng làm phải đang hoạt động, cùng Team / Đội và có quyền báo cáo.')
        d.execute('DELETE FROM report_collaborators WHERE fid=?', (fid,))
        d.executemany('INSERT INTO report_collaborators(fid,user) VALUES(?,?)', ((fid, uid) for uid in users))
        d.execute('INSERT INTO report_collaboration_state VALUES(?,?) ON CONFLICT(fid) DO UPDATE SET revision=excluded.revision',
                  (fid, current['revision'] + 1))
        store.audit(d, u['id'], 'report-collaborators-edit', fid)
        return view(d, row)
    if action != 'contributions' or method != 'POST':
        raise Error(405, 'Thao tác góp ảnh không hợp lệ.')
    d.execute('BEGIN IMMEDIATE')
    row = folder(d, fid, Error)
    if row['module'] not in MODULES:
        raise Error(400, 'Chỉ góp ảnh cho báo cáo Sự cố hoặc Di dời.')
    store.module(u, row['module'])
    if row['owner'] != u['id'] and u['role'] != 'admin' and not is_member(d, u, row):
        raise Error(403, 'Bạn chưa được chọn làm cùng báo cáo này.')
    operation = b.get('operation')
    if not isinstance(operation, str) or not re.fullmatch('[a-f0-9-]{32,36}', operation):
        raise Error(400, 'Mã lần góp ảnh không hợp lệ.')
    images = b.get('images')
    if not isinstance(images, list) or not 1 <= len(images) <= 10000:
        raise Error(400, 'Mỗi lần góp cần từ 1 đến 10000 ảnh.')
    try:
        encoded_request = json.dumps(images, sort_keys=True, ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError):
        raise Error(400, 'Thông tin ảnh không hợp lệ.')
    if len(encoded_request.encode()) > 2 * 1024 * 1024:
        raise Error(413, 'Thông tin ảnh quá lớn.')
    digest = hashlib.sha256(encoded_request.encode()).hexdigest()
    receipt = d.execute('SELECT * FROM report_contribution_receipts WHERE fid=? AND user=? AND operation=?',
                        (fid, u['id'], operation)).fetchone()
    if receipt:
        if receipt['digest'] != digest:
            raise Error(409, 'Mã lần góp ảnh đã dùng cho nội dung khác.')
        return {'revision': receipt['revision'], 'replayed': True}
    allow_upload(store, d, u, row, Error)
    payload = json.loads(row['payload'])
    if payload.get('completed') or (payload.get('report') or {}).get('completed'):
        raise Error(409, 'Báo cáo đã hoàn thành. Người lập cần mở lại trước khi thêm ảnh.')
    report = payload.get('report')
    if report is None:
        report = payload['report'] = {}
    if not isinstance(report, dict):
        raise Error(400, 'Báo cáo không hợp lệ.')
    groups = report.setdefault('groups', [])
    if not isinstance(groups, list):
        raise Error(400, 'Nhóm ảnh của báo cáo không hợp lệ.')
    from mobile import refs
    used = set()
    for item in images:
        if not isinstance(item, dict) or not isinstance(item.get('group'), str) or not isinstance(item.get('image'), dict):
            raise Error(400, 'Nhóm hoặc thông tin ảnh không hợp lệ.')
        name, image = item['group'], item['image']
        safe_image(image, Error)
        if not image.get('file') or any(type(image.get(k)) is not int or not 1 <= image[k] <= 32768 for k in ('width', 'height')):
            raise Error(400, 'Ảnh cần mã ảnh và kích thước hợp lệ.')
        if 'caption' in image and not isinstance(image['caption'], str):
            raise Error(400, 'Mô tả ảnh không hợp lệ.')
        try:
            assets = refs(image)
        except ValueError as e:
            raise Error(400, str(e))
        for aid in assets:
            if not d.execute('SELECT 1 FROM report_asset_bindings b JOIN mobile_assets a ON a.id=b.asset '
                             'WHERE b.fid=? AND b.user=? AND b.asset=? AND a.owner=? AND a.module=?',
                             (fid, u['id'], aid, row['owner'], row['module'])).fetchone():
                raise Error(400, 'Ảnh chưa được chính tài khoản này gửi vào báo cáo này.')
        matches = [g for g in groups if isinstance(g, dict) and g.get('name') == name]
        if len(matches) > 1:
            raise Error(400, 'Tên nhóm ảnh bị trùng. Người lập cần kiểm tra báo cáo.')
        if not matches:
            if name not in STANDARD_GROUPS[row['module']]:
                raise Error(400, 'Không tìm thấy nhóm ảnh trong báo cáo.')
            group = {'name': name, 'note': '', 'pairNotes': [], 'images': []}
            groups.append(group)
        else:
            group = matches[0]
        target = group.setdefault('images', [])
        if not isinstance(target, list):
            raise Error(400, 'Nhóm ảnh của báo cáo không hợp lệ.')
        # Copy only the new photo. Existing captions, notes and report fields remain untouched.
        target.append({**image, 'uploadedBy': u['id'], 'uploadedByName': u['display_name']})
        used.update(assets)
    if sum(len(g.get('images', [])) for g in groups if isinstance(g, dict) and isinstance(g.get('images', []), list)) > 10000:
        raise Error(413, 'Báo cáo có quá nhiều ảnh.')
    encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False)
    if len(encoded.encode()) > 2 * 1024 * 1024:
        raise Error(413, 'Thông tin báo cáo quá lớn.')
    revision = row['revision'] + 1
    d.execute('UPDATE mobile_folders SET payload=?,revision=?,updated=? WHERE id=?', (encoded, revision, now, fid))
    accept_assets(d, fid, u['id'], used)
    d.execute('INSERT INTO report_contribution_receipts VALUES(?,?,?,?,?)', (fid, u['id'], operation, digest, revision))
    store.audit(d, u['id'], 'report-photo-contribute', fid)
    return {'revision': revision}
