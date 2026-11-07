"""HP Telecom standalone Windows trial server. Python 3.10+, no packages required."""
import ipaddress
import mobile
import chat
import argparse, base64, hashlib, hmac, json, mimetypes, os, secrets, sqlite3, time, uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

MODULES = ['incident', 'cable', 'relocation']
class APIError(Exception):
    def __init__(self, status, message, code=None): self.status, self.message, self.code = status, message, code

def password_hash(password):
    if not isinstance(password,str) or not 10 <= len(password) <= 200:
        raise APIError(400,'Mật khẩu cần từ 10 đến 200 ký tự.')
    salt=secrets.token_hex(16)
    return salt+':'+hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(salt),600000).hex()

def password_ok(password, encoded):
    salt,digest=encoded.split(':')
    return hmac.compare_digest(digest,hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(salt),600000).hex())

def user_view(row):
    return {**({k:row[k] for k in ['id','username','display_name','role','enabled','created_at','team']}), **({'deleted_at':row['deleted_at'] if 'deleted_at' in row.keys() else 0,'modules':MODULES if row['role']=='dispatcher' else json.loads(row['modules'])})}

class Store:
    def __init__(self, folder):
        self.folder=Path(folder).resolve();self.folder.mkdir(parents=True,exist_ok=True)
        self.path=self.folder/'hp-telecom.sqlite3'
        with self.db() as d:
            d.executescript('''
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL COLLATE NOCASE,display_name TEXT NOT NULL,password TEXT NOT NULL,role TEXT NOT NULL,enabled INTEGER NOT NULL,modules TEXT NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL,client_type TEXT);
CREATE TABLE IF NOT EXISTS attempts(key TEXT PRIMARY KEY,count INTEGER NOT NULL,until_time INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL REFERENCES users(id),module TEXT NOT NULL,title TEXT NOT NULL,details TEXT NOT NULL,revision INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS report_owner ON reports(owner_id,module);
CREATE TABLE IF NOT EXISTS images(id TEXT PRIMARY KEY,report_id TEXT NOT NULL REFERENCES reports(id) ON DELETE CASCADE,name TEXT NOT NULL,mime TEXT NOT NULL,content BLOB NOT NULL,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id TEXT,action TEXT NOT NULL,target TEXT NOT NULL,created_at INTEGER NOT NULL);
''')
            if 'team' not in [r[1] for r in d.execute('PRAGMA table_info(users)')]:
                d.execute("ALTER TABLE users ADD COLUMN team TEXT NOT NULL DEFAULT ''")
            if 'deleted_at' not in [r[1] for r in d.execute('PRAGMA table_info(users)')]:
                d.execute('ALTER TABLE users ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0')
            if 'client_type' not in [r[1] for r in d.execute('PRAGMA table_info(sessions)')]:
                d.execute('ALTER TABLE sessions ADD COLUMN client_type TEXT')
            d.execute('CREATE TABLE IF NOT EXISTS teams(name TEXT PRIMARY KEY COLLATE NOCASE)')
            for team in ['Tây Đắk Lắk','Đông Đắk Lắk']:
                d.execute('INSERT OR IGNORE INTO teams(name) VALUES(?)',(team,))
            mobile.schema(d)
            chat.schema(d)
            for table,owner in [('reports','owner_id'),('mobile_folders','owner')]:
                if 'team' not in [r[1] for r in d.execute('PRAGMA table_info('+table+')')]:
                    d.execute("ALTER TABLE "+table+" ADD COLUMN team TEXT NOT NULL DEFAULT ''")
                    d.execute("UPDATE "+table+" SET team=COALESCE((SELECT team FROM users WHERE users.id="+table+"."+owner+"),'')")
            d.execute("UPDATE mobile_folders SET team=(SELECT t.team FROM cable_members m JOIN cable_tasks t ON t.id=m.task WHERE m.folder=mobile_folders.id) WHERE team='' AND EXISTS(SELECT 1 FROM cable_members m JOIN cable_tasks t ON t.id=m.task WHERE m.folder=mobile_folders.id AND t.team!='')")
    @contextmanager
    def db(self):
        d=sqlite3.connect(self.path,timeout=20);d.row_factory=sqlite3.Row;d.execute('PRAGMA foreign_keys=ON')
        try:
            with d: yield d
        finally:d.close()
    def audit(self,d,user,action,target):d.execute('INSERT INTO audit(user_id,action,target,created_at) VALUES(?,?,?,?)',(user,action,target,int(time.time())))
    def auth(self,d,token):
        row=d.execute('SELECT u.* FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires>? AND u.enabled=1 AND u.deleted_at=0',(hashlib.sha256(token.encode()).hexdigest(),int(time.time()))).fetchone()
        if not row:raise APIError(401,'Phiên đăng nhập đã hết hạn hoặc bị thay thế do tài khoản đăng nhập trên điện thoại khác.')
        return user_view(row)
    def admin(self,u):
        if u['role']!='admin':raise APIError(403,'Chỉ quản trị viên được thực hiện thao tác này.')
    def manage(self,u):
        if u['role'] not in ['admin','dispatcher']:raise APIError(403,'Cần quyền Quản trị hoặc Hỗ trợ.')
    def read_all(self,u):return u['role'] in ['admin','viewer','dispatcher']
    def team_visible(self,u,team):
        return u['role'] not in ['viewer','dispatcher'] or bool(u.get('team') and u['team']==team)
    def assign(self,u):
        if u['role'] not in ['admin','dispatcher']:raise APIError(403,'Bạn chưa có quyền giao việc.')
        self.module(u,'cable')
        if u['role']=='dispatcher' and not u.get('team'):raise APIError(403,'Tài khoản cần được quản trị gán Team / Đội trước khi giao việc.')
    def module(self,u,module):
        if module not in MODULES:raise APIError(400,'Loại báo cáo không hợp lệ.')
        if u['role'] not in ['admin','dispatcher'] and module not in u['modules']:raise APIError(403,'Bạn chưa được cấp quyền cho mục này.')
    def report(self,d,u,rid):
        r=d.execute('SELECT * FROM reports WHERE id=?',(rid,)).fetchone()
        if not r:raise APIError(404,'Không tìm thấy báo cáo.')
        if not self.read_all(u) and r['owner_id']!=u['id']:raise APIError(404,'Không tìm thấy báo cáo.')
        if not self.team_visible(u,r['team']):raise APIError(404,'Không tìm thấy báo cáo.')
        self.module(u,r['module']);return r
    def report_view(self,d,r):
        return {**({k:r[k] for k in ['id','owner_id','module','title','revision','created_at','updated_at']}), **({'details':json.loads(r['details']),'images':[dict(x) for x in d.execute('SELECT id,name,mime,length(content) AS size FROM images WHERE report_id=? ORDER BY created_at,id',(r['id'],))]})}
    def team_field(self,value):
        if not isinstance(value,str) or len(value.strip())>100:raise APIError(400,'Tên Team/Đội tối đa 100 ký tự.')
        return value.strip()
    def user_fields(self,b,creating=False):
        name=str(b.get('display_name','')).strip();role=b.get('role','staff');mods=b.get('modules',[])
        if not name or len(name)>100:raise APIError(400,'Tên hiển thị cần từ 1 đến 100 ký tự.')
        if role not in ['admin','staff','viewer','dispatcher'] or not isinstance(mods,list) or any(m not in MODULES for m in mods):raise APIError(400,'Quyền không hợp lệ.')
        if type(b.get('enabled',True)) is not bool:raise APIError(400,'Trạng thái không hợp lệ.')
        return name,role,int(b.get('enabled',True)),json.dumps(sorted(set(mods)))
    def dispatch(self,method,path,b,token,ip,media_ticket=''):
        parts=path.strip('/').split('/');now=int(time.time())
        with self.db() as d:
            if path=='/api/status' and method=='GET':return {'needs_setup':d.execute('SELECT count(*) FROM users').fetchone()[0]==0,'name':'HP Telecom Server','version':'4.0.15','capabilities':['idempotent-mobile-upload','cable-assignments','cable-route-reopen','cable-assignment-delta','shared-report-photos','single-mobile-session','shared-photo-library','video-library','photo-library-trash','team-chat'],'server_id':d.execute('SELECT value FROM server_meta WHERE id=1').fetchone()[0]}
            if path=='/api/setup' and method=='POST':
                if ip not in ['127.0.0.1','::1']:raise APIError(403,'Tạo quản trị đầu tiên trực tiếp trên máy server.')
                hashed=password_hash(b.get('password',''));name,_,_,_=self.user_fields(b)
                username=self.username(b.get('username',''))
                d.execute('BEGIN IMMEDIATE')
                if d.execute('SELECT count(*) FROM users').fetchone()[0]:raise APIError(409,'Server đã được thiết lập.')
                uid=uuid.uuid4().hex;d.execute('INSERT INTO users(id,username,display_name,password,role,enabled,modules,created_at) VALUES(?,?,?,?,?,?,?,?)',(uid,username,name,hashed,'admin',1,json.dumps(MODULES),now));self.audit(d,uid,'setup',uid)
                return {'ok':True}
            if path=='/api/login' and method=='POST':
                username=str(b.get('username','')).strip().lower();password=b.get('password','')
                if not isinstance(password,str) or len(password)>200:raise APIError(400,'Thông tin đăng nhập không hợp lệ.')
                key=ip+'|'+username;a=d.execute('SELECT * FROM attempts WHERE key=?',(key,)).fetchone()
                if a and a['until_time']>now and a['count']>=5:raise APIError(429,'Đăng nhập sai nhiều lần. Hãy thử lại sau 10 phút.')
                row=d.execute('SELECT * FROM users WHERE username=?',(username,)).fetchone()
                if not row or not password_ok(password,row['password']) or not row['enabled'] or row['deleted_at']:
                    count=a['count']+1 if a and a['until_time']>now else 1
                    d.execute('INSERT OR REPLACE INTO attempts VALUES(?,?,?)',(key,count,now+600));d.commit()
                    raise APIError(401,'Tên đăng nhập hoặc mật khẩu không đúng, hoặc tài khoản bị khóa.')
                d.execute('DELETE FROM attempts WHERE key=?',(key,));d.execute('DELETE FROM sessions WHERE expires<=?',(now,))
                client_type=b.get('client_type')
                if client_type not in ('mobile','web'):client_type='legacy'
                # One field-device session per user. Older app builds have no
                # client_type marker, so a new or legacy phone login also clears
                # those stale sessions. Browser sessions are kept separate.
                if client_type in ('mobile','legacy'):
                    d.execute("DELETE FROM sessions WHERE user_id=? AND (client_type IN ('mobile','legacy') OR client_type IS NULL)",(row['id'],))
                value=secrets.token_urlsafe(40);d.execute('INSERT INTO sessions(token_hash,user_id,expires,client_type) VALUES(?,?,?,?)',(hashlib.sha256(value.encode()).hexdigest(),row['id'],now+43200,client_type));self.audit(d,row['id'],'login',row['id'])
                return {'token':value,'user':user_view(row),'expires_at':now+43200}
            session_hash=hashlib.sha256(token.encode()).hexdigest()
            if media_ticket and not token and method=='GET' and len(parts)==4 and parts[:3]==['api','mobile','videos']:
                u,session_hash=mobile.video_library.ticket_user(d,media_ticket,parts[3],now,APIError,user_view)
            else:u=self.auth(d,token)
            media_read_ticket=method=='POST' and len(parts)==5 and parts[:3]==['api','mobile','videos'] and parts[4]=='stream'
            chat_send=method=='POST' and path=='/api/chat/messages'
            if u['role']=='viewer' and method!='GET' and path not in ['/api/logout','/api/password'] and not media_read_ticket and not chat_send:
                assigning=u['role']=='dispatcher' and method in ['POST','PUT'] and (path=='/api/mobile/cable-tasks' or (len(parts)==4 and parts[:3]==['api','mobile','cable-tasks']))
                if not assigning:raise APIError(403,'Vai trò này không được sửa hoặc xóa dữ liệu báo cáo/tài khoản.')
            if path=='/api/assignees' and method=='GET':
                self.assign(u)
                return {'users':[{**({k:r[k] for k in ['id','username','display_name','team','role','enabled']}), **({'modules':json.loads(r['modules'])})} for r in d.execute("SELECT * FROM users WHERE enabled=1 AND deleted_at=0 AND role IN ('admin','staff','dispatcher') ORDER BY display_name") if self.team_visible(u,r['team']) and (r['role'] in ('admin','dispatcher') or 'cable' in json.loads(r['modules']))]}

            if path.startswith('/api/chat/'):
                return chat.dispatch(d,u,method,path,b,now,APIError)
            if path.startswith('/api/mobile/'):
                return mobile.dispatch(self,d,u,method,path,b,now,APIError,session_hash)
            if path=='/api/me' and method=='GET':return u
            if path=='/api/logout' and method=='POST':
                d.execute('DELETE FROM sessions WHERE token_hash=?',(hashlib.sha256(token.encode()).hexdigest(),));return {'ok':True}
            if path=='/api/password' and method=='POST':
                row=d.execute('SELECT password FROM users WHERE id=?',(u['id'],)).fetchone()
                old=b.get('old_password','')
                if not isinstance(old,str) or len(old)>200 or not password_ok(old,row['password']):raise APIError(400,'Mật khẩu hiện tại không đúng.')
                hashed=password_hash(b.get('password',''));d.execute('UPDATE users SET password=? WHERE id=?',(hashed,u['id']));d.execute('DELETE FROM sessions WHERE user_id=?',(u['id'],));self.audit(d,u['id'],'password-change',u['id']);return {'ok':True}
            if path=='/api/teams':
                if method=='GET':
                    rows=d.execute("SELECT name FROM teams UNION SELECT team FROM users UNION SELECT team FROM mobile_folders UNION SELECT team FROM cable_tasks UNION SELECT team FROM reports ORDER BY 1")
                    return {'teams':[r[0] for r in rows if r[0] and (u['role']=='admin' or r[0]==u['team'])]}
                if method=='POST':
                    self.admin(u);name=self.team_field(b.get('name',''))
                    if not name:raise APIError(400,'Nhập tên Team / Đội.')
                    try:d.execute('INSERT INTO teams(name) VALUES(?)',(name,))
                    except sqlite3.IntegrityError:raise APIError(409,'Team / Đội đã tồn tại.')
                    self.audit(d,u['id'],'team-create',name);return {'name':name}
            if path=='/api/users/deleted' and method=='GET':
                self.admin(u)
                return {'users':[user_view(r) for r in d.execute('SELECT * FROM users WHERE deleted_at>0 ORDER BY deleted_at DESC,username')]}
            if len(parts)==4 and parts[:2]==['api','users'] and parts[3]=='restore' and method=='POST':
                self.admin(u);uid=parts[2];d.execute('BEGIN IMMEDIATE')
                if not d.execute('SELECT 1 FROM users WHERE id=? AND deleted_at>0',(uid,)).fetchone():raise APIError(404,'Không tìm thấy tài khoản đã xóa.')
                d.execute('UPDATE users SET deleted_at=0,enabled=0 WHERE id=?',(uid,))
                self.audit(d,u['id'],'user-restore',uid);return {'ok':True}
            if len(parts)==3 and parts[:2]==['api','users'] and method=='DELETE':
                self.admin(u);uid=parts[2];d.execute('BEGIN IMMEDIATE')
                old=d.execute('SELECT * FROM users WHERE id=? AND deleted_at=0',(uid,)).fetchone()
                if not old:raise APIError(404,'Không tìm thấy tài khoản.')
                if uid==u['id']:raise APIError(400,'Không thể xóa tài khoản đang đăng nhập.')
                if old['role']=='admin' and old['enabled'] and d.execute("SELECT count(*) FROM users WHERE role='admin' AND enabled=1 AND deleted_at=0").fetchone()[0]<=1:raise APIError(400,'Phải giữ ít nhất một quản trị viên hoạt động.')
                d.execute('UPDATE users SET enabled=0,deleted_at=? WHERE id=?',(now,uid))
                d.execute('DELETE FROM sessions WHERE user_id=?',(uid,))
                d.execute('UPDATE cable_members SET active=0 WHERE user=?',(uid,))
                self.audit(d,u['id'],'user-delete',uid);return {'ok':True}
            if path=='/api/users':
                self.admin(u)
                if method=='GET':return {'users':[user_view(x) for x in d.execute('SELECT * FROM users WHERE deleted_at=0 ORDER BY created_at,username') if self.team_visible(u,x['team'])]}
                if method=='POST':
                    self.admin(u)
                    name,role,enabled,mods=self.user_fields(b);uid=uuid.uuid4().hex;username=self.username(b.get('username',''));hashed=password_hash(b.get('password',''))
                    try:d.execute('INSERT INTO users(id,username,display_name,password,role,enabled,modules,created_at) VALUES(?,?,?,?,?,?,?,?)',(uid,username,name,hashed,role,enabled,mods,now))
                    except sqlite3.IntegrityError:raise APIError(409,'Tên đăng nhập đã được sử dụng.')
                    d.execute('UPDATE users SET team=? WHERE id=?',(self.team_field(b.get('team','')),uid))
                    self.audit(d,u['id'],'user-create',uid);return {'id':uid}
            if len(parts)==3 and parts[:2]==['api','users'] and method=='PATCH':
                self.manage(u);uid=parts[2];name,role,enabled,mods=self.user_fields(b);d.execute('BEGIN IMMEDIATE');old=d.execute('SELECT * FROM users WHERE id=?',(uid,)).fetchone()
                if not old or old['deleted_at']:raise APIError(404,'Không tìm thấy người dùng.')
                if u['role']=='dispatcher' and (not self.team_visible(u,old['team']) or old['role']=='admin' or role=='admin' or self.team_field(b.get('team',old['team']))!=u['team']):raise APIError(403,'Hỗ trợ chỉ sửa tài khoản trong đội; không thay đổi hoặc cấp quyền quản trị.')
                if old['role']=='admin' and old['enabled'] and (role!='admin' or not enabled) and d.execute("SELECT count(*) FROM users WHERE role='admin' AND enabled=1").fetchone()[0]<=1:raise APIError(400,'Phải giữ ít nhất một quản trị viên hoạt động.')
                d.execute('UPDATE users SET display_name=?,role=?,enabled=?,modules=? WHERE id=?',(name,role,enabled,mods,uid))
                d.execute('UPDATE users SET team=? WHERE id=?',(self.team_field(b.get('team',old['team'])),uid))
                for table,owner in [('reports','owner_id'),('mobile_folders','owner')]:
                    d.execute("UPDATE "+table+" SET team=? WHERE "+owner+"=? AND team=''",(self.team_field(b.get('team',old['team'])),uid))
                if b.get('password'):d.execute('UPDATE users SET password=? WHERE id=?',(password_hash(b['password']),uid))
                d.execute('DELETE FROM sessions WHERE user_id=?',(uid,));self.audit(d,u['id'],'user-update',uid);return {'ok':True}
            if path=='/api/reports':
                if method=='GET':
                    if self.read_all(u):rows=d.execute('SELECT r.*,u.display_name AS owner FROM reports r JOIN users u ON r.owner_id=u.id ORDER BY r.updated_at DESC LIMIT 500')
                    else:rows=d.execute('SELECT r.*,u.display_name AS owner FROM reports r JOIN users u ON r.owner_id=u.id WHERE r.owner_id=? ORDER BY r.updated_at DESC LIMIT 500',(u['id'],))
                    return {'reports':[{k:r[k] for k in ['id','module','title','owner','revision','updated_at']} for r in rows if self.team_visible(u,r['team']) and (u['role']=='admin' or r['module'] in u['modules'])]}
                if method=='POST':
                    module=b.get('module');self.module(u,module);title,details=self.details(b);rid=uuid.uuid4().hex
                    d.execute('INSERT INTO reports(id,owner_id,module,title,details,revision,created_at,updated_at,team) VALUES(?,?,?,?,?,?,?,?,?)',(rid,u['id'],module,title,details,1,now,now,u['team']));self.audit(d,u['id'],'report-create',rid);return {'id':rid}
            if len(parts)==3 and parts[:2]==['api','reports']:
                rid=parts[2];r=self.report(d,u,rid)
                if method=='GET':return self.report_view(d,r)
                if method=='DELETE':self.manage(u)
                if method in ['PUT','DELETE']:
                    if b.get('revision')!=r['revision']:raise APIError(409,'Báo cáo đã thay đổi. Hãy tải lại trước khi sửa/xóa.')
                    if method=='PUT':
                        title,details=self.details(b)
                        cur=d.execute('UPDATE reports SET title=?,details=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?',(title,details,now,rid,b['revision']))
                    else:cur=d.execute('DELETE FROM reports WHERE id=? AND revision=?',(rid,b['revision']))
                    if cur.rowcount!=1:raise APIError(409,'Báo cáo đã thay đổi. Hãy tải lại.')
                    self.audit(d,u['id'],'report-'+method.lower(),rid);return {'ok':True}
            if len(parts)==4 and parts[:2]==['api','reports'] and parts[3]=='images' and method=='POST':
                rid=parts[2];self.report(d,u,rid)
                try:raw=base64.b64decode(b.get('base64',''),validate=True)
                except Exception:raise APIError(400,'Dữ liệu ảnh không hợp lệ.')
                if not 1<=len(raw)<=10*1024*1024:raise APIError(400,'Mỗi ảnh tối đa 10 MB.')
                mime='image/jpeg' if raw.startswith(b'\xff\xd8\xff') else 'image/png' if raw.startswith(b'\x89PNG\r\n\x1a\n') else None
                if not mime:raise APIError(400,'Chỉ nhận ảnh JPG hoặc PNG.')
                iid=uuid.uuid4().hex;name=str(b.get('name','Ảnh'))[:150]
                d.execute('INSERT INTO images VALUES(?,?,?,?,?,?)',(iid,rid,name,mime,raw,now));d.execute('UPDATE reports SET revision=revision+1,updated_at=? WHERE id=?',(now,rid));self.audit(d,u['id'],'image-upload',iid);return {'id':iid}
            if len(parts)==3 and parts[:2]==['api','images']:
                image=d.execute('SELECT * FROM images WHERE id=?',(parts[2],)).fetchone()
                if not image:raise APIError(404,'Không tìm thấy ảnh.')
                self.report(d,u,image['report_id'])
                if method=='GET':return (image['content'],image['mime'])
                if method=='DELETE':
                    d.execute('DELETE FROM images WHERE id=?',(image['id'],));d.execute('UPDATE reports SET revision=revision+1,updated_at=? WHERE id=?',(now,image['report_id']));self.audit(d,u['id'],'image-delete',image['id']);return {'ok':True}
            if path=='/api/audit' and method=='GET':
                self.manage(u);return {'events':[dict(x) for x in d.execute('SELECT a.*,u.username,u.team FROM audit a LEFT JOIN users u ON a.user_id=u.id ORDER BY a.id DESC LIMIT 100') if self.team_visible(u,x['team'])]}
            raise APIError(404,'Không tìm thấy chức năng.')
    def username(self,value):
        value=str(value).strip().lower()
        if not 3<=len(value)<=60 or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789._-' for c in value):raise APIError(400,'Tên đăng nhập 3-60 ký tự: chữ không dấu, số, dấu chấm, gạch dưới hoặc gạch ngang.')
        return value
    def details(self,b):
        title=str(b.get('title','')).strip();details=b.get('details',{})
        if not title or len(title)>160:raise APIError(400,'Tên báo cáo cần từ 1 đến 160 ký tự.')
        if not isinstance(details,dict) or any(not isinstance(k,str) or not isinstance(v,str) for k,v in details.items()):raise APIError(400,'Thông tin báo cáo không hợp lệ.')
        data=json.dumps(details,ensure_ascii=False)
        if len(data)>20000:raise APIError(400,'Thông tin báo cáo quá dài.')
        return title,data

class Handler(BaseHTTPRequestHandler):
    server_version='HP-Telecom-Trial/0.1'
    def log_message(self,fmt,*args):pass
    def send(self,status,payload,mime='application/json; charset=utf-8'):
        raw=json.dumps(payload,ensure_ascii=False).encode() if not isinstance(payload,bytes) else payload
        self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('X-Frame-Options','DENY');self.send_header('Referrer-Policy','no-referrer');self.send_header('Content-Security-Policy',"default-src 'self'; img-src 'self' blob:; script-src 'self'; style-src 'self'; frame-ancestors 'none'");self.end_headers();self.wfile.write(raw)
    def request(self):
        try:
            host=urlparse('http://'+self.headers.get('Host','')).hostname
            try: local_host=host=='localhost' or ipaddress.ip_address(host).is_private
            except ValueError:local_host=False
            public_host=host in getattr(self.server,'public_hosts',set())
            if not public_host and (not local_host or (self.server.server_address[0]=='127.0.0.1' and host not in ['localhost','127.0.0.1'])):
                raise APIError(403,'Địa chỉ truy cập không được phép.')
            path=urlparse(self.path).path
            if not path.startswith('/api/'):
                if self.command!='GET':raise APIError(405,'Không hỗ trợ thao tác.')
                names={'/':'landing.html','/quantri':'index.html','/quantri/':'index.html','/dangnhap':'index.html','/nguoidung':'index.html','/tonghop':'index.html','/tonghop/suco':'index.html','/tonghop/didoi':'index.html','/baocao':'index.html','/baocao/suco':'index.html','/baocao/tuantuyen':'index.html','/baocao/didoi':'index.html','/thuvien':'index.html','/thuvien/':'index.html','/nhatky':'index.html','/app.js':'app.js','/export-bundle.js':'export-bundle.js','/style.css':'style.css','/logo.png':'logo.png','/icon.png':'icon.png','/landing.css':'landing.css','/brand-mark.png':'brand-mark.png','/brand-mark-light.png':'brand-mark-light.png','/homepage-preview.jpg':'homepage-preview.jpg','/privacy':'privacy.html','/support':'support.html','/policy.css':'policy.css'}
                if path not in names:raise APIError(404,'Không tìm thấy trang.')
                f=Path(__file__).parent/'static'/names[path];return self.send(200,f.read_bytes(),mimetypes.guess_type(f.name)[0]+'; charset=utf-8')
            if self.command not in ['GET','POST','PUT','PATCH','DELETE']:raise APIError(405,'Không hỗ trợ thao tác.')
            origin=self.headers.get('Origin')
            if origin and origin not in ['http://'+self.headers.get('Host',''),'https://'+self.headers.get('Host','')]:raise APIError(403,'Nguồn yêu cầu không được phép.')
            b={}
            if self.command=='GET' and path.startswith('/api/chat/'):b={k:v[0] for k,v in parse_qs(urlparse(self.path).query).items()}
            if self.command!='GET':
                if self.headers.get('Content-Type','').split(';')[0]!='application/json':raise APIError(415,'Yêu cầu phải là JSON.')
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=15*1024*1024:raise APIError(413,'Yêu cầu quá lớn hoặc không có dữ liệu.')
                self.connection.settimeout(30)
                try:b=json.loads(self.rfile.read(length))
                except Exception:raise APIError(400,'Nội dung JSON không hợp lệ.')
                if not isinstance(b,dict):raise APIError(400,'Nội dung JSON không hợp lệ.')
            auth=self.headers.get('Authorization','');token=auth[7:] if auth.startswith('Bearer ') else ''
            ticket=parse_qs(urlparse(self.path).query).get('ticket',[''])[0]
            result=self.server.store.dispatch(self.command,path,b,token,self.client_address[0],ticket)
            if isinstance(result,mobile.video_library.VideoFile):mobile.video_library.send_file(self,result)
            elif isinstance(result,tuple):self.send(200,*result)
            else:self.send(200,result)
        except APIError as e:self.send(e.status,dict({'error':e.message},**({'code':e.code} if e.code else {})))
        except (ValueError,TypeError):self.send(400,{'error':'Thông tin gửi lên không hợp lệ.'})
        except (ConnectionError,TimeoutError):pass
        except Exception:self.send(500,{'error':'Server chưa xử lý được yêu cầu. Hãy thử lại.'})
    do_GET=request;do_POST=request;do_PUT=request;do_PATCH=request;do_DELETE=request;do_OPTIONS=request

def make_server(host,port,folder):
    import public_hosts
    allowed=public_hosts.load(Path(folder)/'public-hosts.json')
    server=ThreadingHTTPServer((host,port),Handler);server.daemon_threads=True;server.store=Store(folder);server.public_hosts=allowed;return server
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--host',default='127.0.0.1');parser.add_argument('--port',type=int,default=8787);parser.add_argument('--data',default=str(Path(__file__).parent/'data'));parser.add_argument('--backup')
    args=parser.parse_args()
    if args.backup:
        store=Store(args.data);dest=Path(args.backup).resolve();dest.parent.mkdir(parents=True,exist_ok=True)
        if dest.exists():raise SystemExit('Backup destination already exists. Choose another filename.')
        with store.db() as source:
            target=sqlite3.connect(dest);source.backup(target);target.close()
        print('Backup saved.');raise SystemExit(0)
    server=make_server(args.host,args.port,args.data)
    photo_stop=mobile.photo_jobs.start(server.store)
    print(f'HP Telecom: http://127.0.0.1:{server.server_port}  (Ctrl+C to stop)',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:photo_stop.set();server.server_close()
