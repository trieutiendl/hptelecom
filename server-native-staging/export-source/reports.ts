import { strToU8, strFromU8, unzipSync, zipSync } from 'fflate';
import type { Photo } from './data';
export function sheetName(name: string) {
  const clean = name.replace(/[\\/:*?\[\]\u0000-\u001F]/g,'_').trim().replace(/^'+|'+$/g,'');
  const short = clean.slice(0,31).replace(/[\uD800-\uDBFF]$/,'').replace(/'+$/,'');
  return !short ? 'Báo cáo' : short.toLowerCase()==='history' ? 'History_' : short;
}
export const xml = (v: unknown) => String(v).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&apos;');
export const filename = (s: string) => s.replace(/[\\/:*?"<>|\u0000-\u001F]/g,'_').trim().replace(/[. ]+$/g,'').slice(0,70) || 'HP-Telecom';
const order = (rows: Photo[]) => [...rows].sort((a,b) => a.seq-b.seq || a.id-b.id);
const photoDate = (p: any) => String(p?.imageDate ?? p?.displayDate ?? p?.date ?? '').trim();
export function kmz(name: string, rows: Photo[]) {
  const sorted = order(rows);
  const points = sorted.filter((p,i) => i===0 || i===sorted.length-1 || /MX|DP/i.test(p.note));
  if (!sorted.length) throw new Error('Chưa có vị trí để xuất KMZ.');
  const line = sorted.length>1 ? `<Placemark><name>${xml(name)}</name><Style><LineStyle><color>ffffff00</color><width>3</width></LineStyle></Style><LineString><tessellate>1</tessellate><coordinates>${sorted.map(p=>`${p.lon},${p.lat},0`).join(' ')}</coordinates></LineString></Placemark>` : '';
  const marks = points.map(p=>`<Placemark><name>${xml(p===sorted[0] || p===sorted[sorted.length-1] ? p.name : p.seq+' - '+p.name)}</name><description>${xml(p.note+' • '+photoDate(p))}</description><Point><coordinates>${p.lon},${p.lat},0</coordinates></Point></Placemark>`).join('');
  return zipSync({'doc.kml':strToU8(`<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>${xml(name)}</name><Folder><name>${xml(name)}</name><open>1</open>${line}${marks}</Folder></Document></kml>`) });
}
export function xlsx(name: string, rows: Photo[], images: Record<string, Uint8Array>, info?: any) {
  if(info?.cableWork&&!info._partition){const result=nokWorkbook(name,rows,images,info);if(result)return result;}
  const offset=info?.cableWork?3:0;
  const sorted = order(rows); const files: Record<string,Uint8Array> = {};
  const put=(path:string,content:string)=>files[path]=strToU8('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'+content);
  const ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main';
  const rel='http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const pkg='http://schemas.openxmlformats.org/package/2006/relationships';
  put('[Content_Types].xml',`<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`);
  put('_rels/.rels',`<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`);
  put('xl/workbook.xml',`<workbook xmlns="${ns}" xmlns:r="${rel}"><sheets><sheet name="${xml(sheetName(name))}" sheetId="1" r:id="rId1"/></sheets></workbook>`);
  put('xl/_rels/workbook.xml.rels',`<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="${rel}/styles" Target="styles.xml"/></Relationships>`);
  put('xl/styles.xml',`<styleSheet xmlns="${ns}"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="18"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="thin"><color rgb="FF000000"/></bottom></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf fontId="1" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf fontId="0" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`);
  const cell=(col:string,row:number,value:unknown)=>`<c r="${col}${row}" s="${offset?(row===1?1:2):0}" t="inlineStr"><is><t xml:space="preserve">${xml(value)}</t></is></c>`;
  const header=offset?['STT','Địa chỉ','Tên vị trí','Hình ảnh','Mô tả','Kiến nghị / Giải pháp']:['STT','Hình ảnh','Tên vị trí','Lat','Long','Ghi chú','Ngày chụp'];
  const routeName=info?.routeName||name;
  const days=[...new Set(sorted.map(p=>photoDate(p).split(' ')[0]).filter(Boolean))].sort((a,b)=>{const key=(d:string)=>d.split('/').reverse().join('-');return key(a).localeCompare(key(b));})[0]||'';
  const title='Báo cáo '+({maintenance:'bảo dưỡng',inspection:'kiểm tra'}[info?.workType as string]||'tuyến cáp')+' '+routeName;
  const meta=offset?[[title,'','','','',''],['Team / Đội','',info?.team||'','Người thực hiện','',info?.reporter||''],['Tên tuyến','',routeName,'','',''],['Ngày tuần tuyến','',info?.firstDate??days,'Tổng chiều dài tuyến (km)','',info?.lengthKm??'']].map((values,i)=>`<row r="${i+1}" ht="${[54,28,28,32][i]}" customHeight="1">${values.map((v,j)=>cell(String.fromCharCode(65+j),i+1,v)).join('')}</row>`).join(''):`<row r="1">${cell('A',1,name)}</row>`;
  const merges=offset?'<mergeCells count="7">'+['A1:F1','A2:B2','D2:E2','A3:B3','D3:E3','A4:B4','D4:E4'].map(ref=>`<mergeCell ref="${ref}"/>`).join('')+'</mergeCells>':'';
  const legacyData=sorted.map((p,i)=>`<row r="${i+3+offset}" ht="190" customHeight="1"><c r="A${i+3+offset}"><v>${p.seq}</v></c>${cell('C',i+3+offset,p.name)}<c r="D${i+3+offset}"><v>${p.lat}</v></c><c r="E${i+3+offset}"><v>${p.lon}</v></c>${cell('F',i+3+offset,p.note)}${cell('G',i+3+offset,photoDate(p))}</row>`).join('');
  const sizes=sorted.map(p=>{const b=images[p.file];let w=320,h=400;if(b){if(b[0]===137&&b[1]===80){const n=(i:number)=>b[i]*16777216+b[i+1]*65536+b[i+2]*256+b[i+3];w=n(16);h=n(20);}else{for(let i=2;i+9<b.length;){if(b[i]!==255)break;const marker=b[i+1],len=b[i+2]*256+b[i+3];if([192,193,194].includes(marker)){h=b[i+5]*256+b[i+6];w=b[i+7]*256+b[i+8];break;}if(len<2)break;i+=len+2;}}}const height=Math.min(378,200*h/Math.max(1,w)),width=Math.min(200,height*w/Math.max(1,h));return {width,height};});
  const data=offset?sorted.map((p,i)=>{const r=i+6,q=info?.points?.[p.seq]||{};return `<row r="${r}" ht="${sizes[i].height*0.75}" customHeight="1">${[p.seq,q.address||'',p.name,'',q.description??p.note,[q.assessment,q.proposal].filter(Boolean).join(' • ')].map((v,j)=>cell(String.fromCharCode(65+j),r,v)).join('')}</row>`;}).join(''):legacyData;
  const cols=offset?[8,24,25,28,28,28].map((width,i)=>`<col min="${i+1}" max="${i+1}" width="${width}" customWidth="1"/>`).join(''):'<col min="1" max="1" width="8" customWidth="1"/><col min="2" max="2" width="34" customWidth="1"/><col min="3" max="3" width="25" customWidth="1"/><col min="4" max="5" width="18" customWidth="1"/><col min="6" max="6" width="35" customWidth="1"/><col min="7" max="7" width="25" customWidth="1"/>';
  put('xl/worksheets/sheet1.xml',`<worksheet xmlns="${ns}" xmlns:r="${rel}"><sheetViews><sheetView workbookViewId="0" showGridLines="0"/></sheetViews><cols>${cols}</cols><sheetData>${meta}<row r="${2+offset}" ht="42" customHeight="1">${header.map((h,i)=>cell(String.fromCharCode(65+i),2+offset,h)).join('')}</row>${data}</sheetData>${offset?`<autoFilter ref="A5:F${sorted.length+5}"/>`:""}${merges}<drawing r:id="rId1"/></worksheet>`);
  put('xl/worksheets/_rels/sheet1.xml.rels',`<Relationships xmlns="${pkg}"><Relationship Id="rId1" Type="${rel}/drawing" Target="../drawings/drawing1.xml"/></Relationships>`);
  // Fixed portrait canvas matches the stamped photo preview (width 320, height 400).
  put('xl/drawings/drawing1.xml',`<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${rel}">${sorted.map((p,i)=>`<xdr:oneCellAnchor><xdr:from><xdr:col>${offset?3:1}</xdr:col><xdr:colOff>${offset?Math.round((200-sizes[i].width)/2*9525):20000}</xdr:colOff><xdr:row>${i+2+offset}</xdr:row><xdr:rowOff>${offset?0:20000}</xdr:rowOff></xdr:from><xdr:ext cx="${offset?Math.round(sizes[i].width*9525):1905000}" cy="${offset?Math.round(sizes[i].height*9525):2381250}"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i+1}" name="Ảnh ${p.seq}"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i+1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${offset?Math.round(sizes[i].width*9525):1905000}" cy="${offset?Math.round(sizes[i].height*9525):2381250}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>`).join('')}</xdr:wsDr>`);
  put('xl/drawings/_rels/drawing1.xml.rels',`<Relationships xmlns="${pkg}">${sorted.map((p,i)=>`<Relationship Id="rId${i+1}" Type="${rel}/image" Target="../media/image${i+1}.jpg"/>`).join('')}</Relationships>`);
  sorted.forEach((p,i)=>{ if (!images[p.file]) throw new Error('Không đọc được ảnh '+p.seq); files[`xl/media/image${i+1}.jpg`]=images[p.file]; });
  if(offset){
   files['xl/styles.xml']=strToU8(strFromU8(files['xl/styles.xml']).replace('fills count="2"','fills count="3"').replace('</fills>','<fill><patternFill patternType="solid"><fgColor rgb="FF255D91"/><bgColor indexed="64"/></patternFill></fill></fills>').replace('<xf fontId="1" borderId="1" applyBorder="1"','<xf fillId="2" applyFill="1" fontId="1" borderId="1" applyBorder="1"').replace('cellXfs count="3"','cellXfs count="4"').replace('</cellXfs>','<xf fontId="0" fillId="2" applyFill="1" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs>'));
   files['xl/worksheets/sheet1.xml']=strToU8(strFromU8(files['xl/worksheets/sheet1.xml']).replace(/<c r="([ABDE][234])" s="2"/g,'<c r="$1" s="3"'));
   files['xl/styles.xml']=strToU8(strFromU8(files['xl/styles.xml']).replace('fonts count="2"','fonts count="3"').replace('</fonts>','<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>').replace('cellXfs count="4"','cellXfs count="6"').replace('</cellXfs>',[0,2].map(fill=>`<xf fontId="2" applyFont="1" fillId="${fill}" applyFill="1" borderId="1" applyBorder="1" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>`).join('')+'</cellXfs>'));
   files['xl/worksheets/sheet1.xml']=strToU8(strFromU8(files['xl/worksheets/sheet1.xml']).replace(/<c r="([A-F][2-5])" s="([23])"/g,(_,ref,style)=>`<c r="${ref}" s="${style==='3'?5:4}"`));

  }
  return zipSync(files,{level:1});
}

export const isNok=(q:any)=>q?.assessment==='NOK'||(!q?.assessment&&/^NOK\b/i.test(q?.proposal||''));
export function cableImageFiles(info:any):string[]{return Object.values(info?.points||{}).flatMap((q:any)=>['before','after'].map(k=>q[k]?.file).filter(Boolean));}
function nokWorkbook(name:string,rows:Photo[],images:Record<string,Uint8Array>,info:any){
 const sorted=order(rows),nok=sorted.filter(p=>isNok(info.points?.[p.seq]));
 if(!nok.length)return null;
 const days=sorted.map(p=>photoDate(p)).filter(Boolean).sort((a,b)=>a.split('/').reverse().join('-').localeCompare(b.split('/').reverse().join('-')));
 const meta={...info,_partition:true,firstDate:days[0]||''};
 const first=unzipSync(xlsx(name,sorted,images,meta));
 const expanded:Photo[]=[],points:Record<string,any>={},slots:{row:number;col:number}[]=[];
 for(const p of nok){const q=info.points?.[p.seq]||{};for(const key of ['before','after']){const pic=key==='before'?(q.before?.file?q.before:p):q.after;if(!pic?.file)continue;const seq=expanded.length+1;slots.push({row:nok.indexOf(p),col:key==='before'?3:4});expanded.push({...p,id:seq,seq,file:pic.file,name:p.name+(key==='original'?'':key==='before'?' • Trước sửa':' • Sau sửa'),date:photoDate(pic)||photoDate(p),imageDate:(pic as any).imageDate});points[seq]={...q,description:key==='original'?(q.description??p.note):key==='before'?'Ảnh trước khi sửa':'Ảnh sau khi sửa',assessment:'NOK',proposal:q.proposal||''};}}
 const second=unzipSync(xlsx('Chưa đạt - Sửa chữa',expanded,images,{...meta,points}));
 const rawSheet=strFromU8(second['xl/worksheets/sheet1.xml']);
 const heights=[...rawSheet.matchAll(/<row r="(\d+)" ht="([\d.]+)"/g)].filter(m=>Number(m[1])>=6).map(m=>Number(m[2]));
 const rowHeights=nok.map((p,i)=>Math.max(54,...slots.map((slot,j)=>slot.row===i?(heights[j]||54):0)));
 const repairCell=(col:string,row:number,value:any)=>`<c r="${col}${row}" s="2" t="inlineStr"><is><t>${xml(value)}</t></is></c>`;
 const repairRows=nok.map((p,i)=>{const q=info.points?.[p.seq]||{},row=i+6;return `<row r="${row}" ht="${rowHeights[i]}" customHeight="1">${[p.seq,q.address||'',p.name,'','',q.proposal||''].map((v,j)=>repairCell(String.fromCharCode(65+j),row,v)).join('')}</row>`;}).join('');
 second['xl/worksheets/sheet1.xml']=strToU8(rawSheet.replace(/<row r="(?:[6-9]|[1-9]\d+)"[^>]*>.*?<\/row>/g,'').replace('</sheetData>',repairRows+'</sheetData>').replace('>Hình ảnh<','>Hình ảnh trước<').replace('>Mô tả<','>Hình ảnh sau<').replace(/<col min="5" max="5" width="24"/,'<col min="5" max="5" width="40"').replace(/<autoFilter ref="[^"]+"/,`<autoFilter ref="A5:F${nok.length+5}"`));
 let slotIndex=0;
 second['xl/drawings/drawing1.xml']=strToU8(strFromU8(second['xl/drawings/drawing1.xml']).replace(/<xdr:oneCellAnchor>.*?<\/xdr:oneCellAnchor>/g,anchor=>{const slot=slots[slotIndex++];return anchor.replace(/<xdr:col>\d+<\/xdr:col>/,`<xdr:col>${slot.col}</xdr:col>`).replace(/<xdr:row>\d+<\/xdr:row>/,`<xdr:row>${slot.row+5}</xdr:row>`);}));
 const read=(p:string)=>strFromU8(second[p]);
 first['xl/worksheets/sheet2.xml']=second['xl/worksheets/sheet1.xml'];
 first['xl/worksheets/_rels/sheet2.xml.rels']=strToU8(read('xl/worksheets/_rels/sheet1.xml.rels').replace('drawing1.xml','drawing2.xml'));
 first['xl/drawings/drawing2.xml']=second['xl/drawings/drawing1.xml'];
 first['xl/drawings/_rels/drawing2.xml.rels']=strToU8(read('xl/drawings/_rels/drawing1.xml.rels').replace(/media\/image/g,'media/nok-image'));
 for(const [p,bytes]of Object.entries(second))if(p.startsWith('xl/media/'))first[p.replace('/image','/nok-image')]=bytes;
 const edit=(p:string,fn:(v:string)=>string)=>first[p]=strToU8(fn(strFromU8(first[p])));
 edit('xl/workbook.xml',s=>s.replace('</sheets>','<sheet name="Chưa đạt - Sửa chữa" sheetId="2" r:id="rId3"/></sheets>'));
 edit('xl/_rels/workbook.xml.rels',s=>s.replace('</Relationships>','<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>'));
 edit('[Content_Types].xml',s=>s.replace('</Types>','<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/drawings/drawing2.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/></Types>'));
 return zipSync(first,{level:1});
}

