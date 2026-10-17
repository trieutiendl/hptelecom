package vn.vltelecom

import androidx.room.*

@Entity(tableName="places")
data class Place(@PrimaryKey(autoGenerate=true) val id:Long=0,val kind:String,val name:String)
@Entity(tableName="jobs",foreignKeys=[ForeignKey(entity=Place::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)],indices=[Index("placeId")])
data class Job(@PrimaryKey(autoGenerate=true) val id:Long=0,val placeId:Long,val name:String,val completed:Boolean=false)
@Entity(tableName="photos",foreignKeys=[ForeignKey(entity=Job::class,parentColumns=["id"],childColumns=["jobId"],onDelete=ForeignKey.CASCADE)],indices=[Index(value=["jobId","seq"],unique=true)])
data class Photo(@PrimaryKey(autoGenerate=true) val id:Long=0,val jobId:Long,val seq:Int,val locationName:String,val lat:Double,val lon:Double,val accuracy:Float,val note:String,val path:String,val capturedAt:Long,val originalPath:String=path,@ColumnInfo(defaultValue="1") val showDate:Boolean=true,@ColumnInfo(defaultValue="-1") val textColor:Int=-1,@ColumnInfo(defaultValue="") val photographer:String="")
@Entity(tableName="file_cleanup") data class FileCleanup(@PrimaryKey val path:String)
@Dao interface WorkDao {
    @Query("SELECT * FROM file_cleanup") suspend fun cleanupFiles():List<FileCleanup>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun queueFile(file:FileCleanup)
    @Delete suspend fun forgetFile(file:FileCleanup)

    @Query("SELECT * FROM incident_reports WHERE placeId=:id") suspend fun incident(id:Long):IncidentRecord?
    @Query("SELECT * FROM incident_reports") suspend fun incidents():List<IncidentRecord>
    @Query("DELETE FROM incident_reports WHERE placeId=:id") suspend fun deleteIncident(id:Long)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveIncident(record:IncidentRecord)
    @Query("SELECT * FROM places WHERE kind=:kind ORDER BY name") suspend fun places(kind:String):List<Place>
    @Query("SELECT * FROM places WHERE id=:id") suspend fun place(id:Long):Place?
    @Insert suspend fun addPlace(p:Place):Long
    @Query("UPDATE places SET name=:name WHERE id=:id") suspend fun renamePlace(id:Long,name:String)
    @Query("UPDATE places SET kind=:kind WHERE id=:id") suspend fun movePlace(id:Long,kind:String)
    @Query("UPDATE jobs SET name=:name WHERE placeId=:id AND name=:oldName") suspend fun renameLinkedJobs(id:Long,oldName:String,name:String)
    @Delete suspend fun deletePlace(p:Place)
    @Query("SELECT * FROM jobs WHERE placeId=:id ORDER BY id DESC") suspend fun jobs(id:Long):List<Job>
    @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id:Long):Job?
    @Insert suspend fun addJob(j:Job):Long
    @Query("UPDATE jobs SET completed=:completed WHERE id=:id") suspend fun markJob(id:Long,completed:Boolean)
    @Query("SELECT * FROM photos WHERE jobId=:id ORDER BY seq, id") suspend fun photos(id:Long):List<Photo>
    @Query("SELECT * FROM photos WHERE id=:id") suspend fun photo(id:Long):Photo?
    @Insert suspend fun addPhoto(p:Photo):Long
    @Update suspend fun updatePhoto(p:Photo)
    @Delete suspend fun deletePhoto(p:Photo)
}
@Entity(tableName="incident_reports",foreignKeys=[ForeignKey(entity=Place::class,parentColumns=["id"],childColumns=["placeId"],onDelete=ForeignKey.CASCADE)])
data class IncidentRecord(@PrimaryKey val placeId:Long,val value:String)
