package vn.vltelecom
import org.json.JSONObject
object CablePoints{
 fun warnings(rows:List<Photo>,r:JSONObject):List<String> = rows.sortedBy{it.seq}.flatMapIndexed{i,p->val q=r.optJSONObject("points")?.optJSONObject(p.seq.toString())?:JSONObject();val result=mutableListOf<String>();if(q.optJSONObject("far")==null)result.add("Vị trí ${i+1}: thiếu ảnh xa");if(r.optString("workType")=="maintenance"&&q.optBoolean("repair")){if(q.optString("proposal").isBlank())result.add("Vị trí ${i+1}: thiếu đề xuất");if(q.optJSONObject("before")==null||q.optJSONObject("after")==null)result.add("Vị trí ${i+1}: thiếu ảnh trước/sau")};result}

 suspend fun move(app:WorkApp,id:Long,old:Int,new:Int?){if(old==new)return;val saved=app.db.dao().incident(id)?:return;val r=JSONObject(saved.value);if(!r.optBoolean("cableWork"))return;val points=r.optJSONObject("points")?:return;val q=points.optJSONObject(old.toString())?:return;points.remove(old.toString());if(new!=null)points.put(new.toString(),q)else for(k in listOf("far","before","after")){val file=q.optJSONObject(k)?.optString("file");if(!file.isNullOrBlank())app.db.dao().queueFile(FileCleanup(file))};app.db.dao().saveIncident(IncidentRecord(id,r.toString()))}
}
