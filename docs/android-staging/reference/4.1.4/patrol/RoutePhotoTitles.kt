package vn.vltelecom

import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Repair both newly renamed routes and stale images from earlier app versions. */
object RoutePhotoTitles {
    suspend fun refresh(app:WorkApp,placeId:Long)=withContext(Dispatchers.IO){
        val dao=app.db.dao();val place=dao.place(placeId)?:return@withContext 0
        if(place.kind!="Tuyến cáp")return@withContext 0
        val made=mutableListOf<File>();val updates=mutableListOf<Pair<Photo,Photo>>()
        var committed=false;var skipped=0;var renamed=false
        try{
            for(job in dao.jobs(placeId))for(photo in dao.photos(job.id)){
                val expected="${place.name} | ${photo.seq} | ${photo.locationName} | ${photo.note}"
                
                val currentText=runCatching{ExifInterface(photo.path).getAttribute(ExifInterface.TAG_USER_COMMENT)==PhotoTextEncoding.routeKey(expected)}.getOrDefault(false)
                if(currentText)continue
                if(photo.originalPath==photo.path||!File(photo.originalPath).isFile){skipped++;continue}
                val target=File(app.accountFilesDir,"photos/route-title-${UUID.randomUUID()}.jpg")
                target.parentFile?.mkdirs();made.add(target)
                val draft=Draft(job.id,photo.id,photo.originalPath,photo.seq.toString(),photo.locationName,photo.note,photo.lat,photo.lon,photo.accuracy,photo.capturedAt,photo.capturedAt,showDate=photo.showDate,textColor=photo.textColor)
                PhotoRenderer.write(File(photo.originalPath),target,place.name,draft)
                updates.add(photo to photo.copy(path=target.path))
            }
            app.db.withTransaction{
                check(dao.place(placeId)?.name==place.name){"Tên tuyến vừa thay đổi. Hãy mở lại tuyến."}
                updates.forEach{(old,new)->check(dao.photo(old.id)==old){"Ảnh vừa thay đổi. Hãy mở lại tuyến."};dao.updatePhoto(new)}
                dao.jobs(placeId).filter{it.name!=place.name}.forEach{job->dao.renameLinkedJobs(placeId,job.name,place.name);renamed=true}
            }
            committed=true
            if(updates.isNotEmpty()||renamed)AutoSync.changed()
        }finally{if(!committed)made.forEach{it.delete()}}
        skipped
    }
}
