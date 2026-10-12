package vn.vltelecom

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FolderPersistenceTest {
 @Test fun acknowledgedDeleteKeepsCrossReportMediaAndRejectsChangedSnapshot()=runBlocking{
  val env=InstrumentationRegistry.getInstrumentation();val app=env.targetContext.applicationContext as WorkApp
  val db=Room.inMemoryDatabaseBuilder(app,WorkDatabase::class.java).build();val root=File(app.cacheDir,"folders-retention-${System.nanoTime()}").apply{mkdirs()};val drafts=DraftStore(env.context);val store=FolderStore(app,db,root,drafts)
  try{
   val dao=db.dao();val first=dao.addPlace(Place(kind="Sự cố",name="Finished"));val second=dao.addPlace(Place(kind="Sự cố",name="Still editing"))
   val shared=File(root,"shared.jpg").apply{writeBytes(byteArrayOf(1,2,3))};val original=File(shared.path+".original.jpg").apply{writeBytes(byteArrayOf(4,5,6))}
   for(id in listOf(first,second)){val report=IncidentReport(completed=id==first);report.groups[0].images.add(IncidentImage(shared.path,2,3));dao.saveIncident(IncidentRecord(id,report.json()))}
   assertFalse(store.deleteGuarded(listOf(first)){false});assertNotNull(dao.place(first));assertTrue(shared.exists());assertTrue(dao.cleanupFiles().isEmpty())
   assertTrue(store.deleteGuarded(listOf(first)){true});assertNull(dao.place(first));assertNotNull(dao.place(second));assertTrue(shared.isFile);assertTrue(original.isFile)
   // A persisted cleanup entry from an interrupted older run is checked again.
   dao.queueFile(FileCleanup(shared.path));store.drain();assertTrue(shared.isFile);assertTrue(original.isFile);assertTrue(dao.cleanupFiles().isEmpty())
   store.delete(listOf(second));assertFalse(shared.exists());assertFalse(original.exists())
  }finally{drafts.clear();db.close();root.deleteRecursively()}
 }
 @Test fun copiesOwnFilesAndBatchDeletePreservesUnselectedFolder()=runBlocking{
  val env=InstrumentationRegistry.getInstrumentation();val app=env.targetContext.applicationContext as WorkApp
  val db=Room.inMemoryDatabaseBuilder(app,WorkDatabase::class.java).build();val root=File(app.cacheDir,"folders-test-${System.nanoTime()}").apply{mkdirs()};val drafts=DraftStore(env.context);val store=FolderStore(app,db,root,drafts)
  try{
   val dao=db.dao();val id=dao.addPlace(Place(kind="Sự cố",name="A"));val original=dao.place(id)!!
   val image=File(root,"a.jpg").apply{writeBytes(byteArrayOf(1,2,3))};val r=IncidentReport();r.groups[0].images.add(IncidentImage(image.path,2,3));dao.saveIncident(IncidentRecord(id,r.json()))
   val job=dao.addJob(Job(placeId=id,name="A"));dao.addPhoto(Photo(jobId=job,seq=1,locationName="P",lat=10.0,lon=106.0,accuracy=5f,note="",path=image.path,capturedAt=1,showDate=false))
   store.copy(original);val copied=dao.places("Sự cố").first{it.id!=id};val copiedReport=IncidentReport.parse(dao.incident(copied.id)!!.value);val copiedFile=File(copiedReport.groups[0].images[0].path)
   assertNotEquals(image.path,copiedFile.path);assertArrayEquals(image.readBytes(),copiedFile.readBytes());assertFalse(dao.photos(dao.jobs(copied.id).first().id).first().showDate)
   drafts.save(Draft(job,rawPath=image.path));store.delete(listOf(id));assertNull(dao.place(id));assertNull(dao.incident(id));assertFalse(image.exists());assertTrue(copiedFile.isFile);assertNull(drafts.read());assertTrue(dao.cleanupFiles().isEmpty())
   store.delete(listOf(copied.id));assertFalse(copiedFile.exists())
  }finally{drafts.clear();db.close();root.deleteRecursively()}
 }
 @Test fun legacyMultipleRoutesFlattenOnceAndMissingCopyRollsBack()=runBlocking{
  val env=InstrumentationRegistry.getInstrumentation();val app=env.targetContext.applicationContext as WorkApp
  val db=Room.inMemoryDatabaseBuilder(app,WorkDatabase::class.java).build();val root=File(app.cacheDir,"folders-test-${System.nanoTime()}").apply{mkdirs()};val store=FolderStore(app,db,root,DraftStore(env.context))
  try{
   val dao=db.dao();val id=dao.addPlace(Place(kind="Sự cố",name="Original"));val image=File(root,"a.jpg").apply{writeBytes(byteArrayOf(1,2))};val r=IncidentReport();r.groups[0].images.add(IncidentImage(image.path,2,3))
   val dossier=IncidentDossier.fromLegacy(r,"Original");dossier.add("Second");dao.saveIncident(IncidentRecord(id,dossier.json()));store.flatten("Sự cố");assertEquals(2,dao.places("Sự cố").size)
   val second=dao.places("Sự cố").first{it.id!=id};val firstImage=IncidentReport.parse(dao.incident(id)!!.value).groups[0].images[0].path;val secondImage=IncidentReport.parse(dao.incident(second.id)!!.value).groups[0].images[0].path
   assertNotEquals(firstImage,secondImage);store.flatten("Sự cố");assertEquals(2,dao.places("Sự cố").size)
   val broken=IncidentReport.parse(dao.incident(id)!!.value);broken.groups[0].images.add(IncidentImage(File(root,"missing.jpg").path,1,1));dao.saveIncident(IncidentRecord(id,broken.json()))
   val files=root.listFiles()!!.map{it.name}.toSet();var failed=false;try{store.copy(dao.place(id)!!)}catch(e:Exception){failed=true};assertTrue(failed);assertEquals(2,dao.places("Sự cố").size);assertEquals(files,root.listFiles()!!.map{it.name}.toSet())
  }finally{db.close();root.deleteRecursively()}
 }
}
