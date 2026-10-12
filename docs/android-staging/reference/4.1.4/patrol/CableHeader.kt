package vn.vltelecom

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object CableHeader {
 fun apply(file:File,name:String,rows:List<Photo>,info:JSONObject?,partition:Boolean=false) {
  if(info?.optBoolean("cableWork")!=true)return
  if(!partition&&rows.any{nok(info.optJSONObject("points")?.optJSONObject(it.seq.toString()))}){split(file,name,rows,info);return}
  val files=linkedMapOf<String,ByteArray>()
  ZipFile(file).use{z->z.entries().asSequence().forEach{e->files[e.name]=z.getInputStream(e).use{it.readBytes()}}}
  fun change(path:String,fn:(String)->String){files[path]=fn(String(files.getValue(path),Charsets.UTF_8)).toByteArray(Charsets.UTF_8)}
  val route=info.optString("routeName").ifBlank{name}
  val type=when(info.optString("workType")){"maintenance"->"bảo dưỡng";"inspection"->"kiểm tra";else->"tuyến cáp"}
  val days=rows.map{it.capturedAt}.filter{it>0}.sorted().map{SimpleDateFormat("dd/MM/yyyy",Locale.US).format(java.util.Date(it))}.firstOrNull().orEmpty()
  fun cell(col:String,r:Int,v:String)="<c r=\"$col$r\" s=\"${if(r==1)2 else 3}\" t=\"inlineStr\"><is><t>${Exports.xml(v)}</t></is></c>"
  val head=listOf(listOf("Báo cáo $type $route","","","","",""),listOf("Team / Đội","",info.optString("team"),"Người thực hiện","",ServerSession.user?.optString("display_name")?:info.optString("reporter")),listOf("Tên tuyến","",route,"","",""),listOf("Ngày tuần tuyến","",info.optString("firstDate",days),"Tổng chiều dài tuyến (km)","",info.optString("lengthKm"))).mapIndexed{i,values->"<row r=\"${i+1}\" ht=\"${listOf(54,28,28,32)[i]}\" customHeight=\"1\">"+values.mapIndexed{j,v->cell(('A'+j).toString(),i+1,v)}.joinToString("")+"</row>"}.joinToString("")
  val sorted=rows.sortedBy{it.seq}
  val sizes=sorted.map{p->val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(p.path,bounds);val w=if(bounds.outWidth>0)bounds.outWidth else 320;val h=if(bounds.outHeight>0)bounds.outHeight else 400;val height=minOf(378.0,200.0*h/w);Pair(minOf(200.0,height*w/h),height)}
  change("xl/worksheets/sheet1.xml"){raw->
   var s=raw.replace(Regex("<row r=\"1\"[^>]*>.*?</row>",RegexOption.DOT_MATCHES_ALL),"")
   s=s.replace(Regex("r=\"([A-Z]*)([0-9]+)\"")){m->"r=\"${m.groupValues[1]}${m.groupValues[2].toInt()+3}\""}
   s=s.replace("<sheetData>","<sheetData>$head").replace("ySplit=\"2\"","ySplit=\"5\"").replace("topLeftCell=\"A3\"","topLeftCell=\"A6\"")
   s=s.replace(Regex("<autoFilter[^>]*/>"),"<autoFilter ref=\"A5:F${rows.size+5}\"/>")
   val labels=listOf("STT","Địa chỉ","Tên vị trí","Hình ảnh","Mô tả","Kiến nghị / Giải pháp")
   val table="<row r=\"5\" ht=\"42\" customHeight=\"1\">"+labels.mapIndexed{i,v->cell(('A'+i).toString(),5,v)}.joinToString("")+"</row>"+sorted.mapIndexed{i,p->val r=i+6;val q=info.optJSONObject("points")?.optJSONObject(p.seq.toString());"<row r=\"$r\" ht=\"${sizes[i].second*0.75}\" customHeight=\"1\">"+listOf(p.seq.toString(),q?.optString("address")?:"",p.locationName,"",q?.optString("description",p.note)?:p.note,listOf(q?.optString("assessment").orEmpty(),q?.optString("proposal").orEmpty()).filter{it.isNotBlank()}.joinToString(" • ")).mapIndexed{j,v->cell(('A'+j).toString(),r,v)}.joinToString("")+"</row>"}.joinToString("")
   s=s.replace(Regex("<sheetData>.*?</sheetData>",RegexOption.DOT_MATCHES_ALL),"<sheetData>$head$table</sheetData>")
   s=s.replace(Regex("<cols>.*?</cols>"),"<cols>"+listOf(8,24,25,28,28,28).mapIndexed{i,w->"<col min=\"${i+1}\" max=\"${i+1}\" width=\"$w\" customWidth=\"1\"/>"}.joinToString("")+"</cols>")
   s=s.replace("<sheetView workbookViewId=\"0\">","<sheetView workbookViewId=\"0\" showGridLines=\"0\">")
   val merges=listOf("A1:F1","A2:B2","D2:E2","A3:B3","D3:E3","A4:B4","D4:E4")
   s.replace(Regex("<mergeCells.*?</mergeCells>"),"<mergeCells count=\"${merges.size}\">"+merges.joinToString(""){"<mergeCell ref=\"$it\"/>"}+"</mergeCells>")
  }
  change("xl/drawings/drawing1.xml"){raw->
   var i=0
   raw.replace(Regex("<xdr:twoCellAnchor.*?</xdr:twoCellAnchor>",RegexOption.DOT_MATCHES_ALL)){m->val index=i++;val pic=Regex("<xdr:pic>.*?</xdr:pic>",RegexOption.DOT_MATCHES_ALL).find(m.value)!!.value;val size=sizes[index];"<xdr:oneCellAnchor><xdr:from><xdr:col>3</xdr:col><xdr:colOff>${((200-size.first)/2*9525).toLong()}</xdr:colOff><xdr:row>${index+5}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx=\"${(size.first*9525).toLong()}\" cy=\"${(size.second*9525).toLong()}\"/>$pic<xdr:clientData/></xdr:oneCellAnchor>"}
  }
  change("xl/styles.xml"){s->s.replace("fonts count=\"1\"","fonts count=\"2\"").replace("</fonts>","<font><b/><sz val=\"18\"/><name val=\"Calibri\"/></font></fonts>")
   .replace("borders count=\"1\"","borders count=\"2\"").replace("</borders>","<border>"+listOf("left","right","top","bottom").joinToString(""){"<$it style=\"thin\"><color rgb=\"FF000000\"/></$it>"}+"</border></borders>")
   .replace("cellXfs count=\"2\"","cellXfs count=\"4\"").replace("</cellXfs>","<xf fontId=\"1\" borderId=\"1\" applyBorder=\"1\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf><xf fontId=\"0\" borderId=\"1\" applyBorder=\"1\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf></cellXfs>")}
  change("xl/styles.xml"){s->s.replace("fills count=\"2\"","fills count=\"3\"").replace("</fills>","<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF255D91\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>").replace("<xf fontId=\"1\" borderId=\"1\" applyBorder=\"1\"","<xf fillId=\"2\" applyFill=\"1\" fontId=\"1\" borderId=\"1\" applyBorder=\"1\"").replace("cellXfs count=\"4\"","cellXfs count=\"5\"").replace("</cellXfs>","<xf fontId=\"0\" fillId=\"2\" applyFill=\"1\" borderId=\"1\" applyBorder=\"1\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf></cellXfs>")}
  change("xl/worksheets/sheet1.xml"){s->s.replace(Regex("<c r=\"([ABDE][234])\" s=\"3\"")){m->"<c r=\"${m.groupValues[1]}\" s=\"4\""}}
  change("xl/styles.xml"){s->s.replace("fonts count=\"2\"","fonts count=\"3\"").replace("</fonts>","<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>").replace("cellXfs count=\"5\"","cellXfs count=\"7\"").replace("</cellXfs>",listOf(0,2).joinToString(""){fill->"<xf fontId=\"2\" applyFont=\"1\" fillId=\"$fill\" applyFill=\"1\" borderId=\"1\" applyBorder=\"1\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"}+"</cellXfs>")}
  change("xl/worksheets/sheet1.xml"){s->s.replace(Regex("<c r=\"([A-F][2-5])\" s=\"([34])\"")){m->"<c r=\"${m.groupValues[1]}\" s=\"${if(m.groupValues[2]=="4")6 else 5}\""}}
  ZipOutputStream(file.outputStream()).use{z->files.forEach{(path,bytes)->z.putNextEntry(ZipEntry(path));z.write(bytes);z.closeEntry()}}
 }
 private fun nok(q:JSONObject?)=q?.optString("assessment")=="NOK" || (q?.optString("assessment").isNullOrBlank()&&Regex("^NOK\\b",RegexOption.IGNORE_CASE).containsMatchIn(q?.optString("proposal").orEmpty()))
 private fun split(file:File,name:String,rows:List<Photo>,info:JSONObject){
  val all=rows.sortedBy{it.seq};val meta=JSONObject(info.toString()).put("firstDate",rows.map{it.capturedAt}.filter{it>0}.minOrNull()?.let{SimpleDateFormat("dd/MM/yyyy",Locale.US).format(java.util.Date(it))}.orEmpty())
  val good=all
  Exports.xlsx(file,name,good);apply(file,name,good,meta,true)
  val second=File(file.parentFile,"nok-${java.util.UUID.randomUUID()}.xlsx")
  try{val expanded=mutableListOf<Photo>();val slots=mutableListOf<Pair<Int,Int>>();val repair=all.filter{nok(info.optJSONObject("points")?.optJSONObject(it.seq.toString()))};val points=JSONObject();for(p in repair){val q=info.optJSONObject("points")?.optJSONObject(p.seq.toString())?:JSONObject();for(k in listOf("before","after")){val path=if(k=="before")q.optJSONObject(k)?.optString("file").orEmpty().ifBlank{p.path} else q.optJSONObject(k)?.optString("file").orEmpty();if(path.isBlank())continue;val n=expanded.size+1;slots.add(repair.indexOf(p) to if(k=="before")3 else 4);expanded.add(p.copy(seq=n,path=path,locationName=p.locationName+when(k){"before"->" • Trước sửa";"after"->" • Sau sửa";else->""}));points.put(n.toString(),JSONObject(q.toString()).put("description",when(k){"before"->"Ảnh trước khi sửa";"after"->"Ảnh sau khi sửa";else->q.optString("description",p.note)}))}}
   Exports.xlsx(second,"Chưa đạt - Sửa chữa",expanded);apply(second,name,expanded,JSONObject(meta.toString()).put("points",points),true)
   fun load(f:File)=linkedMapOf<String,ByteArray>().apply{ZipFile(f).use{z->z.entries().asSequence().forEach{e->put(e.name,z.getInputStream(e).use{it.readBytes()})}}}
   val first=load(file);val next=load(second)
   val rawSheet=String(next.getValue("xl/worksheets/sheet1.xml"),Charsets.UTF_8)
   val heights=Regex("<row r=\"(\\d+)\" ht=\"([\\d.]+)\"").findAll(rawSheet).filter{it.groupValues[1].toInt()>=6}.map{it.groupValues[2].toDouble()}.toList()
   fun repairCell(col:Char,row:Int,value:String)="<c r=\"$col$row\" s=\"3\" t=\"inlineStr\"><is><t>${Exports.xml(value)}</t></is></c>"
   val repairRows=repair.mapIndexed{i,p->val q=info.optJSONObject("points")?.optJSONObject(p.seq.toString());val row=i+6;val height=maxOf(54.0,slots.mapIndexed{j,slot->if(slot.first==i)heights.getOrElse(j){54.0}else 0.0}.maxOrNull()?:54.0);"<row r=\"$row\" ht=\"$height\" customHeight=\"1\">"+listOf(p.seq.toString(),q?.optString("address").orEmpty(),p.locationName,"","",q?.optString("proposal").orEmpty()).mapIndexed{j,v->repairCell('A'+j,row,v)}.joinToString("")+"</row>"}.joinToString("")
   next["xl/worksheets/sheet1.xml"]=rawSheet.replace(Regex("<row r=\"(?:[6-9]|[1-9]\\d+)\"[^>]*>.*?</row>"),"").replace("</sheetData>",repairRows+"</sheetData>").replace(">Hình ảnh<",">Hình ảnh trước<").replace(">Mô tả<",">Hình ảnh sau<").replace("<col min=\"5\" max=\"5\" width=\"24\"","<col min=\"5\" max=\"5\" width=\"40\"").replace(Regex("<autoFilter ref=\"[^\"]+\""),"<autoFilter ref=\"A5:F${repair.size+5}\"").toByteArray(Charsets.UTF_8)
   var slotIndex=0
   next["xl/drawings/drawing1.xml"]=String(next.getValue("xl/drawings/drawing1.xml"),Charsets.UTF_8).replace(Regex("<xdr:oneCellAnchor>.*?</xdr:oneCellAnchor>")){m->val slot=slots[slotIndex++];m.value.replace(Regex("<xdr:col>\\d+</xdr:col>"),"<xdr:col>${slot.second}</xdr:col>").replace(Regex("<xdr:row>\\d+</xdr:row>"),"<xdr:row>${slot.first+5}</xdr:row>")}.toByteArray(Charsets.UTF_8)
   fun read(path:String)=String(next.getValue(path),Charsets.UTF_8)
   first["xl/worksheets/sheet2.xml"]=next.getValue("xl/worksheets/sheet1.xml")
   first["xl/worksheets/_rels/sheet2.xml.rels"]=read("xl/worksheets/_rels/sheet1.xml.rels").replace("drawing1.xml","drawing2.xml").toByteArray()
   first["xl/drawings/drawing2.xml"]=next.getValue("xl/drawings/drawing1.xml")
   first["xl/drawings/_rels/drawing2.xml.rels"]=read("xl/drawings/_rels/drawing1.xml.rels").replace("media/image","media/nok-image").toByteArray()
   next.filterKeys{it.startsWith("xl/media/")}.forEach{(path,bytes)->first[path.replace("/image","/nok-image")]=bytes}
   fun edit(path:String,old:String,new:String){first[path]=String(first.getValue(path),Charsets.UTF_8).replace(old,new).toByteArray(Charsets.UTF_8)}
   edit("xl/workbook.xml","</sheets>","<sheet name=\"Chưa đạt - Sửa chữa\" sheetId=\"2\" r:id=\"rId3\"/></sheets>")
   edit("xl/_rels/workbook.xml.rels","</Relationships>","<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>")
   edit("[Content_Types].xml","</Types>","<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/drawings/drawing2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/></Types>")
   ZipOutputStream(file.outputStream()).use{z->first.forEach{(path,bytes)->z.putNextEntry(ZipEntry(path));z.write(bytes);z.closeEntry()}}
  }finally{second.delete()}
 }

}
