package vn.vltelecom

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.net.*
import javax.net.ssl.SSLException

object ServerDiscovery {
 const val defaultUrl="https://hptelecom.id.vn"
 const val defaultServerId="9d11bc2ae21e4807bd307e44ba64a4af"
 private fun local(value:String)=runCatching{val u=URL(value);u.protocol=="http"&&Regex("(192\\.168|10|172\\.(1[6-9]|2\\d|3[01]))\\..*").matches(u.host)}.getOrDefault(false)
 private suspend fun probe(address:String,expected:String,timeout:Int):Boolean=withContext(Dispatchers.IO){
  val c=URL(address+"/api/status").openConnection() as HttpURLConnection
  try{c.connectTimeout=timeout;c.readTimeout=timeout;c.instanceFollowRedirects=false;c.setRequestProperty("User-Agent","HP-Telecom-Android/1.0")
   if(c.responseCode!=200)return@withContext false
   val s=JSONObject(c.inputStream.bufferedReader().use{it.readText()});val id=s.optString("server_id")
   Regex("[a-f0-9]{32}").matches(id)&&(expected.isEmpty()||id==expected)&&s.optJSONArray("capabilities")?.toString()?.contains("cable-assignments")==true
  }catch(e:CancellationException){throw e}catch(_:Exception){false}finally{c.disconnect()}
 }
 suspend fun checkServer(address:String,expected:String=""):String=withContext(Dispatchers.IO){
  val normalized=try{ServerSession.normalize(address)}catch(e:Exception){throw IllegalArgumentException(e.message?:"Địa chỉ máy chủ không hợp lệ.")}
  val c=try{URL("$normalized/api/status").openConnection() as HttpURLConnection}catch(e:Exception){throw IllegalArgumentException("Địa chỉ máy chủ không hợp lệ.")}
  try{
   c.connectTimeout=8000;c.readTimeout=8000;c.instanceFollowRedirects=false
   c.setRequestProperty("User-Agent","HP-Telecom-Android/1.0");c.setRequestProperty("Accept","application/json")
   val code=c.responseCode
   if(code !in 200..299){
    val redirect=c.getHeaderField("Location")?.takeIf{code in 300..399}
    throw IllegalStateException(if(redirect!=null)"Máy chủ chuyển hướng đến địa chỉ khác. Hãy dùng HTTPS và địa chỉ đúng." else "Máy chủ trả HTTP $code. Kiểm tra địa chỉ hoặc cấu hình truy cập.")
   }
   val status=try{JSONObject(c.inputStream.bufferedReader().use{it.readText()})}catch(_:Exception){throw IllegalStateException("Máy chủ có phản hồi nhưng dữ liệu không đúng định dạng HP Telecom.")}
   val id=status.optString("server_id")
   if(!Regex("[a-f0-9]{32}").matches(id))throw IllegalStateException("Địa chỉ này không phản hồi mã máy chủ HP Telecom hợp lệ.")
   if(expected.isNotEmpty()&&id!=expected)throw IllegalStateException("Địa chỉ đang mở một máy chủ khác với máy chủ đã lưu. Hãy kiểm tra lại địa chỉ.")
   val capabilities=status.optJSONArray("capabilities")?.toString().orEmpty()
   if("cable-assignments" !in capabilities||"single-mobile-session" !in capabilities)throw IllegalStateException("Máy chủ đang chạy phiên bản cũ. Hãy cập nhật server lên 4.0.9 trở lên.")
   "Kết nối thành công · server ${status.optString("version","đang chạy")}."
  }catch(e:CancellationException){throw e}
   catch(e:UnknownHostException){throw IllegalStateException("Không tìm thấy tên miền máy chủ. Kiểm tra kết nối mạng hoặc địa chỉ.")}
   catch(e:SocketTimeoutException){throw IllegalStateException("Máy chủ không phản hồi trong 8 giây. Kiểm tra mạng hoặc VPN.")}
   catch(e:SSLException){throw IllegalStateException("Không tạo được kết nối HTTPS an toàn. Kiểm tra ngày giờ điện thoại và mạng.")}
   catch(e:ConnectException){throw IllegalStateException("Không kết nối được tới máy chủ. Kiểm tra mạng hoặc địa chỉ.")}
   catch(e:IllegalStateException){throw e}
   catch(e:Exception){throw IllegalStateException("Lỗi kết nối: ${e.localizedMessage?:e.javaClass.simpleName}")}
  finally{c.disconnect()}
 }
 suspend fun resolve(app:WorkApp,address:String,expected:String):String=coroutineScope{
  val normalized=ServerSession.normalize(address)
  // Migrate this server's saved LAN address only after verifying its identity.
  if(local(normalized)&&expected==defaultServerId&&probe(defaultUrl,expected,8000))return@coroutineScope defaultUrl
  if(probe(normalized,expected,if(local(normalized))1800 else 8000))return@coroutineScope normalized
  // Public/custom HTTPS endpoints must never fall back to another LAN service.
  if(!local(normalized))error("Không kết nối được server đã cấu hình. Kiểm tra kết nối mạng và server.")
  check(expected.isNotEmpty()){ "Chưa có mã nhận dạng server để tự tìm lại. Vui lòng kiểm tra cấu hình server." }
  val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val network=cm.activeNetwork
  check(network!=null&&cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true){"Cần kết nối cùng Wi-Fi với máy Windows để tìm server nội bộ. Bạn vẫn có thể dùng offline."}
  val ip=cm.getLinkProperties(network)?.linkAddresses?.firstOrNull{it.address is Inet4Address&&it.address.isSiteLocalAddress}?.address?.hostAddress
  check(ip!=null){"Chưa đọc được địa chỉ Wi-Fi. Kiểm tra kết nối mạng."}
  val prefix=ip.substringBeforeLast('.');val port=URL(normalized).port.takeIf{it>0}?:8787;val limit=Semaphore(24)
  val candidates=(1..254).map{"http://$prefix.$it:$port"}.filter{it!=normalized}
  val found=candidates.map{candidate->async{limit.withPermit{if(probe(candidate,expected,500))candidate else null}}}.awaitAll().filterNotNull()
  found.firstOrNull()?:error("Không tìm thấy server HP Telecom trong Wi-Fi. Hãy mở server trên Windows và kiểm tra hai máy dùng cùng mạng. Dữ liệu vẫn lưu trên điện thoại.")
 }
}
