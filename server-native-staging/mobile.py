"""Versioned mobile folders and account-owned media. No trust in client owner IDs."""
import math
import route_catalog
import photo_ocr
import photo_jobs
import cable_tasks
import report_collaboration
import video_library
from datetime import datetime,timezone,timedelta
import base64,hashlib,json,re,uuid

def schema(d):
 d.executescript('''
 CREATE TABLE IF NOT EXISTS incident_summary(fid TEXT PRIMARY KEY REFERENCES mobile_folders(id) ON DELETE CASCADE,revision INTEGER NOT NULL,cells TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS mobile_receipts(owner TEXT NOT NULL,fid TEXT NOT NULL,operation TEXT NOT NULL,digest TEXT NOT NULL,revision INTEGER NOT NULL,PRIMARY KEY(owner,fid,operation));
 CREATE TABLE IF NOT EXISTS mobile_deleted(id TEXT PRIMARY KEY,owner TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS server_meta(id INTEGER PRIMARY KEY,value TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS mobile_assets(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),module TEXT NOT NULL,digest TEXT NOT NULL,mime TEXT NOT NULL,content BLOB NOT NULL,created INTEGER NOT NULL,UNIQUE(owner,module,digest));
 CREATE TABLE IF NOT EXISTS photo_library(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),name TEXT NOT NULL,mime TEXT NOT NULL,digest TEXT NOT NULL,content BLOB NOT NULL,details TEXT NOT NULL DEFAULT '{}',captured_at INTEGER NOT NULL,created_at INTEGER NOT NULL,UNIQUE(owner,digest));
 CREATE INDEX IF NOT EXISTS photo_library_owner ON photo_library(owner,created_at DESC);
 CREATE TABLE IF NOT EXISTS mobile_folders(id TEXT PRIMARY KEY,owner TEXT NOT NULL REFERENCES users(id),module TEXT NOT NULL,name TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,updated INTEGER NOT NULL);
 ''')
 photo_jobs.schema(d)
 cable_tasks.schema(d)
 report_collaboration.schema(d)
 video_library.schema(d)
 d.execute('INSERT OR IGNORE INTO server_meta VALUES(1,?)',(uuid.uuid4().hex,))
 if 'details' not in [row[1] for row in d.execute('PRAGMA table_info(photo_library)')]:d.execute("ALTER TABLE photo_library ADD COLUMN details TEXT NOT NULL DEFAULT '{}'")
 if 'deleted_at' not in [row[1] for row in d.execute('PRAGMA table_info(photo_library)')]:d.execute('ALTER TABLE photo_library ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0')
 d.execute('CREATE INDEX IF NOT EXISTS photo_library_trash ON photo_library(owner,deleted_at,created_at DESC)')

def refs(value):
 found=set()
 def walk(v):
  if isinstance(v,dict):
   for k,x in v.items():
    if k in ('file','originalFile') and x:
     if not isinstance(x,str) or not re.fullmatch('[a-f0-9]{32}',x):raise ValueError('Mã ảnh không hợp lệ.')
     found.add(x)
    else:walk(x)
  elif isinstance(v,list):
   for x in v:walk(x)
 walk(value);return found

def cleanup(d,owner,module,removed):
 used=set()
 for r in d.execute('SELECT payload FROM mobile_folders WHERE owner=? AND module=?',(owner,module)):
  used.update(refs(json.loads(r['payload'])))
 used.update(report_collaboration.pending_assets(d,owner,module))
 for aid in removed-used:d.execute('DELETE FROM mobile_assets WHERE id=? AND owner=? AND module=?',(aid,owner,module))

def transfer_asset_refs(d,fid,owner,source_module,target_module,payload,Error):
 """Copy referenced media into the new module while leaving other reports intact."""
 old_assets=refs(payload)
 if source_module==target_module:return old_assets
 if d.execute('SELECT 1 FROM report_asset_bindings b JOIN mobile_assets a ON a.id=b.asset WHERE b.fid=? AND b.consumed=0 AND a.module=? LIMIT 1',(fid,source_module)).fetchone():
  raise Error(409,'Có ảnh Người cùng làm đang chờ lưu. Hãy đồng bộ xong rồi chuyển báo cáo.')
 mapping={}
 for aid in old_assets:
  asset=d.execute('SELECT * FROM mobile_assets WHERE id=? AND owner=?',(aid,owner)).fetchone()
  if not asset or asset['module']!=source_module:continue
  target=d.execute('SELECT id FROM mobile_assets WHERE owner=? AND module=? AND digest=?',(owner,target_module,asset['digest'])).fetchone()
  else_use=bool(d.execute('SELECT 1 FROM report_asset_bindings WHERE fid<>? AND asset=? LIMIT 1',(fid,aid)).fetchone())
  if not else_use:
   else_use=any(aid in refs(json.loads(folder['payload'])) for folder in d.execute('SELECT payload FROM mobile_folders WHERE owner=? AND id<>?',(owner,fid)))
  if target:
   mapping[aid]=target['id']
  elif not else_use:
   d.execute('UPDATE mobile_assets SET module=? WHERE id=? AND owner=? AND module=?',(target_module,aid,owner,source_module))
  else:
   d.execute('INSERT OR IGNORE INTO mobile_assets VALUES(?,?,?,?,?,?,?)',
             (uuid.uuid4().hex,owner,target_module,asset['digest'],asset['mime'],asset['content'],asset['created']))
   target=d.execute('SELECT id FROM mobile_assets WHERE owner=? AND module=? AND digest=?',(owner,target_module,asset['digest'])).fetchone()
   mapping[aid]=target['id']
 if mapping:
  def remap(value):
   if isinstance(value,dict):
    for key,child in list(value.items()):
     if key in ('file','originalFile') and isinstance(child,str):value[key]=mapping.get(child,child)
     else:remap(child)
   elif isinstance(value,list):
    for child in value:remap(child)
  remap(payload)
  for old,new in mapping.items():
   bindings=d.execute('SELECT user,created,consumed FROM report_asset_bindings WHERE fid=? AND asset=?',(fid,old)).fetchall()
   d.execute('DELETE FROM report_asset_bindings WHERE fid=? AND asset=?',(fid,old))
   d.executemany('INSERT OR IGNORE INTO report_asset_bindings(fid,user,asset,created,consumed) VALUES(?,?,?,?,?)',
                 ((fid,row['user'],new,row['created'],row['consumed']) for row in bindings))
 return old_assets

def report_time(payload):
 report=payload.get('report') or {}
 def parse(value):
  if not isinstance(value,str) or not value.strip():return None
  value=' '.join(value.strip().split())
  for fmt in ['%d/%m/%Y %H:%M:%S','%d/%m/%Y %H:%M','%d/%m/%Y %I:%M:%S %p','%d/%m/%Y %I:%M %p','%d/%m/%Y']:
   try:return datetime.strptime(value,fmt)
   except ValueError:pass
  try:
   parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
   return parsed.astimezone(timezone(timedelta(hours=7))).replace(tzinfo=None) if parsed.tzinfo else parsed
  except ValueError:return None
 if payload.get('module')=='relocation':
  performed=parse(report.get('date'))
  return (performed,'Ngày thực hiện: '+str(report['date'])) if performed else (datetime.min,'Chưa có ngày thực hiện')
 received=parse(report.get('received'))
 if received:return received,'Nhận: '+str(report['received'])
 date=parse(report.get('date'))
 if date:return date,('Ngày thực hiện: ' if payload.get('module')=='relocation' else 'Ngày báo cáo: ')+str(report['date'])
 return datetime.min,'Chưa có thời gian nhận'

def dispatch(store,d,u,method,path,b,now,Error,session_hash=''):
 parts=path.strip('/').split('/');uid=u['id']
 if path=='/api/mobile/videos' or path.startswith('/api/mobile/videos/'):
  return video_library.dispatch(store,d,u,method,path,b,now,Error,session_hash)
 if path=='/api/mobile/library' and method=='POST':
  if u['role']=='viewer':raise Error(403,'Tài khoản chỉ có quyền xem và tải ảnh.')
  try:raw=base64.b64decode(b.get('base64',''),validate=True)
  except Exception:raise Error(400,'Dữ liệu ảnh thư viện không hợp lệ.')
  if not 1<=len(raw)<=10*1024*1024:raise Error(400,'Mỗi ảnh thư viện tối đa 10 MB.')
  mime='image/jpeg' if raw.startswith(b'\xff\xd8\xff') else 'image/png' if raw.startswith(b'\x89PNG\r\n\x1a\n') else None
  if not mime:raise Error(400,'Thư viện chỉ nhận ảnh JPG hoặc PNG.')
  name=str(b.get('name','Ảnh HP Telecom')).strip()[:150] or 'Ảnh HP Telecom'
  details=b.get('details',{})
  if not isinstance(details,dict) or len(json.dumps(details,ensure_ascii=False))>4000:raise Error(400,'Thông tin ảnh trong thư viện không hợp lệ.')
  captured=b.get('captured_at',now*1000)
  if isinstance(captured,bool) or not isinstance(captured,(int,float)) or not 0<=captured<=4102444800000:captured=now*1000
  digest=hashlib.sha256(raw).hexdigest();photo_id=uuid.uuid4().hex
  d.execute('INSERT OR IGNORE INTO photo_library(id,owner,name,mime,digest,content,details,captured_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)',(photo_id,uid,name,mime,digest,raw,json.dumps(details,ensure_ascii=False),int(captured),now))
  photo=d.execute('SELECT id,deleted_at FROM photo_library WHERE owner=? AND digest=?',(uid,digest)).fetchone()
  if photo['deleted_at']:raise Error(409,'Ảnh này đang ở Thùng rác trên server. Hãy khôi phục ảnh trong Thư viện nếu muốn dùng lại.','PHOTO_IN_TRASH')
  store.audit(d,uid,'photo-library-upload',photo['id'])
  return {'id':photo['id'],'name':name,'photographer':u['display_name'],'captured_at':int(captured)}
 if len(parts)==5 and parts[:3]==['api','mobile','library'] and parts[4] in ('trash','restore') and method=='POST':
  if u['role']=='viewer':raise Error(403,'Tài khoản chỉ có quyền xem và tải ảnh.')
  d.execute('BEGIN IMMEDIATE')
  photo=d.execute('SELECT id,owner,deleted_at FROM photo_library WHERE id=?',(parts[3],)).fetchone()
  if not photo or (photo['owner']!=uid and u['role']!='admin'):raise Error(404,'Không tìm thấy ảnh có thể quản lý trong thư viện.')
  deleting=parts[4]=='trash';deleted_at=photo['deleted_at'] if deleting and photo['deleted_at'] else now if deleting else 0
  if bool(photo['deleted_at'])!=deleting:
   d.execute('UPDATE photo_library SET deleted_at=? WHERE id=?',(deleted_at,photo['id']))
   store.audit(d,uid,'photo-library-'+parts[4],photo['id'])
  return {'id':photo['id'],'trashed':deleting,'deleted_at':deleted_at}
 if path in ('/api/mobile/library','/api/mobile/library/trash') and method=='GET':
  trash=path.endswith('/trash')
  query='SELECT p.id,p.name,p.mime,length(p.content) AS size,p.details,p.captured_at,p.created_at,p.owner,p.deleted_at,u.username,u.display_name AS photographer,u.team FROM photo_library p JOIN users u ON u.id=p.owner WHERE p.deleted_at'+('>0' if trash else '=0')
  args=()
  if u['role']=='dispatcher' and not trash:
   query+=" AND (p.owner=? OR (u.team=? AND ?<>''))";args=(uid,u.get('team') or '',u.get('team') or '')
  elif u['role']!='admin':query+=' AND p.owner=?';args=(uid,)
  items=[dict(row) for row in d.execute(query+' ORDER BY p.created_at DESC,p.id DESC LIMIT 500',args)]
  return {'photos':[dict({'id':p['id'],'name':p['name'],'mime':p['mime'],'size':p['size'],'captured_at':p['captured_at'],'created_at':p['created_at'],'owner':p['owner'],'username':p['username'],'photographer':p['photographer'],'team':p['team'],'details':json.loads(p['details'])},**({'deleted_at':p['deleted_at']} if trash else {})) for p in items]}
 if len(parts)==4 and parts[:3]==['api','mobile','library'] and method=='GET':
  photo=d.execute('SELECT p.*,u.team FROM photo_library p JOIN users u ON u.id=p.owner WHERE p.id=?',(parts[3],)).fetchone()
  can_view_all=u['role'] in ('admin','dispatcher')
  if not photo or photo['deleted_at'] or (photo['owner']!=uid and (not can_view_all or not store.team_visible(u,photo['team']))):raise Error(404,'Không tìm thấy ảnh trong thư viện.')
  return photo['content'],photo['mime']
 if path=='/api/mobile/photo-ocr' and method=='POST':return photo_ocr.dispatch(store,d,u,b,Error)
 if path=='/api/mobile/route-catalog':return route_catalog.dispatch(store,d,u,method,b,now,Error)
 if path.startswith('/api/mobile/cable-tasks'):return cable_tasks.dispatch(store,d,u,method,path,b,now,Error)
 if len(parts)==5 and parts[:3]==['api','mobile','folders'] and parts[4] in ['collaborators','contributions']:
  return report_collaboration.dispatch(store,d,u,method,parts[3],parts[4],b,now,Error)
 if path=='/api/mobile/assets' and method=='POST':
  module=b.get('module');store.module(u,module)
  owner_folder=None
  if b.get('folderId'):
   d.execute('BEGIN IMMEDIATE')
   owner_folder=report_collaboration.folder(d,b['folderId'],Error)
   if owner_folder['module']!=module:
    if {owner_folder['module'],module}!={'incident','relocation'}:raise Error(400,'Thư mục ảnh không hợp lệ.')
    if owner_folder['owner']!=uid and u['role']!='admin':raise Error(403,'Chỉ người lập hoặc quản trị được chuyển báo cáo.')
    store.module(u,owner_folder['module'])
    store.module(u,module)
   else:report_collaboration.allow_upload(store,d,u,owner_folder,Error)
   uid=owner_folder['owner']
  try:raw=base64.b64decode(b.get('base64',''),validate=True)
  except Exception:raise Error(400,'Ảnh không hợp lệ.')
  if not 1<=len(raw)<=10*1024*1024:raise Error(400,'Ảnh tối đa 10 MB. Hãy giảm dung lượng trước khi gửi.')
  mime='image/jpeg' if raw.startswith(b'\xff\xd8\xff') else 'image/png' if raw.startswith(b'\x89PNG\r\n\x1a\n') else None
  if not mime:raise Error(400,'Chỉ nhận JPG/PNG.')
  digest=hashlib.sha256(raw).hexdigest();aid=uuid.uuid4().hex
  d.execute('INSERT OR IGNORE INTO mobile_assets VALUES(?,?,?,?,?,?,?)',(aid,uid,module,digest,mime,raw,now))
  row=d.execute('SELECT id FROM mobile_assets WHERE owner=? AND module=? AND digest=?',(uid,module,digest)).fetchone()
  if owner_folder:report_collaboration.bind_asset(d,owner_folder,u['id'],row['id'],now)
  return {'id':row['id']}
 if len(parts)==4 and parts[:3]==['api','mobile','assets'] and method=='GET':
  row=d.execute('SELECT * FROM mobile_assets WHERE id=?',(parts[3],)).fetchone()
  shared=bool(row and report_collaboration.can_read_asset(d,u,row,refs))
  if not row or (row['owner']!=uid and not store.read_all(u) and not shared):raise Error(404,'Không tìm thấy ảnh.')
  store.module(u,row['module'])
  if u['role'] in ['viewer','dispatcher'] and not shared:
   visible=bool(u.get('team')) and any(row['id'] in refs(json.loads(f['payload'])) for f in d.execute('SELECT payload FROM mobile_folders WHERE owner=? AND module=? AND team=?',(row['owner'],row['module'],u['team'])))
   if not visible:raise Error(404,'Không tìm thấy ảnh.')
  return row['content'],row['mime']
 if path in ['/api/mobile/folders','/api/mobile/admin'] and method=='GET':
  if path.endswith('/admin') and not store.read_all(u):store.admin(u)
  if store.read_all(u):rows=d.execute('SELECT f.*,u.display_name AS author FROM mobile_folders f JOIN users u ON u.id=f.owner ORDER BY updated DESC')
  else:rows=d.execute('SELECT f.*,u.display_name AS author FROM mobile_folders f JOIN users u ON u.id=f.owner WHERE owner=? OR EXISTS(SELECT 1 FROM report_collaborators c WHERE c.fid=f.id AND c.user=?) ORDER BY updated DESC',(uid,uid))
  folders=[]
  for r in rows:
   shared=report_collaboration.is_member(d,u,r)
   if r['owner']!=uid and not store.read_all(u) and not shared:continue
   if (not shared and not store.team_visible(u,r['team'])) or (u['role']!='admin' and r['module'] not in u['modules']):continue
   item={k:r[k] for k in ('id','owner','module','name','revision','updated','author','team')}
   item['shared']=shared
   if r['owner']==uid or u['role']=='admin':item['collaborators']=len(report_collaboration.members(d,r))
   if r['module'] in ['incident','relocation']:
    dt,label=report_time(json.loads(r['payload']));item['time_label']=label;item['report_time']=dt.isoformat()
   task=cable_tasks.assignment(d,r['id'])
   if task:item['task_id']=task['task'];item['task_active']=bool(task['active'])
   folders.append(item)
  # Sort each category independently; uploads/edits do not change its report date.
  result=[]
  for module in ['incident','cable','relocation']:
   group=[f for f in folders if f['module']==module]
   if module=='incident':group.sort(key=lambda f:(f['report_time']=='0001-01-01T00:00:00',f['report_time'],f['id']))
   elif module=='relocation':group.sort(key=lambda f:(f['report_time']=='0001-01-01T00:00:00',f['report_time'],f['id']))
   result.extend(group)
  return {'folders':result}
 if len(parts)==4 and parts[:3]==['api','mobile','summary']:
  store.manage(u)
  if method!='PUT':raise Error(405,'Thao tác không hợp lệ.')
  d.execute('BEGIN IMMEDIATE')
  fid=parts[3];row=d.execute('SELECT * FROM mobile_folders WHERE id=?',(fid,)).fetchone()
  if not row or row['module']!='incident' or not store.team_visible(u,row['team']):raise Error(404,'Không tìm thấy báo cáo sự cố.')
  if u['role']!='admin' and report_collaboration.is_member(d,u,row):raise Error(403,'Người cùng làm chỉ được góp ảnh, không sửa bảng tổng hợp.')
  old=d.execute('SELECT * FROM incident_summary WHERE fid=?',(fid,)).fetchone();rev=old['revision'] if old else 0
  if b.get('revision')!=rev:raise Error(409,'Bảng tổng hợp đã được sửa. Hãy tải lại trước khi lưu.')
  cells=b.get('cells');numeric={0,3,13,*range(14,28),30,32};dates={11,12}
  if not isinstance(cells,dict):raise Error(400,'Dữ liệu không hợp lệ.')
  for key,value in cells.items():
   if key not in {str(i) for i in range(38)}:raise Error(400,'Cột không hợp lệ.')
   if value is None:continue
   i=int(key)
   if i in numeric|dates:
    if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or value<0:raise Error(400,'Số không hợp lệ.')
    if i==0 and (value<1 or value!=int(value)):raise Error(400,'STT phải là số nguyên dương.')
    if i in dates and not 1<=value<=2958465:raise Error(400,'Ngày không hợp lệ.')
   elif not isinstance(value,str) or len(value)>4000:raise Error(400,'Nội dung quá dài hoặc không hợp lệ.')
  d.execute('INSERT INTO incident_summary VALUES(?,?,?) ON CONFLICT(fid) DO UPDATE SET revision=excluded.revision,cells=excluded.cells',(fid,rev+1,json.dumps(cells,ensure_ascii=False)))
  store.audit(d,uid,'incident-summary-edit',fid)
  return {'revision':rev+1,'cells':cells}
 if len(parts)==4 and parts[:3]==['api','mobile','folders']:
  fid=parts[3]
  if not re.fullmatch('[a-f0-9-]{32,36}',fid):raise Error(400,'Mã thư mục không hợp lệ.')
  if method in ['PUT','DELETE']:d.execute('BEGIN IMMEDIATE')
  row=d.execute('SELECT * FROM mobile_folders WHERE id=?',(fid,)).fetchone()
  shared=bool(row and report_collaboration.is_member(d,u,row))
  if row and row['owner']!=uid and not store.read_all(u) and not shared:raise Error(404,'Không tìm thấy thư mục.')
  if row and not shared and not store.team_visible(u,row['team']):raise Error(404,'Không tìm thấy thư mục.')
  if method=='GET':
   if not row:raise Error(404,'Không tìm thấy thư mục.')
   store.module(u,row['module']);payload=photo_jobs.enrich(d,json.loads(row['payload']));assigned=cable_tasks.assignment(d,fid)
   if assigned:payload['report']={**(payload.get('report') or {}),'cableWork':True,'workType':assigned['work_type'],'routeName':assigned['name'],'lengthKm':assigned['length_km'],'team':assigned['team'],'reporter':d.execute('SELECT display_name FROM users WHERE id=?',(row['owner'],)).fetchone()[0]}
   summary=d.execute('SELECT * FROM incident_summary WHERE fid=?',(fid,)).fetchone()
   author=d.execute('SELECT display_name FROM users WHERE id=?',(row['owner'],)).fetchone()['display_name']
   return {'id':fid,'owner':row['owner'],'author':author,'shared':shared,'revision':row['revision'],'payload':payload,'summary':{'revision':summary['revision'],'cells':json.loads(summary['cells'])} if summary else {'revision':0,'cells':{}}}
  if method not in ['PUT','DELETE']:raise Error(405,'Thao tác không hợp lệ.')
  if shared and u['role']!='admin':raise Error(403,'Người cùng làm chỉ được góp ảnh, không sửa hoặc xóa toàn bộ báo cáo.')
  task=cable_tasks.assignment(d,fid) if row else None
  if task and u['role']!='admin' and not task['active']:raise Error(403,'Tuyến đã ngừng giao cho tài khoản này. Dữ liệu trên máy vẫn được giữ.')
  owner=row['owner'] if row else uid
  if method=='DELETE':store.manage(u)
  operation=b.get('operation');request_digest=None
  if method=='PUT':
   store.module(u,b['payload'].get('module') if isinstance(b.get('payload'),dict) else None)
   if d.execute('SELECT 1 FROM mobile_deleted WHERE id=?',(fid,)).fetchone():raise Error(409,'Thư mục đã bị xóa trên server. Không tự tạo lại. Hãy sao chép thành thư mục mới nếu muốn gửi lại.')
   if operation is not None:
    if not isinstance(operation,str) or not re.fullmatch('[a-f0-9-]{32,36}',operation):raise Error(400,'Mã lần gửi không hợp lệ.')
    request_digest=hashlib.sha256(json.dumps({'revision':b.get('revision'),'payload':b.get('payload')},sort_keys=True,ensure_ascii=False,allow_nan=False).encode()).hexdigest()
    receipt=d.execute('SELECT * FROM mobile_receipts WHERE owner=? AND fid=? AND operation=?',(uid,fid,operation)).fetchone()
    if receipt:
     if receipt['digest']!=request_digest:raise Error(409,'Mã lần gửi đã dùng cho nội dung khác.')
     return {'id':fid,'revision':receipt['revision'],'replayed':True}
  revision=b.get('revision')
  if type(revision) is not int or revision!=(row['revision'] if row else 0):raise Error(409,'Bản trên server đã thay đổi. Tải về thành bản sao để đối chiếu; chưa ghi đè dữ liệu.')
  if method=='DELETE':
   if not row:raise Error(404,'Không tìm thấy thư mục.')
   store.module(u,row['module']);removed=refs(json.loads(row['payload']))|report_collaboration.bound_assets(d,fid);d.execute('INSERT OR IGNORE INTO mobile_deleted VALUES(?,?)',(fid,owner));d.execute('DELETE FROM mobile_folders WHERE id=?',(fid,));cleanup(d,owner,row['module'],removed);store.audit(d,uid,'mobile-delete',fid);return {'ok':True}
  p=b.get('payload');module=p.get('module') if isinstance(p,dict) else None;store.module(u,module)
  if module=='cable' and not row and u['role']!='admin':raise Error(403,'Tuyến cáp phải được quản trị tạo và giao trên server. Hãy nhận tuyến được giao trong ứng dụng.')
  previous_module=row['module'] if row else module
  changing_kind=bool(row and module!=previous_module)
  # Keep accepting an already queued transfer from 4.0.2 clients during rollout.
  # Version 4.0.3 removes every user-facing action that can start a new transfer.
  if changing_kind:
   if {previous_module,module}!={'incident','relocation'}:raise Error(400,'Chỉ có thể chuyển báo cáo giữa Sự cố và Di dời.')
   store.module(u,previous_module)
  if not isinstance(p,dict) or p.get('version')!=1 or not isinstance(p.get('name'),str) or not 1<=len(p['name'].strip())<=160 or not isinstance(p.get('photos'),list):raise Error(400,'Hồ sơ điện thoại không hợp lệ.')
  if task:p={**p,'name':task['name'],'report':{**(p.get('report') or {}),'cableWork':True,'workType':task['work_type'],'routeName':task['name'],'lengthKm':task['length_km'],'team':task['team'],'reporter':d.execute('SELECT display_name FROM users WHERE id=?',(row['owner'],)).fetchone()[0]}}
  if isinstance(p.get('report'),dict):
   p={**p,'report':{**p['report'],'reporter':d.execute('SELECT display_name FROM users WHERE id=?',(owner,)).fetchone()['display_name']}}
  if p.get('report') is not None and not isinstance(p['report'],dict):raise Error(400,'Báo cáo không hợp lệ.')
  if len(p['photos'])>10000:raise Error(400,'Thư mục có quá nhiều vị trí.')
  seqs=set()
  for photo in p['photos']:
   if not isinstance(photo,dict) or not photo.get('file') or type(photo.get('seq')) is not int or photo['seq']<1:raise Error(400,'Vị trí không hợp lệ.')
   if photo['seq'] in seqs:raise Error(400,'Trùng số thứ tự vị trí.')
   seqs.add(photo['seq'])
   for key,limit in [('lat',90),('lon',180)]:
    n=photo.get(key)
    if type(n) not in (int,float) or not math.isfinite(n) or abs(n)>limit:raise Error(400,'Tọa độ không hợp lệ.')
  def safe_tree(v):
   if isinstance(v,dict):
    if any(k in v for k in ['path','pendingPhoto','pendingOriginal','pendingCapture','routes','raw']):raise Error(400,'Hãy lưu hoặc hủy ảnh đang nhập dở trước khi gửi.')
    for x in v.values():safe_tree(x)
   elif isinstance(v,list):
    for x in v:safe_tree(x)
  safe_tree(p)
  if changing_kind:
   if not isinstance(p.get('report'),dict):raise Error(400,'Chỉ báo cáo Sự cố/Di dời có thể chuyển loại.')
   old_assets=transfer_asset_refs(d,fid,owner,previous_module,module,p,Error)
   p['report']['relocation']=module=='relocation'
  try:assets=refs(p)
  except ValueError as e:raise Error(400,str(e))
  for aid in assets:
   if not d.execute('SELECT 1 FROM mobile_assets WHERE id=? AND owner=? AND module=?',(aid,owner,module)).fetchone():raise Error(400,'Thiếu ảnh hoặc ảnh không thuộc tài khoản này. Hãy gửi lại.')
  encoded=json.dumps(p,ensure_ascii=False,allow_nan=False)
  if len(encoded.encode())>2*1024*1024:raise Error(413,'Thông tin thư mục quá lớn.')
  d.execute('INSERT INTO mobile_folders(id,owner,module,name,payload,revision,updated,team) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET module=excluded.module,name=excluded.name,payload=excluded.payload,revision=excluded.revision,updated=excluded.updated',(fid,owner,module,p['name'].strip(),encoded,revision+1,now,row['team'] if row else u['team']))
  report_collaboration.accept_assets(d,fid,uid,assets)
  if row:
   previous_assets=refs(json.loads(row['payload']))|report_collaboration.bound_assets(d,fid)
   cleanup(d,owner,previous_module,old_assets if changing_kind else previous_assets)
   if previous_module==module:cleanup(d,owner,module,previous_assets)
  if operation:d.execute('INSERT INTO mobile_receipts VALUES(?,?,?,?,?)',(uid,fid,operation,request_digest,revision+1))
  store.audit(d,uid,'mobile-admin-edit' if row and u['role']=='admin' else 'mobile-push',fid);return {'id':fid,'revision':revision+1}
 raise Error(404,'Không tìm thấy chức năng điện thoại.')
