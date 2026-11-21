package vn.vltelecom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File

/** Preserve original MX evidence for upload after the local reader has processed it. */
object MxPhotoUpload {
 const val maxBytes=10L*1024*1024
 private val png=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
 fun extension(file:File):String {
  val header=file.inputStream().use{input->ByteArray(8).also{input.read(it)}}
  return if(header.contentEquals(png))"png" else "jpg"
 }
 internal fun copyOriginal(source:File,target:File):Boolean {
  if(source.length() !in 1..maxBytes)return false
  val header=source.inputStream().use{input->ByteArray(8).also{input.read(it)}}
  val jpeg=header[0]==0xff.toByte()&&header[1]==0xd8.toByte()&&header[2]==0xff.toByte()
  if(!jpeg&&!header.contentEquals(png))return false
  source.copyTo(target,overwrite=true)
  return true
 }
 fun write(source:File,target:File):Pair<Int,Int>{
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  BitmapFactory.decodeFile(source.path,bounds)
  check(bounds.outWidth>0&&bounds.outHeight>0){"Không đọc được ảnh"}
  if(copyOriginal(source,target)){
   val rotated=runCatching{ExifInterface(source).rotationDegrees%180!=0}.getOrDefault(false)
   return if(rotated)bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
  }
  val bitmap=PhotoRenderer.thumbnail(source.path,4096)?:error("Không đọc được ảnh")
  try{
   for(quality in listOf(95,90,85,80)){
    target.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it)){"Không ghi được ảnh"}}
    if(target.length()<=maxBytes-65536)break
   }
   // Transfer existing EXIF tags verbatim. The NAS interprets them; the app
   // never turns file metadata into a latitude/longitude or report field.
   runCatching{
    val original=ExifInterface(source);val output=ExifInterface(target)
    for(tag in listOf(ExifInterface.TAG_GPS_LATITUDE,ExifInterface.TAG_GPS_LATITUDE_REF,
      ExifInterface.TAG_GPS_LONGITUDE,ExifInterface.TAG_GPS_LONGITUDE_REF)){
     original.getAttribute(tag)?.let{output.setAttribute(tag,it)}
    }
    output.saveAttributes()
   }
   check(target.length() in 1..maxBytes){"Ảnh vượt giới hạn 10 MB của máy chủ. Vui lòng chọn ảnh nhỏ hơn."}
   return bitmap.width to bitmap.height
  }finally{bitmap.recycle()}
 }
 fun needsCoordinates(payload:JSONObject):Boolean{
  if(payload.optString("module") !in listOf("incident","relocation"))return false
  val groups=payload.optJSONObject("report")?.optJSONArray("groups")?:return false
  for(i in 0 until groups.length()){
   val group=groups.optJSONObject(i)?:continue
   if(!group.optString("name").contains("MX",ignoreCase=true)&&!group.optString("name").contains("Măng xông",ignoreCase=true))continue
   val images=group.optJSONArray("images")?:continue
   for(j in 0 until images.length()){
    val image=images.optJSONObject(j)?:continue
    fun valid(value:JSONObject?)=value!=null&&MxCoordinates.of(value.optDouble("lat",Double.NaN),value.optDouble("lon",Double.NaN),"server")!=null
    if(!valid(image)&&!valid(image.optJSONObject("gps")))return true
   }
  }
  return false
 }
}
