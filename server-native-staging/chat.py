import time

MAX_BODY = 2000
MAX_PAGE = 100
RATE_LIMIT = 30


def schema(d):
    d.execute('CREATE TABLE IF NOT EXISTS chat_messages(id INTEGER PRIMARY KEY AUTOINCREMENT,team TEXT NOT NULL COLLATE NOCASE,user_id TEXT NOT NULL,user_name TEXT NOT NULL,body TEXT NOT NULL,created_at INTEGER NOT NULL,deleted_at INTEGER NOT NULL DEFAULT 0)')
    d.execute('CREATE INDEX IF NOT EXISTS chat_team_id ON chat_messages(team,id)')


def known_teams(d):
    rows = d.execute("SELECT name FROM teams UNION SELECT team FROM users WHERE deleted_at=0 UNION SELECT team FROM chat_messages ORDER BY 1")
    return [r[0] for r in rows if r[0]]


def rooms_for(d, u):
    if u['role'] == 'admin':
        return known_teams(d)
    return [u['team']] if u.get('team') else []


def view(r):
    gone = bool(r['deleted_at'])
    return {'id': r['id'], 'team': r['team'], 'user_id': r['user_id'], 'user_name': r['user_name'],
            'body': '' if gone else r['body'], 'deleted': gone, 'created_at': r['created_at']}


def room(d, u, team, APIError):
    team = str(team or '').strip()
    if not team:
        raise APIError(400, 'Chọn Team / Đội để chat.')
    allowed = rooms_for(d, u)
    match = next((t for t in allowed if t.lower() == team.lower()), None)
    if match is None:
        raise APIError(403, 'Bạn không thuộc Team / Đội này.')
    return match


def dispatch(d, u, method, path, b, now, APIError):
    parts = path.strip('/').split('/')
    if parts[:2] != ['api', 'chat']:
        return None
    if len(parts) == 3 and parts[2] == 'rooms' and method == 'GET':
        out = []
        for team in rooms_for(d, u):
            last = d.execute('SELECT * FROM chat_messages WHERE team=? ORDER BY id DESC LIMIT 1', (team,)).fetchone()
            out.append({'team': team, 'last': view(last) if last else None,
                        'last_id': last['id'] if last else 0})
        return {'rooms': out}
    if len(parts) == 3 and parts[2] == 'messages':
        if method == 'GET':
            team = room(d, u, b.get('team'), APIError)
            try:
                after = max(0, int(b.get('after') or 0))
                limit = min(MAX_PAGE, max(1, int(b.get('limit') or 50)))
            except ValueError:
                raise APIError(400, 'Tham số không hợp lệ.')
            if after:
                rows = d.execute('SELECT * FROM chat_messages WHERE team=? AND id>? ORDER BY id LIMIT ?', (team, after, limit)).fetchall()
            else:
                rows = list(reversed(d.execute('SELECT * FROM chat_messages WHERE team=? ORDER BY id DESC LIMIT ?', (team, limit)).fetchall()))
            return {'team': team, 'messages': [view(r) for r in rows]}
        if method == 'POST':
            team = room(d, u, b.get('team'), APIError)
            body = b.get('body')
            if not isinstance(body, str) or not body.strip():
                raise APIError(400, 'Nhập nội dung tin nhắn.')
            body = body.strip()
            if len(body) > MAX_BODY:
                raise APIError(400, 'Tin nhắn tối đa %d ký tự.' % MAX_BODY)
            recent = d.execute('SELECT count(*) FROM chat_messages WHERE user_id=? AND created_at>?', (u['id'], now - 60)).fetchone()[0]
            if recent >= RATE_LIMIT:
                raise APIError(429, 'Bạn gửi quá nhanh. Hãy thử lại sau ít phút.')
            cur = d.execute('INSERT INTO chat_messages(team,user_id,user_name,body,created_at) VALUES(?,?,?,?,?)',
                            (team, u['id'], u.get('display_name') or u.get('username', ''), body, now))
            return view(d.execute('SELECT * FROM chat_messages WHERE id=?', (cur.lastrowid,)).fetchone())
    if len(parts) == 4 and parts[2] == 'messages' and method == 'DELETE':
        try:
            mid = int(parts[3])
        except ValueError:
            raise APIError(404, 'Không tìm thấy tin nhắn.')
        row = d.execute('SELECT * FROM chat_messages WHERE id=? AND deleted_at=0', (mid,)).fetchone()
        if not row:
            raise APIError(404, 'Không tìm thấy tin nhắn.')
        room(d, u, row['team'], APIError)
        if row['user_id'] != u['id'] and u['role'] != 'admin':
            raise APIError(403, 'Chỉ người gửi hoặc quản trị được thu hồi tin nhắn.')
        d.execute('UPDATE chat_messages SET deleted_at=? WHERE id=?', (now, mid))
        return {'ok': True}
    raise APIError(404, 'Không tìm thấy chức năng chat.')
