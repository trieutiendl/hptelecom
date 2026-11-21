"""Conservative OCR coordinate parsing. Unknown coordinate systems are not guessed."""
import re
NUM=r'\d+(?:[.,]\d+)?'
ANGLE=rf'(?:[+-]?\d{{1,3}}\s*°\s*{NUM}\s*\x27\s*{NUM}\s*"|[+-]?\d{{1,3}}\s*°\s*{NUM}\s*\x27|[+-]?\d{{1,3}}[.,]\d{{1,10}}\s*°?|[+-]?\d{{1,3}}\s*°)'
TOKEN=re.compile(rf'(?<![\w.])(?:(?P<label>latitude|longitude|lat|long|lon|vĩ\s*độ|kinh\s*độ)\s*[:=]?\s*)?(?:(?P<pre>[NSEW])\s*[:=]?\s*)?(?P<angle>{ANGLE})\s*(?P<post>[NSEW])?(?![\d.%])',re.I)
def coordinate(match):
 label=(match['label'] or '').lower();pre=(match['pre'] or '').upper();post=(match['post'] or '').upper()
 if pre and post and pre!=post:return None
 direction=post or pre;axis='lat' if label.startswith('lat') or label.startswith('vĩ') else 'lon' if label else ''
 if direction:
  expected='lat' if direction in 'NS' else 'lon'
  if axis and axis!=expected:return None
  axis=expected
 angle=match['angle'].strip();parts=[float(n.replace(',','.')) for n in re.findall(r'[+-]?\d+(?:[.,]\d+)?',angle)]
 if len(parts)>1 and (not 0<=parts[1]<60 or len(parts)>2 and not 0<=parts[2]<60):return None
 value=abs(parts[0])+(parts[1]/60 if len(parts)>1 else 0)+(parts[2]/3600 if len(parts)>2 else 0)
 negative=angle.startswith('-')
 if negative and direction in ['N','E']:return None
 if negative or direction in ['S','W']:value=-value
 # Naked decimal pairs need enough precision to avoid prices and ordinary counts.
 if not axis and '°' not in angle and not re.search(r'[.,]\d{3,}',angle):return None
 return value,axis
def candidates(text):
 text=text.replace('−','-').replace('º','°').replace('˚','°').replace('′',"'").replace('’',"'").replace('″','"').replace('“','"').replace('”','"')
 tokens=list(TOKEN.finditer(text));out=[]
 for first,second in zip(tokens,tokens[1:]):
  if not re.fullmatch(r'[\s,;|/()]*',text[first.end():second.start()]):continue
  a=coordinate(first);b=coordinate(second)
  if not a or not b:continue
  if a[1] and a[1]==b[1]:continue
  if a[1]=='lon' or b[1]=='lat':lat,lon=b[0],a[0]
  else:lat,lon=a[0],b[0]
  pair=[round(lat,8),round(lon,8)]
  if abs(lat)<=90 and abs(lon)<=180 and pair not in out:out.append(pair)
 return out
