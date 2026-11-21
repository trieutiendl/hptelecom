"""Read route length and connection types from an uploaded workbook, without executing formulas."""
import base64,io,json,math,posixpath,re,zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
N={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
def parse(raw):
 if len(raw)>8*1024*1024:raise ValueError('File tối đa 8 MB.')
 with zipfile.ZipFile(io.BytesIO(raw)) as z:
  if sum(i.file_size for i in z.infolist())>40*1024*1024:raise ValueError('File giải nén quá lớn.')
  strings=[]
  if 'xl/sharedStrings.xml' in z.namelist():strings=[''.join(si.itertext()) for si in ET.fromstring(z.read('xl/sharedStrings.xml'))]
  records=[]
  for name in z.namelist():
   if not re.fullmatch(r'xl/worksheets/sheet\d+\.xml',name):continue
   heads=None
   for row in ET.fromstring(z.read(name)).findall('s:sheetData/s:row',N):
    values={}
    for c in row:
     ref=re.sub(r'\d','',c.get('r',''));v=c.find('s:v',N);value=v.text if v is not None else ''
     if c.get('t')=='s':value=strings[int(value)]
     elif c.get('t')=='inlineStr':value=''.join(c.find('s:is',N).itertext())
     values[ref]=(value or '').strip()
    if heads is None:
     h={v.casefold():k for k,v in values.items()}
     if all(k in h for k in ['tên tuyến','loại kết nối','chiều dài tuyến (km)']):heads=[h[k] for k in ['tên tuyến','loại kết nối','chiều dài tuyến (km)']]
     continue
    route,link,length=[values.get(k,'') for k in heads]
    if not route:continue
    number=None
    if length:
     try:number=float(length.replace(',','.'))
     except ValueError:raise ValueError('Chiều dài không hợp lệ tại tuyến '+route)
     if not math.isfinite(number) or number<0:raise ValueError('Chiều dài không hợp lệ tại tuyến '+route)
    records.append({'name':route,'link':link,'length':number})
    if len(records)>20000:raise ValueError('Danh sách tối đa 20.000 tuyến.')
  if not records:raise ValueError('Cần các cột Tên tuyến, Loại kết nối, Chiều dài tuyến (km).')
  return records
def schema(d):
 d.execute('CREATE TABLE IF NOT EXISTS route_catalog(team TEXT PRIMARY KEY,revision INTEGER NOT NULL,content TEXT NOT NULL)')
def get(d,team):
 row=d.execute('SELECT * FROM route_catalog WHERE team=?',(team,)).fetchone()
 if row:return {'revision':row['revision'],'rows':json.loads(row['content'])}
 seed=Path(__file__).with_name('route_catalog_seed.json')
 return {'revision':0,'rows':json.loads(seed.read_text(encoding='utf-8')) if seed.exists() else []}
def dispatch(store,d,u,method,b,now,Error):
 team='' if u['role']=='admin' else u.get('team','')
 if not team and u['role']!='admin':raise Error(403,'Cần được gán Team / Đội.')
 schema(d)
 if method=='GET':
  result=get(d,team)
  if team and not result['revision']:result={**get(d,''),'revision':0}
  return result
 store.manage(u)
 if method!='PUT':raise Error(405,'Thao tác không hợp lệ.')
 try:rows=parse(base64.b64decode(b.get('file',''),validate=True))
 except Exception as e:raise Error(400,str(e) if isinstance(e,ValueError) else 'Không đọc được file Excel .xlsx.')
 d.execute('BEGIN IMMEDIATE');old=get(d,team)
 if b.get('revision')!=old['revision']:raise Error(409,'Danh sách đã thay đổi. Hãy mở lại trước khi cập nhật.')
 d.execute('INSERT INTO route_catalog VALUES(?,?,?) ON CONFLICT(team) DO UPDATE SET revision=excluded.revision,content=excluded.content',(team,old['revision']+1,json.dumps(rows,ensure_ascii=False)))
 store.audit(d,u['id'],'route-catalog-update',team or 'all')
 return {'revision':old['revision']+1,'rows':rows}
