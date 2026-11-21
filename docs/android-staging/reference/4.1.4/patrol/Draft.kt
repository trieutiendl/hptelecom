package vn.vltelecom

import android.content.Context
import org.json.JSONObject

/** Draft exists before launching the external camera and survives Activity/process recreation. */
data class Draft(val jobId:Long,val photoId:Long=0,val rawPath:String="",val seq:String="",val name:String="",val note:String="",val lat:Double?=null,val lon:Double?=null,val accuracy:Float=0f,val fixTime:Long=0,val capturedAt:Long=0,val capturePending:Boolean=false,val showDate:Boolean=true,val textColor:Int=-1,val address:String?=null,val proposal:String?=null,val assessment:String?=null)
class DraftStore(context:Context){
    private val prefs=context.getSharedPreferences("editor_draft",Context.MODE_PRIVATE)
    fun read():Draft?=runCatching {
        val j=JSONObject(prefs.getString("draft",null)?:return null)
        Draft(j.getLong("job"),j.optLong("photo"),j.optString("raw"),j.optString("seq"),j.optString("name"),j.optString("note"),if(j.has("lat"))j.getDouble("lat") else null,if(j.has("lon"))j.getDouble("lon") else null,j.optDouble("accuracy",0.0).toFloat(),j.optLong("fixTime"),j.optLong("captured"),j.optBoolean("pending"),j.optBoolean("showDate",true),j.optInt("textColor",-1),if(j.has("address"))j.getString("address")else null,if(j.has("proposal"))j.getString("proposal")else null,if(j.has("assessment"))j.getString("assessment")else null)
    }.getOrNull()
    fun save(d:Draft){val j=JSONObject().put("job",d.jobId).put("photo",d.photoId).put("raw",d.rawPath).put("seq",d.seq).put("name",d.name).put("note",d.note).put("accuracy",d.accuracy).put("fixTime",d.fixTime).put("captured",d.capturedAt).put("pending",d.capturePending).put("showDate",d.showDate).put("textColor",d.textColor)
        d.address?.let{j.put("address",it)};d.proposal?.let{j.put("proposal",it)};d.assessment?.let{j.put("assessment",it)};d.lat?.let{j.put("lat",it)};d.lon?.let{j.put("lon",it)};prefs.edit().putString("draft",j.toString()).apply()
    }
    fun clear(){prefs.edit().remove("draft").apply()}
}
