"""Server-created cable work, with isolated contributions per assigned account."""
import json,uuid,math
WORK_TYPES={'maintenance':'Bảo dưỡng','inspection':'Kiểm tra'}

def schema(d):
 d.executescript('''
 CREATE TABLE IF NOT EXISTS cable_tasks(id TEXT PRIMARY KEY,name TEXT NOT NULL,note TEXT NOT NULL,mode TEXT NOT NULL,revision INTEGER NOT NULL,creator TEXT NOT NULL REFERENCES users(id),created INTEGER NOT NULL,updated INTEGER NOT NULL);
 CREATE TABLE IF NOT EXISTS cable_members(task TEXT NOT NULL REFERENCES cable_tasks(id),user TEXT NOT NULL REFERENCES users(id),folder TEXT NOT NULL UNIQUE REFERENCES mobile_folders(id) ON DELETE CASCADE,active INTEGER NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(task,user));
 ''')
 if 'work_type' not in [r[1] for r in d.execute('PRAGMA table_info(cable_tasks)')]:
  d.execute("ALTER TABLE cable_tasks ADD COLUMN work_type TEXT NOT NULL DEFAULT ''")

 for column in ['length_km','team']:
  if column not in [r[1] for r in d.execute('PRAGMA table_info(cable_tasks)')]:
   d.execute("ALTER TABLE cable_tasks ADD COLUMN "+column+" TEXT NOT NULL DEFAULT ''")
 d.executescript('''
 CREATE INDEX IF NOT EXISTS cable_task_updated_id ON cable_tasks(updated,id);
 CREATE INDEX IF NOT EXISTS cable_member_user_active_task ON cable_members(user,active,task);
 CREATE INDEX IF NOT EXISTS cable_member_task_active_position ON cable_members(task,active,position,user);
 CREATE INDEX IF NOT EXISTS mobile_folder_updated_id ON mobile_folders(updated,id);
 ''')

def assignment(d,fid):
 return d.execute('SELECT m.*,t.name,t.note,t.mode,t.work_type,t.length_km,t.team FROM cable_members m JOIN cable_tasks t ON t.id=m.task WHERE m.folder=?',(fid,)).fetchone()

def view(d,row,u):
 members=[]
 for m in d.execute('SELECT m.*,u.display_name,f.payload,f.revision FROM cable_members m JOIN users u ON u.id=m.user JOIN mobile_folders f ON f.id=m.folder WHERE m.task=? ORDER BY m.position,m.user',(row['id'],)):
  if u['role'] not in ['admin','viewer','dispatcher'] and m['user']!=u['id']:continue
  payload=json.loads(m['payload'])
  members.append({'user':m['user'],'author':m['display_name'],'folder':m['folder'],'active':bool(m['active']),'points':len(payload.get('photos',[])),'completed':bool(payload.get('completed')),'revision':m['revision']})
 label=WORK_TYPES.get(row['work_type'],'')
 note=('Công việc: '+label+'\n' if label else '')+row['note']
 return {**({k:row[k] for k in ['id','name','mode','work_type','length_km','team','revision','created','updated']}), **({'members':members,'note':note.strip(),'raw_note':row['note']})}

def views(d,rows,u):
 rows=list(rows)
 if not rows:return []
 result={r['id']:{**({k:r[k] for k in ['id','name','mode','work_type','length_km','team','revision','created','updated']}), 'members':[], 'note':'', 'raw_note':r['note']} for r in rows}
 task_ids=list(result)
 # Keep each IN clause comfortably below SQLite's parameter limit while
 # replacing the former two extra queries per task with one query per batch.
 for offset in range(0,len(task_ids),400):
  batch=task_ids[offset:offset+400]
  marks=','.join('?' for _ in batch)
  query=('SELECT m.*,u.display_name,f.payload,f.revision FROM cable_members m '
         'JOIN users u ON u.id=m.user JOIN mobile_folders f ON f.id=m.folder '
         'WHERE m.task IN ('+marks+') ORDER BY m.task,m.position,m.user')
  params=list(batch)
  if u['role'] not in ['admin','viewer','dispatcher']:
   query=query.replace('WHERE m.task IN (','WHERE m.user=? AND m.task IN (')
   params=[u['id']]+params
  for m in d.execute(query,params):
   payload=json.loads(m['payload'])
   result[m['task']]['members'].append({'user':m['user'],'author':m['display_name'],'folder':m['folder'],'active':bool(m['active']),'points':len(payload.get('photos',[])),'completed':bool(payload.get('completed')),'revision':m['revision']})
 for r in rows:
  label=WORK_TYPES.get(r['work_type'],'')
  result[r['id']]['note']=((('Công việc: '+label+'\n') if label else '')+r['note']).strip()
 return [result[r['id']] for r in rows]

def changed_rows(d,u,since,store):
 # Assignment edits touch cable_tasks.updated. Field completion and report
 # uploads touch mobile_folders.updated, so the union also catches a reopened
 # task from an older month without scanning and returning every old task.
 changed='''WITH changed(task) AS (
  SELECT id FROM cable_tasks WHERE updated>=?
  UNION
  SELECT m.task FROM cable_members m JOIN mobile_folders f ON f.id=m.folder WHERE f.updated>=?
 ) '''
 if store.read_all(u):
  return d.execute(changed+'SELECT t.* FROM changed c JOIN cable_tasks t ON t.id=c.task ORDER BY t.created,t.id',(since,since))
 return d.execute(changed+'SELECT t.* FROM changed c JOIN cable_tasks t ON t.id=c.task JOIN cable_members m ON m.task=t.id WHERE m.user=? AND m.active=1 ORDER BY t.created,t.id',(since,since,u['id']))

def dispatch(store,d,u,method,path,b,now,Error):
 parts=path.strip('/').split('/');base='/api/mobile/cable-tasks'
 if len(parts)==5 and parts[:4]==['api','mobile','cable-tasks','since'] and method=='GET':
  store.module(u,'cable')
  if not parts[4].isdigit() or int(parts[4])<0:raise Error(400,'Mốc đồng bộ không hợp lệ.')
  since=int(parts[4]);rows=changed_rows(d,u,since,store)
  rows=[r for r in rows if store.team_visible(u,r['team'])]
  return {'tasks':views(d,rows,u),'sync_cursor':now}
 if path==base and method=='GET':
  store.module(u,'cable')
  if store.read_all(u):rows=d.execute('SELECT * FROM cable_tasks ORDER BY created,id')
  else:rows=d.execute('SELECT t.* FROM cable_tasks t JOIN cable_members m ON m.task=t.id WHERE m.user=? AND m.active=1 ORDER BY t.created,t.id',(u['id'],))
  rows=[r for r in rows if store.team_visible(u,r['team'])]
  return {'tasks':views(d,rows,u),'sync_cursor':now}
 if len(parts)==7 and parts[:3]==['api','mobile','cable-tasks'] and parts[4]=='members' and parts[6]=='reopen' and method=='POST':
  store.assign(u);d.execute('BEGIN IMMEDIATE')
  task=d.execute('SELECT * FROM cable_tasks WHERE id=?',(parts[3],)).fetchone()
  if not task or not store.team_visible(u,task['team']):raise Error(404,'Không tìm thấy tuyến được giao.')
  if type(b.get('revision')) is not int or b['revision']!=task['revision']:raise Error(409,'Phân công đã thay đổi. Hãy tải lại.')
  member=d.execute('SELECT * FROM cable_members WHERE task=? AND user=? AND active=1',(task['id'],parts[5])).fetchone()
  if not member:raise Error(404,'Không tìm thấy người đang được giao tuyến này.')
  folder=d.execute('SELECT * FROM mobile_folders WHERE id=?',(member['folder'],)).fetchone()
  if not folder:raise Error(404,'Không tìm thấy dữ liệu tuyến trên server.')
  payload=json.loads(folder['payload'])
  if payload.get('completed'):
   payload['completed']=False
   d.execute('UPDATE mobile_folders SET payload=?,revision=revision+1,updated=? WHERE id=?',(json.dumps(payload,ensure_ascii=False),now,folder['id']))
   d.execute('UPDATE cable_tasks SET revision=revision+1,updated=? WHERE id=?',(now,task['id']))
   store.audit(d,u['id'],'cable-task-reopen',task['id']+':'+parts[5])
  return view(d,d.execute('SELECT * FROM cable_tasks WHERE id=?',(task['id'],)).fetchone(),u)
 if len(parts)==4 and method=='DELETE':
  store.manage(u);d.execute('BEGIN IMMEDIATE');task=d.execute('SELECT * FROM cable_tasks WHERE id=?',(parts[3],)).fetchone()
  if task and not store.team_visible(u,task['team']):raise Error(404,'Không tìm thấy tuyến được giao.')
  if not task:raise Error(404,'Không tìm thấy tuyến được giao.')
  if type(b.get('revision')) is not int or b['revision']!=task['revision']:raise Error(409,'Phân công đã thay đổi. Hãy tải lại.')
  import mobile
  for folder in d.execute('SELECT f.* FROM mobile_folders f JOIN cable_members m ON m.folder=f.id WHERE m.task=?',(task['id'],)).fetchall():
   d.execute('INSERT OR IGNORE INTO mobile_deleted VALUES(?,?)',(folder['id'],folder['owner']))
   d.execute('DELETE FROM mobile_folders WHERE id=?',(folder['id'],));mobile.cleanup(d,folder['owner'],'cable',mobile.refs(json.loads(folder['payload'])))
  d.execute('DELETE FROM cable_tasks WHERE id=?',(task['id'],));store.audit(d,u['id'],'cable-task-delete',task['id']);return {'ok':True}
 if (path==base and method=='POST') or (len(parts)==4 and method=='PUT'):
  store.assign(u);d.execute('BEGIN IMMEDIATE');old=None
  if method=='PUT':
   old=d.execute('SELECT * FROM cable_tasks WHERE id=?',(parts[3],)).fetchone()
   if old and not store.team_visible(u,old['team']):raise Error(404,'Không tìm thấy tuyến được giao.')
   if not old:raise Error(404,'Không tìm thấy tuyến được giao.')
   if type(b.get('revision')) is not int or b['revision']!=old['revision']:raise Error(409,'Phân công đã thay đổi. Hãy tải lại.')
  name=b.get('name');note=b.get('note','');mode=b.get('mode');users=b.get('users')
  if not isinstance(name,str) or not 1<=len(name.strip())<=160:raise Error(400,'Tên tuyến từ 1 đến 160 ký tự.')
  if not isinstance(note,str) or len(note)>4000:raise Error(400,'Nội dung giao việc quá dài.')
  work_type=b.get('work_type',old['work_type'] if old else '')
  if work_type not in ['',*WORK_TYPES]:raise Error(400,'Chọn Bảo dưỡng hoặc Kiểm tra.')
  length=str(b.get('length_km',old['length_km'] if old else '')).strip().replace(',','.')
  try:
   if length and (not math.isfinite(float(length)) or float(length)<0):raise ValueError()
  except ValueError:raise Error(400,'Chiều dài tuyến phải là số km không âm.')
  team=b.get('team',old['team'] if old else '')
  if not isinstance(team,str) or len(team)>160:raise Error(400,'Tên đội quá dài.')
  team=team.strip()
  if not store.team_visible(u,team):raise Error(403,'Chỉ được giao việc trong Team / Đội của mình.')
  if old and old['team']!=team and d.execute('SELECT 1 FROM cable_members WHERE task=? LIMIT 1',(old['id'],)).fetchone():raise Error(409,'Tuyến đã giao thuộc đội cũ. Hãy tạo tuyến mới cho đội khác để giữ riêng dữ liệu.')
  if mode not in ['merged','separate']:raise Error(400,'Chọn cách tổng hợp kết quả.')
  if not isinstance(users,list) or len(users)>100 or any(not isinstance(x,str) for x in users) or len(set(users))!=len(users):raise Error(400,'Danh sách nhân viên không hợp lệ.')
  for uid in users:
   person=d.execute('SELECT * FROM users WHERE id=?',(uid,)).fetchone()
   if not person or person['role'] not in ['admin','staff','dispatcher'] or not person['enabled'] or person['deleted_at'] or (person['role'] not in ['admin','dispatcher'] and 'cable' not in json.loads(person['modules'])):raise Error(400,'Người được giao phải đang hoạt động và có quyền Tuyến cáp.')
   if person['team']!=team:raise Error(400,'Người được giao phải thuộc Team / Đội đã chọn.')
  tid=old['id'] if old else uuid.uuid4().hex
  d.execute('INSERT INTO cable_tasks(id,name,note,mode,revision,creator,created,updated,work_type) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,note=excluded.note,mode=excluded.mode,work_type=excluded.work_type,revision=excluded.revision,updated=excluded.updated',(tid,name.strip(),note,mode,(old['revision'] if old else 0)+1,u['id'],old['created'] if old else now,now,work_type))
  d.execute('UPDATE cable_tasks SET length_km=?,team=? WHERE id=?',(length,team.strip(),tid))
  d.execute('UPDATE cable_members SET active=0 WHERE task=?',(tid,))
  for position,uid in enumerate(users):
   member=d.execute('SELECT * FROM cable_members WHERE task=? AND user=?',(tid,uid)).fetchone()
   if member:d.execute('UPDATE cable_members SET active=1,position=? WHERE task=? AND user=?',(position,tid,uid))
   else:
    fid=uuid.uuid4().hex;p={'version':1,'module':'cable','name':name.strip(),'completed':False,'photos':[],'report':None}
    d.execute('INSERT INTO mobile_folders(id,owner,module,name,payload,revision,updated,team) VALUES(?,?,?,?,?,?,?,?)',(fid,uid,'cable',name.strip(),json.dumps(p,ensure_ascii=False),1,now,team))
    d.execute('INSERT INTO cable_members VALUES(?,?,?,?,?)',(tid,uid,fid,1,position))
  # Rename metadata without touching any worker's photos or completion state.
  for folder in d.execute('SELECT f.* FROM mobile_folders f JOIN cable_members m ON m.folder=f.id WHERE m.task=?',(tid,)).fetchall():
   p=json.loads(folder['payload']);p['name']=name.strip();d.execute('UPDATE mobile_folders SET name=?,payload=? WHERE id=?',(name.strip(),json.dumps(p,ensure_ascii=False),folder['id']))
  store.audit(d,u['id'],'cable-task-update' if old else 'cable-task-create',tid)
  return view(d,d.execute('SELECT * FROM cable_tasks WHERE id=?',(tid,)).fetchone(),u)
 raise Error(405,'Thao tác giao tuyến không hợp lệ.')
