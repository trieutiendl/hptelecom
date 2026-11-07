package vn.vltelecom

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun deletingRouteRemovesOnlyItsData()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,WorkDatabase::class.java).build()
        try {
            val dao=db.dao()
            val route=Place(id=dao.addPlace(Place(kind="Tuyến cáp",name="A")),kind="Tuyến cáp",name="A")
            val other=dao.addPlace(Place(kind="Tuyến cáp",name="B"))
            val job=dao.addJob(Job(placeId=route.id,name="A"))
            val otherJob=dao.addJob(Job(placeId=other,name="B"))
            val photo=Photo(jobId=job,seq=1,locationName="Cột",lat=10.0,lon=106.0,accuracy=5f,note="MX",path="a.jpg",capturedAt=1)
            dao.addPhoto(photo);dao.addPhoto(photo.copy(jobId=otherJob,path="b.jpg"))
            dao.deletePlace(route)
            assertNull(dao.place(route.id));assertNull(dao.job(job));assertTrue(dao.photos(job).isEmpty())
            assertNotNull(dao.place(other));assertEquals(1,dao.photos(otherJob).size)
        } finally {db.close()}
    }
    @Test fun draftSurvivesStoreRecreation(){
        val context=InstrumentationRegistry.getInstrumentation().context
        val d=Draft(12,rawPath="/private/raw.jpg",seq="7",name="Cột 7",note="MX 7",lat=10.1,lon=106.1,accuracy=9f,capturePending=true)
        try{DraftStore(context).save(d);assertEquals(d,DraftStore(context).read())}finally{DraftStore(context).clear()}
    }
    @Test fun roomSurvivesReopenAndRejectsDuplicateSequence()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="test_${System.nanoTime()}.db"
        var db=Room.databaseBuilder(context,WorkDatabase::class.java,name).build()
        try{val dao=db.dao();val place=dao.addPlace(Place(kind="Tuyến cáp",name="Tuyến thử"));val job=dao.addJob(Job(placeId=place,name="Kiểm tra"))
            val p=Photo(jobId=job,seq=10,locationName="Cột",lat=10.0,lon=106.0,accuracy=5f,note="MX",path="photo.jpg",capturedAt=1)
            dao.addPhoto(p);dao.addPhoto(p.copy(seq=2));var rejected=false
            try{dao.addPhoto(p)}catch(e:android.database.sqlite.SQLiteConstraintException){rejected=true};assertTrue(rejected)
            dao.markJob(job,true);db.close();db=Room.databaseBuilder(context,WorkDatabase::class.java,name).build()
            assertTrue(db.dao().job(job)!!.completed);assertEquals(listOf(2,10),db.dao().photos(job).map{it.seq})
        }finally{db.close();context.deleteDatabase(name)}
    }
}
