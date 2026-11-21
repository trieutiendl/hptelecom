"""Account-owned, resumable MP4 library; files are kept outside SQLite (Python 3.8)."""
import base64
import hashlib
import json
import os
import re
import secrets
import uuid
from urllib.parse import quote

MAX_SIZE = 64 * 1024 * 1024
CHUNK_SIZE = 1024 * 1024
TICKET_SECONDS = 600


def schema(d):
    d.executescript('''
    CREATE TABLE IF NOT EXISTS video_library(
      id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id),
      name TEXT NOT NULL, mime TEXT NOT NULL, digest TEXT NOT NULL,
      relative_path TEXT NOT NULL, size INTEGER NOT NULL,
      duration_ms INTEGER NOT NULL, details TEXT NOT NULL,
      captured_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
      UNIQUE(owner,digest));
    CREATE INDEX IF NOT EXISTS video_library_owner ON video_library(owner,created_at DESC);
    CREATE TABLE IF NOT EXISTS video_uploads(
      owner TEXT NOT NULL REFERENCES users(id), upload_id TEXT NOT NULL,
      name TEXT NOT NULL, size INTEGER NOT NULL, digest TEXT NOT NULL,
      duration_ms INTEGER NOT NULL, details TEXT NOT NULL, captured_at INTEGER NOT NULL,
      offset INTEGER NOT NULL DEFAULT 0, video_id TEXT REFERENCES video_library(id),
      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
      PRIMARY KEY(owner,upload_id));
    CREATE TABLE IF NOT EXISTS video_stream_tickets(
      digest TEXT PRIMARY KEY, session_hash TEXT NOT NULL REFERENCES sessions(token_hash) ON DELETE CASCADE,
      video_id TEXT NOT NULL REFERENCES video_library(id) ON DELETE CASCADE,
      expires INTEGER NOT NULL);
    ''')


def _path(store, owner, name):
    # Only server identifiers enter filesystem paths; never use a supplied filename.
    if not re.fullmatch(r'[a-f0-9]{32}', owner):
        raise ValueError('Invalid owner identifier')
    root = store.folder.resolve()
    path = (root / 'video-library' / owner / name).resolve()
    path.relative_to(root)
    return path


def _upload_id(value, Error):
    if not isinstance(value, str) or not re.fullmatch(r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}', value):
        raise Error(400, 'Mã tải video không hợp lệ.')
    return value


def _integer(value, minimum, maximum, Error, message):
    if type(value) is not int or not minimum <= value <= maximum:
        raise Error(400, message)
    return value


def _visible(u, row):
    return row['owner'] == u['id'] or u['role'] == 'admin' or (
        u['role'] == 'dispatcher' and bool(u.get('team')) and u['team'] == row['team'])


def _video(d, u, video_id, Error):
    row = d.execute('SELECT v.*,u.username,u.display_name AS photographer,u.team FROM video_library v JOIN users u ON u.id=v.owner WHERE v.id=?', (video_id,)).fetchone()
    if not row or not _visible(u, row):
        raise Error(404, 'Không tìm thấy video trong thư viện.')
    return row


def _view(row):
    result = {key: row[key] for key in ('id', 'owner', 'name', 'mime', 'size', 'duration_ms', 'captured_at', 'created_at', 'username', 'photographer', 'team')}
    result['details'] = json.loads(row['details'])
    return result


def _ack(row):
    result = {'upload_id': row['upload_id'], 'offset': row['offset'], 'chunk_size': CHUNK_SIZE, 'complete': bool(row['video_id'])}
    if row['video_id']:
        result['id'] = row['video_id']
    return result


def _pending(d, u, upload_id, Error):
    row = d.execute('SELECT * FROM video_uploads WHERE owner=? AND upload_id=?', (u['id'], upload_id)).fetchone()
    if not row:
        raise Error(404, 'Không tìm thấy lượt tải video của bạn.')
    return row


def _digest(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while True:
            chunk = source.read(64 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _sync_directory(path):
    # On NAS, persist directory entries before acknowledging a new file or rename.
    # Windows does not expose directory fsync through this API.
    if os.name != 'nt':
        descriptor = os.open(str(path), os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def _stored_video_ok(store, owner, digest, size):
    """Do not acknowledge a database-only restore or a damaged MP4 as backed up."""
    path = _path(store, owner, digest + '.mp4')
    try:
        return path.is_file() and path.stat().st_size == size and _digest(path) == digest
    except OSError:
        return False


def _mp4(path, size):
    """Validate bounded MP4 boxes without loading or decoding the movie."""
    found = set()
    with path.open('rb') as source:
        offset, boxes = 0, 0
        while offset < size:
            boxes += 1
            if boxes > 100000:
                return False
            source.seek(offset)
            header = source.read(8)
            if len(header) != 8:
                return False
            length, kind = int.from_bytes(header[:4], 'big'), header[4:]
            minimum = 8
            if length == 1:
                large = source.read(8)
                if len(large) != 8:
                    return False
                length, minimum = int.from_bytes(large, 'big'), 16
            elif length == 0:
                length = size - offset
            if length < minimum or offset + length > size:
                return False
            if offset == 0 and (kind != b'ftyp' or length < 16):
                return False
            found.add(kind)
            offset += length
    return {b'ftyp', b'mdat', b'moov'}.issubset(found)


def ticket_user(d, ticket, video_id, now, Error, user_view):
    if not isinstance(ticket, str) or not 20 <= len(ticket) <= 100:
        raise Error(401, 'Liên kết xem video đã hết hạn. Hãy mở lại video.')
    row = d.execute('''SELECT u.*,s.token_hash AS media_session FROM video_stream_tickets t
      JOIN sessions s ON s.token_hash=t.session_hash JOIN users u ON u.id=s.user_id
      WHERE t.digest=? AND t.video_id=? AND t.expires>? AND s.expires>?
      AND u.enabled=1 AND u.deleted_at=0''',
      (hashlib.sha256(ticket.encode()).hexdigest(), video_id, now, now)).fetchone()
    if not row:
        raise Error(401, 'Liên kết xem video đã hết hạn. Hãy mở lại video.')
    return user_view(row), row['media_session']


class VideoFile:
    def __init__(self, path, size, name):
        self.path, self.size, self.name = path, size, name


def byte_range(value, size):
    """Return inclusive range or None for an unsupported/unsatisfiable Range."""
    if not value:
        return 0, size - 1, 200
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', value.strip())
    if not match or not any(match.groups()):
        return None
    left, right = match.groups()
    # Prevent pathological integer strings as well as out-of-bounds ranges.
    if len(left) > 20 or len(right) > 20:
        return None
    if left:
        start, end = int(left), min(int(right), size - 1) if right else size - 1
    else:
        suffix = int(right)
        if suffix <= 0:
            return None
        start, end = max(0, size - suffix), size - 1
    if not 0 <= start <= end < size:
        return None
    return start, end, 206


def send_file(handler, video):
    selected = byte_range(handler.headers.get('Range'), video.size)
    if selected is None:
        handler.send_response(416)
        handler.send_header('Content-Range', 'bytes */' + str(video.size))
        handler.send_header('Content-Length', '0')
        handler.send_header('Cache-Control', 'no-store')
        handler.end_headers()
        return
    start, end, status = selected
    with video.path.open('rb') as source:
        if os.fstat(source.fileno()).st_size != video.size:
            raise OSError('Video file size changed')
        source.seek(start)
        handler.send_response(status)
        handler.send_header('Content-Type', 'video/mp4')
        handler.send_header('Content-Length', str(end - start + 1))
        handler.send_header('Accept-Ranges', 'bytes')
        if status == 206:
            handler.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, video.size))
        handler.send_header('Content-Disposition', "inline; filename*=UTF-8''" + quote(video.name, safe=''))
        handler.send_header('Cache-Control', 'private, no-store')
        handler.send_header('X-Content-Type-Options', 'nosniff')
        handler.send_header('Referrer-Policy', 'no-referrer')
        handler.end_headers()
        remaining = end - start + 1
        try:
            while remaining:
                chunk = source.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                handler.wfile.write(chunk)
                remaining -= len(chunk)
        except (ConnectionError, TimeoutError):
            pass


def dispatch(store, d, u, method, path, b, now, Error, session_hash=''):
    parts = path.strip('/').split('/')
    if path == '/api/mobile/videos' and method == 'GET':
        query = 'SELECT v.*,u.username,u.display_name AS photographer,u.team FROM video_library v JOIN users u ON u.id=v.owner'
        args = ()
        if u['role'] == 'dispatcher':
            query += " WHERE v.owner=? OR (u.team=? AND ?<>'')"
            args = (u['id'], u.get('team') or '', u.get('team') or '')
        elif u['role'] != 'admin':
            query += ' WHERE v.owner=?'
            args = (u['id'],)
        return {'videos': [_view(row) for row in d.execute(query + ' ORDER BY v.created_at DESC,v.id DESC LIMIT 500', args)]}
    if len(parts) in (4, 5) and re.fullmatch(r'[a-f0-9]{32}', parts[3]):
        row = _video(d, u, parts[3], Error)
        if len(parts) == 4 and method == 'GET':
            path = _path(store, row['owner'], row['digest'] + '.mp4')
            if not path.is_file():
                raise Error(404, 'Tệp video chưa có trên server.')
            return VideoFile(path, row['size'], row['name'])
        if len(parts) == 5 and parts[4] == 'stream' and method == 'POST':
            session = d.execute('SELECT expires FROM sessions WHERE token_hash=? AND user_id=?', (session_hash, u['id'])).fetchone()
            if not session or session['expires'] <= now:
                raise Error(401, 'Vui lòng đăng nhập lại.')
            d.execute('DELETE FROM video_stream_tickets WHERE expires<=?', (now,))
            ticket, expires = secrets.token_urlsafe(32), min(now + TICKET_SECONDS, session['expires'])
            d.execute('INSERT INTO video_stream_tickets VALUES(?,?,?,?)', (hashlib.sha256(ticket.encode()).hexdigest(), session_hash, row['id'], expires))
            return {'url': '/api/mobile/videos/' + row['id'] + '?ticket=' + ticket, 'expires_at': expires}
    if method != 'POST' or path not in ('/api/mobile/videos/start', '/api/mobile/videos/chunk', '/api/mobile/videos/finish'):
        raise Error(404, 'Không tìm thấy chức năng video.')
    if u['role'] not in ('admin', 'staff', 'dispatcher'):
        raise Error(403, 'Tài khoản chỉ có quyền xem và tải video.')
    upload_id = _upload_id(b.get('upload_id'), Error)
    d.execute('BEGIN IMMEDIATE')
    if path.endswith('/start'):
        size = _integer(b.get('size'), 1, MAX_SIZE, Error, 'Video tối đa 64 MB.')
        duration = _integer(b.get('duration_ms', 0), 0, 86400000, Error, 'Thời lượng video không hợp lệ.')
        captured = _integer(b.get('captured_at', now * 1000), 0, 4102444800000, Error, 'Thời gian quay video không hợp lệ.')
        digest = b.get('sha256')
        if not isinstance(digest, str) or not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise Error(400, 'Mã kiểm tra video không hợp lệ.')
        details = b.get('details', {})
        if not isinstance(details, dict) or len(json.dumps(details, ensure_ascii=False)) > 4000:
            raise Error(400, 'Thông tin video quá dài hoặc không hợp lệ.')
        name = b.get('name', 'Video HP Telecom.mp4')
        if not isinstance(name, str):
            raise Error(400, 'Tên video không hợp lệ.')
        name = re.sub(r'[\x00-\x1f\x7f/\\]', '_', name.strip())[:145] or 'Video HP Telecom'
        if not name.lower().endswith('.mp4'):
            name += '.mp4'
        row = d.execute('SELECT * FROM video_uploads WHERE owner=? AND upload_id=?', (u['id'], upload_id)).fetchone()
        if row and (row['size'] != size or row['digest'] != digest):
            raise Error(409, 'Mã tải này đã được dùng cho video khác.')
        existing = d.execute('SELECT id,size FROM video_library WHERE owner=? AND digest=?', (u['id'], digest)).fetchone()
        if existing and existing['size'] != size:
            raise Error(409, 'Dung lượng video không khớp mã kiểm tra.')
        if existing and not _stored_video_ok(store, u['id'], digest, size):
            # Keep the stable library id, but repair missing/corrupt bytes from the
            # phone. A metadata-only backup must never produce complete=true.
            d.execute('UPDATE video_uploads SET video_id=NULL,offset=0,updated_at=? WHERE owner=? AND digest=? AND video_id IS NOT NULL', (now, u['id'], digest))
            row = d.execute('SELECT * FROM video_uploads WHERE owner=? AND upload_id=?', (u['id'], upload_id)).fetchone()
            existing = None
        if not row:
            d.execute('''INSERT INTO video_uploads(owner,upload_id,name,size,digest,duration_ms,details,captured_at,offset,video_id,created_at,updated_at)
              VALUES(?,?,?,?,?,?,?,?,?,?,?,?)''', (u['id'], upload_id, name, size, digest, duration, json.dumps(details, ensure_ascii=False), captured, size if existing else 0, existing['id'] if existing else None, now, now))
        elif existing and not row['video_id']:
            d.execute('UPDATE video_uploads SET video_id=?,offset=?,updated_at=? WHERE owner=? AND upload_id=?', (existing['id'], size, now, u['id'], upload_id))
        row = _pending(d, u, upload_id, Error)
        if row['video_id']:
            duplicate_part = _path(store, u['id'], upload_id + '.part')
            if duplicate_part.is_file():
                duplicate_part.unlink()
        if not row['video_id']:
            part = _path(store, u['id'], upload_id + '.part')
            final = _path(store, u['id'], digest + '.mp4')
            # A finish may have renamed the file just before SQLite committed.
            if not (row['offset'] == size and final.is_file() and final.stat().st_size == size):
                actual = min(part.stat().st_size if part.is_file() else 0, row['offset'])
                if part.is_file() and part.stat().st_size != actual:
                    with part.open('r+b') as file:
                        file.truncate(actual)
                if actual != row['offset']:
                    d.execute('UPDATE video_uploads SET offset=? WHERE owner=? AND upload_id=?', (actual, u['id'], upload_id))
        return _ack(_pending(d, u, upload_id, Error))
    row = _pending(d, u, upload_id, Error)
    if row['video_id']:
        if not _stored_video_ok(store, u['id'], row['digest'], row['size']):
            raise Error(409, 'Tệp video trên server cần được sao lưu lại. Hãy gọi start để tiếp tục.')
        if path.endswith('/finish'):
            return dict(_view(_video(d, u, row['video_id'], Error)), complete=True, offset=row['size'])
        return _ack(row)
    part = _path(store, u['id'], upload_id + '.part')
    if path.endswith('/chunk'):
        offset = _integer(b.get('offset'), 0, row['size'], Error, 'Vị trí tải video không hợp lệ.')
        encoded = b.get('base64')
        if not isinstance(encoded, str) or not 1 <= len(encoded) <= 4 * ((CHUNK_SIZE + 2) // 3):
            raise Error(413, 'Mỗi phần video tối đa 1 MB.')
        try:
            raw = base64.b64decode(encoded, validate=True)
        except Exception:
            raise Error(400, 'Dữ liệu phần video không hợp lệ.')
        if not 1 <= len(raw) <= CHUNK_SIZE or offset + len(raw) > row['size']:
            raise Error(413, 'Phần video vượt dung lượng đã khai báo.')
        if offset < row['offset'] and offset + len(raw) <= row['offset'] and part.is_file():
            with part.open('rb') as source:
                source.seek(offset)
                if source.read(len(raw)) == raw:
                    return _ack(row)
        if offset != row['offset']:
            raise Error(409, 'Vị trí tải không khớp. Hãy gọi start để tiếp tục.')
        if offset and (not part.is_file() or part.stat().st_size < offset):
            raise Error(409, 'Tệp đang tải đã thay đổi. Hãy gọi start để tiếp tục.')
        new_file = not part.exists()
        part.parent.mkdir(parents=True, exist_ok=True)
        with part.open('r+b' if part.exists() else 'w+b') as target:
            target.truncate(offset)
            target.seek(offset)
            target.write(raw)
            target.flush()
            os.fsync(target.fileno())
        if new_file:
            _sync_directory(part.parent)
            _sync_directory(part.parent.parent)
            _sync_directory(store.folder)
        d.execute('UPDATE video_uploads SET offset=?,updated_at=? WHERE owner=? AND upload_id=?', (offset + len(raw), now, u['id'], upload_id))
        return _ack(_pending(d, u, upload_id, Error))
    if row['offset'] != row['size']:
        raise Error(409, 'Video chưa tải đủ. Hãy gọi start để tiếp tục.')
    final = _path(store, u['id'], row['digest'] + '.mp4')
    source = part if part.is_file() else final
    if not source.is_file() or source.stat().st_size != row['size'] or _digest(source) != row['digest']:
        raise Error(422, 'Video tải lên không khớp mã kiểm tra.')
    if not _mp4(source, row['size']):
        raise Error(422, 'Tệp không phải video MP4 hoàn chỉnh.')
    if source != final:
        os.replace(str(source), str(final))
        _sync_directory(final.parent)
    video_id = uuid.uuid4().hex
    d.execute('''INSERT OR IGNORE INTO video_library(id,owner,name,mime,digest,relative_path,size,duration_ms,details,captured_at,created_at)
      VALUES(?,?,?,?,?,?,?,?,?,?,?)''', (video_id, u['id'], row['name'], 'video/mp4', row['digest'], str(final.relative_to(store.folder)), row['size'], row['duration_ms'], row['details'], row['captured_at'], now))
    video_id = d.execute('SELECT id FROM video_library WHERE owner=? AND digest=?', (u['id'], row['digest'])).fetchone()['id']
    d.execute('UPDATE video_uploads SET video_id=?,updated_at=? WHERE owner=? AND upload_id=?', (video_id, now, u['id'], upload_id))
    store.audit(d, u['id'], 'video-library-upload', video_id)
    return dict(_view(_video(d, u, video_id, Error)), complete=True, offset=row['size'])
