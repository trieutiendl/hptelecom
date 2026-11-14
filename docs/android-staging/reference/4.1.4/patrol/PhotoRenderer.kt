package vn.vltelecom

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object PhotoRenderer {
    fun thumbnail(path:String,max:Int=900):Bitmap? {
        val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,b)
        if(b.outWidth<=0 || b.outHeight<=0)return null
        val o=BitmapFactory.Options().apply{inSampleSize=1;while(b.outWidth/inSampleSize>max || b.outHeight/inSampleSize>max)inSampleSize*=2}
        val original=BitmapFactory.decodeFile(path,o)?:return null
        try {val exif=ExifInterface(path);val m=Matrix().apply{postRotate(exif.rotationDegrees.toFloat());if(exif.isFlipped)postScale(-1f,1f)}
            val rotated=Bitmap.createBitmap(original,0,0,original.width,original.height,m,true);if(rotated!==original)original.recycle();return rotated
        }catch(e:Exception){original.recycle();throw e}
    }
    fun overlay(raw:File,dest:File,text:List<String>,ink:Int=Color.WHITE){
        val source=thumbnail(raw.path,1800)?:error("Không đọc được ảnh")
        val result=source.copy(Bitmap.Config.ARGB_8888,true)
        try{
            val margin=source.width*0.035f
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);textSize=source.width/34f;setShadowLayer(2.5f,0f,1.5f,if(ink==Color.BLACK)Color.WHITE else Color.BLACK)}
            fun wrap():List<String>{val lines=mutableListOf<String>();text.forEach{paragraph->paragraph.lines().forEach{line->var rest=line;if(rest.isEmpty())lines.add("");while(rest.isNotEmpty()){val n=paint.breakText(rest,true,source.width-2*margin,null).coerceAtLeast(1);lines.add(rest.take(n));rest=rest.drop(n)}}};return lines}
            var lines=wrap();while(lines.size*paint.textSize*1.3f>source.height*0.48f&&paint.textSize>source.width/100f){paint.textSize*=0.9f;lines=wrap()}
            val canvas=Canvas(result);val h=paint.textSize*1.3f;val start=source.height-margin-h*(lines.size-1)
            lines.forEachIndexed{i,line->canvas.drawText(line,margin,start+i*h,paint)}
            dest.outputStream().use{check(result.compress(Bitmap.CompressFormat.JPEG,94,it)){"Không ghi được ảnh"}}
        }finally{source.recycle();result.recycle()}
    }
    fun remember(source:File,target:File,text:List<String>,ink:Int){
        val original=File(target.path+".original.jpg");source.copyTo(original,true)
        val metadata=org.json.JSONObject().put("hpOverlay",1).put("original",original.path).put("lines",org.json.JSONArray(text)).put("ink",ink)
        ExifInterface(target).apply{setAttribute(ExifInterface.TAG_USER_COMMENT,PhotoTextEncoding.asciiJson(metadata.toString()));saveAttributes()}
    }
    private fun overlayMetadata(source:File):org.json.JSONObject?=runCatching{
        org.json.JSONObject(ExifInterface(source).getAttribute(ExifInterface.TAG_USER_COMMENT)?:"").takeIf{it.optInt("hpOverlay")==1&&File(it.optString("original")).isFile}
    }.getOrNull()
    fun textFor(source:File):org.json.JSONArray? = overlayMetadata(source)?.optJSONArray("lines")
    fun originalFor(source:File):String? = overlayMetadata(source)?.optString("original")?.takeIf{it.isNotBlank()}
    fun copyOverlay(source:File,target:File){
        val m=overlayMetadata(source)?:return;val a=m.getJSONArray("lines")
        remember(File(m.getString("original")),target,(0 until a.length()).map{a.getString(it)},m.getInt("ink"))
    }
    fun renamed(source:File,target:File,title:String):Boolean{
        val m=overlayMetadata(source)?:return false;val a=m.getJSONArray("lines")
        val lines=(0 until a.length()).map{if(it==0)"Tuyến: $title" else PhotoTextEncoding.restoreLabel(a.getString(it))}
        val raw=File(m.getString("original"));overlay(raw,target,lines,m.getInt("ink"));remember(raw,target,lines,m.getInt("ink"));return true
    }
    fun write(raw:File,dest:File,job:String,d:Draft){
        val text=mutableListOf("Tuyến: $job","STT ${d.seq} • Vị trí: ${d.name}")
        if(d.showDate)text.add("Ngày chụp: "+SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date(d.capturedAt)))
        text.add(String.format(Locale.US,"Lat: %.6f | Long: %.6f",d.lat,d.lon));text.add("Ghi chú: ${d.note}")
        overlay(raw,dest,text,d.textColor)
        ExifInterface(dest).apply{setLatLong(d.lat!!,d.lon!!);setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,"$job | ${d.seq} | ${d.name} | ${d.note}");setAttribute(ExifInterface.TAG_USER_COMMENT,PhotoTextEncoding.routeKey("$job | ${d.seq} | ${d.name} | ${d.note}"));saveAttributes()}
    }
}
