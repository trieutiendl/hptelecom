package vn.vltelecom

import android.annotation.SuppressLint
import android.graphics.*
import android.location.*
import android.os.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale

object IncidentPhotoGps {
    @SuppressLint("MissingPermission") // Caller requests fine location before entering.
    suspend fun get(manager:LocationManager,handler:Handler,setCancel:((()->Unit)?)->Unit):Location = suspendCancellableCoroutine { continuation ->
        var best:Location?=null;var done=false
        lateinit var listener:LocationListener
        lateinit var timeout:Runnable
        fun finish(error:Exception?=null){
            if(done)return;done=true;handler.removeCallbacks(timeout);runCatching{manager.removeUpdates(listener)};setCancel(null)
            if(continuation.isActive)continuation.resumeWith(if(error!=null)Result.failure(error)else best?.let{Result.success(it)}?:Result.failure(IllegalStateException("Chưa có GPS. Ảnh đã giữ lại; ra ngoài trời rồi bấm Tiếp tục ảnh chờ GPS.")))
        }
        listener=object:LocationListener {
            override fun onLocationChanged(fix:Location){if(done||SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos>30_000_000_000L)return;if(best==null||fix.hasAccuracy()&&fix.accuracy<=(best?.accuracy?:Float.MAX_VALUE))best=fix;if(fix.hasAccuracy()&&fix.accuracy<=20)finish()}
            override fun onProviderEnabled(provider:String){}
            override fun onProviderDisabled(provider:String){}
            @Deprecated("Legacy callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
        }
        timeout=Runnable{finish()};setCancel{finish(IllegalStateException("Đã dừng GPS. Ảnh được giữ lại."))}
        continuation.invokeOnCancellation{handler.post{finish()}}
        try {val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{manager.isProviderEnabled(it)};check(providers.isNotEmpty()){"Hãy bật Dịch vụ định vị. Ảnh đã giữ lại."};handler.postDelayed(timeout,30000);providers.forEach{manager.requestLocationUpdates(it,1000L,0f,listener,Looper.getMainLooper())}}catch(e:Exception){finish(e)}
    }
    fun stamp(source:File,target:File,title:String,date:String,fix:Location,note:String="",showDate:Boolean=true,ink:Int=Color.WHITE){
        val lines=listOfNotNull("Tuyến: $title",if(showDate)"Ngày chụp: $date" else null,String.format(Locale.US,"Lat: %.6f | Long: %.6f",fix.latitude,fix.longitude),"Ghi chú: $note")
        PhotoRenderer.overlay(source,target,lines,ink)
        PhotoRenderer.remember(source,target,lines,ink)
    }
}
