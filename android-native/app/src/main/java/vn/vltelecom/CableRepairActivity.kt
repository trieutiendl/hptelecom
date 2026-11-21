package vn.vltelecom

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

class CableRepairActivity:AppCompatActivity(){
 private val app get()=application as WorkApp
 private var photo:Photo?=null
 private var placeId=0L
 private var slot="before"
 private var busy=false
 private var assessment=""
 private lateinit var address:EditText;private lateinit var description:EditText;private lateinit var proposal:EditText
 private lateinit var status:TextView
 private val camera=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==RESULT_OK){val path=r.data?.getStringExtra("path");if(path!=null)savePicture(File(path),r.data?.getStringExtra("raw"),capturedAt=r.data?.getLongExtra("time",0)?:0)}}
 private val serverPicker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==RESULT_OK){val d=r.data?:return@registerForActivityResult;val path=d.getStringExtra("path")?:return@registerForActivityResult;savePicture(File(path),null,d.getStringExtra("photographer"),d.getLongExtra("capturedAt",0),copyLibrarySource=true)}}
 private val picker=registerForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null){busy=true;lifecycleScope.launch{var f:File?=null;try{f=File(app.accountFilesDir,"photos/repair-${java.util.UUID.randomUUID()}.jpg");withContext(Dispatchers.IO){f.parentFile?.mkdirs();contentResolver.openInputStream(uri)!!.use{source->f.outputStream().use{dest->source.copyTo(dest)}};check(f.length()<=10*1024*1024){"Ảnh vượt 10 MB, hãy chọn ảnh nhỏ hơn."}};savePicture(f,null)}catch(e:Exception){f?.delete();busy=false;status.text=e.message}}}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);slot=savedInstanceState?.getString("slot")?:"before";lifecycleScope.launch{photo=app.db.dao().photo(intent.getLongExtra("photo",0));val p=photo?:run{finish();return@launch};placeId=app.db.dao().job(p.jobId)?.placeId?:run{finish();return@launch};val r=app.db.dao().incident(placeId)?.let{JSONObject(it.value)}?:JSONObject();val q=r.optJSONObject("points")?.optJSONObject(p.seq.toString())?:JSONObject();show(q)}}
 override fun onSaveInstanceState(out:Bundle){out.putString("slot",slot);super.onSaveInstanceState(out)}
 private fun show(q:JSONObject){val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,40,24,30);setBackgroundColor(Color.rgb(0,43,62))};val scroll=ScrollView(this).apply{addView(body)};setContentView(scroll);applyImeInsetsTo(scroll)
  fun button(label:String,action:()->Unit){body.addView(Button(this).apply{text=label;isAllCaps=false;setOnClickListener{if(!busy)action()}})}
  fun field(label:String,value:String):EditText{body.addView(TextView(this).apply{text=label;setTextColor(Color.WHITE)});return EditText(this).apply{setText(value);setTextColor(Color.WHITE);minLines=2;body.addView(this)}}
  button("‹ Quay lại"){finish()};body.addView(TextView(this).apply{text=photo?.locationName;textSize=22f;setTextColor(Color.WHITE)})
  photo?.let{savedPhoto->button("Xem ảnh vị trí đã chụp"){PhotoViewer.show(this,savedPhoto.path)}}
  address=field("Địa chỉ GPS",q.optString("address"));description=field("Mô tả",q.optString("description",photo?.note.orEmpty()));proposal=field("Phương án / Kiến nghị sửa",q.optString("proposal"));assessment=q.optString("assessment")
  val choices=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL};for((i,v)in listOf("OK","NOK").withIndex())choices.addView(RadioButton(this).apply{id=100+i;text=v;setTextColor(Color.WHITE);isChecked=assessment==v});choices.setOnCheckedChangeListener{_,id->assessment=if(id==100)"OK" else "NOK"};body.addView(choices)
  status=TextView(this).apply{setTextColor(Color.WHITE);text="Đổi OK / NOK rồi bấm Lưu thông tin. Không cần chụp lại ảnh; ảnh trước/sau cũ vẫn giữ."};body.addView(status)
  for((key,label)in listOf("before" to "Trước sửa","after" to "Sau sửa")){
   q.optJSONObject(key)?.optString("file")?.takeIf{it.isNotBlank()}?.let{path->button("Xem ảnh $label"){PhotoViewer.show(this,path)}}
   button("Chụp ảnh $label"){slot=key;lifecycleScope.launch{val p=photo?:return@launch;val title=app.db.dao().place(placeId)?.name.orEmpty();camera.launch(Intent(this@CableRepairActivity,CaptureActivity::class.java).putExtra("title",title).putExtra("seq",p.seq.toString()).putExtra("name",p.locationName).putExtra("note",label+" · "+description.text.toString()).putExtra("showDate",p.showDate).putExtra("textColor",p.textColor).putExtra("repair",true).putExtra("route",false))}}
   button("Chọn ảnh $label"){slot=key;picker.launch("image/*")}
   button("Chọn ảnh $label từ thư viện"){slot=key;serverPicker.launch(Intent(this,PhotoLibraryActivity::class.java))}
  }
  button("Lưu thông tin"){busy=true;lifecycleScope.launch{try{persist(null);finish()}catch(e:Exception){status.text="Chưa lưu được: ${e.message}"}finally{busy=false}}}
 }
 private suspend fun persist(file:File?,photographer:String?=null){var changed=false;app.db.withTransaction{val p=photo?:error("Không còn vị trí");check(app.db.dao().photo(p.id)!=null){"Vị trí đã bị xóa"};val saved=app.db.dao().incident(placeId)?.value;val r=saved?.let{JSONObject(it)}?:JSONObject().put("cableWork",true);val points=r.optJSONObject("points")?:JSONObject();val q=points.optJSONObject(p.seq.toString())?:JSONObject();q.put("address",address.text.toString()).put("description",description.text.toString()).put("proposal",proposal.text.toString()).put("assessment",if(file!=null)"NOK" else assessment)
  if(file!=null){val old=q.optJSONObject(slot)?.optString("file");q.put(slot,JSONObject().put("file",file.path).apply{if(!photographer.isNullOrBlank())put("photographer",photographer)});if(!old.isNullOrBlank()&&old!=file.path)app.db.dao().queueFile(FileCleanup(old))}
  points.put(p.seq.toString(),q);r.put("points",points);val value=r.toString();if(value!=saved){app.db.dao().saveIncident(IncidentRecord(placeId,value));changed=true}
 };if(changed)AutoSync.changed()}
 private fun savePicture(file:File,raw:String?,photographerOverride:String?=null,capturedAt:Long=0L,copyLibrarySource:Boolean=false){
  busy=true
  val account=app.accountScope
  val accountRoot=app.accountFilesDir.canonicalFile
  lifecycleScope.launch{
   var committed=false
   // A report owns its copy. Replacing/removing it must never delete the Camera archive or NAS download.
   var reportFile:File?=if(copyLibrarySource)null else file
   try{
    if(copyLibrarySource){
     val source=file.canonicalFile
     check(source.isFile&&source.toPath().startsWith(accountRoot.toPath())&&source.extension.lowercase() in listOf("jpg","jpeg")){
      "Ảnh thư viện không còn trong tài khoản đang đăng nhập."
     }
     val target=File(accountRoot,"photos/repair-library-${java.util.UUID.randomUUID()}.jpg")
     reportFile=target
     withContext(Dispatchers.IO){
      target.parentFile?.mkdirs()
      source.inputStream().use{input->target.outputStream().use{output->input.copyTo(output)}}
      check(target.length()==source.length()&&target.length()>0){"Chưa sao chép đủ ảnh vào báo cáo."}
     }
    }
    check(account==app.accountScope&&ServerSession.token.isNotEmpty()){"Tài khoản đã thay đổi. Ảnh thư viện vẫn được giữ."}
    val savedFile=reportFile?:error("Chưa nhận được ảnh báo cáo")
    val p=photo
    val photographer=if(!raw.isNullOrBlank())PhotoLibrary.photographer() else photographerOverride
    persist(savedFile,photographer);committed=true
    if(!raw.isNullOrBlank()&&raw!=savedFile.path)File(raw).delete()
    val r=app.db.dao().incident(placeId)!!
    show(JSONObject(r.value).getJSONObject("points").getJSONObject(p!!.seq.toString()))
    if(!raw.isNullOrBlank()){
     val takenAt=capturedAt.takeIf{it>0}?:System.currentTimeMillis()
     val warnings=mutableListOf<String>()
     runCatching{PhotoLibrary.save(this@CableRepairActivity,savedFile,takenAt)}.onFailure{warnings.add("album điện thoại: ${it.message}")}
     val details=JSONObject().put("seq",p.seq).put("locationName",p.locationName).put("lat",p.lat).put("lon",p.lon).put("accuracy",p.accuracy.toDouble()).put("note",p.note)
     runCatching{PhotoLibrary.queueServerUpload(app,savedFile,takenAt,details)}.onFailure{warnings.add("thư viện server: ${it.message}")}
     AutoSync.wake()
     if(warnings.isNotEmpty())status.text="Ảnh đã vào báo cáo; chưa lưu được vào ${warnings.joinToString(" và ")}."
    }
   }catch(e:Exception){
    if(!committed)reportFile?.delete()
    if(e is CancellationException)throw e
    status.text="Chưa lưu ảnh: ${e.message}"
   }finally{busy=false}
  }
 }
}
