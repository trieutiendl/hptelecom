import base64,io,json,os,re,subprocess,tempfile,threading,shutil
from pathlib import Path
gate=threading.BoundedSemaphore(2)
from gps_formats import candidates

def _loose_candidates(text):
 text=text.replace('−','-').replace('º','°').replace('˚','°')
 text=re.sub(r'(\d{1,3})[.,]\s+(\d{3,8})',r'\1.\2',text)
 nums=[]
 for m in re.finditer(r'(?<!\d)([+-]?\d{1,3}[.,]\d{3,8})(?!\d)',text):
  try:value=round(float(m.group(1).replace(',','.')),8)
  except ValueError:continue
  if value not in nums:nums.append(value)
 lats=[x for x in nums if abs(x)<=90]
 lons=[x for x in nums if 90<abs(x)<=180]
 return [[lats[0],lons[0]]] if lats and lons else []

def _fit(im, limit=3900, minimum=2):
 scale=min(max(minimum, limit/max(im.size)), limit/max(im.size) if max(im.size)>limit else max(minimum,1))
 return im.resize((max(1,int(im.width*scale)),max(1,int(im.height*scale))))

def _ocr_variants(full, Image, ImageOps, ImageFilter):
 w,h=full.size
 # Phone/app stamps are commonly placed at the lower-left, while older GPS
 # camera stamps can be lower-right and green.  Read focused crops first so
 # OCR sees the coordinate text instead of the whole construction photo.
 boxes=[
  (0,int(h*.70),int(w*.70),h),
  (0,int(h*.68),int(w*.70),h),
  (0,int(h*.66),int(w*.82),h),
  (0,int(h*.72),w,h),
  (0,int(h*.80),int(w*.74),h),
  (int(w*.38),int(h*.86),w,h),
 ]
 for box in boxes:
  crop=full.crop(box)
  yield ImageOps.expand(_fit(ImageOps.autocontrast(ImageOps.grayscale(crop)),2600,2),border=40,fill=255)
  white=Image.new('L',crop.size)
  white.putdata([0 if r>145 and g>145 and b>145 and max(r,g,b)-min(r,g,b)<95 else 255 for r,g,b in crop.getdata()])
  yield ImageOps.expand(_fit(white,2600,2),border=40,fill=255)
  strict=Image.new('L',crop.size)
  strict.putdata([0 if r>185 and g>185 and b>185 and max(r,g,b)-min(r,g,b)<75 else 255 for r,g,b in crop.getdata()])
  strict=strict.filter(ImageFilter.MinFilter(3))
  yield ImageOps.expand(_fit(strict,2600,2),border=60,fill=255)
  green=Image.new('L',crop.size)
  green.putdata([0 if g>r*1.12 and g>b*1.05 and g>70 else 255 for r,g,b in crop.getdata()])
  yield ImageOps.expand(_fit(green,2600,2),border=40,fill=255)
 im=full.copy();im.thumbnail((1400,1400))
 yield ImageOps.autocontrast(ImageOps.grayscale(im))
 green=Image.new('L',im.size)
 green.putdata([0 if g>r*1.12 and g>b*1.05 and g>70 else 255 for r,g,b in im.getdata()])
 yield _fit(green,2600,2)

def _read_text(path):
 if os.name=='nt':
  result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(Path(__file__).with_name('Read-PhotoText.ps1').resolve()),'-ImagePath',str(path)],capture_output=True,text=True,encoding='utf-8',timeout=25,creationflags=0x08000000)
  if result.returncode:raise RuntimeError('Windows OCR unavailable')
  return json.loads(result.stdout)['text']
 binary=shutil.which('tesseract')
 if not binary:raise RuntimeError('NAS chưa có Tesseract OCR.')
 env=dict(os.environ,OMP_THREAD_LIMIT='1')
 result=subprocess.run([binary,str(path),'stdout','-l','eng','--psm','11'],capture_output=True,text=True,encoding='utf-8',timeout=25,env=env)
 if result.returncode:raise RuntimeError('Tesseract OCR failed')
 return result.stdout

def recognize(raw):
 from PIL import Image,ImageOps,ImageFilter
 with Image.open(io.BytesIO(raw)) as original:
  if original.width*original.height>25000000:raise ValueError('Ảnh quá lớn.')
  full=ImageOps.exif_transpose(original).convert('RGB')
 texts=[];found=[];votes={};multi=False
 with tempfile.TemporaryDirectory(prefix='hp-photo-ocr-') as tmp:
  for i,variant in enumerate(_ocr_variants(full,Image,ImageOps,ImageFilter)):
   path=Path(tmp)/'photo.png';variant.save(path)
   text=_read_text(path);texts.append(text)
   parsed=candidates(text)
   multi=multi or len(parsed)>1
   for item in parsed:
    key=tuple(item);votes[key]=votes.get(key,0)+1
    if item not in found:found.append(item)
 # Prefer repeated agreement over a single corrupted reading of the same stamp.
 ranked=sorted(votes.items(),key=lambda x:x[1],reverse=True)
 if len(ranked)>1 and not multi and ranked[0][1]>=3 and ranked[0][1]>=3*ranked[1][1]:
  found=[list(ranked[0][0])]
 # Never combine unrelated numbers across OCR passes into a guessed location.
 return {'candidates':[{'lat':a,'lon':b} for a,b in found],'text':'\n'.join(texts)[:4000]}
def dispatch(store,d,u,b,Error):
 store.module(u,b.get('module'))
 if b.get('module') not in ['incident','relocation']:raise Error(400,'Chỉ dùng cho Sự cố và Di dời.')
 try:raw=base64.b64decode(b.get('image',''),validate=True)
 except Exception:raise Error(400,'Ảnh không hợp lệ.')
 if not raw or len(raw)>5*1024*1024:raise Error(400,'Ảnh tối đa 5 MB.')
 if not gate.acquire(False):raise Error(503,'Server đang đọc ảnh khác, thử lại sau.')
 try:return recognize(raw)
 except ImportError:raise Error(503,'Server cần cài Pillow để nhận dạng ảnh.')
 except subprocess.TimeoutExpired:raise Error(503,'Đọc ảnh quá lâu. Server sẽ thử lại sau.')
 except Exception as e:raise Error(400,str(e) if isinstance(e,ValueError) else 'Không đọc được ảnh. Hãy thử ảnh khác.')
 finally:gate.release()
