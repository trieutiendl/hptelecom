package vn.vltelecom

import android.content.Context
import android.graphics.Color
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ServerSync {
 private val mutex=Mutex()
 private val contributing=mutableSetOf<Long>()
 suspend fun drain(){mutex.withLock{}}
 private fun sha(s:String)=java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
 private fun stateFile(app:WorkApp,id:Long)=AtomicFile(File(app.accountFilesDir,"sync/$id.json").apply{parentFile?.mkdirs()})
 private fun pending(app:WorkApp,id:Long)=runCatching{JSONObject(String(stateFile(app,id).readFully(),Charsets.UTF_8))}.getOrNull()
 private fun savePending(app:WorkApp,id:Long,value:JSONObject){val f=stateFile(app,id);val out=f.startWrite();try{out.write(value.toString().toByteArray(Charsets.UTF_8));f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}}
 private fun baseFile(app:WorkApp,id:Long)=AtomicFile(File(app.accountFilesDir,"sync/$id-base.json").apply{parentFile?.mkdirs()})
 private fun baseline(app:WorkApp,id:Long)=runCatching{JSONObject(String(baseFile(app,id).readFully(),Charsets.UTF_8))}.getOrNull()
 private fun saveBaseline(app:WorkApp,id:Long,revision:Int,local:JSONObject,wire:JSONObject){
  val f=baseFile(app,id);val out=f.startWrite()
  try{out.write(JSONObject().put("revision",revision).put("localWire",local).put("wire",wire).toString().toByteArray(Charsets.UTF_8));f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
 }
 private fun plain(value:Any?):Any?=when(value){
  null,JSONObject.NULL->null
  is JSONObject->value.keys().asSequence().associateWith{plain(value.opt(it))}
  is JSONArray->(0 until value.length()).map{plain(value.opt(it))}
  else->value
 }
 private fun merge(base:JSONObject,local:JSONObject,remote:JSONObject):JSONObject {
  val result=SyncMerge.merge(plain(base),plain(local),plain(remote))
  if(!result.successful)throw ServerSession.Failure(409,"Cùng nội dung đã được sửa trên điện thoại và server (${result.conflicts.take(2).joinToString()}). Đã giữ dữ liệu trên máy, chưa ghi đè.")
  @Suppress("UNCHECKED_CAST") return JSONObject(result.value as Map<String,Any?>)
 }
 private fun assetKey(module:String,path:String,f:File)=sha("$module|$path|${f.length()}|${f.lastModified()}")
 private fun folderAssetKey(remote:String,module:String,path:String,f:File)="folder-$remote-"+assetKey(module,path,f)
 fun canRemoveSharedImage(app:WorkApp,id:Long,path:String):Boolean {
  if(!SharedReports.contributor(app,id))return true
  if(id in contributing||SharedReports.revoked(app,id)||pending(app,id)!=null)return false
  return path.isNotBlank()&&baseline(app,id)?.optJSONObject("localWire")!=null
 }
 suspend fun cleanupReportPhotos(app:WorkApp,paths:List<String>)=mutex.withLock{FolderStore(app).cleanupUnusedPhotos(paths)}

 private suspend fun images(value:Any?,map:suspend (String)->String){when(value){is JSONObject->{for(key in value.keys().asSequence().toList()){val v=value.opt(key);if(key in listOf("file","originalFile")&&v is String&&v.isNotEmpty())value.put(key,map(v))else images(v,map)}};is JSONArray->{for(i in 0 until value.length())images(value.opt(i),map)}}}
 private fun wireReport(value:String):JSONObject {val r=JSONObject(value);val legacy=IncidentReport.parse(value);if(r.optString("closureLocation").isBlank()&&legacy.closureLocation.isNotBlank())r.put("closureLocation",legacy.closureLocation);if(r.optString("closureLocation2").isBlank()&&legacy.closureLocation2.isNotBlank())r.put("closureLocation2",legacy.closureLocation2);listOf("pendingPhoto","pendingOriginal","pendingNote","pendingGroup","pendingDate","pendingStamped","routes","dossierVersion").forEach{r.remove(it)};val recovery=r.optJSONObject("recovery")?:JSONObject().put("enabled",r.optBoolean("recovery")).put("length",r.optString("recoveryLength")).put("cable",r.optString("recoveryCable"));r.put("recovery",recovery);if(r.optBoolean("relocation"))r.put("commonRelocationCable",r.optString("cable"));val groups=r.optJSONArray("groups")?:JSONArray();for(i in 0 until groups.length()){val pics=groups.getJSONObject(i).optJSONArray("images")?:JSONArray();for(j in 0 until pics.length()){val img=pics.getJSONObject(j);val path=img.optString("path");img.put("file",path);val original=img.optString("originalFile").takeIf{it.isNotBlank()&&File(it).isFile}?:PhotoRenderer.originalFor(File(path));if(original!=null)img.put("originalFile",original);PhotoRenderer.textFor(File(path))?.let{img.put("overlayLines",it)};img.remove("path")}};return r}
 private fun nativeReport(r:JSONObject):String {val recovery=r.optJSONObject("recovery")?:JSONObject();r.put("recovery",recovery.optBoolean("enabled"));r.put("recoveryLength",recovery.optString("length"));r.put("recoveryCable",recovery.optString("cable"));if(r.optBoolean("relocation")&&r.has("commonRelocationCable")){r.put("cable",r.optString("commonRelocationCable"));r.put("recoveryCable",r.optString("commonRelocationCable"))};r.remove("pendingCapture");r.remove("routes");val groups=r.optJSONArray("groups")?:JSONArray();for(i in 0 until groups.length()){val pics=groups.getJSONObject(i).optJSONArray("images")?:JSONArray();for(j in 0 until pics.length()){val img=pics.getJSONObject(j);img.put("path",img.getString("file"));img.remove("file")}};return IncidentReport.parse(r.toString()).json()}
 suspend fun snapshot(app:WorkApp,place:Place):JSONObject=app.db.withTransaction {
  val dao=app.db.dao();val current=dao.place(place.id)?:error("Thư mục đã bị xóa");val photos=JSONArray();val jobs=dao.jobs(place.id)
  check(jobs.size<=1){"Thư mục cũ có nhiều bộ ảnh. Hãy tách thành thư mục riêng."}
  for(job in jobs)for(p in dao.photos(job.id)){photos.put(JSONObject().put("seq",p.seq).put("name",p.locationName).put("lat",p.lat).put("lon",p.lon).put("accuracy",p.accuracy.toDouble()).put("note",p.note).put("file",p.path).put("originalFile",p.originalPath).put("date",SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date(p.capturedAt))).put("capturedAt",p.capturedAt).put("showDate",p.showDate).put("textColor",String.format("#%06X",0xFFFFFF and p.textColor)).put("photographer",p.photographer))}
  JSONObject().put("version",1).put("module",ServerSession.module(current.kind)).put("name",current.name).put("completed",if(current.kind=="Tuyến cáp")jobs.firstOrNull()?.completed?:false else dao.incident(place.id)?.let{JSONObject(it.value).optBoolean("completed")}?:jobs.firstOrNull()?.completed?:false).put("photos",photos).put("report",dao.incident(place.id)?.let{wireReport(it.value)}?:JSONObject.NULL)
 }
 suspend fun syncState(app:WorkApp,place:Place):Pair<String,Boolean>{val sig=sha(snapshot(app,place).toString());val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);return sig to (pending(app,place.id)!=null||prefs.getString("folder-${place.id}-signature",null)!=sig)}
 suspend fun upload(app:WorkApp,place:Place,progress:(String)->Unit)=mutex.withLock{uploadLocked(app,place,progress)}
 private suspend fun uploadContribution(app:WorkApp,place:Place,progress:(String)->Unit){
  SharedReports.ensureContributorActive(app,place.id)
  val account=app.accountScope;val remote=SharedReports.remote(app,place.id)
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);val cache=app.getSharedPreferences("server-assets",Context.MODE_PRIVATE);val key="folder-${place.id}"
  suspend fun finish(){
   val journal=pending(app,place.id)?:return
   val photoMutation=journal.optString("kind")=="photo-mutations"
   check((photoMutation||journal.optString("kind")=="contribution")&&journal.getString("remote")==remote){"Còn thao tác báo cáo cũ cần đối chiếu; ảnh trên máy vẫn được giữ."}
   val endpoint="mobile/folders/$remote/"+if(photoMutation)"photo-mutations"else "contributions"
   val result=try{ServerSession.api(endpoint,"POST",journal.getJSONObject("request"))}catch(e:ServerSession.Failure){
    if(e.status==403)SharedReports.markRevoked(app,place.id)
    if(e.status in listOf(404,405))throw ServerSession.Failure(e.status,"Chưa gửi được ảnh cùng làm: hãy kiểm tra quyền báo cáo và cập nhật server. Ảnh vẫn được giữ trên máy.")
    if(!photoMutation||e.status!=409)throw e
    // A 409 definitively rejected this operation. Rebase only photo intent; never a whole stale report.
    val latest=ServerSession.api("mobile/folders/$remote")
    check(account==app.accountScope){"Tài khoản đã thay đổi"}
    if(ReportCompletion.fromServer(latest.getJSONObject("payload")))throw ServerSession.Failure(409,"Báo cáo đã hoàn thành; người lập cần mở lại trước. Thay đổi ảnh vẫn giữ trên máy.")
    val rebased=try {
     @Suppress("UNCHECKED_CAST")
     PhotoMutationDelta.rebase(PhotoMutationDelta.fromWire(plain(journal.getJSONObject("request")) as Map<String,Any?>),plain(latest.getJSONObject("payload")) as Map<String,Any?>)
    }catch(conflict:IllegalArgumentException){stateFile(app,place.id).delete();throw ServerSession.Failure(409,conflict.message.orEmpty())}
    if(rebased.empty)latest else {
     val request=JSONObject(rebased.wire()).put("operation",UUID.randomUUID().toString()).put("revision",latest.getInt("revision"))
     journal.put("request",request);savePending(app,place.id,journal)
     ServerSession.api(endpoint,"POST",request)
    }
   }
   check(account==app.accountScope){"Tài khoản đã thay đổi"}
   val source=journal.getJSONObject("source");val revision=result.getInt("revision")
   val canonical=if(photoMutation)result.getJSONObject("payload")else source
   saveBaseline(app,place.id,revision,source,canonical)
   check(prefs.edit().putInt("$key-revision",revision).putString("$key-signature",journal.getString("signature")).putBoolean("$key-refresh",true).commit()){"Chưa lưu được kết quả gửi ảnh"}
   stateFile(app,place.id).delete()
   if(MxPhotoUpload.needsCoordinates(source)){AutoSync.refresh();AutoSync.awaitMx(app,place.id)}
  }
  app.flushReport();finish()
  val payload=snapshot(app,place);val signature=sha(payload.toString())
  if(signature==prefs.getString("$key-signature",null)){progress("Đã đồng bộ ${place.name}");return}
  val base=baseline(app,place.id)?:error("Báo cáo chung chưa có mốc đồng bộ. Giữ ảnh trên máy và bấm Đồng bộ để nhận lại thông tin.")
  val accepted=base.getJSONObject("localWire")
  val module=ServerSession.module(place.kind)
  check(module in listOf("incident","relocation")){"Loại báo cáo chưa hỗ trợ cùng làm"}
  val unknown=mutableSetOf<String>()
  images(payload){path->cache.getString(folderAssetKey(remote,module,path,File(path)),null)?:path.also{unknown.add(it)}}
  @Suppress("UNCHECKED_CAST")
  val delta=PhotoMutationDelta.plan(plain(accepted) as Map<String,Any?>,plain(payload) as Map<String,Any?>,plain(base.getJSONObject("wire")) as Map<String,Any?>)
  if(delta.empty){
   // Normalization of local-only draft fields does not warrant another network request.
   check(prefs.edit().putString("$key-signature",signature).commit())
   progress("Không có thay đổi ảnh cần gửi: ${place.name}");AutoSync.receiveIfPending(app);return
  }
  ServerSession.refresh();check(account==app.accountScope);check(ServerSession.allowed(place.kind)){"Bạn chưa được cấp quyền"}
  val request=JSONObject(delta.wire()).put("operation",UUID.randomUUID().toString()).put("revision",base.getInt("revision"))
  val added=request.getJSONArray("images")
  val replacements=mutableMapOf<String,String>()
  images(added){path->if(path !in unknown)path else replacements[path]?:run{
   val file=File(path).canonicalFile;val root=File(app.accountFilesDir,"photos").canonicalFile.toPath()
   check(file.toPath().startsWith(root)&&file.isFile){"Ảnh chưa sẵn sàng: ${file.name}"};check(file.length()<=10*1024*1024){"Ảnh ${file.name} vượt 10 MB"}
   progress("Đang gửi ảnh cùng làm ${replacements.size+1} • ${place.name}")
   val data=withContext(Dispatchers.IO){Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)}
   val asset=try{ServerSession.api("mobile/assets","POST",JSONObject().put("module",module).put("folderId",remote).put("base64",data)).getString("id")}catch(e:ServerSession.Failure){if(e.status==403)SharedReports.markRevoked(app,place.id);throw e}
   check(account==app.accountScope);check(cache.edit().putString(folderAssetKey(remote,module,path,file),asset).putString("local-${place.id}-$asset",path).commit())
   replacements[path]=asset;asset
  }}
  images(payload){replacements[it]?:it}
  val journal=JSONObject().put("kind","photo-mutations").put("remote",remote).put("signature",signature).put("source",payload).put("request",request)
  savePending(app,place.id,journal);finish()
  progress("Đã đồng bộ thay đổi ảnh vào báo cáo ${place.name}")
  AutoSync.receiveIfPending(app)
 }
 private suspend fun uploadLocked(app:WorkApp,place:Place,progress:(String)->Unit){
  assignmentSchema(app);finishImport(app,place.id)
  if(SharedReports.contributor(app,place.id)){contributing.add(place.id);try{uploadContribution(app,place,progress)}finally{contributing.remove(place.id)};return}
  RoutePhotoTitles.refresh(app,place.id)
  val scope=app.accountScope;ServerSession.refresh();check(ServerSession.allowed(place.kind)){"Bạn chưa được cấp quyền"};app.flushReport()
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);val cache=app.getSharedPreferences("server-assets",Context.MODE_PRIVATE);val key="folder-${place.id}"
  suspend fun finishPending(){
   val p=pending(app,place.id)?:return
   var request=p.getJSONObject("request")
   val remote=p.getString("remote")
   val r=try{ServerSession.api("mobile/folders/$remote","PUT",request)}catch(e:ServerSession.Failure){
    if(e.status!=409)throw e
    val record=try{ServerSession.api("mobile/folders/$remote")}catch(missing:ServerSession.Failure){
     if(missing.status==404)throw ServerSession.Failure(409,"Báo cáo không còn trên server. Dữ liệu điện thoại vẫn được giữ; không tự tạo lại.")
     throw missing
    }
    check(scope==app.accountScope){"Tài khoản đã thay đổi"}
    if(record.getInt("revision")<request.getInt("revision"))throw ServerSession.Failure(409,"Server đang có phiên bản cũ hơn. Giữ báo cáo điện thoại, chưa tự gộp dữ liệu.")
    val accepted=p.optJSONObject("base")?:baseline(app,place.id)?.takeIf{it.optInt("revision")==request.optInt("revision")}?.optJSONObject("wire")
    val remotePayload=record.getJSONObject("payload")
    val reconciled=try{
     if(plain(request.getJSONObject("payload"))==plain(remotePayload))remotePayload
     else if(accepted!=null)merge(accepted,request.getJSONObject("payload"),remotePayload)
     else throw ServerSession.Failure(409,"Báo cáo cũ đã thay đổi ở cả hai nơi và chưa có mốc đối chiếu. Ảnh trên máy vẫn được giữ; chưa ghi đè server.")
    }catch(conflict:ServerSession.Failure){
     // The server definitively rejected this operation. Keep the report and baseline,
     // but release this stale request so later local edits can be considered.
     stateFile(app,place.id).delete();throw conflict
    }
    request=JSONObject().put("operation",UUID.randomUUID().toString()).put("revision",record.getInt("revision")).put("payload",reconciled)
    p.put("request",request).put("base",remotePayload)
    savePending(app,place.id,p)
    progress("Đang gộp thay đổi và gửi lại ${place.name}")
    ServerSession.api("mobile/folders/$remote","PUT",request)
   }
   check(scope==app.accountScope){"Tài khoản đã thay đổi"}
   val source=p.optJSONObject("source")?:request.getJSONObject("payload")
   val needsNasMx=MxPhotoUpload.needsCoordinates(source)
   saveBaseline(app,place.id,r.getInt("revision"),source,request.getJSONObject("payload"))
   check(prefs.edit().putInt("$key-revision",r.getInt("revision")).putString("$key-signature",p.getString("signature"))
    .putBoolean("$key-refresh",plain(source)!=plain(request.getJSONObject("payload"))||needsNasMx).commit()){"Chưa lưu được trạng thái đồng bộ"}
   stateFile(app,place.id).delete()
   if(needsNasMx){AutoSync.refresh();AutoSync.awaitMx(app,place.id)}
  }
  suspend fun retryPending(){try{finishPending()}catch(e:ServerSession.Failure){if(e.status==400&&e.message.orEmpty().contains("Thiếu ảnh")){stateFile(app,place.id).delete();cache.edit().clear().commit()};throw e}}
  retryPending();val payload=snapshot(app,place);val signature=sha(payload.toString());if(prefs.getString("$key-signature",null)==signature){if(place.kind=="Tuyến cáp")archiveCompletedCable(app,place,prefs.getString(key,null),true,prefs.getInt("$key-revision",0));progress("Đã đồng bộ ${place.name}");return}
  val module=ServerSession.module(place.kind);check(module!="cable"||prefs.contains(key)){"Tuyến cáp phải được quản trị tạo và giao trên server."};val remote=prefs.getString(key,null)?:UUID.randomUUID().toString().also{check(prefs.edit().putString(key,it).commit())};val revision=prefs.getInt("$key-revision",0)
  val map=mutableMapOf<String,String>();images(payload){path->map[path]?:run {val f=File(path).canonicalFile;val root=File(app.accountFilesDir,"photos").canonicalFile.toPath();check(f.toPath().startsWith(root)&&f.isFile){"Ảnh chưa sẵn sàng: ${f.name}"};check(f.length()<=10*1024*1024){"Ảnh ${f.name} vượt 10 MB"};val cacheKey=folderAssetKey(remote,module,path,f);val known=cache.getString(cacheKey,null)?:if(!SharedReports.shared(app,place.id))cache.getString(assetKey(module,path,f),null)else null;val id=if(known!=null)known else{progress("Đang đồng bộ ảnh ${map.size+1} • ${place.name}");val data=withContext(Dispatchers.IO){Base64.encodeToString(f.readBytes(),Base64.NO_WRAP)};ServerSession.api("mobile/assets","POST",JSONObject().put("module",module).put("base64",data).apply{if(revision>0)put("folderId",remote)}).getString("id")};check(scope==app.accountScope);check(cache.edit().putString(cacheKey,id).putString("local-${place.id}-$id",path).commit());map[path]=id;id}}
  val base=baseline(app,place.id)?.takeIf{it.optInt("revision")==revision}
  val outgoing=if(base!=null)merge(base.getJSONObject("localWire"),payload,base.getJSONObject("wire"))else payload
  val journal=JSONObject().put("remote",remote).put("signature",signature).put("source",payload).put("request",JSONObject().put("operation",UUID.randomUUID().toString()).put("revision",revision).put("payload",outgoing))
  base?.optJSONObject("wire")?.let{journal.put("base",it)}
  savePending(app,place.id,journal)
  retryPending();app.flushReport();val remainsPending=syncState(app,place).second;if(place.kind=="Tuyến cáp"&&!remainsPending)archiveCompletedCable(app,place,prefs.getString(key,null),true,prefs.getInt("$key-revision",0));progress(if(remainsPending)"Có thay đổi mới đang chờ gửi: ${place.name}" else "Đã đồng bộ ${place.name}")
 }
 suspend fun download(app:WorkApp,id:String,progress:(String)->Unit)=mutex.withLock{downloadLocked(app,id,progress)}
 private suspend fun downloadLocked(app:WorkApp,id:String,progress:(String)->Unit,attach:Boolean=false,existing:Place?=null,expected:String?=null,collaborators:Int?=null){
  val account=app.accountScope;ServerSession.refresh();val record=ServerSession.api("mobile/folders/$id")
  val wire=record.getJSONObject("payload");val p=JSONObject(wire.toString())
  val kind=ServerSession.moduleNames[p.getString("module")]?:error("Loại thư mục không hợp lệ")
  check(ServerSession.allowed(kind)&&p.optInt("version")==1){"Không có quyền hoặc định dạng chưa hỗ trợ"}
  if(existing!=null)check(existing.kind==kind||setOf(existing.kind,kind)==setOf("Sự cố","Di dời")){"Không thể đổi loại thư mục"}
  val dir=File(app.accountFilesDir,"photos").apply{mkdirs()};val created=mutableListOf<File>()
  val cache=app.getSharedPreferences("server-assets",Context.MODE_PRIVATE)
  var imported=existing?.id?:0L;var committed=false;var importedSnapshot:JSONObject?=null;val map=mutableMapOf<String,String>();assignmentSchema(app)
  try{
   images(p){aid->
    check(Regex("[a-f0-9]{32}").matches(aid)){"Mã ảnh không hợp lệ"}
    map[aid]?:run {
     val cached=existing?.let{cache.getString("local-${it.id}-$aid",null)}?.let{File(it).canonicalFile}
     val file=if(cached!=null&&cached.toPath().startsWith(dir.canonicalFile.toPath())&&cached.isFile)cached else {
      progress("Đang nhận ảnh ${map.size+1} • "+p.getString("name"))
      val raw=ServerSession.bytes("mobile/assets/$aid")
      File(dir,"server-${UUID.randomUUID()}.jpg").also{created.add(it);withContext(Dispatchers.IO){it.writeBytes(raw)}}
     }
     file.absolutePath.also{map[aid]=it}
    }
   }
   check(account==app.accountScope){"Tài khoản đã thay đổi"};app.flushReport()
   app.db.withTransaction {
    val dao=app.db.dao();val name=p.getString("name")+(if(attach)"" else " (server)")
    val place=if(existing==null)dao.addPlace(Place(kind=kind,name=name))else{
     check(app.canReceiveReport(existing.id)){"Đang mở báo cáo; sẽ nhận bản mới sau khi quay về danh sách."}
     check(sha(snapshot(app,existing).toString())==expected){"Có thay đổi mới trên máy; sẽ đồng bộ ở lần tiếp theo."}
     check(pending(app,existing.id)==null){"Còn thay đổi đang gửi"}
     if(existing.kind!=kind)dao.movePlace(existing.id,kind)
     dao.renamePlace(existing.id,name);dao.renameLinkedJobs(existing.id,existing.name,name);existing.id
    };imported=place
    val jobs=dao.jobs(place);check(jobs.size<=1){"Thư mục cũ có nhiều bộ ảnh; chưa thay thế dữ liệu."}
    val completed=ReportCompletion.fromServer(p)
    val job=jobs.firstOrNull()?.id?:dao.addJob(Job(placeId=place,name=name,completed=completed))
    dao.markJob(job,completed)
    val oldPhotos=dao.photos(job);val used=mutableSetOf<Long>()
    // Free unique sequence slots, retaining row IDs for photos that still exist.
    oldPhotos.forEachIndexed{index,photo->dao.updatePhoto(photo.copy(seq=-(index+1)))}
    val photos=p.getJSONArray("photos")
    for(i in 0 until photos.length()){
     val x=photos.getJSONObject(i);val path=x.getString("file");val original=x.optString("originalFile").ifBlank{path}
     val old=oldPhotos.firstOrNull{it.id !in used&&(it.path==path||it.originalPath==original)}
     val captured=x.optLong("capturedAt");val dateFormat=SimpleDateFormat("dd/MM/yyyy",Locale.US).apply{isLenient=false}
     val date=x.optString("date").removePrefix("Ngày chụp: ")
     val time=if(captured>0&&(date.isBlank()||dateFormat.format(Date(captured))==date))captured else runCatching{dateFormat.parse(date)!!.time}.getOrDefault(captured.coerceAtLeast(0L))
     val photo=Photo(id=old?.id?:0L,jobId=job,seq=x.getInt("seq"),locationName=x.optString("name"),lat=x.optDouble("lat",0.0),lon=x.optDouble("lon",0.0),accuracy=x.optDouble("accuracy",-1.0).toFloat(),note=x.optString("note"),path=path,capturedAt=time,originalPath=original,showDate=x.optBoolean("showDate",x.optInt("showDate",1)!=0),textColor=runCatching{Color.parseColor(x.optString("textColor","#ffffff"))}.getOrDefault(Color.WHITE),photographer=x.optString("photographer"))
     if(old==null)dao.addPhoto(photo)else{used.add(old.id);dao.updatePhoto(photo)}
    }
    oldPhotos.filter{it.id !in used}.forEach{dao.deletePhoto(it)}
    val report=p.optJSONObject("report")
    if(report==null)dao.deleteIncident(place)else dao.saveIncident(IncidentRecord(place,nativeReport(report.put("completed",completed))))
    if(attach)app.db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO cable_assignment_links VALUES(?,?,?)",arrayOf<Any>(id,place,record.getInt("revision")))
    importedSnapshot=snapshot(app,dao.place(place)?:error("Không lưu được báo cáo"))
    if(attach){
     val local=checkNotNull(importedSnapshot);val localWire=JSONObject(local.toString());val inverse=map.entries.associate{it.value to it.key}
     images(localWire){path->inverse[path]?:error("Thiếu liên kết ảnh đã nhận")}
     val access=JSONObject().put("shared",record.optBoolean("shared")).put("owner",record.optString("owner")).put("author",record.optString("author"))
     collaborators?.let{access.put("collaborators",it)}
     val receipt=JSONObject().put("remote",id).put("revision",record.getInt("revision")).put("signature",sha(local.toString())).put("localWire",localWire).put("wire",wire).put("assets",JSONObject(map)).put("access",access)
     app.db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO mobile_import_receipts(placeId,value) VALUES(?,?)",arrayOf<Any>(place,receipt.toString()))
    }
   }
   committed=true
   if(attach)finishImport(app,imported)
   app.notifyFoldersDownloaded(kind)
  }catch(e:Exception){if(!committed)created.forEach{it.delete()};throw e}
 }
 private suspend fun refreshLinked(app:WorkApp,place:Place,id:String,revision:Int,progress:(String)->Unit):Boolean {
  finishImport(app,place.id)
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);val key="folder-${place.id}"
  if(revision<prefs.getInt("$key-revision",0))throw ServerSession.Failure(409,"${place.name}: server đang có phiên bản cũ hơn. Giữ dữ liệu điện thoại để đối chiếu.")
  val needsReceive=prefs.getInt("$key-revision",0)!=revision||prefs.getBoolean("$key-refresh",false)
  app.flushReport()
  val local=snapshot(app,place);val signature=sha(local.toString())
  if(pending(app,place.id)!=null||signature!=prefs.getString("$key-signature",null))return false
  if(!needsReceive){
   // Upgrade old installations using the acknowledged local snapshot and asset receipts.
   if(baseline(app,place.id)==null){
    val wire=JSONObject(local.toString());val cache=app.getSharedPreferences("server-assets",Context.MODE_PRIVATE)
    var complete=true
    images(wire){path->val asset=cache.getString(assetKey(ServerSession.module(place.kind),path,File(path)),null);if(asset==null)complete=false;asset?:path}
    if(complete)saveBaseline(app,place.id,revision,wire,wire)
   }
   return false
  }
  prefs.edit().putBoolean("$key-refresh",true).apply()
  if(!app.canReceiveReport(place.id)){
   progress("${place.name}: có bản mới trên server; sẽ nhận khi quay về danh sách.")
   return false
  }
  progress("Đang cập nhật từ server: ${place.name}")
  downloadLocked(app,id,progress,true,place,signature)
  return true
 }
 /** OCR enrichment is asynchronous and can keep the same report revision. */
 fun requestMxRefresh(app:WorkApp,placeId:Long):Boolean {
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE)
  val key="folder-$placeId"
  if(!prefs.contains(key))return false
  return prefs.edit().putBoolean("$key-refresh",true).commit()
 }
 fun hasIncoming(app:WorkApp)=app.getSharedPreferences("server-links",Context.MODE_PRIVATE).all.any{it.key.endsWith("-refresh")&&it.value==true}
 // Receive missing folders owned by this account; administrative visibility is not ownership.
 suspend fun receiveOwnFolders(app:WorkApp,progress:(String)->Unit)=mutex.withLock{
  val scope=app.accountScope;val owner=ServerSession.user?.optString("id")?.takeIf{it.isNotBlank()}?:return@withLock
  val rows=ServerSession.api("mobile/folders").getJSONArray("folders");assignmentSchema(app)
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE)
  for(i in 0 until rows.length()){
   val row=rows.getJSONObject(i);val kind=ServerSession.moduleNames[row.optString("module")]?:continue
   if((row.optString("owner")!=owner&&!row.optBoolean("shared"))||row.has("task_id")||!ServerSession.allowed(kind))continue
   check(scope==app.accountScope){"Tài khoản đã thay đổi"};val id=row.getString("id")
   val linked=withContext(Dispatchers.IO){app.db.openHelper.readableDatabase.query("SELECT placeId,revision FROM cable_assignment_links WHERE remote=?",arrayOf(id)).use{if(it.moveToFirst())it.getLong(0) to it.getInt(1) else null}}
   val prefId=prefs.all.entries.firstOrNull{it.value==id&&Regex("folder-[0-9]+").matches(it.key)}?.key?.removePrefix("folder-")?.toLongOrNull()
   val existing=linked?.let{app.db.dao().place(it.first)}?:prefId?.let{app.db.dao().place(it)}
   if(existing!=null){
    SharedReports.record(app,existing.id,row)
    // Repair only the link after an interrupted preferences write; keep local changes dirty.
    if(!prefs.contains("folder-${existing.id}"))check(prefs.edit().putString("folder-${existing.id}",id).putInt("folder-${existing.id}-revision",linked?.second?:0).commit())
    refreshLinked(app,existing,id,row.getInt("revision"),progress)
    continue
   }
   if(prefs.getBoolean("server-only-$id",false)){
    if(row.optString("owner")!=owner)continue
    val record=ServerSession.api("mobile/folders/$id")
    if(record.optString("owner")!=owner)continue
    val payload=record.optJSONObject("payload")?:continue
    if(payload.optString("module")!=ServerSession.module(kind)||payload.optInt("version")!=1)continue
    if(LocalReportRetention.shouldRestore(true,ReportCompletion.fromServer(payload))){
     progress("Báo cáo được mở lại trên server; đang nhận về điện thoại: "+row.optString("name"))
     downloadLocked(app,id,progress,true,collaborators=if(row.has("collaborators"))row.optInt("collaborators")else null)
     check(prefs.edit().remove("server-only-$id").commit()){"Đã nhận lại báo cáo nhưng chưa lưu trạng thái trên điện thoại."}
     if(prefId!=null&&app.db.dao().place(prefId)==null){
      val stale=prefs.edit();prefs.all.keys.filter{it=="folder-$prefId"||it.startsWith("folder-$prefId-")}.forEach{stale.remove(it)}
      check(stale.commit()){"Đã nhận lại báo cáo nhưng chưa làm mới liên kết trên điện thoại."}
     }
    }
    continue
   }
   // A previous link also records folders deliberately removed from this phone.
   var alreadyLocal=false
   for((key,value)in prefs.all){if(value==id&&Regex("folder-[0-9]+").matches(key)){alreadyLocal=true;break}}
   if(alreadyLocal)continue
   progress("Đang nhận báo cáo: "+row.optString("name"));downloadLocked(app,id,progress,true,collaborators=if(row.has("collaborators"))row.optInt("collaborators")else null)
   app.notifyFoldersDownloaded(kind)
  }
  val visible=(0 until rows.length()).map{rows.getJSONObject(it).optString("id")}.toSet()
  for((key,value)in prefs.all){
   val local=key.removePrefix("folder-").toLongOrNull()?:continue
   if(value is String&&value !in visible&&SharedReports.contributor(app,local))SharedReports.markRevoked(app,local)
  }
 }
 /** Retain completed incidents on the server after exact acknowledgement; other reports keep the month-end rule. */
 suspend fun removePastMonthReports(app:WorkApp):Int=mutex.withLock{
  val account=app.accountScope
  if(ServerSession.token.isBlank()||account=="locked")return@withLock 0
  val currentMonth=java.time.YearMonth.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString()
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE)
  val cache=app.getSharedPreferences("server-assets",Context.MODE_PRIVATE)
  val dao=app.db.dao();var removed=0;val changedKinds=mutableSetOf<String>()
  ServerSession.refresh()
  val owner=ServerSession.user?.optString("id")?.takeIf{it.isNotBlank()}?:return@withLock 0
  for(kind in listOf("Sự cố","Di dời")){
   for(place in dao.places(kind)){
    if(account!=app.accountScope||ServerSession.token.isBlank())return@withLock removed
    if(SharedReports.shared(app,place.id)||!app.canReceiveReport(place.id))continue
    val incident=dao.incident(place.id)?:continue
    val month=runCatching{RouteMonth.reportKey(IncidentReport.parse(incident.value).date)}.getOrDefault("")
    if(!LocalReportRetention.eligible(kind,month,currentMonth,ReportCompletion.isComplete(incident.value),true))continue
    val key="folder-${place.id}";val remote=prefs.getString(key,null)?:continue
    val (signature,dirty)=syncState(app,place)
    if(dirty||stateFile(app,place.id).baseFile.exists()||prefs.getBoolean("$key-refresh",false)||prefs.getString("$key-signature",null)!=signature)continue
    val record=try{ServerSession.api("mobile/folders/$remote")}catch(error:ServerSession.Failure){if(error.status==404)continue else throw error}
    if(account!=app.accountScope) return@withLock removed
    val payload=record.optJSONObject("payload")?:continue
    if(payload.optString("module")!=ServerSession.module(kind)||payload.optInt("version")!=1||payload.optJSONObject("report")==null)continue
    if(!LocalReportRetention.eligible(kind,month,currentMonth,ReportCompletion.isComplete(incident.value),ReportCompletion.fromServer(payload)))continue
    val accepted=baseline(app,place.id)?:continue
    val acceptedLocal=accepted.optJSONObject("localWire")?:continue
    val acceptedServer=accepted.optJSONObject("wire")?:continue
    val localWire=snapshot(app,place)
    val photoRoot=File(app.accountFilesDir,"photos").canonicalFile.toPath()
    var allAssetsAcknowledged=true
    images(localWire){path->
     val file=File(path).canonicalFile
     val asset=if(file.isFile&&file.toPath().startsWith(photoRoot))cache.getString(folderAssetKey(remote,ServerSession.module(kind),path,file),null)else null
     if(asset==null)allAssetsAcknowledged=false
     asset?:path
    }
    if(!LocalReportRetention.acknowledged(record.optString("owner")==owner,
      ReportCompletion.isComplete(incident.value),ReportCompletion.fromServer(payload),stateFile(app,place.id).baseFile.exists(),prefs.getBoolean("$key-refresh",false),app.canReceiveReport(place.id),
      prefs.getInt("$key-revision",0),record.optInt("revision",0),accepted.optInt("revision",0),signature,prefs.getString("$key-signature",null),
      allAssetsAcknowledged&&LocalReportRetention.localPayloadAcknowledged(localWire,acceptedLocal)))continue
    if(!LocalReportRetention.payloadsMatch(localWire,acceptedLocal,payload,acceptedServer)){
     if(!LocalReportRetention.needsServerRefresh(localWire,acceptedLocal,payload,acceptedServer))continue
     // Photo GPS enrichment can change GET output without increasing revision.
     // Download and persist the full accepted snapshot, retaining the local copy
     // for this cycle. Never ignore changed fields just to allow removal.
     check(prefs.edit().putBoolean("$key-refresh",true).commit()){"Chưa lưu được yêu cầu nhận tọa độ; báo cáo vẫn giữ trên điện thoại."}
     downloadLocked(app,remote,{},true,place,signature)
     AutoSync.refresh()
     continue
    }
    // Recheck the local record inside the same Room transaction that removes it.
    // ServerSync's mutex prevents upload/receive from changing the accepted revision.
    val deleted=FolderStore(app).deleteGuarded(listOf(place.id)){
     if(account!=app.accountScope||ServerSession.user?.optString("id")!=owner||prefs.getString(key,null)!=remote||
       prefs.getInt("$key-revision",0)!=record.optInt("revision",0)||prefs.getBoolean("$key-refresh",false)||
       stateFile(app,place.id).baseFile.exists()||!app.canReceiveReport(place.id)||dao.incident(place.id)?.value!=incident.value)return@deleteGuarded false
     val (currentSignature,stillDirty)=syncState(app,place)
     if(stillDirty||currentSignature!=signature)return@deleteGuarded false
     check(prefs.edit().putBoolean("server-only-$remote",true).commit()){"Chưa ghi được trạng thái giữ báo cáo trên server; bản trên điện thoại vẫn được giữ."}
     true
    }
    if(!deleted)continue
    baseFile(app,place.id).delete()
    val edit=cache.edit()
    cache.all.keys.filter{it.startsWith("local-${place.id}-")}.forEach{edit.remove(it)}
    check(edit.commit()){"Đã lưu báo cáo trên server nhưng chưa dọn xong dữ liệu ảnh trên điện thoại."}
    removed++;changedKinds.add(kind)
   }
  }
  changedKinds.forEach{app.notifyFoldersDownloaded(it)}
  removed
 }
 private suspend fun assignmentSchema(app:WorkApp)=withContext(Dispatchers.IO){
  app.db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS cable_assignment_links(remote TEXT PRIMARY KEY,placeId INTEGER NOT NULL REFERENCES places(id) ON DELETE CASCADE,revision INTEGER NOT NULL)")
  app.db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS mobile_import_receipts(placeId INTEGER PRIMARY KEY REFERENCES places(id) ON DELETE CASCADE,value TEXT NOT NULL)")
 }
 private suspend fun finishImport(app:WorkApp,id:Long){
  val scope=app.accountScope
  val raw=withContext(Dispatchers.IO){app.db.openHelper.readableDatabase.query("SELECT value FROM mobile_import_receipts WHERE placeId=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0)else null}}?:return
  check(scope==app.accountScope){"Tài khoản đã thay đổi"}
  val receipt=JSONObject(raw);val wire=receipt.getJSONObject("wire");val assets=receipt.getJSONObject("assets")
  val edits=app.getSharedPreferences("server-assets",Context.MODE_PRIVATE).edit()
  for(aid in assets.keys()){val path=assets.getString(aid);val file=File(path);if(file.isFile){edits.putString("local-$id-$aid",path);edits.putString(folderAssetKey(receipt.getString("remote"),wire.getString("module"),path,file),aid);if(!receipt.optJSONObject("access").let{it?.optBoolean("shared")==true})edits.putString(assetKey(wire.getString("module"),path,file),aid)}}
  check(edits.commit()){"Chưa lưu được danh sách ảnh đồng bộ"}
  saveBaseline(app,id,receipt.getInt("revision"),receipt.getJSONObject("localWire"),wire)
  receipt.optJSONObject("access")?.let{SharedReports.record(app,id,it)}
  check(app.getSharedPreferences("server-links",Context.MODE_PRIVATE).edit().putString("folder-$id",receipt.getString("remote")).putInt("folder-$id-revision",receipt.getInt("revision")).putString("folder-$id-signature",receipt.getString("signature")).putBoolean("folder-$id-refresh",false).commit()){"Chưa lưu được kết quả nhận báo cáo"}
  withContext(Dispatchers.IO){app.db.openHelper.writableDatabase.execSQL("DELETE FROM mobile_import_receipts WHERE placeId=? AND value=?",arrayOf<Any>(id,raw))}
 }
 suspend fun receiveCableTasks(app:WorkApp,progress:(String)->Unit)=mutex.withLock{
  if(!ServerSession.allowed("Tuyến cáp"))return@withLock
  val scope=app.accountScope;val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);val cursorKey="cable-task-sync-cursor";val oldCursor=prefs.getLong(cursorKey,0L)
  val response=if(oldCursor>0L)try{ServerSession.api("mobile/cable-tasks/since/$oldCursor")}catch(e:ServerSession.Failure){if(e.status==404||e.status==405)ServerSession.api("mobile/cable-tasks")else throw e}else ServerSession.api("mobile/cable-tasks")
  val tasks=response.getJSONArray("tasks");assignmentSchema(app)
  for(i in 0 until tasks.length()){val task=tasks.getJSONObject(i);val members=task.getJSONArray("members")
   for(j in 0 until members.length()){val member=members.getJSONObject(j);if(member.optString("user")!=ServerSession.user?.optString("id")||!member.optBoolean("active"))continue
    check(scope==app.accountScope){"Tài khoản đã thay đổi"};val fid=member.getString("folder")
    val linked=withContext(Dispatchers.IO){app.db.openHelper.readableDatabase.query("SELECT placeId,revision FROM cable_assignment_links WHERE remote=?",arrayOf(fid)).use{if(it.moveToFirst())it.getLong(0) to it.getInt(1) else null}}
    val existing=linked?.let{app.db.dao().place(it.first)}
    val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);val archivedKey="cable-archived-$fid"
    if(prefs.getBoolean(archivedKey,false)){
     if(member.optBoolean("completed")){if(existing!=null)FolderStore(app).delete(listOf(existing.id));continue}
     check(prefs.edit().remove(archivedKey).commit()){"Chưa lưu được trạng thái giao lại tuyến"}
    }
    if(existing==null&&member.optBoolean("completed")){
     check(prefs.edit().putBoolean(archivedKey,true).commit()){"Chưa lưu được trạng thái tuyến hoàn thành"};continue
    }
    if(existing!=null){
     val key="folder-${existing.id}"
     if(!prefs.contains(key))check(prefs.edit().putString(key,fid).putInt("$key-revision",linked.second).commit())
     refreshLinked(app,existing,fid,member.getInt("revision"),progress)
     if(archiveCompletedCable(app,existing,fid,member.optBoolean("completed"),member.getInt("revision")))continue
     val local=snapshot(app,existing)
     if(pending(app,existing.id)==null&&prefs.getString("$key-signature",null)==sha(local.toString())){
      when(reconcileCableSync(prefs.getInt("$key-revision",0),member.getInt("revision"),local.getJSONArray("photos").length(),member.getInt("points"),local.optBoolean("completed"),member.optBoolean("completed"))){
       CableSyncCheck.CONFLICT->continue // Defer an open report; clean closed reports were refreshed above.
       CableSyncCheck.RESEND->check(prefs.edit().remove("$key-signature").commit()){"Chưa đánh dấu được báo cáo cần gửi lại"}
       CableSyncCheck.MATCH->Unit
      }
     }
     saveCableMetadata(app,existing.id,task);app.getSharedPreferences("server-links",Context.MODE_PRIVATE).edit().putString("assignment-note-${existing.id}",task.optString("note")).apply();app.db.withTransaction{app.db.dao().renamePlace(existing.id,task.getString("name"));app.db.dao().renameLinkedJobs(existing.id,existing.name,task.getString("name"))};RoutePhotoTitles.refresh(app,existing.id);if(!prefs.contains("folder-${existing.id}"))check(prefs.edit().putString("folder-${existing.id}",fid).putInt("folder-${existing.id}-revision",linked.second).commit());continue}
    progress("Đang nhận tuyến được giao: "+task.getString("name"));downloadLocked(app,fid,progress,true);val newPlace=withContext(Dispatchers.IO){app.db.openHelper.readableDatabase.query("SELECT placeId FROM cable_assignment_links WHERE remote=?",arrayOf(fid)).use{if(it.moveToFirst())it.getLong(0)else 0L}};if(newPlace>0){saveCableMetadata(app,newPlace,task);app.getSharedPreferences("server-links",Context.MODE_PRIVATE).edit().putString("assignment-note-$newPlace",task.optString("note")).apply()}
   }
  }
  check(scope==app.accountScope){"Tài khoản đã thay đổi"}
  val newCursor=response.optLong("sync_cursor",0L)
  if(newCursor>0L)check(prefs.edit().putLong(cursorKey,newCursor).commit()){"Chưa lưu được mốc đồng bộ tuyến"}
 }

 private suspend fun saveCableMetadata(app:WorkApp,placeId:Long,task:JSONObject){
  val report=app.db.dao().incident(placeId)?.let{JSONObject(it.value)}?:JSONObject()
  report.put("cableWork",true).put("workType",task.optString("work_type")).put("routeName",task.optString("name"))
   .put("lengthKm",task.optString("length_km")).put("team",task.optString("team"))
  app.db.dao().saveIncident(IncidentRecord(placeId,report.toString()))
  check(app.getSharedPreferences("server-links",Context.MODE_PRIVATE).edit().putLong("assignment-created-$placeId",task.optLong("created")).commit()){"Chưa lưu được tháng giao tuyến"}
 }

 private suspend fun archiveCompletedCable(app:WorkApp,place:Place,remote:String?,serverCompleted:Boolean,serverRevision:Int):Boolean{
  if(remote.isNullOrBlank()||place.kind!="Tuyến cáp"||!app.canReceiveReport(place.id))return false
  val prefs=app.getSharedPreferences("server-links",Context.MODE_PRIVATE);val key="folder-${place.id}"
  val signature=sha(snapshot(app,place).toString());val pending=pending(app,place.id)!=null
  val safe=CableArchiveCheck.allowed(snapshot(app,place).optBoolean("completed"),serverCompleted,pending,
   prefs.getInt("$key-revision",0),serverRevision,signature,prefs.getString("$key-signature",null),prefs.getBoolean("$key-refresh",false))
  if(!safe)return false
  check(prefs.edit().putBoolean("cable-archived-$remote",true).commit()){"Chưa lưu được trạng thái tuyến hoàn thành"}
  try{FolderStore(app).delete(listOf(place.id))}catch(e:Exception){prefs.edit().remove("cable-archived-$remote").commit();throw e}
  return true
 }

}
