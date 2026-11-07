package vn.vltelecom

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

object RoutePhotoMove {
    suspend fun move(app:WorkApp,sourceJobId:Long,ids:Set<Long>,destinationId:Long):Int=withContext(Dispatchers.IO){
        check(ids.isNotEmpty()){ "Hãy chọn ảnh cần chuyển." }
        val db=app.db;val dao=db.dao();val made=mutableListOf<File>();var committed=false
        try {
            val count=db.withTransaction {
                val sourceJob=dao.job(sourceJobId)?:error("Tuyến nguồn không còn tồn tại")
                val source=dao.place(sourceJob.placeId)?:error("Tuyến nguồn không còn tồn tại")
                val target=dao.place(destinationId)?:error("Tuyến đích không còn tồn tại")
                check(source.kind=="Tuyến cáp"&&target.kind=="Tuyến cáp"&&source.id!=target.id){"Hãy chọn tuyến đích khác tuyến hiện tại."}
                val draft=DraftStore(app).read()
                check(draft==null||dao.job(draft.jobId)?.placeId !in listOf(source.id,target.id)){"Hãy lưu hoặc hủy bản nháp ở tuyến nguồn và tuyến đích trước."}
                val selected=dao.photos(sourceJobId).filter{it.id in ids}.sortedBy{it.seq}
                check(selected.size==ids.size){"Danh sách ảnh đã thay đổi. Hãy chọn lại."}
                val targetJob=dao.jobs(target.id).firstOrNull()?:Job(id=dao.addJob(Job(placeId=target.id,name=target.name)),placeId=target.id,name=target.name)
                val from=dao.incident(source.id)?.let{JSONObject(it.value)}?:JSONObject().put("cableWork",true)
                val to=dao.incident(target.id)?.let{JSONObject(it.value)}?:JSONObject().put("cableWork",true)
                val fromPoints=from.optJSONObject("points")?:JSONObject()
                val toPoints=to.optJSONObject("points")?:JSONObject()
                val occupied=dao.jobs(target.id).flatMap{dao.photos(it.id)}.map{it.seq}+toPoints.keys().asSequence().mapNotNull{it.toIntOrNull()}.toList()
                val numbers=RouteMoveNumbers.allocate(occupied,selected.size)
                selected.zip(numbers).forEach{(photo,seq)->
                    val raw=File(photo.originalPath)
                    check(photo.originalPath!=photo.path&&raw.isFile){"Ảnh STT ${photo.seq} thiếu ảnh gốc. Chưa chuyển ảnh nào."}
                    val file=File(app.accountFilesDir,"photos/moved-${UUID.randomUUID()}.jpg");file.parentFile?.mkdirs();made.add(file)
                    val d=Draft(targetJob.id,photo.id,raw.path,seq.toString(),photo.locationName,photo.note,photo.lat,photo.lon,photo.accuracy,photo.capturedAt,photo.capturedAt,showDate=photo.showDate,textColor=photo.textColor)
                    PhotoRenderer.write(raw,file,target.name,d)
                    dao.updatePhoto(photo.copy(jobId=targetJob.id,seq=seq,path=file.path))
                    fromPoints.remove(photo.seq.toString())?.let{toPoints.put(seq.toString(),it)}
                }
                from.put("points",fromPoints);to.put("points",toPoints)
                dao.saveIncident(IncidentRecord(source.id,from.toString()));dao.saveIncident(IncidentRecord(target.id,to.toString()))
                dao.markJob(sourceJob.id,false);dao.markJob(targetJob.id,false)
                selected.size
            }
            committed=true;count
        }finally{if(!committed)made.forEach{it.delete()}}
    }
}
