// Monthly summaries use only report records already authorized by the server.
globalThis.HPSummary=(()=>{
const E=globalThis.HPExports,esc=E.xml;
function date(value){const s=String(value||'').trim();let m=s.match(/^(\d{2})\/(\d{2})\/(\d{4})(?:[ T](\d{1,2}):(\d{2}))?$/);if(m){const y=+m[3],mo=+m[2],d=+m[1],h=+(m[4]||0),mi=+(m[5]||0),t=new Date(Date.UTC(y,mo-1,d,h,mi));return t.getUTCFullYear()===y&&t.getUTCMonth()===mo-1&&t.getUTCDate()===d&&h<24&&mi<60?t:null;}m=s.match(/^(\d{4})-(\d{2})-(\d{2})(?:T.*)?$/);if(m)return date(`${m[3]}/${m[2]}/${m[1]}`);return null;}
function when(x){if(x.payload.module==='incident'&&Object.hasOwn(x.summary?.cells||{},'11')){const v=x.summary.cells['11'];return typeof v==='number'?new Date((v-25569)*86400000):null;}const r=x.payload.report||{};return date(x.payload.module==='relocation'?r.date:r.received)||date(r.date)||date(r.reportDate);}
function month(x){const d=when(x);return d?d.toISOString().slice(0,7):'';}
function province(r){if(r.province?.trim())return r.province.trim();const f=r.locationFix;if(!f)return '';if(f.region||f.province)return String(f.region||f.province).trim();const bits=String(r.location||'').split('\n')[0].split(',').map(s=>s.trim()).filter(s=>s&&!/^(việt nam|vietnam|vn)$/i.test(s));const last=bits.at(-1)||'';return bits.length>1&&!/\d|lat|long/i.test(last)?last.replace(/^(tỉnh|thành phố|tp\.)\s+/i,''):'';}
function n(v){return v!==null&&v!==undefined&&String(v).trim()!==''&&/^\d+(?:[.,]\d+)?$/.test(String(v).trim())?Number(String(v).replace(',','.')):null;}
function material(r,name,type){const found=(r.materials||[]).filter(m=>String(m.name).toLocaleLowerCase('vi')===name.toLocaleLowerCase('vi')&&(!type||String(m.type).toUpperCase()===type)).map(m=>n(m.quantity)).filter(x=>x!==null);return found.length?found.reduce((a,b)=>a+b,0):null;}
// Cabinet/ODF welding entries share the same summary columns.
function odfWelding(r,type){
 const normal=v=>String(v||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/đ/gi,'d').toLowerCase().replace(/\s+/g,' ').trim();
 const quantities=(r.materials||[]).filter(m=>{
  const label=normal(m.name==='Vật tư khác'?m.type:m.name);
  if(!/^han(?: noi)? (?:tu(?: odf)?|odf)(?:\s|$)/.test(label))return false;
  const sizes=[...normal(label+' '+m.type).matchAll(/\b(96|48|24|12|8)\s*fo\b/g)].map(x=>x[1]+'FO');
  return new Set(sizes).size===1&&sizes[0]===type;
 }).map(m=>n(m.quantity)).filter(v=>v!==null);
 if(quantities.length)return quantities.reduce((a,b)=>a+b,0);
 // Fall back to explicitly counted welding work in the plan; do not double-count materials.
 const counts=[];
 for(const clause of normal(r.solution).split(/[;,\n]+/)){
  if(/\b(?:khong|chua)\s+(?:can\s+)?han\b/.test(clause))continue;
  const pattern=/\bhan(?:\s+(?:lai|noi))?\s+(?:(\d+)\s+(?:tu(?:\s+cap|\s+odf)?|odf)|(?:tu(?:\s+cap|\s+odf)?|odf)\s*[:x]?\s*(\d+)(?!\d|\s*fo))/g;
  const matches=[...clause.matchAll(pattern)];
  if(!matches.length)continue;
  const sizes=[...clause.matchAll(/\b(96|48|24|12|8)\s*fo\b/g)].map(m=>m[1]+'FO');
  const selected=sizes.length?(new Set(sizes).size===1?sizes[0]:''):String(r.cable||'').replace(/\s+/g,'').toUpperCase();
  if(selected===type)counts.push(...matches.map(m=>Number(m[1]||m[2])));
 }
 return counts.length?counts.reduce((a,b)=>a+b,0):null;
}
function coords(r){const f=r.locationFix||(r.groups||[]).filter(g=>/trước di dời|hiện trường/i.test(g.title||g.name||'')).flatMap(g=>g.images||[]).find(p=>p.gps)?.gps||{};return [typeof f.lat==='number'?f.lat:null,typeof (f.lon??f.long)==='number'?(f.lon??f.long):null];}
function totalLength(...values){const nums=values.filter(v=>v!==null&&Number.isFinite(v));return nums.length?Number(nums.reduce((a,b)=>a+b,0).toFixed(6)):null;}
// Older reports may store the recovered length only in the work description.
function recoveryFromPlan(value){
 const text=plain(value),amounts=[];
 for(const clause of text.split(/[;\n]+/)){
  if(/\b(?:khong|chua)\s+thu hoi/.test(clause))continue;
  for(const match of clause.matchAll(/\bthu hoi\s+(?:cap\s+)?(?:keo lai\s*)?(\d+(?:[.,]\d+)?)\s*m(?:et)?\b/g)){
   if(/keo lai/.test(match[0])||/keo lai/.test(clause.slice(match.index+match[0].length)))amounts.push(n(match[1]));
  }
 }
 return amounts.length?totalLength(...amounts):null;
}
function model(x){const p=x.payload,r={...E.emptyIncident(),...p.report},relo=p.module==='relocation';r.relocation=relo;r.materials=(r.materials||[]).map(m=>m.name==='Thẻ tên'?{...m,name:'Biển báo'}:m);const oldReuse=relo&&r.recovery?.enabled&&!r.splitRelocation;const cable=oldReuse?null:E.cableLength(r);const recordedRecovery=r.recovery?.enabled?n(oldReuse?r.relocationLength:r.recovery.length):null;const recovery=recordedRecovery??recoveryFromPlan(r.solution),pull=totalLength(cable,recovery);return {p,r,relo,cable,recovery,pull,solution:E.solutionText(r),province:province(r),author:x.author||r.reporter||'',team:x.team||r.team||''};}
const incidentHeaders=["STT","Tuyến","Tỉnh/Thành","Chiều dài (km)","Ưu tiên","Link kết nối","Đầu mối","Vị trí đứt","Nguyên nhân sự cố","Phương án khắc phục","Nghiệm thu","Thời gian bắt đầu sự cố","Thời gian kết thức sự cố","Số phút","Kéo cáp quang (m)","Hàn nối măng xông (core hàn)","Lắp đặt biển báo (cái)","Lắp đặt bộ treo (bộ)","Lắp đặt bộ néo (bộ)","Lắp đặt bộ gá (1 ốp D12, 2 đai , 2 khoá inox) ","Lắp đặt Kẹp cáp 2 rãnh 3 lỗ (bộ)","Lắp đặt Phụ kiện ốp cột - 1 đế U, 2 dây đai, 2 khóa đai  (bộ)","Lắp đặt bộ đai ốp ( Móc J, đai, khoá đai) treo MX, cáp dự phòng","Hàn nối ODF 96FO","Hàn nối ODF 48FO","Hàn nối ODF 24FO","Hàn nối ODF 12FO","Hàn nối ODF 8FO","Chủng loại cáp quang","Khoảng vượt cáp quang","Khối lượng cáp quang (m)","Chủng loại măng xông","Khối lượng măng xông (bộ)","Vị trí Măng xông 1","Vị trí Măng xông 2","Thuộc HĐ VHKT","Đơn vị VHKT","Note  nội dung chú ý"];
const relocationHeaders=['STT','Tuyến','Tỉnh/Thành','Ngày lập báo cáo','Ngày thực hiện','Người lập','Team / Đội','Nguyên nhân di dời','Phương án','Chủng loại cáp','Đầu số 1','Đầu số 2','Kéo cáp quang (m)','Thu hồi kéo lại (m)','Măng xông (bộ)','Néo (bộ)','Treo (bộ)','Ty','Kẹp','Gông dự phòng','D12','Đai inox','Biển báo','Vật tư khác','Lat','Long','Khoảng vượt'];
const serial=d=>d?d.getTime()/86400000+25569:null;
function originalValues(x,i){const {p,r,relo,cable,recovery,pull,solution,province,author,team}=model(x),gps=coords(r),others=(r.materials||[]).filter(m=>!(relo?['Măng xông','Néo','Treo','Ty','Kẹp','Gông dự phòng','D12','Đai Inox','Biển báo']:['Măng xông','Néo','Treo']).some(name=>name.toLocaleLowerCase('vi')===String(m.name).trim().toLocaleLowerCase('vi'))).map(m=>[m.quantity,m.unit,m.name,m.type,m.note].filter(Boolean).join(' ')).join('; ');if(relo)return [i,p.name,province,serial(date(r.reportDate)),serial(date(r.date)),author,team,r.cause,solution,r.commonRelocationCable||r.cable,n(r.start),n(r.end),pull,recovery,material(r,'Măng xông'),material(r,'Néo'),material(r,'Treo'),...['Ty','Kẹp','Gông dự phòng','D12','Đai Inox','Biển báo'].map(name=>material(r,name)),others,...gps,r.cableSpan||''];return [i,p.name,province,n(r.routeLength),r.priority||'',r.connectionLink||'',r.contact||'',String(r.location||'').trim(),r.cause,solution,r.acceptance||'',serial(date(r.received)),serial(date(r.restored)),null,pull,n(r.spliceCores),material(r,'Biển báo'),material(r,'Treo'),material(r,'Néo'),material(r,'Bộ gá'),material(r,'Kẹp'),material(r,'Phụ kiện ốp cột'),material(r,'Bộ đai ốp'),...['96FO','48FO','24FO','12FO','8FO'].map(t=>odfWelding(r,t)),r.cable,r.cableSpan||'',cable,[...new Set((r.materials||[]).filter(m=>m.name==='Măng xông').map(m=>m.type).filter(Boolean))].join(', '),material(r,'Măng xông'),r.closureLocation||'',r.closureLocation2||'',r.maintenanceContract||'',r.maintenanceUnit||'',r.summaryNote||''];}
let catalog=[];
const routeKey=name=>String(name||'').normalize('NFC').trim().toUpperCase().replace(/[–—−]/g,'-').replace(/\s+/g,'');
function setCatalog(rows){catalog=rows||[];}
function routeInfo(name){const key=routeKey(name);let rows=catalog.filter(r=>routeKey(r.name)===key);if(!rows.length&&/^[A-Z0-9.]+-[A-Z0-9.]+$/.test(key)){const reversed=key.split('-').reverse().join('-');rows=catalog.filter(r=>routeKey(r.name)===reversed);}const unique=k=>[...new Set(rows.map(r=>r[k]).filter(v=>v!==null&&v!==''))];const lengths=unique('length'),links=unique('link');return {length:lengths.length===1?lengths[0]:null,link:links.length===1?links[0]:'',ambiguous:lengths.length>1||links.length>1};}
function plain(v){return String(v??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/đ/gi,'d').toLowerCase().replace(/\s+/g,' ').trim();}
function coordNumber(v,limit){const n=typeof v==='number'?v:typeof v==='string'&&/^[+-]?\d+(?:[.,]\d+)?$/.test(v.trim())?Number(v.replace(',','.')):NaN;return Number.isFinite(n)&&Math.abs(n)<=limit?n:null;}
function coordText(text){text=String(text||'').replace('−','-').replace('º','°').replace('˚','°').replace('′',"'").replace('’',"'").replace('″','"').replace('“','"').replace('”','"');let m=text.match(/Lat(?:itude)?\s*[:=]?\s*([+-]?\d{1,2}(?:[.,]\d+)?)\s*(?:[|;,/\s]+|\s+)Long?(?:itude)?\s*[:=]?\s*([+-]?\d{2,3}(?:[.,]\d+)?)/i)||text.match(/([+-]?\d{1,2}[.,]\d{3,})\s*[,;|/\s]+\s*([+-]?\d{2,3}[.,]\d{3,})/);if(!m)return null;const lat=coordNumber(m[1],90),lon=coordNumber(m[2],180);return lat===null||lon===null?null:[lat,lon];}
function photoCoord(photo){const gps=photo?.gps||{};let lat=coordNumber(gps.lat??photo?.lat,90),lon=coordNumber(gps.lon??gps.long??photo?.lon??photo?.long,180);if(lat!==null&&lon!==null)return `${lat.toFixed(6)}, ${lon.toFixed(6)}`;const parsed=coordText([photo?.caption,photo?.note,photo?.description].filter(Boolean).join('\n'));return parsed?`${parsed[0].toFixed(6)}, ${parsed[1].toFixed(6)}`:'';}
function photoEntries(report,scene=false){
 const entries=[];
 for(const g of report?.groups||[]){
  const name=plain(g.name||g.title),dedicated=/\b(mx|mang xong|mangxong)\b/.test(name)&&!/treo|neo|vat tu/.test(name);
  if(scene&&!/hien truong|su co/.test(name))continue;
  const photos=g.images||[];
  photos.forEach((photo,index)=>{
   const pair=g.pairNotes?.[Math.floor(index/2)]||'',own=plain([photo.caption,photo.note,photo.description,photo.name,photo.title].filter(Boolean).join(' '));
   const note=plain(pair||g.note||'');
   const mx=/\b(mx|mx\d+|mang xong|mangxong)\b/;
   const support=/\b(treo|neo)\b/;
   const isMx=dedicated||mx.test(own)||(!support.test(own)&&mx.test(note));
   if(!scene&&!isMx) return;
   let coordinate=photoCoord(photo);
   if(!coordinate){
    const candidates=String(pair||g.note||'').split(/\r?\n/).map(coordText).filter(Boolean);
    // A two-image row with one coordinate is ambiguous; never duplicate it into both MX columns.
    const count=Math.min(2,photos.length-Math.floor(index/2)*2);
    const point=candidates.length===count?candidates[index%2]:null;
    if(point)coordinate=`${point[0].toFixed(6)}, ${point[1].toFixed(6)}`;
   }
   entries.push({photo,coordinate});
  });
 }
 return entries;
}
function photoLocationsByKind(report,kind='mx',scene=false){return photoEntries(report,scene).map(e=>e.coordinate).filter(Boolean);}
function mxPhotoLocations(report,scene=false){return photoLocationsByKind(report,'mx',scene).slice(0,2);}function values(x,i){const v=originalValues(x,i);if(x.payload.module==='incident'){v[2]=String(x.team||x.payload.report?.team||'').trim();const mx=mxPhotoLocations(x.payload.report);if(!v[33])v[33]=mx[0]||'';if(!v[34])v[34]=mx[1]||'';const info=routeInfo(x.payload.name);if(v[3]===null)v[3]=info.length;if(!v[5])v[5]=info.link;}if(x.payload.module==='incident')for(const [k,n]of Object.entries(x.summary?.cells||{})){if(+k>=0&&+k<38&&!([7,33,34].includes(+k)&&(n===null||n==='')))v[+k]=n;}if(x.payload.module==='incident'&&!Object.hasOwn(x.summary?.cells||{},'15')&&n(x.payload.report?.spliceCores)===null){const type=String(v[28]||'').trim().match(/^(2|4|12|24|48|96)\s*FO$/i),quantity=n(v[32]);v[15]=type&&quantity!==null&&Number.isInteger(quantity)?Number(type[1])*quantity:null;}return v;}
const materialHeaders=['STT','Vật tư','Chủng loại','Đơn vị','Tổng số lượng'];
function materialRows(records){
 const groups=new Map(),clean=v=>String(v??'').normalize('NFC').trim().replace(/\s+/g,' '),type=v=>clean(v).replace(/(\d+)\s*fo\b/ig,'$1FO');
 function add(name,kind,unit,quantity){name=clean(name);kind=type(kind);unit=clean(unit);if(!name||quantity===null||!Number.isFinite(quantity)||quantity<=0)return;const key=JSON.stringify([name,kind,unit].map(v=>v.toLocaleLowerCase('vi')));const old=groups.get(key);if(old)old[3]=Number((old[3]+quantity).toFixed(6));else groups.set(key,[name,kind,unit,quantity]);}
 for(const x of records){const {r,relo,cable,recovery}=model(x);add('Cáp kéo mới',relo?(r.commonRelocationCable||r.cable):r.cable,'m',cable);for(const m of r.materials||[])add(m.name,m.type,m.unit,n(m.quantity));}
 return [...groups.values()].sort((a,b)=>a[0].localeCompare(b[0],'vi')||a[1].localeCompare(b[1],'vi',{numeric:true})||a[2].localeCompare(b[2],'vi')).map((r,i)=>[i+1,...r]);
}
function col(i){let s='';for(i++;i;i=Math.floor((i-1)/26))s=String.fromCharCode(65+(i-1)%26)+s;return s;}
function xlsx(records,ym){if(!/^\d{4}-\d{2}$/.test(ym))throw Error('Chọn tháng tổng hợp.');const ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main',rel='http://schemas.openxmlformats.org/officeDocument/2006/relationships',pkg='http://schemas.openxmlformats.org/package/2006/relationships',files={},put=(p,s)=>files[p]=fflate.strToU8(s);const groups=['incident','relocation'].map(type=>({type,rows:records.filter(x=>x.payload.module===type&&month(x)===ym).sort((a,b)=>when(a)-when(b)||String(a.payload.name).localeCompare(String(b.payload.name),'vi'))})).filter(g=>g.rows.length);if(!groups.length)throw Error('Không có báo cáo trong tháng đã chọn.');
const cell=(c,r,v,style=0,formula='')=>`<c r="${c}${r}" s="${style}"${typeof v==='number'||formula?'':' t="inlineStr"'}>${formula?`<f>${esc(formula)}</f>`:typeof v==='number'?`<v>${v}</v>`:`<is><t xml:space="preserve">${esc(v??'')}</t></is>`}</c>`;
groups.forEach(({type,rows},index)=>{const re=type==='relocation',heads=re?relocationHeaders:incidentHeaders,end=col(heads.length-1),title=`Tổng hợp ${re?'Di dời':'Sự cố'} tháng ${+ym.slice(5)}/${ym.slice(0,4)}`,dates=re?[3,4]:[11,12];let data=`<row r="1" ht="32" customHeight="1">${cell('A',1,title,1)}</row><row r="2" ht="26" customHeight="1">${re?cell('A',2,'Thông tin chưa có để trống. Tỉnh/Thành lấy từ địa chỉ định vị đã lưu; chưa xác định thì bổ sung trong báo cáo.'):heads.map((_,j)=>cell(col(j),2,j===14?'Phụ kiện cáp quang':j===28?'Vật tư':'',1)).join('')}</row><row r="3" ht="96" customHeight="1">${heads.map((h,i)=>cell(col(i),3,h,1)).join('')}</row>`;
rows.forEach((x,i)=>{const r=i+4,v=values(x,i+1);data+=`<row r="${r}" ht="55" customHeight="1">`+v.map((v,j)=>cell(col(j),r,v,dates.includes(j)?(re?2:3):0,!re&&j===13&&!Object.hasOwn(x.summary?.cells||{},'13')?`IF(OR(L${r}="",M${r}="",M${r}<L${r}),"",ROUND((M${r}-L${r})*1440,0))`:'')).join('')+'</row>';});const total=rows.length+4,sum=re?[12,13,14,15,16,17,18,19,20,21,22]:[13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,30,32];data+=`<row r="${total}" ht="26" customHeight="1">`+heads.map((_,j)=>cell(col(j),total,j===0?'Tổng':'',4,sum.includes(j)?`IF(COUNT(${col(j)}4:${col(j)}${total-1})=0,"",SUM(${col(j)}4:${col(j)}${total-1}))`:'')).join('')+'</row>';
put(`xl/worksheets/sheet${index+1}.xml`,`<worksheet xmlns="${ns}"><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="3" topLeftCell="A4" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${heads.map((_,i)=>`<col min="${i+1}" max="${i+1}" width="${i===0?7:i===1?25:dates.includes(i)?24:[8,9,10,32,34].includes(i)?30:20}" customWidth="1"/>`).join('')}</cols><sheetData>${data}</sheetData><autoFilter ref="A3:${end}${total-1}"/><mergeCells count="${re?2:3}"><mergeCell ref="A1:${end}1"/>${re?`<mergeCell ref="A2:${end}2"/>`:`<mergeCell ref="O2:W2"/><mergeCell ref="AC2:AG2"/>`}</mergeCells></worksheet>`);});
const sheetNames=groups.map(g=>`T${+ym.slice(5)} - ${g.type==='incident'?'Sự cố':'Di dời'}`);
groups.forEach((g,i)=>{const index=groups.length+i+1,data=materialRows(g.rows),name=groups.length===1?'Tổng hợp vật tư':`Vật tư - ${g.type==='incident'?'Sự cố':'Di dời'}`;sheetNames.push(name);const sheetRows=`<row r="1" ht="32" customHeight="1">${cell('A',1,`Tổng hợp vật tư ${g.type==='incident'?'Sự cố':'Di dời'} tháng ${+ym.slice(5)}/${ym.slice(0,4)}`,1)}</row><row r="2" ht="40" customHeight="1">${cell('A',2,'Sheet chính: Kéo cáp quang đã cộng cáp mới và thu hồi kéo lại. Sheet vật tư chỉ tính cáp kéo mới; không đưa cáp thu hồi kéo lại vào vật tư.')}</row><row r="3" ht="30" customHeight="1">${materialHeaders.map((h,j)=>cell(col(j),3,h,1)).join('')}</row>`+(data.length?data.map((r,j)=>`<row r="${j+4}" ht="28" customHeight="1">${r.map((v,k)=>cell(col(k),j+4,v)).join('')}</row>`).join(''):`<row r="4">${cell('B',4,'Chưa có vật tư được ghi nhận.')}</row>`);put(`xl/worksheets/sheet${index}.xml`,`<worksheet xmlns="${ns}"><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="3" topLeftCell="A4" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${[8,32,24,16,22].map((width,j)=>`<col min="${j+1}" max="${j+1}" width="${width}" customWidth="1"/>`).join('')}</cols><sheetData>${sheetRows}</sheetData>${data.length?`<autoFilter ref="A3:E${data.length+3}"/>`:''}<mergeCells count="2"><mergeCell ref="A1:E1"/><mergeCell ref="A2:E2"/></mergeCells></worksheet>`);});
put('xl/styles.xml',`<styleSheet xmlns="${ns}"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF255D91"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border>${['left','right','top','bottom'].map(x=>`<${x} style="thin"><color rgb="FF000000"/></${x}>`).join('')}</border></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5">${[0,0,14,22,0].map((num,i)=>`<xf numFmtId="${num}" fontId="${i===1||i===4?1:0}" fillId="${i===1||i===4?2:0}" borderId="0" xfId="0" applyBorder="1" applyFont="1" applyFill="1" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>`).join('')}</cellXfs></styleSheet>`);
put('xl/workbook.xml',`<workbook xmlns="${ns}" xmlns:r="${rel}"><sheets>${sheetNames.map((name,i)=>`<sheet name="${name}" sheetId="${i+1}" r:id="rId${i+1}"/>`).join('')}</sheets><calcPr fullCalcOnLoad="1"/></workbook>`);put('_rels/.rels',`<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`);put('xl/_rels/workbook.xml.rels',`<Relationships xmlns="${pkg}">${sheetNames.map((_,i)=>`<Relationship Id="rId${i+1}" Type="${rel}/worksheet" Target="worksheets/sheet${i+1}.xml"/>`).join('')}<Relationship Id="styles" Type="${rel}/styles" Target="styles.xml"/></Relationships>`);put('[Content_Types].xml',`<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheetNames.map((_,i)=>`<Override PartName="/xl/worksheets/sheet${i+1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`);return fflate.zipSync(files);}
return {mxPhotoLocations,setCatalog,routeInfo,date,month,when,province,model,values,xlsx,incidentHeaders,relocationHeaders,materialHeaders,materialRows};})();

