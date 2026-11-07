import {session as accountSession} from './serverSession';
import { unzipSync, zipSync, strToU8 } from 'fflate';
// @ts-ignore Native Node tests need the extension; Metro supports it too.
import { xlsx, xml } from './reports.ts';
export const cableTypes = ['2FO','4FO','6FO','8FO','12FO','24FO','48FO','96FO'];
export const materialNames = ['Măng xông','Néo','Treo','Ty','Kẹp','Gông dự phòng','D12','Đai Inox','Biển báo','Vật tư khác'];
export const groupNames = ['Hiện trường sự cố','Đầu số cáp','Chủng loại cáp','Măng xông / treo néo','Kết quả đo kiểm','Trong thi công'];
export const relocationGroups=['Trước di dời','Trong thi công','Sau hoàn thành'];
// Keep original indices for photo actions and pending captures; only change presentation.
export function orderedPhotoGroups(groups:Incident['groups']) {
  let entries=groups.map((g,i)=>({g,i}));
  const construction=entries.findIndex(({g})=>g.name==='Trong thi công');
  if(construction>=0&&entries.some(({g})=>g.name==='Kết quả đo kiểm')){
    const [item]=entries.splice(construction,1);
    entries.splice(entries.findIndex(({g})=>g.name==='Kết quả đo kiểm'),0,item);
  }
  const normal=(name:string)=>name.normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/đ/gi,'d').toLowerCase().trim();
  const isMx=({g}:{g:Incident['groups'][number]})=>/\b(mx\d*|mang xong)\b/.test(normal(g.name))&&!/treo|neo|vat tu/.test(normal(g.name));
  if(entries.some(({g})=>/\bdau so cap\b/.test(normal(g.name)))){
    const mx=entries.filter(isMx);
    entries=entries.filter(e=>!isMx(e));
    entries.splice(entries.findIndex(({g})=>/\bdau so cap\b/.test(normal(g.name)))+1,0,...mx);
  }
  return entries;
}
export const isMaterialGroup=(g:{name:string})=>g.name==='Măng xông / treo néo'||relocationGroups.includes(g.name);
export function imagePairs<T>(items:T[]):T[][] {return Array.from({length:Math.ceil(items.length/2)},(_,i)=>items.slice(i*2,i*2+2));}
export type ReportImage = {file:string; width:number; height:number; caption:string; date?:string; imageDate?:string; displayDate?:string; textColor?:string};
export function appendReportPhoto(group:Incident['groups'][number],photo:ReportImage,note='') {
 const images=[...group.images,photo];
 if(!note.trim())return {...group,images};
 const pairIndex=Math.floor(group.images.length/2);
 const pairNotes=imagePairs(images).map((_,i)=>group.pairNotes?.[i]??group.note);
 pairNotes[pairIndex]=[...new Set([pairNotes[pairIndex],note].flatMap(s=>s.split('\n')).map(s=>s.trim()).filter(Boolean))].join('\n');
 return {...group,images,pairNotes};
}
export type Incident = {commonRelocationCable?:string;splitRelocation?:boolean;newCableEnabled?:boolean;privateGroups?:string[];relocation?:boolean;relocationLength?:string;completed?:boolean;pendingCapture?:{showDate?:boolean;textColor?:string;file:string;group:number;date:string;width:number;height:number;note?:string};locationFix?:{lat:number;lon:number;accuracy:number|null;timestamp:number};recovery?:{enabled:boolean; length:string; cable:string};location:string; distance:string; reporter:string; date:string; received:string; restored:string; cause:string; solution:string; cable:string; start:string; end:string; materials:{name:string; type:string; quantity:string; unit:string; note:string}[]; groups:{name:string; note:string; pairNotes?:string[]; images:ReportImage[]}[]};
export function emptyIncident():Incident {return {location:'',distance:'',reporter:'',date:new Date().toLocaleDateString('en-GB'),received:'',restored:'',cause:'',solution:'',cable:'',start:'',end:'',materials:[],groups:groupNames.map(name=>({name,note:'',images:[]}))};}
export function emptyRelocation():Incident {return {...emptyIncident(),relocation:true,relocationLength:'',recovery:{enabled:true,length:'',cable:''},groups:relocationGroups.map(name=>({name,note:'',images:[]}))};}
export function separateRelocationCable(r:Incident):Incident {
 if(!r.relocation||r.splitRelocation)return r;
 const reuse=!!r.recovery?.enabled;
 return {...r,splitRelocation:true,newCableEnabled:!reuse&&!!(r.cable||r.start||r.end||r.relocationLength),
 recovery:{enabled:reuse,length:reuse?(r.relocationLength??''):(r.recovery?.length??''),cable:reuse?r.cable:(r.recovery?.cable??'')},
 cable:reuse?'':r.cable,start:reuse?'':r.start,end:reuse?'':r.end,relocationLength:reuse?'':r.relocationLength};
}
export function commonRelocationCable(r:Incident):Incident {
 const v=separateRelocationCable({...r,materials:r.materials.map(m=>m.name==='Thẻ tên'?{...m,name:'Biển báo'}:m)});
 if(!v.relocation||v.commonRelocationCable!==undefined)return v;
 const fresh=v.cable.trim(),reuse=v.recovery?.cable.trim()??'';
 const cable=fresh&&reuse&&fresh!==reuse?'':fresh||reuse;
 return {...v,commonRelocationCable:cable};
}
export function numberValue(s:string):number|null {return /^\d+(?:[.,]\d+)?$/.test(s.trim()) && Number.isFinite(Number(s.replace(',','.'))) ? Number(s.replace(',','.')) : null;}
export function cableLength(r:Incident) {if(r.relocation&&r.splitRelocation){if(!r.newCableEnabled)return null;const a=numberValue(r.start),b=numberValue(r.end);if(!r.start.trim()&&!r.end.trim())return numberValue(r.relocationLength??'');return a===null||b===null?null:Math.abs(a-b);}if(r.relocation&&(r.recovery?.enabled||!r.start.trim()&&!r.end.trim()))return numberValue(r.relocationLength??'');const a=numberValue(r.start), b=numberValue(r.end);return a===null||b===null?null:Math.abs(a-b);}
export function recoveryText(r:Incident) {
  if(!r.recovery?.enabled)return '';
  const n=numberValue(r.relocation&&!r.splitRelocation?(r.relocationLength??''):r.recovery.length);
  const cable=(r.relocation?(r.commonRelocationCable??(!r.splitRelocation?r.cable:r.recovery.cable)):r.recovery.cable).trim();
  return 'Thu hồi kéo lại'+(n!==null&&n>0?' '+String(n).replace('.',',')+'m':'')+(cable?' '+cable:'');
}
export function solutionText(r:Incident) {return [recoveryText(r),r.solution.trim()].filter(Boolean).join(', ');}
export function materialSummary(r:Incident) {
  if(r.recovery?.enabled&&!r.relocation)return 'Không sử dụng cáp mới và vật tư';
  const parts:string[]=[],length=cableLength(r);
  const num=(n:number)=>String(Number(n.toFixed(6))).replace('.',',');
  const cable=(r.relocation?r.commonRelocationCable??r.cable:r.cable).trim();
  if(length!==null&&cable)parts.push(`${num(length)}m cáp ${cable}${r.relocation&&!r.splitRelocation&&r.recovery?.enabled?' (thu hồi, kéo lại)':''}`);
  for(const m of r.materials){
    const quantity=numberValue(m.quantity);if(quantity===null||quantity<=0||!m.name.trim())continue;
    const name=m.name==='Vật tư khác'?m.type.trim():(m.name==='Thẻ tên'?'Biển báo':m.name).toLocaleLowerCase('vi-VN')+(m.type.trim()?' '+m.type.trim():'');
    if(!name)continue;
    const unit=m.unit.trim();
    parts.push(`${num(quantity)}${unit&&!['cái','bộ'].includes(unit.toLocaleLowerCase('vi-VN'))?' '+unit:''} ${name}`);
  }
  return parts.join(', ');
}
export function validDate(value:string,time=false) {const m=value.trim().match(time?/^(\d{1,2})\/(\d{1,2})\/(\d{4}) (\d{1,2}):(\d{2})$/:/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);if(!m)return false;const d=new Date(+m[3],+m[2]-1,+m[1]);return d.getFullYear()===+m[3]&&d.getMonth()===+m[2]-1&&d.getDate()===+m[1]&&(!time||(+m[4]<24&&+m[5]<60));}
export function handlingMinutes(received:string,restored:string):number|null {
 if(!validDate(received,true)||!validDate(restored,true))return null;
 const stamp=(value:string)=>{const [d,m,y,h,min]=value.trim().split(/[ /:]/).map(Number);return Date.UTC(y,m-1,d,h,min);};
 const minutes=(stamp(restored)-stamp(received))/60000;return minutes>=0?minutes:null;
}
export function reportWarnings(r:Incident) {
  const warnings:string[]=[];
  if(r.relocation){
    for(const [key,label]of [['location','Địa điểm'],['reporter','Người thực hiện'],['date','Ngày thực hiện'],['cause','Nguyên nhân di dời'],['solution','Nội dung di dời']] as const)if(!r[key].trim())warnings.push('Thiếu '+label.toLowerCase());
    if(r.date&&!validDate(r.date))warnings.push('Ngày thực hiện chưa hợp lệ');
    if(r.splitRelocation&&r.recovery?.enabled){if(!cableTypes.includes(r.commonRelocationCable??r.recovery.cable))warnings.push('Chưa chọn loại cáp thu hồi');if((numberValue(r.recovery.length)??0)<=0)warnings.push('Số mét thu hồi chưa hợp lệ');}
    if((!r.splitRelocation||r.newCableEnabled)&&(r.cable||r.relocationLength||r.start||r.end||r.newCableEnabled)){if(!cableTypes.includes(r.commonRelocationCable??r.cable))warnings.push('Chưa chọn loại cáp');if((cableLength(r)??0)<=0)warnings.push('Chiều dài di dời chưa hợp lệ');}
    r.materials.forEach((m,i)=>{if(!m.name.trim()||!m.unit.trim()||(numberValue(m.quantity)??0)<=0||m.name==='Măng xông'&&!cableTypes.includes(m.type)||m.name==='Vật tư khác'&&!m.type.trim())warnings.push(`Vật tư ${i+1}: cần bổ sung thông tin`);});
    r.groups.forEach(g=>{if(!g.images.length)warnings.push(g.name+': chưa có ảnh');});
    if(r.pendingCapture)warnings.push('Có ảnh đang chờ GPS');return warnings;
  }
  if(r.pendingCapture)warnings.push('Có ảnh đang chờ ghi GPS, chưa đưa vào báo cáo');
  for(const [k,label] of Object.entries({location:'Địa điểm',reporter:'Người báo cáo',date:'Ngày báo cáo',received:'Thời gian nhận',restored:'Thời gian khôi phục',cause:'Nguyên nhân',solution:'Phương án xử lý',cable:'Chủng loại cáp'})) if(!(r.recovery?.enabled&&['solution','cable'].includes(k))&&!String(r[k as keyof Incident]).trim())warnings.push('Thiếu '+label.toLowerCase());
  if(!r.recovery?.enabled&&cableLength(r)===null)warnings.push('Chưa có hai đầu số cáp hợp lệ');
  if(!r.recovery?.enabled&&r.cable&&!cableTypes.includes(r.cable))warnings.push('Chủng loại cáp chưa hợp lệ');
  if(r.date&&!validDate(r.date))warnings.push('Ngày báo cáo chưa đúng ngày/tháng/năm');
  for(const key of ['received','restored'] as const)if(r[key]&&!validDate(r[key],true))warnings.push((key==='received'?'Thời gian nhận':'Thời gian khôi phục')+': cần ngày/tháng/năm giờ:phút');
  if(r.distance&&numberValue(r.distance)===null)warnings.push('Khoảng cách chưa hợp lệ');
  if(r.recovery?.enabled){
    if(!cableTypes.includes(r.recovery.cable))warnings.push('Chưa chọn chủng loại cáp thu hồi');
    const length=numberValue(r.recovery.length);if(length===null||length<=0)warnings.push('Chưa có số mét cáp thu hồi hợp lệ');
  }
  if(!r.recovery?.enabled)r.materials.forEach((m,i)=>{if(!m.name.trim()||!m.unit.trim()||numberValue(m.quantity)===null||numberValue(m.quantity)===0||(m.name==='Măng xông'&&!cableTypes.includes(m.type))||(m.name==='Vật tư khác'&&!m.type.trim()))warnings.push(`Vật tư ${i+1}: thiếu tên, loại, đơn vị hoặc số lượng hợp lệ`);});
  r.groups.forEach(g=>{if(!g.images.length)warnings.push(g.name+': chưa có ảnh');});
  return warnings;
}
// Compact report uses the same proven OOXML packaging as the existing photo export.
export function incidentXlsx(name:string,r:Incident,images:Record<string,Uint8Array>,warnings=reportWarnings(r)) {
  r={...r,reporter:accountSession.user?.display_name??r.reporter};
  const files:Record<string,Uint8Array>=unzipSync(xlsx(name,[],{}));
  const ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main',rel='http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const rows:string[]=[],merges:string[]=[],anchors:string[]=[],rels:string[]=[];let row=0,imageIndex=0;
  const cell=(col:string,n:number,v:unknown,style=4)=>typeof v==='number'?`<c r="${col}${n}" s="${style}" t="n"><v>${v}</v></c>`:`<c r="${col}${n}" s="${style}" t="inlineStr"><is><t xml:space="preserve">${xml(v)}</t></is></c>`;
  const add=(parts:[string,string,unknown][],height=24,style=4,wide=false)=>{if(!wide)parts=parts.map(([a,b,v])=>[String.fromCharCode(65+(a.charCodeAt(0)-65)*2),String.fromCharCode(66+(b.charCodeAt(0)-65)*2),v]);row++;rows.push(`<row r="${row}" ht="${height}" customHeight="1">${parts.map(([a,b,v])=>{if(a!==b)merges.push(`${a}${row}:${b}${row}`);return cell(a,row,v,style);}).join('')}</row>`);return row;};
  const band=(s:string)=>add([['A','L',s]],27,1);
  band((r.relocation?'BÁO CÁO DI DỜI • ':'BÁO CÁO SỰ CỐ • ')+name);
  const info=(values:(string|number)[])=>{
    row++;const height=Math.min(409,Math.max(54,...values.filter((_,i)=>i%2===1).map(v=>String(v).split('\n').reduce((h,line)=>h+Math.max(1,Math.ceil(line.length/16))*15,0))));
    rows.push(`<row r="${row}" ht="${height}" customHeight="1">${values.map((v,i)=>{const start=Math.floor(i/2)*6+(i%2?2:0),end=start+(i%2?3:1),col=String.fromCharCode(65+start);merges.push(`${col}${row}:${String.fromCharCode(65+end)}${row}`);return cell(col,row,v,i%2?3:1);}).join('')}</row>`);
  };
  info([r.relocation?'Địa điểm di dời':'Địa điểm sự cố',r.location,r.relocation?'Ngày lập báo cáo':'Khoảng cách đến trạm',r.relocation?((r as Incident & {reportDate?:string}).reportDate??''):r.distance+(r.distance?' km':''),r.relocation?'Ngày thực hiện':'Ngày báo cáo',r.date,'Người lập báo cáo',r.reporter]);
  info(['Tuyến',name,(!r.relocation||r.received)?'Thời gian nhận thông tin':'',r.received,(!r.relocation||r.restored)?(r.relocation?'Thời gian kết thúc':'Thời gian khôi phục dịch vụ'):'',r.restored,(!r.relocation||(r.received&&r.restored))?'Tổng phút xử lý':'',handlingMinutes(r.received,r.restored)??'']);
  add([['A','B',r.relocation?'Nguyên nhân di dời':'Nguyên nhân'],['C','L',r.cause]],Math.max(36,Math.ceil(r.cause.length/65)*15));
  const solution=solutionText(r);
  add([['A','B',r.relocation?'Nội dung di dời':'Phương án xử lý'],['C','L',solution]],Math.max(36,solution.split('\n').reduce((h,line)=>h+Math.max(1,Math.ceil(line.length/46))*15,0)));
  const summary=materialSummary(r);
  add([['A','B','Vật tư'],['C','L',summary]],Math.max(30,Math.ceil(summary.length/46)*15));
  for(const {g} of orderedPhotoGroups(r.groups)){
    if(!g.images.length)continue;
    band(g.name==='Trong thi công'?'Hình ảnh thi công':g.name);
    for(const [pairIndex,pair] of imagePairs(g.images).entries()){
    const note=g.pairNotes?.[pairIndex]||g.note;
    const anchorRow=row;
    const rowHeight=Math.min(409,Math.max(54,...pair.map(p=>Math.ceil((Math.min(378,304*Math.max(1,p.height)/Math.max(1,p.width))+2)*3/4)),Math.ceil((note.length+pair.reduce((s,p)=>s+p.caption.length,0))/22)*15));
    add([['A','I',''],['J','O',[note,...pair.map(p=>p.caption)].filter(Boolean).join('\n')],['P','X','']],rowHeight,3,true);
    pair.forEach((p,i)=>{
      if(!images[p.file])throw new Error('Không đọc được ảnh nhóm '+g.name);
      const id=++imageIndex,w=Math.max(1,p.width),h=Math.max(1,p.height),boxWidth=304,boxHeight=Math.min(378,rowHeight*4/3-2);
      const scale=Math.min(boxWidth/w,boxHeight/h),width=w*scale,height=h*scale;
      const cx=Math.round(width*9525),cy=Math.round(height*9525),dx=Math.round((1+(boxWidth-width)/2)*9525),dy=Math.round((1+(boxHeight-height)/2)*9525);
      files[`xl/media/image${id}.jpg`]=images[p.file];
      rels.push(`<Relationship Id="rId${id}" Type="${rel}/image" Target="../media/image${id}.jpg"/>`);
      anchors.push(`<xdr:oneCellAnchor><xdr:from><xdr:col>${i===0?0:15}</xdr:col><xdr:colOff>${dx}</xdr:colOff><xdr:row>${anchorRow}</xdr:row><xdr:rowOff>${dy}</xdr:rowOff></xdr:from><xdr:ext cx="${cx}" cy="${cy}"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${id}" name="Ảnh ${id}"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${id}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>`);
    });
  }
  }
  rows.forEach((text,index)=>{
    const n=Number(text.match(/r="(\d+)"/)![1]);const cells=new Map([...text.matchAll(/<c\b[^>]*>.*?<\/c>/gs)].map(m=>[m[0].match(/r="([A-X])/)![1],m[0]]));
    for(const merge of merges){const [a,b]=merge.split(':');if(Number(a.slice(1))!==n)continue;const style=Number(cells.get(a[0])?.match(/s="(\d+)"/)?.[1]??0);for(let code=a.charCodeAt(0);code<=b.charCodeAt(0);code++){const col=String.fromCharCode(code);if(!cells.has(col))cells.set(col,cell(col,n,'',style));}}
    rows[index]=text.slice(0,text.indexOf('>')+1)+[...cells.entries()].sort(([a],[b])=>a.localeCompare(b)).map(([,v])=>v).join('')+'</row>';
  });
  const put=(p:string,s:string)=>files[p]=strToU8('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'+s);
  put('xl/worksheets/sheet1.xml',`<worksheet xmlns="${ns}" xmlns:r="${rel}"><sheetViews><sheetView showGridLines="0" workbookViewId="0"/></sheetViews><cols><col min="1" max="24" width="4.140625" customWidth="1"/></cols><sheetData>${rows.join('')}</sheetData><mergeCells count="${merges.length}">${merges.map(m=>`<mergeCell ref="${m}"/>`).join('')}</mergeCells><pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/><drawing r:id="rId1"/></worksheet>`);
  put('xl/styles.xml',`<styleSheet xmlns="${ns}"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF194B5C"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="thin"><color rgb="FF000000"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5">${[0,1,2,3,4].map(i=>`<xf numFmtId="0" fontId="${i===1?1:0}" fillId="${i===1?2:i===2?3:0}" borderId="${i===0?0:1}" xfId="0" applyAlignment="1"><alignment ${i===1||i===3?'horizontal="center" ':''}vertical="center" wrapText="1"/></xf>`).join('')}</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`);
  put('xl/drawings/drawing1.xml',`<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${rel}">${anchors.join('')}</xdr:wsDr>`);
  put('xl/drawings/_rels/drawing1.xml.rels',`<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${rels.join('')}</Relationships>`);
  return zipSync(files,{level:1});
}

