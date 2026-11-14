package vn.vltelecom

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.*

data class IncidentMaterial(var name:String="",var type:String="",var quantity:String="",var unit:String="",var note:String="")
data class IncidentImage(val path:String,val width:Int,val height:Int,val caption:String="",val originalFile:String="",val metadata:String="")
data class IncidentGroup(val name:String,var note:String="",val images:MutableList<IncidentImage> = mutableListOf(),val pairNotes:MutableList<String> = mutableListOf())
data class IncidentReport(
    val privateGroups:MutableSet<String> = mutableSetOf(),
    var splitRelocation:Boolean=false,var newCableEnabled:Boolean=false,
    var relocation:Boolean=false,var completed:Boolean=false,var relocationLength:String="",
    var pendingOriginal:String="",var pendingStamped:Boolean=false,var pendingNote:String="",var pendingPhoto:String="",var pendingGroup:Int=0,var pendingDate:Long=0,
    var location:String="",var distance:String="",var reporter:String="",var date:String=SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date()),
    var received:String="",var restored:String="",var cause:String="",var solution:String="",var cable:String="",var cableSpan:String="",var start:String="",var end:String="",
    var recovery:Boolean=false,var recoveryLength:String="",var recoveryCable:String="",var locationMode:String="manual",
    var closureLocation:String="",var closureLocation2:String="",
    val materials:MutableList<IncidentMaterial> = mutableListOf(),val groups:MutableList<IncidentGroup> = IncidentRules.groupNames.map{IncidentGroup(it)}.toMutableList()
) {
    fun json():String = JSONObject().apply {
        listOf("location" to location,"distance" to distance,"reporter" to reporter,"date" to date,"received" to received,"restored" to restored,"cause" to cause,"solution" to solution,"cable" to cable,"cableSpan" to cableSpan,"start" to start,"end" to end,"recoveryLength" to recoveryLength,"recoveryCable" to recoveryCable,"locationMode" to locationMode,"closureLocation" to closureLocation,"closureLocation2" to closureLocation2).forEach{put(it.first,it.second)}
        put("privateGroups",JSONArray(privateGroups.toList()));put("completed",completed);put("relocation",relocation);put("relocationLength",relocationLength)
        put("pendingOriginal",pendingOriginal);put("pendingStamped",pendingStamped);put("pendingNote",pendingNote);put("pendingPhoto",pendingPhoto);put("pendingGroup",pendingGroup);put("pendingDate",pendingDate)
        // Use the shared Android/iOS/server recovery object while retaining the
        // legacy flat fields for older locally saved reports.
        put("recovery",JSONObject().apply{put("enabled",recovery);put("length",recoveryLength);put("cable",recoveryCable)});put("splitRelocation",splitRelocation);put("newCableEnabled",newCableEnabled)
        put("materials",JSONArray().apply{materials.forEach{m->put(JSONObject().apply{put("name",m.name);put("type",m.type);put("quantity",m.quantity);put("unit",m.unit);put("note",m.note)})}})
        put("groups",JSONArray().apply{groups.forEach{g->put(JSONObject().apply{put("name",g.name);put("note",g.note);put("pairNotes",JSONArray(g.pairNotes));put("images",JSONArray().apply{g.images.forEach{p->put((if(p.metadata.isBlank())JSONObject()else JSONObject(p.metadata)).apply{remove("file");put("path",p.path);put("width",p.width);put("height",p.height);put("caption",p.caption);put("originalFile",p.originalFile)})}})})}})
    }.toString()
    /** The summary sheet has one MX-location cell for each of the first two MX photos. */
    fun setMxSummaryLocation(imageIndex:Int,coordinates:MxCoordinates) {
        if(relocation)return
        when(imageIndex){0->closureLocation=coordinates.description();1->closureLocation2=coordinates.description()}
    }
    /** Backfill legacy reports where OCR was stored on each image but not in the summary cells. */
    fun backfillMxSummaryLocations() {
        if(relocation)return
        val images=groups.firstOrNull{IncidentRules.isMx(it)}?.images?:return
        if(closureLocation.isBlank())images.getOrNull(0)?.let{MxCoordinates.fromMetadata(it.metadata)}?.let{closureLocation=it.description()}
        if(closureLocation2.isBlank())images.getOrNull(1)?.let{MxCoordinates.fromMetadata(it.metadata)}?.let{closureLocation2=it.description()}
    }
    companion object {
        fun relocation():IncidentReport=IncidentReport(relocation=true,recovery=true).apply{groups.clear();groups.addAll(IncidentRules.relocationGroups.map{IncidentGroup(it)})}
        fun parse(value:String):IncidentReport {
            val o=JSONObject(value);val r=IncidentReport(splitRelocation=o.optBoolean("splitRelocation"),newCableEnabled=o.optBoolean("newCableEnabled"),completed=o.optBoolean("completed"),relocation=o.optBoolean("relocation"),relocationLength=o.optString("relocationLength"),pendingOriginal=o.optString("pendingOriginal"),pendingStamped=o.optBoolean("pendingStamped"),pendingNote=o.optString("pendingNote"),pendingPhoto=o.optString("pendingPhoto"),pendingGroup=o.optInt("pendingGroup"),pendingDate=o.optLong("pendingDate"),location=o.optString("location"),distance=o.optString("distance"),reporter=o.optString("reporter"),date=o.optString("date"),received=o.optString("received"),restored=o.optString("restored"),cause=o.optString("cause"),solution=o.optString("solution"),cable=o.optString("cable"),cableSpan=o.optString("cableSpan"),start=o.optString("start"),end=o.optString("end"),recovery=o.optBoolean("recovery"),recoveryLength=o.optString("recoveryLength"),recoveryCable=o.optString("recoveryCable"),locationMode=o.optString("locationMode","manual"),closureLocation=o.optString("closureLocation"),closureLocation2=o.optString("closureLocation2"))
            (o.opt("recovery") as? JSONObject)?.let{recovery->r.recovery=recovery.optBoolean("enabled");r.recoveryLength=recovery.optString("length",r.recoveryLength);r.recoveryCable=recovery.optString("cable",r.recoveryCable)}
            o.optJSONArray("privateGroups")?.let{a->for(i in 0 until a.length())r.privateGroups.add(a.getString(i))}
            o.optJSONArray("materials")?.let{a->for(i in 0 until a.length()){val m=a.getJSONObject(i);r.materials.add(IncidentMaterial(m.optString("name").let{if(it=="Thẻ tên")"Biển báo" else it},m.optString("type"),m.optString("quantity"),m.optString("unit"),m.optString("note")))}}
            o.optJSONArray("groups")?.let{a->r.groups.clear();for(i in 0 until a.length()){val g=a.getJSONObject(i);val group=IncidentGroup(g.getString("name"),g.optString("note"));g.optJSONArray("pairNotes")?.let{n->for(j in 0 until n.length())group.pairNotes.add(n.optString(j))};g.optJSONArray("images")?.let{p->for(j in 0 until p.length()){val img=p.getJSONObject(j);group.images.add(IncidentImage(img.getString("path"),img.optInt("width",320),img.optInt("height",400),img.optString("caption"),img.optString("originalFile"),img.toString()))}};r.groups.add(group)}}
            r.groups.filter{IncidentRules.isMx(it)}.forEach{group->
                group.images.indices.forEach{index->group.images[index]=MxCoordinates.cleanAutomaticCaption(group.images[index])}
            }
            r.backfillMxSummaryLocations()
            return r
        }
    }
}
object IncidentRules {
    val causes=listOf("Cháy trụ điện","Xe máng cáp","Sóc cắn","Đứt MX","Đứt ODF","Bị chặt trúng cáp")
    val cableTypes=listOf("2FO","4FO","6FO","8FO","12FO","24FO","48FO","96FO")
    val materials=listOf("Măng xông","Néo","Treo","Ty","Kẹp","Gông dự phòng","D12","Đai Inox","Biển báo","Vật tư khác")
    val groupNames=listOf("Hiện trường sự cố","Đầu số cáp","Chủng loại cáp","Vật tư","Kết quả đo kiểm","Trong thi công","Măng xông (MX)")
    val relocationGroups=listOf("Trước di dời","Trong thi công","Sau hoàn thành","Vật tư","Măng xông (MX)")
    val relocationCauses=listOf("Điện lực yêu cầu","Dân yêu cầu di dời")
    fun prepare(r:IncidentReport){
        splitCable(r)
        // Preserve every legacy image and row description; old mixed groups cannot be classified safely.
        r.groups.indices.forEach{i->if(r.groups[i].name in listOf("Măng xông / treo néo","Treo/néo","Treo / néo","Treo néo"))r.groups[i]=r.groups[i].copy(name="Vật tư")}
        listOf("Vật tư","Măng xông (MX)").forEach{name->if(r.groups.none{it.name==name})r.groups.add(IncidentGroup(name))}

        if(r.groups.none{it.name=="Trong thi công"})r.groups.add(IncidentGroup("Trong thi công"))
        if(r.relocation){listOf("Đầu số cáp","Kết quả đo kiểm").forEach{n->if(r.groups.none{it.name==n})r.groups.add(IncidentGroup(n))}}
    }
    fun ordered(r:IncidentReport):List<IndexedValue<IncidentGroup>> {
        val items=r.groups.withIndex().toMutableList();val construction=items.firstOrNull{it.value.name=="Trong thi công"};val measurement=items.firstOrNull{it.value.name=="Kết quả đo kiểm"}
        if(construction!=null&&measurement!=null){items.remove(construction);items.add(items.indexOf(measurement),construction)};return items
    }
    fun orderedForExcel(r:IncidentReport):List<IndexedValue<IncidentGroup>> {
        val items=ordered(r)
        return items.filter{it.value.name!="Kết quả đo kiểm"}+items.filter{it.value.name=="Kết quả đo kiểm"}
    }
    fun groupTitle(name:String)=if(name=="Trong thi công")"Hình ảnh thi công" else name
    fun addNote(g:IncidentGroup,note:String){if(note.isBlank())return;val pair=(g.images.size-1).coerceAtLeast(0)/2;while(g.pairNotes.size<=pair)g.pairNotes.add(g.note);g.pairNotes[pair]=(g.pairNotes[pair].lines()+note.lines()).map{it.trim()}.filter{it.isNotBlank()}.distinct().joinToString("\n")}
    fun isMx(g:IncidentGroup)=g.name=="Măng xông (MX)"
    fun mxDescription(note:String,lat:Double,lon:Double):String {
        require(lat.isFinite()&&lon.isFinite()&&lat in -90.0..90.0&&lon in -180.0..180.0)
        val gps=String.format(Locale.US,"Tọa độ MX: %.6f, %.6f",lat,lon)
        val coordinateLine=Regex("Tọa độ MX:\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)",RegexOption.IGNORE_CASE)
        var seen=false
        val lines=note.trim().lines().filter{line->
            val value=coordinateLine.matchEntire(line.trim())
            val same=value?.let{String.format(Locale.US,"Tọa độ MX: %.6f, %.6f",it.groupValues[1].toDouble(),it.groupValues[2].toDouble())==gps}?:false
            if(!same)true else if(seen)false else {seen=true;true}
        }.let{if(note.isBlank())emptyList() else it}
        return (lines+if(seen)emptyList() else listOf(gps)).joinToString("\n")
    }
    fun materialGroup(g:IncidentGroup)=g.name in listOf("Vật tư","Măng xông (MX)","Măng xông / treo néo")||g.name in relocationGroups
    fun number(s:String):Double?=if(s.trim().matches(Regex("\\d+(?:[.,]\\d+)?")))s.trim().replace(',','.').toDoubleOrNull()?.takeIf{it.isFinite()} else null
    fun splitCable(r:IncidentReport) {
        if(r.splitRelocation)return
        r.splitRelocation=true
        if(!r.relocation){r.newCableEnabled=!r.recovery;return}
        r.newCableEnabled=!r.recovery&&(r.cable.isNotBlank()||r.relocationLength.isNotBlank()||r.start.isNotBlank()||r.end.isNotBlank())
        if(r.recovery){r.recoveryLength=r.relocationLength;r.recoveryCable=r.cable;r.relocationLength="";r.start="";r.end=""}
    }
    fun length(r:IncidentReport):Double? {if(r.splitRelocation&&!r.newCableEnabled)return null;if(r.relocation&&r.splitRelocation){if(r.start.isBlank()&&r.end.isBlank())return number(r.relocationLength);val a=number(r.start)?:return null;val b=number(r.end)?:return null;return kotlin.math.abs(a-b)};if(!r.relocation&&r.recovery&&!r.splitRelocation)return null;if(r.relocation)return number(r.relocationLength);val a=number(r.start)?:return null;val b=number(r.end)?:return null;return kotlin.math.abs(a-b)}
    fun num(n:Double)=java.math.BigDecimal.valueOf(n).stripTrailingZeros().toPlainString().replace('.',',')
    fun solution(r:IncidentReport):String {
        if(!r.relocation)return r.solution
        val reuse=if(r.recovery){val length=if(r.relocation&&!r.splitRelocation)r.relocationLength else r.recoveryLength;val cable=if(r.relocation)r.cable else r.recoveryCable
            "Thu hồi kéo lại"+(number(length)?.takeIf{it>0}?.let{" ${num(it)}m"}?:"")+cable.takeIf{it.isNotBlank()}?.let{" $it"}.orEmpty()
        }else ""
        return listOf(reuse,r.solution.trim()).filter{it.isNotBlank()}.joinToString(", ")
    }
    fun summary(r:IncidentReport):String {
        if(r.recovery&&!r.relocation&&(!r.splitRelocation||!r.newCableEnabled))return "Không sử dụng cáp mới và vật tư"
        val parts=mutableListOf<String>();length(r)?.let{if(r.cable.isNotBlank())parts.add("${num(it)}m cáp ${r.cable}"+(if(r.relocation&&!r.splitRelocation&&r.recovery)" (thu hồi, kéo lại)" else ""))}
        r.materials.forEach{m->val q=number(m.quantity);if(q!=null&&q>0){val name=if(m.name=="Vật tư khác")m.type else m.name.lowercase(Locale.forLanguageTag("vi"))+(if(m.type.isNotBlank())" ${m.type}" else "");if(name.isNotBlank())parts.add(num(q)+(if(m.unit.isNotBlank()&&m.unit.lowercase() !in listOf("cái","bộ"))" ${m.unit}" else "")+" $name")}}
        return parts.joinToString(", ")
    }
    fun validDate(s:String,time:Boolean):Boolean {val pattern=if(time)"dd/MM/yyyy HH:mm" else "dd/MM/yyyy";val format=SimpleDateFormat(pattern,Locale.US).apply{isLenient=false};val p=java.text.ParsePosition(0);return format.parse(s,p)!=null&&p.index==s.length}
    fun handlingMinutes(received:String,restored:String):Long? {
        if(!validDate(received,true)||!validDate(restored,true))return null
        val format=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.US).apply{isLenient=false;timeZone=TimeZone.getTimeZone("UTC")}
        val minutes=(format.parse(restored)!!.time-format.parse(received)!!.time)/60000
        return minutes.takeIf{it>=0}
    }
    fun warnings(r:IncidentReport):List<String> {
        if(r.relocation){
            val w=mutableListOf<String>()
            listOf("Địa điểm" to r.location,"Người thực hiện" to r.reporter,"Ngày thực hiện" to r.date,"Nội dung di dời" to r.solution).forEach{if(it.second.isBlank())w.add("Thiếu ${it.first.lowercase()}")}
            if(r.date.isNotBlank()&&!validDate(r.date,false))w.add("Ngày thực hiện không hợp lệ")
            if(r.splitRelocation&&r.recovery){if(r.cable !in cableTypes)w.add("Chưa chọn chủng loại cáp");if((number(r.recoveryLength)?:0.0)<=0)w.add("Số mét thu hồi chưa hợp lệ")}
            if((!r.splitRelocation||r.newCableEnabled)&&(r.cable.isNotBlank()||r.relocationLength.isNotBlank()||r.newCableEnabled)){if(r.cable !in cableTypes)w.add("Chưa chọn loại cáp");if((length(r)?:0.0)<=0)w.add("Chiều dài di dời chưa hợp lệ")}
            r.materials.forEachIndexed{i,m->if(m.name.isBlank()||m.unit.isBlank()||(number(m.quantity)?:0.0)<=0||m.name=="Măng xông"&&m.type !in cableTypes||m.name=="Vật tư khác"&&m.type.isBlank())w.add("Vật tư ${i+1}: cần bổ sung thông tin")}
            r.groups.forEach{if(it.images.isEmpty())w.add("${it.name}: chưa có ảnh")}
            if(r.pendingPhoto.isNotBlank())w.add("Có ảnh đang chờ ghi GPS")
            return w
        }
        val w=mutableListOf<String>();if(r.pendingPhoto.isNotBlank())w.add("Có ảnh đang chờ ghi GPS, chưa đưa vào báo cáo");listOf("Địa điểm" to r.location,"Người báo cáo" to r.reporter,"Ngày báo cáo" to r.date,"Thời gian nhận" to r.received,"Thời gian khôi phục" to r.restored,"Nguyên nhân" to r.cause).forEach{if(it.second.isBlank())w.add("Thiếu ${it.first.lowercase()}")}
        val newCableEnabled=if(r.splitRelocation)r.newCableEnabled else !r.recovery
        if(!r.recovery&&r.solution.isBlank())w.add("Thiếu phương án xử lý")
        if(newCableEnabled){if(r.cable !in cableTypes)w.add("Chưa chọn chủng loại cáp mới");if(length(r)==null)w.add("Chưa có hai đầu số cáp hợp lệ")
            r.materials.forEachIndexed{i,m->if(m.name.isBlank()||m.unit.isBlank()||(number(m.quantity)?:0.0)<=0||m.name=="Măng xông"&&m.type !in cableTypes||m.name=="Vật tư khác"&&m.type.isBlank())w.add("Vật tư ${i+1}: thiếu tên, loại, đơn vị hoặc số lượng hợp lệ")}
        }else if(!r.recovery)w.add("Chọn thu hồi kéo lại hoặc kéo cáp mới")
        if(r.recovery){if(r.recoveryCable !in cableTypes)w.add("Chưa chọn chủng loại cáp thu hồi");if((number(r.recoveryLength)?:0.0)<=0)w.add("Chưa có số mét cáp thu hồi hợp lệ")}
        if(r.date.isNotBlank()&&!validDate(r.date,false))w.add("Ngày báo cáo không hợp lệ")
        listOf("Thời gian nhận" to r.received,"Thời gian khôi phục" to r.restored).forEach{if(it.second.isNotBlank()&&!validDate(it.second,true))w.add("${it.first} không hợp lệ")}
        if(r.distance.isNotBlank()&&number(r.distance)==null)w.add("Khoảng cách chưa hợp lệ")
        r.groups.forEach{if(it.images.isEmpty())w.add("${it.name}: chưa có ảnh")}
        return w
    }
}

object IncidentExport {
    fun xlsx(file:File,title:String,r:IncidentReport) {
        // Reuse the existing OOXML container, then replace its sheet and drawings.
        val base=File.createTempFile("incident-base", ".xlsx",file.parentFile)
        val files=linkedMapOf<String,ByteArray>()
        try {Exports.xlsx(base,title,emptyList());ZipFile(base).use{z->z.entries().asSequence().forEach{e->files[e.name]=z.getInputStream(e).use{it.readBytes()}}}}finally{base.delete()}
        val ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main";val rel="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val rows=StringBuilder();val merges=mutableListOf<String>();val anchors=StringBuilder();val relationships=StringBuilder();val imageFiles=linkedMapOf<String,File>();var row=0;var imageId=0
        fun cell(col:String,n:Int,value:Any,style:Int=4)=if(value is Number)"<c r=\"$col$n\" s=\"$style\" t=\"n\"><v>$value</v></c>" else "<c r=\"$col$n\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${Exports.xml(value.toString())}</t></is></c>"
        fun add(parts:List<Triple<String,String,Any>>,height:Int=24,style:Int=4,wide:Boolean=false){val adjusted=if(wide)parts else parts.map{(a,b,v)->Triple((65+(a[0].code-65)*2).toChar().toString(),(66+(b[0].code-65)*2).toChar().toString(),v)};row++;rows.append("<row r=\"$row\" ht=\"${height.coerceAtMost(409)}\" customHeight=\"1\">");adjusted.forEach{(a,b,v)->if(a!=b)merges.add("$a$row:$b$row");rows.append(cell(a,row,v,style))};rows.append("</row>")}
        fun band(text:String)=add(listOf(Triple("A","L",text)),27,1)
        fun height(text:String,width:Int)=text.split('\n').sumOf{((it.length+width-1)/width).coerceAtLeast(1)*15}
        band((if(r.relocation)"BÁO CÁO DI DỜI • " else "BÁO CÁO SỰ CỐ • ")+title)
        if(r.relocation){
            add(listOf(Triple("A","C","Địa điểm"),Triple("D","L",r.location)),height(r.location,46).coerceAtLeast(30))
            add(listOf(Triple("A","C","Người thực hiện"),Triple("D","F",r.reporter),Triple("G","I","Ngày thực hiện"),Triple("J","L",r.date)),34)
        }else {
        fun info(values:List<Any>){
            row++;val h=values.filterIndexed{i,_->i%2==1}.maxOfOrNull{height(it.toString(),16)}?:54
            rows.append("<row r=\"$row\" ht=\"${h.coerceIn(54,409)}\" customHeight=\"1\">")
            values.forEachIndexed{i,v->val start=(i/2)*6+(if(i%2==0)0 else 2);val end=start+(if(i%2==0)1 else 3);val col=(65+start).toChar().toString();merges.add("$col$row:${(65+end).toChar()}$row");rows.append(cell(col,row,v,if(i%2==0)1 else 3))};rows.append("</row>")
        }
        info(listOf("Địa điểm sự cố",r.location,"Khoảng cách đến trạm",r.distance+(if(r.distance.isNotBlank())" km" else ""),"Ngày báo cáo",r.date,"Người báo cáo",r.reporter))
        info(listOf("Tuyến",title,"Thời gian nhận thông tin",r.received,"Thời gian khôi phục dịch vụ",r.restored,"Tổng phút xử lý",IncidentRules.handlingMinutes(r.received,r.restored)?:""))
        add(listOf(Triple("A","B","Nguyên nhân"),Triple("C","L",r.cause)),height(r.cause,46).coerceAtLeast(36))
        }
        if(r.relocation&&r.cause.isNotBlank())add(listOf(Triple("A","B","Nguyên nhân di dời"),Triple("C","L",r.cause)),height(r.cause,46).coerceAtLeast(30))
        val solution=IncidentRules.solution(r);add(listOf(Triple("A","B",if(r.relocation)"Nội dung di dời" else "Phương án xử lý"),Triple("C","L",solution)),height(solution,46).coerceAtLeast(36))
        val summary=IncidentRules.summary(r);add(listOf(Triple("A","B","Vật tư"),Triple("C","L",summary)),height(summary,46).coerceAtLeast(30))
        IncidentRules.orderedForExcel(r).map{it.value}.filter{it.images.isNotEmpty()}.forEach{g->band(IncidentRules.groupTitle(g.name))
            g.images.chunked(2).forEachIndexed{pairIndex,pair->
                val note=g.pairNotes.getOrNull(pairIndex)?:g.note;val anchorRow=row
                val caption=(listOf(note)+pair.map{it.caption}).flatMap{it.lines()}.map{it.trim()}.filter{it.isNotBlank()}.distinct().joinToString("\n")
                val imageHeight=pair.maxOf{p->kotlin.math.ceil((minOf(378.0,304.0*p.height.coerceAtLeast(1)/p.width.coerceAtLeast(1))+2)*3/4).toInt()}
                val rowHeight=maxOf(54,imageHeight,height(caption,22)).coerceAtMost(409)
                add(listOf(Triple("A","I",""),Triple("J","O",caption),Triple("P","X","")),rowHeight,3,true)
                pair.forEachIndexed{i,p->val source=File(p.path);check(source.isFile&&source.length()>0){"Không đọc được ảnh nhóm ${g.name}"};val id=++imageId;val extension=MxPhotoUpload.extension(source);imageFiles["xl/media/image$id.$extension"]=source
                    val w=p.width.coerceAtLeast(1).toDouble();val h=p.height.coerceAtLeast(1).toDouble();val boxWidth=304.0;val boxHeight=minOf(378.0,rowHeight*4.0/3.0-2)
                    val scale=minOf(boxWidth/w,boxHeight/h);val width=w*scale;val height=h*scale
                    val cx=kotlin.math.round(width*9525).toLong();val cy=kotlin.math.round(height*9525).toLong()
                    val dx=kotlin.math.round((1+(boxWidth-width)/2)*9525).toLong();val dy=kotlin.math.round((1+(boxHeight-height)/2)*9525).toLong()
                    relationships.append("<Relationship Id=\"rId$id\" Type=\"$rel/image\" Target=\"../media/image$id.$extension\"/>")
                    anchors.append("""<xdr:oneCellAnchor><xdr:from><xdr:col>${if(i==0)0 else 15}</xdr:col><xdr:colOff>$dx</xdr:colOff><xdr:row>$anchorRow</xdr:row><xdr:rowOff>$dy</xdr:rowOff></xdr:from><xdr:ext cx="$cx" cy="$cy"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="$id" name="Ảnh $id"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId$id"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""")
                }
            }
        }
        // Style the perimeter cells too, so merged ranges retain all four borders in Excel.
        val borderedRows=Regex("<row\\b[^>]*>.*?</row>",RegexOption.DOT_MATCHES_ALL).replace(rows.toString()){match->
            val text=match.value;val n=Regex("r=\"(\\d+)\"").find(text)!!.groupValues[1].toInt()
            val cells=Regex("<c\\b[^>]*>.*?</c>",RegexOption.DOT_MATCHES_ALL).findAll(text).associate{c->Regex("r=\"([A-X])").find(c.value)!!.groupValues[1] to c.value}.toMutableMap()
            merges.filter{it.substringBefore(':').drop(1)==n.toString()}.forEach{m->val a=m[0];val b=m.substringAfter(':')[0];val style=Regex("s=\"(\\d+)\"").find(cells[a.toString()]?:"")?.groupValues?.get(1)?.toInt()?:0
                for(col in a..b)if(col.toString() !in cells)cells[col.toString()]=cell(col.toString(),n,"",style)
            }
            text.substringBefore('>')+">"+cells.toSortedMap().values.joinToString("")+"</row>"
        }
        rows.clear();rows.append(borderedRows)
        fun put(path:String,xml:String){files[path]=("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+xml).toByteArray(Charsets.UTF_8)}
        put("xl/worksheets/sheet1.xml","""<worksheet xmlns="$ns" xmlns:r="$rel"><sheetViews><sheetView showGridLines="0" workbookViewId="0"/></sheetViews><cols><col min="1" max="24" width="4.140625" customWidth="1"/></cols><sheetData>$rows</sheetData><mergeCells count="${merges.size}">${merges.joinToString(""){"<mergeCell ref=\"$it\"/>"}}</mergeCells><pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="portrait" fitToWidth="1" fitToHeight="0"/><drawing r:id="rId1"/></worksheet>""")
        put("xl/styles.xml","""<styleSheet xmlns="$ns"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Arial"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF194B5C"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/></patternFill></fill></fills><borders count="2"><border/><border><left style="thin"><color rgb="FF000000"/></left><right style="thin"><color rgb="FF000000"/></right><top style="thin"><color rgb="FF000000"/></top><bottom style="thin"><color rgb="FF000000"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="5">${(0..4).joinToString(""){"<xf numFmtId=\"0\" fontId=\"${if(it==1)1 else 0}\" fillId=\"${if(it==1)2 else if(it==2)3 else 0}\" borderId=\"${if(it==0)0 else 1}\" xfId=\"0\" applyAlignment=\"1\"><alignment ${if(it==1||it==3)"horizontal=\"center\" " else ""}vertical=\"center\" wrapText=\"1\"/></xf>"}}</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        put("xl/drawings/drawing1.xml","""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="$rel">$anchors</xdr:wsDr>""")
        put("xl/drawings/_rels/drawing1.xml.rels","""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationships</Relationships>""")
        val types=String(files.getValue("[Content_Types].xml"),Charsets.UTF_8)
        files["[Content_Types].xml"]=types.replace("</Types>","<Default Extension=\"png\" ContentType=\"image/png\"/></Types>").toByteArray(Charsets.UTF_8)
        ZipOutputStream(file.outputStream()).use{z->files.forEach{(p,bytes)->z.putNextEntry(ZipEntry(p));z.write(bytes);z.closeEntry()};imageFiles.forEach{(p,f)->z.putNextEntry(ZipEntry(p));f.inputStream().use{it.copyTo(z)};z.closeEntry()}}
    }
}
