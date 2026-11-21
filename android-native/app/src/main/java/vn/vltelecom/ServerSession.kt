package vn.vltelecom

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ServerSession {
 data class Pinned(val account:String,val token:String,val url:String)
 fun pin(app:WorkApp):Pinned {
  check(token.isNotEmpty()&&Regex("[a-f0-9]{32}-[a-f0-9]{32}").matches(app.accountScope)){"Hãy đăng nhập để mở thư viện và đồng bộ."}
  return Pinned(app.accountScope,token,url)
 }
 fun matches(app:WorkApp,pinned:Pinned)=app.accountScope==pinned.account&&token==pinned.token&&url==pinned.url&&pinned.token.isNotEmpty()
 /** Library operations retain originating credentials even if another login happens on Main. */
 suspend fun apiPinned(app:WorkApp,pinned:Pinned,path:String,body:JSONObject?=null,method:String="POST",maxResponseBytes:Int=262144):JSONObject =
  JSONObject(String(bytesPinned(app,pinned,path,body,method,maxResponseBytes),Charsets.UTF_8))
 suspend fun bytesPinned(app:WorkApp,pinned:Pinned,path:String,body:JSONObject?=null,method:String="GET",maxResponseBytes:Int=10*1024*1024):ByteArray=withContext(Dispatchers.IO){
  require(method in listOf("GET","POST")&&maxResponseBytes in 1..(10*1024*1024))
  check(matches(app,pinned)){"Tài khoản hoặc kết nối đã thay đổi. Dữ liệu trên điện thoại vẫn được giữ."}
  val c=URL(pinned.url+"/api/"+path).openConnection() as HttpURLConnection
  try{
   c.requestMethod=method;c.connectTimeout=15000;c.readTimeout=if(path=="mobile/photo-ocr")120000 else 30000;c.instanceFollowRedirects=false
   c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","HP-Telecom-Android/1.0")
   c.setRequestProperty("Authorization","Bearer ${pinned.token}")
   check(matches(app,pinned)){"Tài khoản đã thay đổi. Dữ liệu trên điện thoại vẫn được giữ."}
   if(body!=null){val encoded=body.toString().toByteArray(Charsets.UTF_8);c.doOutput=true;c.setFixedLengthStreamingMode(encoded.size);c.outputStream.use{it.write(encoded)}}
   val status=c.responseCode
   if(status !in 200..299){
    val error=runCatching{JSONObject(c.errorStream?.use{String(readBounded(it,16384),Charsets.UTF_8)}?:"{}")}.getOrDefault(JSONObject())
    val message=error.optString("error","Không kết nối được server")
    if(status==401)withContext(Dispatchers.Main.immediate){if(pinned.token==token){runCatching{offlineContext?.let{RememberLogin.save((it as? WorkApp)?.baseContext?:it,null,offlineSlot);RememberLogin.save(it,null,offlineSlot)}};invalidToken.value=pinned.token}}
    throw Failure(status,message,error.optString("code"))
   }
   val bytes=c.inputStream.use{readBounded(it,maxResponseBytes)}
   check(matches(app,pinned)){"Tài khoản hoặc kết nối đã thay đổi. Dữ liệu trên điện thoại vẫn được giữ."}
   bytes
  }finally{c.disconnect()}
 }
 private fun readBounded(input:java.io.InputStream,limit:Int):ByteArray=java.io.ByteArrayOutputStream().use{output->
  val buffer=ByteArray(8192)
  while(true){val count=input.read(buffer);if(count<0)break;check(output.size()+count<=limit){"Phản hồi thư viện vượt dung lượng cho phép."};output.write(buffer,0,count)}
  output.toByteArray()
 }
 private var offlineContext:Context?=null
 private val invalidToken=MutableStateFlow<String?>(null)
 val invalidSessionToken=invalidToken.asStateFlow()
 private const val offlineSlot="hp-telecom-offline-session"
 fun offlineAccount(app:WorkApp):JSONObject?=(RememberLogin.read(app.baseContext,offlineSlot)?:RememberLogin.read(app,offlineSlot))?.takeIf{it.optString("token").isNotBlank()&&it.optString("token")!=invalidToken.value&&it.optString("scope").endsWith("-"+(it.optJSONObject("user")?.optString("id")?:"INVALID"))&&it.optJSONObject("user")?.optJSONArray("modules")!=null}
 suspend fun restoreOffline(app:WorkApp){AutoSync.stop();val saved=offlineAccount(app)?:error("Cần đăng nhập có mạng một lần trước khi dùng offline.");check(saved.getString("token")!=invalidToken.value){"Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại."};url=normalize(saved.getString("url"));invalidToken.value=null;token=saved.getString("token");user=saved.getJSONObject("user");offlineContext=app;app.switchAccount(saved.getString("scope"));AutoSync.start(app)}
 private fun saveOffline(app:WorkApp){RememberLogin.save(app.baseContext,JSONObject().put("url",url).put("token",token).put("user",user).put("scope",app.accountScope),offlineSlot)}

 var url="";var token="";var user:JSONObject?=null
 fun displayKind(kind:String)=if(kind=="Tuyến cáp")"Tuần tuyến" else kind
 val moduleNames=linkedMapOf("incident" to "Sự cố","cable" to "Tuyến cáp","relocation" to "Di dời")
 fun module(kind:String)=moduleNames.entries.firstOrNull{it.value==kind}?.key?:""
 fun allowed(kind:String):Boolean {val u=user?:return false;return u.optString("role")=="admin" || (0 until (u.optJSONArray("modules")?.length()?:0)).any{u.getJSONArray("modules").getString(it)==module(kind)}}
 class Failure(val status:Int,message:String,val code:String=""):Exception(message)
 fun normalize(value:String):String {val u=URL(value.trim());require(u.protocol in listOf("http","https")&&u.userInfo==null&&u.query==null&&u.ref==null&&u.path in listOf("","/")){"Nhập địa chỉ server, ví dụ http://192.168.1.20:8787"};if(u.protocol=="http")require(Regex("localhost|127\\.0\\.0\\.1|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+").matches(u.host)){"Server ngoài mạng nội bộ phải dùng HTTPS"};return "${u.protocol}://${u.authority}"}
 suspend fun bytes(path:String,method:String="GET",body:JSONObject?=null):ByteArray=withContext(Dispatchers.IO){
  val requestToken=token
  val c=URL(url+"/api/"+path).openConnection() as HttpURLConnection
  try{c.requestMethod=method;c.connectTimeout=15000;c.readTimeout=if(path=="mobile/assets")120000 else 20000;c.instanceFollowRedirects=false;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("User-Agent","HP-Telecom-Android/1.0");if(requestToken.isNotEmpty())c.setRequestProperty("Authorization","Bearer $requestToken")
   if(body!=null){val raw=body.toString().toByteArray(Charsets.UTF_8);c.doOutput=true;c.setFixedLengthStreamingMode(raw.size);c.outputStream.use{it.write(raw)}}
   val status=c.responseCode;if(status !in 200..299){val message=runCatching{JSONObject(c.errorStream?.bufferedReader()?.use{it.readText()}?:"{}").optString("error","Không kết nối được server")}.getOrDefault("Không kết nối được server");if(status==401&&requestToken.isNotEmpty())withContext(Dispatchers.Main.immediate){
    // Session changes happen on Main. A late response from a previous login must
    // neither invalidate the new session nor erase its remembered credentials.
    if(requestToken==token){runCatching{offlineContext?.let{RememberLogin.save((it as? WorkApp)?.baseContext?:it,null,offlineSlot);RememberLogin.save(it,null,offlineSlot)}};invalidToken.value=requestToken}
   };throw Failure(status,message)}
   c.inputStream.use{it.readBytes()}
  }finally{c.disconnect()}
 }
 suspend fun api(path:String,method:String="GET",body:JSONObject?=null)=JSONObject(String(bytes(path,method,body),Charsets.UTF_8))
 suspend fun login(app:WorkApp,address:String,name:String,password:String){AutoSync.stop();offlineContext=app;token="";user=null;invalidToken.value=null;val prefs=app.getSharedPreferences("server-settings",Context.MODE_PRIVATE);val normalized=runCatching{normalize(address)}.getOrDefault("");val expected=if(normalized==ServerDiscovery.defaultUrl)ServerDiscovery.defaultServerId else prefs.getString("server-id",null)?:offlineAccount(app)?.optString("scope")?.substringBefore('-')?:if(normalized in listOf("http://192.168.1.8:8787","http://192.168.1.59:8787"))ServerDiscovery.defaultServerId else "";url=ServerDiscovery.resolve(app,address,expected);val status=api("status");check(!status.optBoolean("needs_setup")){"Tạo quản trị trên máy Windows trước."};check(status.optJSONArray("capabilities")?.toString()?.contains("cable-assignments")==true){"Hãy cập nhật server lên bản 0.6.0 để nhận tuyến được giao"};check(status.optJSONArray("capabilities")?.toString()?.contains("single-mobile-session")==true){"Hãy cập nhật server lên bản 4.0.9 để giới hạn một điện thoại cho mỗi tài khoản."};val server=status.optString("server_id");check(Regex("[a-f0-9]{32}").matches(server)){"Hãy cập nhật server bản 0.2.0"};val r=api("login","POST",JSONObject().put("username",name).put("password",password).put("client_type","mobile"));invalidToken.value=null;token=r.getString("token");user=r.getJSONObject("user");app.switchAccount(server+"-"+user!!.getString("id"));app.getSharedPreferences("server-settings",Context.MODE_PRIVATE).edit().putString("url",url).putString("server-id",server).apply();saveOffline(app);AutoSync.start(app)}
 suspend fun refresh(){val current=token;val app=offlineContext as? WorkApp
  if(app!=null){val oldUrl=url;val expected=app.accountScope.substringBefore('-');val resolved=ServerDiscovery.resolve(app,url,expected);if(current!=token)return;url=resolved;if(oldUrl!=url){app.getSharedPreferences("server-settings",Context.MODE_PRIVATE).edit().putString("url",url).apply();runCatching{RememberLogin.read(app)?.takeIf{it.optString("url")==oldUrl}?.let{RememberLogin.save(app,it.put("url",url))}}}}
  val result=api("me");if(current==token){user=result;app?.let{saveOffline(it)}}}

 suspend fun logout(){AutoSync.stop();try{api("logout","POST",JSONObject())}finally{offlineContext?.let{RememberLogin.save((it as? WorkApp)?.baseContext?:it,null,offlineSlot);RememberLogin.save(it,null,offlineSlot)};token="";user=null;invalidToken.value=null}}
}
