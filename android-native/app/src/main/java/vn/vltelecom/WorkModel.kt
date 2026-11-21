package vn.vltelecom

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

enum class Screen { HOME, PLACES, JOBS, PHOTOS, EDIT }
data class UiState(val screen:Screen=Screen.HOME,val kind:String="",val place:Place?=null,val job:Job?=null,val places:List<Place> = emptyList(),val jobs:List<Job> = emptyList(),val photos:List<Photo> = emptyList(),val busy:Boolean=false,val message:String?=null,val exportPath:String?=null,val batchWarning:String?=null,val repairPhotoId:Long=0,val routeTypes:Map<Long,String> = emptyMap(),val routeMonths:Map<Long,String> = emptyMap(),val reportMonths:Map<Long,String> = emptyMap(),val reportCompleted:Map<Long,Boolean> = emptyMap())

/** Application-owned operations complete across rotation; a transaction is never tied to an Activity. */
class WorkModel(private val app:WorkApp) {
    val state=MutableStateFlow(UiState())
    fun clearRepair(){state.value=state.value.copy(repairPhotoId=0)}
    val drafts=DraftStore(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val dao get()=app.db.dao()
    private val nav=app.getSharedPreferences("navigation",Context.MODE_PRIVATE)
    init {execute {
        FolderStore(app).drain()
        val d=drafts.read()
        if(d!=null && dao.job(d.jobId)!=null) {
            // Recover a completed photo even if Android killed the app while the camera was open.
            if(d.capturePending && d.rawPath.isNotEmpty() && File(d.rawPath).length()>0)drafts.save(d.copy(capturePending=false,capturedAt=File(d.rawPath).lastModified()))
            openEditor(d.jobId)
        }else if(nav.getLong("job",0)>0 && dao.job(nav.getLong("job",0))!=null) loadPhotos(nav.getLong("job",0))
    }}
    private fun execute(action:suspend ()->Unit){if(state.value.busy)return;state.value=state.value.copy(busy=true,message=null);scope.launch {try{action()}catch(e:Exception){if(e is CancellationException)throw e;state.value=state.value.copy(message=e.message?:"Không thể thực hiện. Hãy thử lại.")}finally{state.value=state.value.copy(busy=false);if(state.value.screen in listOf(Screen.HOME,Screen.PLACES))AutoSync.receiveIfPending(app)}}}
    fun clearMessage(){state.value=state.value.copy(message=null)}
    fun consumeExport(){state.value=state.value.copy(exportPath=null)}
    fun saveExport(uri:android.net.Uri,path:String)=execute{withContext(Dispatchers.IO){val source=File(path);check(source.isFile){"File xuất không còn. Hãy xuất lại."};val output=app.contentResolver.openOutputStream(uri)?:error("Không mở được nơi lưu.");output.use{out->source.inputStream().use{it.copyTo(out)}}};state.value=state.value.copy(message="Đã lưu file báo cáo.")}
    suspend fun close(){scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()}
    fun home(){if(state.value.busy)return;state.value=UiState();nav.edit().clear().apply();AutoSync.refresh()}
    fun places(kind:String)=execute{check(ServerSession.allowed(kind)){"Bạn chưa được cấp quyền mục này"};val initial=listedPlaces(kind);state.value=UiState(Screen.PLACES,kind,places=initial,busy=true,routeTypes=routeTypes(kind),routeMonths=routeMonths(kind),reportMonths=reportMonths(kind,initial),reportCompleted=reportCompleted(kind,initial));if(kind=="Tuyến cáp")try{ServerSync.receiveCableTasks(app){}}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e};FolderStore(app).flatten(kind);val rows=listedPlaces(kind);state.value=UiState(Screen.PLACES,kind,places=rows,busy=true,routeTypes=routeTypes(kind),routeMonths=routeMonths(kind),reportMonths=reportMonths(kind,rows),reportCompleted=reportCompleted(kind,rows));if(kind in listOf("Sự cố","Di dời"))AutoSync.refresh()}
    private suspend fun listedPlaces(kind:String):List<Place>{
        val rows=dao.places(kind)
        if(kind!="Sự cố")return rows
        val records=dao.incidents().associateBy{it.placeId}
        return IncidentReceivedOrder.sorted(rows){place->records[place.id]?.let{IncidentReceivedOrder.fromReport(it.value)}}
    }
    private suspend fun routeTypes(kind:String):Map<Long,String>{
        if(kind!="Tuyến cáp")return emptyMap()
        return dao.places(kind).associate{p->p.id to (dao.incident(p.id)?.let{runCatching{org.json.JSONObject(it.value).optString("workType")}.getOrDefault("")}?:"")}
    }
    private suspend fun routeMonths(kind:String):Map<Long,String>{
        if(kind!="Tuyến cáp")return emptyMap()
        val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE)
        return dao.places(kind).associate{p->p.id to RouteMonth.key(prefs.getLong("assignment-created-${p.id}",0))}
    }
    private suspend fun reportMonths(kind:String,places:List<Place>):Map<Long,String>{
        if(kind !in listOf("Sự cố","Di dời"))return emptyMap()
        val records=dao.incidents().associateBy{it.placeId}
        return places.associate{place->
            val date=records[place.id]?.let{runCatching{IncidentReport.parse(it.value).date}.getOrDefault("")}.orEmpty()
            place.id to RouteMonth.reportKey(date)
        }
    }
    private suspend fun reportCompleted(kind:String,places:List<Place>):Map<Long,Boolean>{
        if(kind !in listOf("Sự cố","Di dời"))return emptyMap()
        val records=dao.incidents().associateBy{it.placeId}
        return places.associate{place->
            val value=records[place.id]?.value
            place.id to if(value!=null)ReportCompletion.isComplete(value) else dao.jobs(place.id).any{it.completed}
        }
    }
    fun refreshPlacesAfterSync(kind:String){
        val before=state.value;if(before.screen!=Screen.PLACES||before.kind!=kind||before.busy)return
        scope.launch{val rows=listedPlaces(kind);val types=routeTypes(kind);val months=routeMonths(kind);val reports=reportMonths(kind,rows);val completed=reportCompleted(kind,rows);val current=state.value;if(current.screen==Screen.PLACES&&current.kind==kind&&!current.busy)state.value=current.copy(places=rows,routeTypes=types,routeMonths=months,reportMonths=reports,reportCompleted=completed)}
    }
    fun createPlace(name:String)=execute{val kind=state.value.kind;check(kind!="Tuyến cáp"){"Tạo tuyến và giao việc trên server."};dao.addPlace(Place(kind=kind,name=name.trim()));AutoSync.changed();state.value=state.value.copy(places=listedPlaces(kind))}
    fun jobs(p:Place)=execute {
        if(p.kind=="Tuyến cáp" || p.kind=="Sự cố" || p.kind=="Di dời") {
            val completed=if(p.kind in listOf("Sự cố","Di dời"))dao.incident(p.id)?.let{ReportCompletion.isComplete(it.value)}?:dao.jobs(p.id).any{it.completed}else false
            val id=app.db.withTransaction {
                val current=dao.jobs(p.id).firstOrNull()
                if(current==null)dao.addJob(Job(placeId=p.id,name=p.name,completed=completed)) else {
                    if(p.kind in listOf("Sự cố","Di dời")&&current.completed!=completed)dao.markJob(current.id,completed)
                    current.id
                }
            }
            loadPhotos(id)
        } else state.value=UiState(Screen.JOBS,p.kind,p,jobs=dao.jobs(p.id),busy=true)
    }
    fun renamePlace(p:Place,name:String)=execute {
        SharedReports.ensureOwner(app,p.id);check(p.kind!="Tuyến cáp"){"Đổi tên tuyến trên server."}
        val value=name.trim();check(value.isNotBlank()){"Hãy nhập tên."}
        val current=dao.place(p.id)?:error("Mục này đã bị xóa")
        val created=mutableListOf<File>();var skipped=0
        try {
            val document=dao.incident(p.id)?.let{org.json.JSONObject(it.value)}
            val replacements=mutableMapOf<String,String>()
            suspend fun replace(path:String):String {
                replacements[path]?.let{return it}
                val target=File(app.accountFilesDir,"photos/renamed-${UUID.randomUUID()}.jpg")
                created.add(target);created.add(File(target.path+".original.jpg"))
                val updated=withContext(Dispatchers.IO){PhotoRenderer.renamed(File(path),target,value)}
                if(!updated)skipped++
                return (if(updated)target.path else path).also{replacements[path]=it}
            }
            suspend fun visit(node:Any?){
                when(node){
                    is org.json.JSONObject->{
                        if(node.has("path")&&node.has("width")&&node.has("height"))node.put("path",replace(node.getString("path")))
                        val keys=node.keys().asSequence().toList();for(key in keys)visit(node.opt(key))
                    }
                    is org.json.JSONArray->for(i in 0 until node.length())visit(node.opt(i))
                }
            }
            visit(document)
            document?.optJSONArray("routes")?.let{routes->for(i in 0 until routes.length()){val route=routes.getJSONObject(i);if(route.optString("name")==current.name)route.put("name",value)}}
            val updates=mutableListOf<Photo>()
            for(job in dao.jobs(p.id))for(photo in dao.photos(job.id)){
                if(photo.originalPath==photo.path||!File(photo.originalPath).isFile){skipped++;continue}
                val target=File(app.accountFilesDir,"photos/renamed-${UUID.randomUUID()}.jpg");created.add(target)
                withContext(Dispatchers.IO){PhotoRenderer.write(File(photo.originalPath),target,value,Draft(job.id,photo.id,photo.originalPath,photo.seq.toString(),photo.locationName,photo.note,photo.lat,photo.lon,photo.accuracy,photo.capturedAt,photo.capturedAt,showDate=photo.showDate,textColor=photo.textColor))}
                updates.add(photo.copy(path=target.path))
            }
            app.db.withTransaction {
                dao.renamePlace(p.id,value);dao.renameLinkedJobs(p.id,current.name,value)
                if(document!=null)dao.saveIncident(IncidentRecord(p.id,document.toString()))
                updates.forEach{dao.updatePhoto(it)}
            }
            AutoSync.changed()
            state.value=state.value.copy(places=listedPlaces(p.kind),message=if(skipped==0)"Đã đổi tên và cập nhật tên trên ảnh." else "Đã đổi tên. $skipped ảnh cũ không còn ảnh gốc hoặc dữ liệu chữ nên chưa thể thay tên trên ảnh.")
        }catch(e:Exception){withContext(Dispatchers.IO){created.forEach{it.delete()}};throw e}
    }

    fun deleteRoute(p:Place)=deleteFolders(listOf(p))
    fun deleteFolders(items:List<Place>)=execute {
        val kind=state.value.kind;items.forEach{SharedReports.ensureOwner(app,it.id)};FolderStore(app).delete(items.map{it.id});nav.edit().clear().apply()
        state.value=UiState(Screen.PLACES,kind,places=listedPlaces(kind),busy=true,message="Đã xóa ${items.size} thư mục và dữ liệu bên trong.")
    }
    fun copyFolder(p:Place)=execute{SharedReports.ensureOwner(app,p.id);check(p.kind!="Tuyến cáp"){"Tạo tuyến và giao việc trên server."};FolderStore(app).copy(p);AutoSync.changed();state.value=state.value.copy(places=listedPlaces(p.kind),message="Đã sao chép thư mục.")}
    fun createJob(name:String)=execute{val p=state.value.place?:error("Không tìm thấy sự cố/tuyến");dao.addJob(Job(placeId=p.id,name=name.trim()));AutoSync.changed();state.value=state.value.copy(jobs=dao.jobs(p.id))}
    fun savedJobs(){if(!state.value.busy)state.value=state.value.copy(screen=Screen.JOBS)}
    fun photos(id:Long)=execute{loadPhotos(id)}
    private suspend fun loadPhotos(id:Long){ServerSync.drain();val skipped=dao.job(id)?.let{RoutePhotoTitles.refresh(app,it.placeId)}?:0;val j=dao.job(id)?:error("Công việc không còn tồn tại");val p=dao.place(j.placeId)?:error("Không tìm thấy sự cố/tuyến");check(ServerSession.allowed(p.kind)){"Bạn chưa được cấp quyền mục này"};state.value=UiState(Screen.PHOTOS,p.kind,p,j,photos=dao.photos(id),jobs=dao.jobs(p.id),busy=true,message=if(skipped>0)"$skipped ảnh không còn ảnh gốc nên chưa cập nhật tên tuyến." else null,reportCompleted=reportCompleted(p.kind,listOf(p)));nav.edit().putLong("job",id).apply()}
    private suspend fun openEditor(id:Long){loadPhotos(id);state.value=state.value.copy(screen=Screen.EDIT)}
    fun newDraft()=execute{
        val j=state.value.job?:error("Chưa chọn công việc");SharedReports.ensureOwner(app,j.placeId);check(!j.completed){"Mở lại công việc trước khi thêm ảnh."}
        val existing=drafts.read();check(existing==null){"Có bản nháp chưa lưu. Hãy mở bản nháp hoặc xóa bản nháp trước."}
        val next=Rules.nextSequence(dao.photos(j.id).map{it.seq})
        drafts.save(Draft(j.id,seq=next.toString()));openEditor(j.id)
    }
    fun resumeDraft()=execute{openEditor(drafts.read()?.jobId?:error("Không còn bản nháp"))}
    fun addLibraryPhoto(photo:LibraryPhoto,file:File)=execute{
        val j=state.value.job?:error("Hãy mở một tuyến trước.")
        check(state.value.screen==Screen.PHOTOS&&dao.place(j.placeId)?.kind=="Tuyến cáp"){"Chỉ thêm ảnh thư viện vào mục Tuần tuyến."}
        check(ServerSession.allowed("Tuyến cáp")){"Bạn chưa được cấp quyền Tuần tuyến."}
        check(!j.completed){"Hãy mở lại tuyến trước khi thêm ảnh."}
        check(drafts.read()?.jobId!=j.id){"Hãy lưu hoặc bỏ bản nháp đang chụp trước."}
        val root=app.accountFilesDir.canonicalFile.toPath();val source=file.canonicalFile
        check(source.isFile&&source.toPath().startsWith(root)){"Ảnh thư viện không còn trên điện thoại."}
        check(photo.lat.isFinite()&&photo.lon.isFinite()&&photo.lat in -90.0..90.0&&photo.lon in -180.0..180.0&&(photo.lat!=0.0||photo.lon!=0.0)){"Ảnh thư viện chưa có tọa độ GPS để đưa vào tuần tuyến."}
        val sequence=Rules.nextSequence(dao.photos(j.id).map{it.seq});val target=File(app.accountFilesDir,"photos/library-report-${UUID.randomUUID()}.jpg")
        try{withContext(Dispatchers.IO){target.parentFile?.mkdirs();source.inputStream().use{input->target.outputStream().use{input.copyTo(it)}}};val now=photo.capturedAt.takeIf{it>0}?:target.lastModified();val row=Photo(jobId=j.id,seq=sequence,locationName=photo.locationName.ifBlank{photo.name},lat=photo.lat,lon=photo.lon,accuracy=photo.accuracy,note=photo.note,path=target.path,capturedAt=now,originalPath=target.path,photographer=photo.photographer);dao.addPhoto(row)}catch(e:Exception){target.delete();throw e}
        AutoSync.changed();loadPhotos(j.id)
    }
    fun edit(p:Photo)=execute{
        check(drafts.read()==null){"Hãy lưu hoặc xóa bản nháp trước khi sửa ảnh khác."}
        val j=dao.job(p.jobId)?:error("Không còn công việc");val q=dao.incident(j.placeId)?.let{org.json.JSONObject(it.value).optJSONObject("points")?.optJSONObject(p.seq.toString())}
        drafts.save(Draft(p.jobId,p.id,p.originalPath,p.seq.toString(),p.locationName,p.note,p.lat,p.lon,p.accuracy,p.capturedAt,p.capturedAt,showDate=p.showDate,textColor=p.textColor,address=q?.optString("address"),proposal=q?.optString("proposal"),assessment=q?.optString("assessment")));openEditor(p.jobId)
    }
    fun discard()=execute{val d=drafts.read()?:return@execute;if(d.photoId==0L && d.rawPath.isNotEmpty())withContext(Dispatchers.IO){File(d.rawPath).delete()};drafts.clear();loadPhotos(d.jobId)}
    fun setRoutePhotoDates(show:Boolean,date:Long?=null)=execute{
        val job=state.value.job?:error("Chưa chọn tuyến")
        val place=dao.place(job.placeId)?:error("Tuyến đã bị xóa")
        check(place.kind=="Tuyến cáp"){"Chỉ áp dụng cho Tuần tuyến"}
        val jobs=dao.jobs(place.id);val jobIds=jobs.map{it.id}.toSet()
        check(drafts.read()?.jobId !in jobIds){"Hãy lưu hoặc hủy bản nháp của tuyến trước."}
        val account=app.accountScope
        val photos=jobs.flatMap{dao.photos(it.id)}.filter{it.showDate!=show||(date!=null&&it.capturedAt!=date)}
        check(photos.isNotEmpty()){if(show)"Các ảnh đã hiện ngày tháng." else "Các ảnh đã ẩn ngày tháng."}
        val made=mutableListOf<File>();val updates=mutableListOf<Pair<Photo,Photo>>();var committed=false
        try{
            withContext(Dispatchers.IO){
                for(p in photos)check(p.originalPath!=p.path&&File(p.originalPath).isFile){"Ảnh STT ${p.seq} thiếu ảnh gốc. Chưa thay đổi ảnh nào."}
                for(p in photos){
                    check(account==app.accountScope){"Tài khoản đã thay đổi"}
                    val out=File(app.accountFilesDir,"photos/date-${UUID.randomUUID()}.jpg");out.parentFile?.mkdirs();made.add(out)
                    PhotoRenderer.write(File(p.originalPath),out,place.name,Draft(p.jobId,p.id,p.originalPath,p.seq.toString(),p.locationName,p.note,p.lat,p.lon,p.accuracy,date?:p.capturedAt,date?:p.capturedAt,showDate=show,textColor=p.textColor))
                    updates.add(p to p.copy(path=out.path,showDate=show,capturedAt=date?:p.capturedAt))
                }
            }
            check(account==app.accountScope){"Tài khoản đã thay đổi"}
            app.db.withTransaction{
                check(dao.place(place.id)==place){"Tuyến vừa thay đổi. Hãy thử lại."}
                check(drafts.read()?.jobId !in jobIds){"Có bản nháp đang sửa tuyến. Hãy lưu hoặc hủy trước."}
                updates.forEach{(old,new)->check(dao.photo(old.id)==old){"Ảnh vừa thay đổi. Chưa lưu thay đổi nào."};dao.updatePhoto(new);dao.queueFile(FileCleanup(old.path))}
            }
            committed=true;AutoSync.changed();loadPhotos(job.id)
            state.value=state.value.copy(message="Đã ${if(show)"hiện" else "ẩn"} ngày tháng trên ${updates.size} ảnh của tuyến.")
        }finally{if(!committed)made.forEach{it.delete()}}
    }
    fun complete()=execute{val j=state.value.job?:error("Chưa chọn công việc");SharedReports.ensureOwner(app,j.placeId);check(drafts.read()?.jobId!=j.id){"Lưu hoặc xóa bản nháp của công việc trước khi hoàn thành."};app.db.withTransaction {if(!j.completed)check(dao.photos(j.id).isNotEmpty() || dao.incident(j.placeId)?.let{IncidentDossier.parse(it.value,j.name).routes.any{r->r.report.groups.any{g->g.images.isNotEmpty()}}}==true){"Cần ít nhất một ảnh đã lưu."};dao.markJob(j.id,!j.completed)};AutoSync.changed();loadPhotos(j.id)}
    fun save()=execute{
        val savedDraft=drafts.read()?:error("Không tìm thấy bản nháp")
        val isPatrol=dao.job(savedDraft.jobId)?.let{dao.place(it.placeId)?.kind=="Tuyến cáp"}==true
        val d=if(isPatrol)savedDraft.copy(note=savedDraft.note.uppercase(java.util.Locale.forLanguageTag("vi-VN"))) else savedDraft
        Rules.validate(d.seq,d.name,d.note,d.lat,d.lon)?.let{error(it)}
        check(!d.capturePending){"Chưa hoàn tất chụp ảnh."}
        val raw=File(d.rawPath);check(raw.isFile && raw.length()>0){"Hãy chụp ảnh trước khi lưu."}
        val j=dao.job(d.jobId)?:error("Không còn công việc");check(!j.completed || d.photoId!=0L){"Công việc đã hoàn thành."}
        val seq=d.seq.toInt();val existing=dao.photos(j.id)
        val old=if(d.photoId!=0L)dao.photo(d.photoId)?:error("Ảnh đã bị xóa") else null
        val patrol=dao.place(j.placeId)?.kind=="Tuyến cáp"
        val shifts=if(patrol)RouteSequence.shifts(existing.map{it.seq},old?.seq,seq) else emptyMap()
        if(!patrol)check(existing.none{it.seq==seq&&it.id!=d.photoId}){"STT đã tồn tại. Hãy chọn STT khác."}
        val changed=existing.filter{it.seq in shifts}
        val rendered=mutableMapOf<Long,File>()
        val dest=File(raw.parentFile,"photo_${UUID.randomUUID()}.jpg")
        try {
            withContext(Dispatchers.IO){
                for(photo in changed){
                    val source=File(photo.originalPath)
                    check(photo.originalPath!=photo.path&&source.isFile){"Ảnh STT ${photo.seq} thiếu ảnh gốc để đổi số trên hình. Chưa đổi STT nào."}
                    val output=File(raw.parentFile,"photo_${UUID.randomUUID()}.jpg");rendered[photo.id]=output
                    PhotoRenderer.write(source,output,j.name,Draft(j.id,photo.id,source.path,shifts.getValue(photo.seq).toString(),photo.locationName,photo.note,photo.lat,photo.lon,photo.accuracy,photo.capturedAt,photo.capturedAt,showDate=photo.showDate,textColor=photo.textColor))
                }
                PhotoRenderer.write(raw,dest,j.name,d)
            }
            app.db.withTransaction {
                val current=dao.job(j.id)?:error("Mục này đã bị xóa")
                check(!current.completed || old!=null){"Công việc đã hoàn thành."}
                check(dao.photos(j.id)==existing){"Danh sách ảnh vừa thay đổi. Hãy lưu lại."}
                if(patrol){
                    val mapping=shifts.toMutableMap();if(old!=null&&old.seq!=seq)mapping[old.seq]=seq
                    if(mapping.isNotEmpty()){
                        val saved=dao.incident(j.placeId)
                        if(saved!=null){
                            val doc=org.json.JSONObject(saved.value);val points=doc.optJSONObject("points")
                            if(points!=null){
                                val moved=mapping.mapNotNull{(from,to)->points.opt(from.toString())?.let{Triple(from,to,it)}}
                                moved.forEach{points.remove(it.first.toString())};moved.forEach{points.put(it.second.toString(),it.third)}
                                dao.saveIncident(IncidentRecord(j.placeId,doc.toString()))
                            }
                        }
                    }
                    val moving=changed+listOfNotNull(old).filter{it.seq!=seq}
                    moving.forEachIndexed{index,photo->dao.updatePhoto(photo.copy(seq=-(index+1)))}
                    changed.forEach{photo->dao.updatePhoto(photo.copy(seq=shifts.getValue(photo.seq),path=rendered.getValue(photo.id).path))}
                }
                val p=Photo(d.photoId,j.id,seq,d.name.trim(),d.lat!!,d.lon!!,d.accuracy,d.note.trim(),dest.path,d.capturedAt,raw.path,d.showDate,d.textColor,if(old==null)PhotoLibrary.photographer() else old.photographer)
                if(old==null)dao.addPhoto(p) else {dao.updatePhoto(p);if(!patrol)CablePoints.move(app,j.placeId,old.seq,seq);dao.markJob(j.id,false)}
                if(d.address!=null||d.proposal!=null||d.assessment!=null){val info=dao.incident(j.placeId)?.let{org.json.JSONObject(it.value)}?:org.json.JSONObject().put("cableWork",true);val points=info.optJSONObject("points")?:org.json.JSONObject();val q=points.optJSONObject(seq.toString())?:org.json.JSONObject();d.address?.let{q.put("address",it)};d.proposal?.let{q.put("proposal",it)};d.assessment?.let{q.put("assessment",it)};q.put("description",d.note.trim());points.put(seq.toString(),q);info.put("points",points);dao.saveIncident(IncidentRecord(j.placeId,info.toString()))}

            }
        }catch(e:Exception){dest.delete();rendered.values.forEach{it.delete()};throw e}
        val libraryDetails=org.json.JSONObject().put("seq",seq).put("locationName",d.name.trim()).put("lat",d.lat).put("lon",d.lon).put("accuracy",d.accuracy.toDouble()).put("note",d.note.trim())
        val libraryWarnings=if(old==null)listOfNotNull(runCatching{PhotoLibrary.save(app,dest,d.capturedAt)}.exceptionOrNull()?.let{"chưa lưu được vào album điện thoại: ${it.message}"},runCatching{PhotoLibrary.queueServerUpload(app,dest,d.capturedAt,libraryDetails)}.exceptionOrNull()?.let{"chưa xếp được vào thư viện server: ${it.message}"}) else emptyList()
        drafts.clear();AutoSync.changed();if(old!=null && old.path!=raw.path)withContext(Dispatchers.IO){File(old.path).delete()};loadPhotos(j.id);if(libraryWarnings.isNotEmpty())state.value=state.value.copy(message="Ảnh đã lưu trong báo cáo. ${libraryWarnings.joinToString("; ")}");if(old==null&&d.assessment=="NOK")state.value=state.value.copy(repairPhotoId=dao.photos(j.id).firstOrNull{it.seq==seq}?.id?:0)
    }
    fun movePhotos(ids:Set<Long>,destinationId:Long)=execute {
        check(ServerSession.allowed("Tuyến cáp")){"Bạn chưa được cấp quyền Tuần tuyến"}
        val job=state.value.job?:error("Hãy mở tuyến nguồn")
        val count=RoutePhotoMove.move(app,job.id,ids,destinationId)
        AutoSync.changed();loadPhotos(job.id)
        state.value=state.value.copy(message="Đã chuyển $count ảnh sang tuyến đích. Hai tuyến được mở lại để kiểm tra.")
    }
    fun delete(p:Photo)=execute {
        check(drafts.read()?.photoId!=p.id){"Hãy lưu hoặc bỏ bản nháp đang sửa vị trí này trước khi xóa."}
        app.db.withTransaction {
            val job=dao.job(p.jobId)?:error("Mục này không còn tồn tại")
            dao.deletePhoto(p);CablePoints.move(app,job.placeId,p.seq,null)
            if(job.completed)dao.markJob(job.id,false)
        }
        AutoSync.changed()
        withContext(Dispatchers.IO) {
            val root=File(app.accountFilesDir,"photos").canonicalFile.toPath()
            listOf(p.path,p.originalPath).distinct().forEach { path ->
                val file=File(path).canonicalFile
                if(file.toPath().startsWith(root))file.delete()
            }
        }
        loadPhotos(p.jobId)
        state.value=state.value.copy(message="Đã xóa vị trí. Có thể dùng lại STT ${p.seq} khi thêm ảnh.")
    }
    private var pendingBatch:Triple<List<Place>,Boolean,Boolean>?=null
    fun confirmBatch(yes:Boolean){val request=pendingBatch;pendingBatch=null;state.value=state.value.copy(batchWarning=null);if(yes&&request!=null)exportFolders(request.first,request.second,true,request.third)}
    fun exportFolders(items:List<Place>,kmz:Boolean,confirmed:Boolean=false,both:Boolean=false)=execute{
        check(items.isNotEmpty()){ "Hãy chọn thư mục." }
        items.forEach{RoutePhotoTitles.refresh(app,it.id)}
        val snapshots=items.map{p->Triple(p,dao.jobs(p.id).flatMap{dao.photos(it.id)},if(p.kind=="Tuyến cáp")null else dao.incident(p.id)?.let{IncidentReport.parse(it.value)}?:if(p.kind=="Di dời")IncidentReport.relocation()else IncidentReport())}
        snapshots.forEach{it.third?.let(IncidentRules::prepare)}
        val warnings=snapshots.flatMap{(p,rows,r)->r?.let{IncidentRules.warnings(it).map{w->"${p.name}: $w"}}?:if(rows.isEmpty())listOf("${p.name}: Chưa có ảnh/vị trí")else emptyList()}
        if(!confirmed&&warnings.isNotEmpty()){pendingBatch=Triple(items,kmz,both);state.value=state.value.copy(batchWarning=warnings.joinToString("\n"));return@execute}
        val folder=File(app.cacheDir,"exports/${UUID.randomUUID()}").apply{mkdirs()}
        val archive=File(folder,"HP-Telecom-${items.size}-${if(both)"excel-kmz" else if(kmz)"kmz" else "xlsx"}.zip")
        try{withContext(Dispatchers.IO){
            val used=mutableSetOf<String>()
            java.util.zip.ZipOutputStream(archive.outputStream()).use{zip->
                snapshots.forEach{(p,rows,r)->
                    val base=p.name.map{if(it.code<32||it in "\\/:*?\"<>|") '_' else it}.joinToString("").take(70).trim().trimEnd('.').ifBlank{"Bao-cao"}
                    for(exportKmz in if(both)listOf(false,true)else listOf(kmz)){
                    val ext=if(exportKmz)"kmz" else "xlsx";var name="$base.$ext";var n=2
                    while(!used.add(name.lowercase(java.util.Locale.ROOT))){name="$base (${n++}).$ext"}
                    val temp=File(folder,"item.tmp")
                    try{
                        if(exportKmz){check(rows.isNotEmpty()){ "Chưa có vị trí để xuất KMZ." };Exports.kmz(temp,p.name,rows)}else if(r!=null)IncidentExport.xlsx(temp,p.name,r)else {Exports.xlsx(temp,p.name,rows);CableHeader.apply(temp,p.name,rows,dao.incident(p.id)?.let{org.json.JSONObject(it.value)})}
                        zip.putNextEntry(java.util.zip.ZipEntry(name));temp.inputStream().use{it.copyTo(zip)};zip.closeEntry()
                    }catch(e:Exception){throw IllegalStateException("${p.name}: ${e.message}",e)}finally{temp.delete()}
                    }
                }
            }
        }}catch(e:Exception){archive.delete();folder.delete();throw e}
        state.value=state.value.copy(exportPath=archive.path)
    }
    fun export(kmz:Boolean)=execute{
        state.value.place?.let{RoutePhotoTitles.refresh(app,it.id)}
        val j=state.value.job?:error("Chưa chọn công việc");val rows=dao.photos(j.id);check(rows.isNotEmpty()){"Chưa có ảnh để xuất."}
        val p=state.value.place?:error("Chưa chọn sự cố/tuyến")
        val title=p.name
        val fileName=title.map {c->if(c.code<32 || c in "\\/:*?\"<>|") '_' else c}
            .joinToString("").take(70).trim().trimEnd('.').ifBlank {"Tuyen_cap"}
        val ext=if(kmz)"kmz" else "xlsx"
        // Separate directories retain earlier shared exports without changing the visible filename.
        val folder=File(File(app.cacheDir,"exports"),UUID.randomUUID().toString()).apply{mkdirs()}
        val dest=File(folder,"$fileName.$ext")
        try{withContext(Dispatchers.IO){if(kmz)Exports.kmz(dest,title,rows) else {Exports.xlsx(dest,title,rows);CableHeader.apply(dest,title,rows,dao.incident(p.id)?.let{org.json.JSONObject(it.value)})}}}catch(e:Exception){dest.delete();folder.delete();throw e}
        state.value=state.value.copy(exportPath=dest.path)
    }
}
