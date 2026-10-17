package vn.vltelecom

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Registration has no access to session writes or remembered login passwords. */
object Registration {
 const val capability="registration-admin-approval"
 private const val pendingMessage="Đã nhận yêu cầu. Tài khoản chỉ đăng nhập được sau khi quản trị phê duyệt."

 fun payload(name:String,username:String,password:String,team:String,phone:String,email:String):JSONObject {
  fun text(value:String,label:String,max:Int):String {
   val trimmed=value.trim()
   require(trimmed.isNotEmpty()&&trimmed.length<=max&&trimmed.none{it.code<32||it.code==127}&&Charsets.UTF_8.newEncoder().canEncode(trimmed)){"$label không hợp lệ."}
   return trimmed
  }
  val displayName=text(name,"Họ tên",100)
  val user=text(username,"Tên đăng nhập",60).lowercase(Locale.ROOT)
  require(Regex("[a-z0-9._-]{3,60}").matches(user)){"Tên đăng nhập 3–60 ký tự: chữ không dấu, số, dấu chấm, gạch dưới hoặc gạch ngang."}
  require(password.length in 10..200&&Charsets.UTF_8.newEncoder().canEncode(password)){"Mật khẩu cần từ 10 đến 200 ký tự hợp lệ."}
  val requestedTeam=text(team,"Tên Team / Đội đề nghị",100)
  val contactPhone=text(phone,"Số điện thoại",40)
  require(Regex("\\+?[0-9 ().-]+").matches(contactPhone)&&contactPhone.count{it in '0'..'9'} in 6..20){"Số điện thoại cần từ 6 đến 20 chữ số."}
  val contactEmail=text(email,"Email",254)
  require(Regex("[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+").matches(contactEmail)){"Địa chỉ email không hợp lệ."}
  return JSONObject().put("display_name",displayName).put("username",user).put("password",password)
   .put("team",requestedTeam).put("phone",contactPhone).put("email",contactEmail)
 }

 internal fun expectedId(address:String,savedId:String?,offlineScope:String?):String {
  val normalized=ServerSession.normalize(address)
  val expected=if(normalized==ServerDiscovery.defaultUrl)ServerDiscovery.defaultServerId
   else savedId?.takeIf{it.isNotBlank()}?:offlineScope?.substringBefore('-')
    ?:if(normalized in listOf("http://192.168.1.8:8787","http://192.168.1.59:8787"))ServerDiscovery.defaultServerId else ""
  check(Regex("[a-f0-9]{32}").matches(expected)){"Chưa xác minh được máy chủ nhận đăng ký. Vui lòng liên hệ quản trị."}
  return expected
 }

 suspend fun submit(app:WorkApp,address:String,body:JSONObject):String {
  val prefs=app.getSharedPreferences("server-settings",Context.MODE_PRIVATE)
  val expected=expectedId(address,prefs.getString("server-id",null),ServerSession.offlineAccount(app)?.optString("scope"))
  val resolved=ServerDiscovery.resolve(app,address,expected)
  return submitVerified(resolved,expected,body){url,path,method,data->withContext(Dispatchers.IO){exchange(url,path,method,data)}}
 }

 internal suspend fun submitVerified(address:String,expected:String,body:JSONObject,
  request:suspend (String,String,String,JSONObject?)->JSONObject):String {
  val normalized=ServerSession.normalize(address)
  check(Regex("[a-f0-9]{32}").matches(expected)){"Chưa xác minh được máy chủ nhận đăng ký."}
  val status=request(normalized,"status","GET",null)
  check(status.optString("server_id")==expected){"Máy chủ nhận đăng ký không đúng. Vui lòng liên hệ quản trị."}
  check(!status.optBoolean("needs_setup",true)){"Máy chủ chưa sẵn sàng nhận đăng ký."}
  val caps=status.optJSONArray("capabilities")
  check(caps!=null&&(0 until caps.length()).any{caps.optString(it)==capability}){"Máy chủ chưa hỗ trợ tạo tài khoản. Cần cập nhật máy chủ lên 4.0.19 trở lên."}
  val result=request(normalized,"register","POST",body)
  check(result.optBoolean("ok")&&result.optString("status")=="pending"&&!result.has("token")){"Máy chủ chưa xác nhận yêu cầu đăng ký. Vui lòng thử lại."}
  return pendingMessage
 }

 internal fun exchange(address:String,path:String,method:String,body:JSONObject?,
  open:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}):JSONObject {
  require((path=="status"&&method=="GET"&&body==null)||(path=="register"&&method=="POST"&&body!=null))
  val c=open(URL("$address/api/$path"))
  try {
   c.requestMethod=method;c.connectTimeout=15000;c.readTimeout=30000;c.instanceFollowRedirects=false
   c.setRequestProperty("Content-Type","application/json; charset=utf-8")
   c.setRequestProperty("Accept","application/json")
   c.setRequestProperty("User-Agent","HP-Telecom-Android/1.0")
   // No Authorization header and no redirect: credentials go only to the
   // identity-checked endpoint, independently of a cached/offline session.
   if(body!=null){val raw=body.toString().toByteArray(Charsets.UTF_8);require(raw.size<=8192){"Yêu cầu đăng ký quá lớn."};c.doOutput=true;c.setFixedLengthStreamingMode(raw.size);c.outputStream.use{it.write(raw)}}
   val code=c.responseCode
   if(code !in 200..299){
    val error=runCatching{c.errorStream?.use{JSONObject(String(readBounded(it),Charsets.UTF_8)).optString("error")}}.getOrNull()
    throw IllegalStateException(error?.takeIf{it.isNotBlank()}?:"Không gửi được yêu cầu (HTTP $code). Vui lòng thử lại.")
   }
   return c.inputStream.use{JSONObject(String(readBounded(it),Charsets.UTF_8))}
  } finally {c.disconnect()}
 }

 private fun readBounded(input:InputStream):ByteArray=ByteArrayOutputStream().use{output->
  val buffer=ByteArray(4096)
  while(true){val count=input.read(buffer);if(count<0)break;check(output.size()+count<=32768){"Phản hồi đăng ký quá lớn."};output.write(buffer,0,count)}
  output.toByteArray()
 }
}
