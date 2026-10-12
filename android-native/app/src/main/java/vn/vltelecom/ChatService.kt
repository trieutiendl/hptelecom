package vn.vltelecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hỏi server định kỳ và báo tin nhắn chat mới của Team / Đội, kể cả khi app chạy nền. */
class ChatService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 private var job:Job?=null
 private val seen=mutableMapOf<String,Long>()

 override fun onBind(intent:Intent?):IBinder?=null

 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  val nm=getSystemService(NotificationManager::class.java)
  nm.createNotificationChannel(NotificationChannel(KEEP,"Chat nhóm chạy nền",NotificationManager.IMPORTANCE_MIN))
  nm.createNotificationChannel(NotificationChannel(MSG,"Tin nhắn chat nhóm",NotificationManager.IMPORTANCE_HIGH))
  val keep=NotificationCompat.Builder(this,KEEP).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("HP Telecom").setContentText("Đang nhận tin nhắn nhóm").setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
  runCatching{
   if(android.os.Build.VERSION.SDK_INT>=29)startForeground(1,keep,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1,keep)
  }.onFailure{stopSelf();return START_NOT_STICKY}
  if(job?.isActive!=true)job=scope.launch{loop()}
  return START_STICKY
 }

 override fun onTimeout(startId:Int,fgsType:Int){stopSelf()}

 override fun onDestroy(){scope.cancel();super.onDestroy()}

 private suspend fun loop(){
  while(true){
   if(ServerSession.token.isEmpty()){stopSelf();return}
   try{poll()}catch(e:CancellationException){throw e}catch(e:Exception){
    if(e is ServerSession.Failure&&e.status==401){stopSelf();return}
   }
   delay(15000)
  }
 }

 private suspend fun poll(){
  val me=ServerSession.user?.optString("id")?:""
  val rooms=ServerSession.api("chat/rooms").getJSONArray("rooms")
  for(i in 0 until rooms.length()){
   val r=rooms.getJSONObject(i);val team=r.getString("team");val id=r.optLong("last_id")
   val before=seen[team];seen[team]=id
   if(before==null||id<=before)continue
   val last=r.optJSONObject("last")?:continue
   if(last.optString("user_id")==me||last.optBoolean("deleted"))continue
   if(ChatActivity.visible)continue
   notify(team,last.optString("user_name"),last.optString("body"),id.toInt())
  }
 }

 private fun notify(team:String,from:String,body:String,id:Int){
  if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return
  val open=PendingIntent.getActivity(this,0,Intent(this,ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val n:Notification=NotificationCompat.Builder(this,MSG).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(team).setContentText("$from: $body").setStyle(NotificationCompat.BigTextStyle().bigText("$from: $body")).setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE).build()
  getSystemService(NotificationManager::class.java).notify(1000+(team.hashCode() and 0xffff),n)
 }

 companion object{
  private const val KEEP="chat_keep";private const val MSG="chat_msg"
  fun start(context:Context){runCatching{context.startForegroundService(Intent(context,ChatService::class.java))}}
 }
}
