"""Persistent server-side photo coordinates; never change phone sync revisions."""
import io,json,threading
import photo_ocr

def schema(d):
 d.execute('CREATE TABLE IF NOT EXISTS photo_coordinates(asset TEXT PRIMARY KEY REFERENCES mobile_assets(id) ON DELETE CASCADE,result TEXT NOT NULL)')

def read(raw):
 from PIL import Image
 try:
  with Image.open(io.BytesIO(raw)) as im:
   gps=im.getexif().get_ifd(34853)
   if gps and 2 in gps and 4 in gps:
    def angle(v):return float(v[0])+float(v[1])/60+float(v[2])/3600
    lat=angle(gps[2])*(-1 if gps.get(1)=='S' else 1)
    lon=angle(gps[4])*(-1 if gps.get(3)=='W' else 1)
    if -90<=lat<=90 and -180<=lon<=180:return {'lat':lat,'lon':lon,'source':'exif'}
 except (ValueError,TypeError,KeyError,ZeroDivisionError):pass
 items=photo_ocr.recognize(raw)['candidates']
 if len(items)==1:return {**items[0],'source':'ocr'}
 return {'status':'ambiguous' if items else 'unreadable'}

def enrich(d,p):
 for group in (p.get('report') or {}).get('groups',[]):
  for image in group.get('images',[]):
   if image.get('gps'):continue
   row=d.execute('SELECT result FROM photo_coordinates WHERE asset=?',(image.get('file'),)).fetchone()
   if not row:continue
   gps=json.loads(row[0])
   if 'lat' not in gps:continue
   image['gps']=gps
   caption=image.get('caption') or ''
   if not photo_ocr.candidates(caption):image['caption']=(caption+'\n' if caption else '')+f"Lat: {gps['lat']:.6f} | Long: {gps['lon']:.6f}"
 return p

OCR_VERSION=3

def process_one(store):
 with store.db() as d:
  row=d.execute("SELECT a.id,a.content FROM mobile_assets a LEFT JOIN photo_coordinates c ON c.asset=a.id WHERE a.module IN ('incident','relocation') AND (c.asset IS NULL OR COALESCE(json_extract(c.result,'$.ocrVersion'),0)<?) ORDER BY a.created DESC LIMIT 1",(OCR_VERSION,)).fetchone()
 if not row:return False
 try:result=read(row['content'])
 except Exception:result={'status':'retry'}
 result['ocrVersion']=OCR_VERSION
 with store.db() as d:
  # Folder deletion may remove the asset while OCR is running.
  if d.execute('SELECT 1 FROM mobile_assets WHERE id=?',(row['id'],)).fetchone():
   d.execute('INSERT OR REPLACE INTO photo_coordinates VALUES(?,?)',(row['id'],json.dumps(result)))
 return True

def start(store):
 with store.db() as d:d.execute("DELETE FROM photo_coordinates WHERE result LIKE '%retry%'")
 stop=threading.Event()
 def run():
  while not stop.is_set():
   try:
    if process_one(store):continue
   except Exception:
    # A temporary OCR failure must not mark the image permanently unreadable.
    pass
   stop.wait(10)
 threading.Thread(target=run,name='photo-coordinates',daemon=True).start()
 return stop
