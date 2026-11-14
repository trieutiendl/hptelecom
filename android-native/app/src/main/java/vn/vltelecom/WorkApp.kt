package vn.vltelecom

import android.app.Application
import kotlinx.coroutines.async
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities=[Place::class,Job::class,Photo::class,IncidentRecord::class,FileCleanup::class],version=5,exportSchema=true)
abstract class WorkDatabase:RoomDatabase(){abstract fun dao():WorkDao}
class WorkApp:Application(){
    var foregroundActivities=0;private set
    override fun onCreate(){super.onCreate();registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks{
        override fun onActivityStarted(a:android.app.Activity){val entering=foregroundActivities==0;foregroundActivities++;if(ServerSession.token.isNotEmpty()&&accountScope!="locked"){AutoSync.start(this@WorkApp);ChatService.start(this@WorkApp);if(entering)AutoSync.refresh()}}
        override fun onActivityStopped(a:android.app.Activity){foregroundActivities=(foregroundActivities-1).coerceAtLeast(0)}
        override fun onActivityCreated(a:android.app.Activity,b:android.os.Bundle?){}
        override fun onActivityResumed(a:android.app.Activity){}
        override fun onActivityPaused(a:android.app.Activity){}
        override fun onActivitySaveInstanceState(a:android.app.Activity,b:android.os.Bundle){}
        override fun onActivityDestroyed(a:android.app.Activity){}
    });runCatching{(getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(object:android.net.ConnectivityManager.NetworkCallback(){
        private val ready=mutableSetOf<android.net.Network>()
        override fun onAvailable(network:android.net.Network){AutoSync.refresh()}
        override fun onCapabilitiesChanged(network:android.net.Network,caps:android.net.NetworkCapabilities){
            if(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)){if(ready.add(network))AutoSync.refresh()}else ready.remove(network)
        }
        override fun onLost(network:android.net.Network){ready.remove(network)}
    })}}

    var accountScope="locked";private set
    val accountFilesDir get()=java.io.File(filesDir,"accounts/$accountScope").apply{mkdirs()}
    private var activeDb:WorkDatabase?=null
    val db:WorkDatabase get() {check(accountScope!="locked"){"Hãy đăng nhập"};return activeDb?:buildDb().also{activeDb=it}}
    private fun buildDb():WorkDatabase = Room.databaseBuilder(this,WorkDatabase::class.java,"server-$accountScope.db").addMigrations(object:androidx.room.migration.Migration(1,2){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS incident_reports (placeId INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY(placeId), FOREIGN KEY(placeId) REFERENCES places(id) ON UPDATE NO ACTION ON DELETE CASCADE)")}},object:androidx.room.migration.Migration(2,3){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("ALTER TABLE photos ADD COLUMN showDate INTEGER NOT NULL DEFAULT 1");db.execSQL("CREATE TABLE IF NOT EXISTS file_cleanup (path TEXT NOT NULL, PRIMARY KEY(path))")}},object:androidx.room.migration.Migration(3,4){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("ALTER TABLE photos ADD COLUMN textColor INTEGER NOT NULL DEFAULT -1")}},object:androidx.room.migration.Migration(4,5){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("ALTER TABLE photos ADD COLUMN photographer TEXT NOT NULL DEFAULT ''")}}).build()
    private var activeModel:WorkModel?=null
    private val reportEditors=mutableMapOf<Long,Int>()
    fun reportOpened(id:Long){reportEditors[id]=(reportEditors[id]?:0)+1}
    fun reportClosed(id:Long){val count=(reportEditors[id]?:0)-1;if(count<=0)reportEditors.remove(id)else reportEditors[id]=count;AutoSync.refresh()}
    suspend fun canReceiveReport(id:Long):Boolean {
        if((reportEditors[id]?:0)>0)return false
        val saved=db.dao().incident(id)?.let{org.json.JSONObject(it.value)}
        if(saved?.optString("pendingPhoto").orEmpty().isNotBlank()||saved?.optString("pendingOriginal").orEmpty().isNotBlank())return false
        val draft=DraftStore(this).read()
        if(draft!=null&&db.dao().jobs(id).any{it.id==draft.jobId})return false
        val current=activeModel?.state?.value
        return current==null||((!current.busy||current.screen==Screen.PLACES)&&(current.place?.id!=id||current.screen in listOf(Screen.HOME,Screen.PLACES)))
    }
    val model:WorkModel get()=activeModel?:WorkModel(this).also{activeModel=it}
    fun notifyFoldersDownloaded(kind:String){activeModel?.refreshPlacesAfterSync(kind)}
    suspend fun switchAccount(scope:String){check(Regex("[a-f0-9-]{65}").matches(scope));reportWrite?.await();activeModel?.close();activeModel=null;activeDb?.close();activeDb=null;accountScope=scope}
    override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences=super.getSharedPreferences(if(name=="server-settings")name else "$accountScope-$name",mode)
    suspend fun flushReport(){reportWrite?.await()}
    private val reportsScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
    private var reportWrite:kotlinx.coroutines.Deferred<Unit>?=null
    fun incidentReporter()=getSharedPreferences("report-defaults",MODE_PRIVATE).getString("reporter","")?:""
    fun saveReport(id:Long,value:String):kotlinx.coroutines.Deferred<Unit>{
        val previous=reportWrite
        return reportsScope.async {previous?.join();val r=IncidentReport.parse(value);if(!SharedReports.shared(this@WorkApp,id))r.reporter=ServerSession.user?.optString("display_name")?:r.reporter;val saved=r.json();if(db.dao().incident(id)?.value!=saved){db.dao().saveIncident(IncidentRecord(id,saved));AutoSync.changed()};if(!r.relocation)getSharedPreferences("report-defaults",MODE_PRIVATE).edit().putString("reporter",r.reporter).apply()}.also{reportWrite=it}
    }
}
