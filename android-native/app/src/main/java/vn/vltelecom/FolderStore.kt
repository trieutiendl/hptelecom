package vn.vltelecom

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/** File copies are prepared before committing metadata. Every folder owns its files. */
class FolderStore(private val app:WorkApp,private val database:WorkDatabase=app.db,private val photoDirectory:File=File(app.accountFilesDir,"photos"),private val draftStore:DraftStore=DraftStore(app)){
 private val dao get()=database.dao()
 private val root get()=photoDirectory.apply{mkdirs()}.canonicalFile
 private fun owned(path:String)=File(path).canonicalFile.takeIf{it.toPath().startsWith(root.toPath())&&it!=root}
 private suspend fun retainedPaths(draft:Draft?=draftStore.read()):Set<String>{
  val retained=mutableSetOf<String>()
  fun retain(path:String){if(path.isNotBlank())owned(path)?.let{retained.add(it.path);retained.add(it.path+".original.jpg")}}
  dao.incidents().forEach{record->paths(record.value).forEach(::retain)}
  database.openHelper.readableDatabase.query("SELECT path,originalPath FROM photos").use{rows->while(rows.moveToNext()){retain(rows.getString(0));retain(rows.getString(1))}}
  draft?.rawPath?.let(::retain)
  return retained
 }
 suspend fun drain()=withContext(Dispatchers.IO){database.withTransaction{
  // A stale cleanup entry must never delete a file now used by another report.
  // If a report cannot be parsed, leave the queue and all files untouched.
  val retained=runCatching{retainedPaths()}.getOrElse{return@withTransaction}
  dao.cleanupFiles().forEach{row->runCatching{
   val f=owned(row.path)
   if(f!=null&&f.path in retained){dao.forgetFile(row);return@runCatching}
   if(f==null||!f.exists()||f.delete()){
    f?.let{val original=File(it.path+".original.jpg");if(original.path !in retained)original.delete()}
    dao.forgetFile(row)
   }
  }}
 }}
 /** Queue after a successful report save; references in every report and draft remain protected. */
 suspend fun cleanupUnusedPhotos(paths:List<String>){
  database.withTransaction{paths.filter{it.isNotBlank()}.mapNotNull{owned(it)?.path}.distinct().forEach{dao.queueFile(FileCleanup(it))}}
  drain()
 }
 private fun duplicate(path:String,made:MutableList<File>,map:MutableMap<String,String>):String {
  if(path.isBlank())return path
  return map.getOrPut(path){val source=owned(path)?:error("Ảnh không thuộc thư mục ứng dụng");check(source.isFile){"Thiếu ảnh ${source.name}. Hãy bổ sung hoặc bỏ ảnh trước khi sao chép."}
   val target=File(root,"copy-${UUID.randomUUID()}.${source.extension.ifBlank{"jpg"}}")
   made.add(target);source.inputStream().use{input->target.outputStream().use{input.copyTo(it)}};check(target.length()==source.length()){"Sao chép ảnh chưa đầy đủ"};made.add(File(target.path+".original.jpg"));PhotoRenderer.copyOverlay(source,target);target.path}
 }
 private fun copyReport(value:String,made:MutableList<File>,map:MutableMap<String,String>):String {
  val obj=JSONObject(value)
  fun walk(v:Any?){when(v){is JSONObject->v.keys().asSequence().toList().forEach{key->val item=v.opt(key);if(key in listOf("path","file","originalFile","pendingPhoto","pendingOriginal")&&item is String&&item.isNotBlank())v.put(key,duplicate(item,made,map))else walk(item)};is JSONArray->for(i in 0 until v.length())walk(v.opt(i))}}
  walk(obj);obj.put("completed",false);return obj.toString()
 }
 private fun paths(value:String):Set<String>{val out=mutableSetOf<String>();fun walk(v:Any?){when(v){is JSONObject->v.keys().forEach{key->val item=v.opt(key);if(key in listOf("path","file","originalFile","pendingPhoto","pendingOriginal")&&item is String&&item.isNotBlank())out.add(item)else walk(item)};is JSONArray->for(i in 0 until v.length())walk(v.opt(i))}};walk(JSONObject(value));return out}
 suspend fun copy(p:Place)=withContext(Dispatchers.IO){
  val made=mutableListOf<File>();val map=mutableMapOf<String,String>()
  try{database.withTransaction{
   val original=dao.place(p.id)?:error("Thư mục đã bị xóa")
   val existing=dao.places(p.kind).map{it.name.lowercase()}.toSet();var name="${original.name} (bản sao)";var n=2;while(name.lowercase() in existing)name="${original.name} (bản sao ${n++})"
   val id=dao.addPlace(Place(kind=p.kind,name=name))
   dao.jobs(p.id).forEach{j->val newJob=dao.addJob(Job(placeId=id,name=name));dao.photos(j.id).forEach{photo->val raw=if(File(photo.originalPath).isFile)duplicate(photo.originalPath,made,map)else duplicate(photo.path,made,map);dao.addPhoto(photo.copy(id=0,jobId=newJob,path=duplicate(photo.path,made,map),originalPath=raw))}}
   dao.incident(p.id)?.let{dao.saveIncident(IncidentRecord(id,copyReport(it.value,made,map)))}
  }}catch(e:Exception){made.forEach{it.delete()};throw e}
 }
 suspend fun delete(ids:List<Long>){deleteGuarded(ids){true}}
 /** The acknowledgement guard and reference-aware deletion share one Room transaction. */
 suspend fun deleteGuarded(ids:List<Long>,allowed:suspend ()->Boolean):Boolean{
  val draft=draftStore.read();var removeDraft=false
  val deleted=database.withTransaction{
   if(!allowed())return@withTransaction false
   val all=mutableSetOf<String>()
   ids.distinct().forEach{id->val p=dao.place(id)?:return@forEach
    val jobs=dao.jobs(id);all.addAll(jobs.flatMap{dao.photos(it.id)}.flatMap{listOf(it.path,it.originalPath)})
    dao.incident(id)?.let{all.addAll(paths(it.value))}
    if(jobs.any{it.id==draft?.jobId}){removeDraft=true;draft?.rawPath?.takeIf{it.isNotBlank()}?.let{all.add(it)}}
    dao.deletePlace(p)
   }
   val retained=retainedPaths(if(removeDraft)null else draftStore.read())
   all.mapNotNull{owned(it)?.path}.filter{it !in retained}.forEach{dao.queueFile(FileCleanup(it))}
   true
  }
  if(!deleted)return false
  if(removeDraft&&draftStore.read()==draft)draftStore.clear()
  drain()
  return true
 }
 /** Legacy shared dossiers become independent folders; no child report is discarded. */
 suspend fun flatten(kind:String)=withContext(Dispatchers.IO){
  if(kind !in listOf("Sự cố","Di dời"))return@withContext
  dao.places(kind).forEach{p->val row=dao.incident(p.id)?:return@forEach;val obj=JSONObject(row.value);if(!obj.has("routes"))return@forEach
   var migrated=false
   val dossier=IncidentDossier.parse(row.value,p.name);val made=mutableListOf<File>()
   try{database.withTransaction{
    if(dao.incident(p.id)?.value!=row.value)return@withTransaction
    val used=dao.places(kind).map{it.name.lowercase()}.toMutableSet()
    dossier.routes.forEachIndexed{index,route->
     val r=IncidentReport.parse(route.report.json());r.relocation=kind=="Di dời";IncidentRules.prepare(r)
     if(index==0){if(r.pendingPhoto.isBlank()&&dossier.shared.pendingPhoto.isNotBlank()){r.pendingPhoto=dossier.shared.pendingPhoto;r.pendingDate=dossier.shared.pendingDate;r.pendingGroup=r.groups.indexOfFirst{it.name==dossier.shared.groups.getOrNull(dossier.shared.pendingGroup)?.name}.coerceAtLeast(0)};dao.saveIncident(IncidentRecord(p.id,r.json()))}
     else{var name=route.name;var n=2;while(!used.add(name.lowercase()))name="${route.name} (${n++})";val id=dao.addPlace(Place(kind=kind,name=name));dao.saveIncident(IncidentRecord(id,copyReport(r.json(),made,mutableMapOf())))}
    }
    val retained=(dao.incident(p.id)?.let{paths(it.value)}?:emptySet())+dao.jobs(p.id).flatMap{dao.photos(it.id)}.flatMap{listOf(it.path,it.originalPath)}
    (paths(row.value)-retained).forEach{dao.queueFile(FileCleanup(it))};migrated=true
   }}catch(e:Exception){made.forEach{it.delete()};throw e}
   if(migrated)AutoSync.changed()
  }
  drain()
 }
}
