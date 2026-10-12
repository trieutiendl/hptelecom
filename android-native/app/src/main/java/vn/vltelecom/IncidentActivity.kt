package vn.vltelecom

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.*
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private data class PreparedIncidentPhoto(val raw:File,val target:File,val hint:Pair<Double,Double>?)

class IncidentActivity:AppCompatActivity(),LocationListener {
    override fun getFilesDir():java.io.File=(application as WorkApp).accountFilesDir
    override fun getSharedPreferences(name:String,mode:Int)= (application as WorkApp).getSharedPreferences(name,mode)

    private val app get()=application as WorkApp
    private val dao get()=app.db.dao()
    private var placeId=0L
    private var titleName=""
    private val contributor get()=SharedReports.contributor(app,placeId)
    private val canContribute get()=contributor&&!report.completed&&!SharedReports.revoked(app,placeId)
    private var report=IncidentReport()
    private lateinit var dossier:IncidentDossier
    private var dossierName=""
    private var routeIndex=0
    private var sharedMode=false
    private var privateEditing=false
    private var managerExpanded=false
    private var lastInformation:Map<String,String> = emptyMap()
    private fun snapshotInformation(){lastInformation=IncidentDossier.fields.associateWith{IncidentDossier.get(report,it)}}
    private fun dossierJson():String = report.json()
    private fun selectReport(index:Int,common:Boolean=false){
        stopGps();routeIndex=0;sharedMode=false;privateEditing=true;managerExpanded=false
        report=dossier.routes.first().report;if(!contributor)IncidentRules.prepare(report);titleName=dossierName
        step=0;otherCause=report.cause.isNotBlank()&&report.cause !in (if(report.relocation)IncidentRules.relocationCauses else IncidentRules.causes)
        collapsed.clear();report.groups.indices.forEach{collapsed.add(it)};snapshotInformation()
    }
    private var step=0
    private var busy=false
    private var existingPhotos:List<Photo> = emptyList()
    private var latest:Deferred<Unit>?=null
    private val ready=CompletableDeferred<Unit>()
    private val collapsed=mutableSetOf<Int>()
    private var reportScroll:ScrollView?=null
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private var savedStatus="Бản nháp lưu trên điện thoại".replace('Б','B')
    private var gpsStatus=""
    private var gpsText:TextView?=null
    private var locationInput:EditText?=null
    private var applyingLocation=false
    private val handler=Handler(Looper.getMainLooper())
    private val gps by lazy{getSystemService(LOCATION_SERVICE) as LocationManager}
    private var gpsGeneration=0
    private var watching=false
    private var best:Location?=null
    private var originalLocation=""
    private val timeout=Runnable{finishGps()}
    private val pendingCamera get()=getSharedPreferences("incident-camera",MODE_PRIVATE)
    private var selectedGroup=0
    private var otherCause=false
    private var photoGpsCancel:(()->Unit)?=null
    private val gallery=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==RESULT_OK){val data=result.data?:return@registerForActivityResult
        val uris=buildList{data.clipData?.let{clip->for(index in 0 until clip.itemCount)add(clip.getItemAt(index).uri)};if(isEmpty())data.data?.let(::add)}
        if(uris.isNotEmpty())work{
        var count=0
        try{uris.forEach{status.text="Đang thêm ảnh ${count+1}/${uris.size}…";addImage(selectedGroup,it,"",note=report.groups[selectedGroup].note);count++}}catch(e:CancellationException){throw e}catch(e:Exception){error("Đã thêm $count/${uris.size} ảnh. ${e.message}")}
    }}}
    private val mediaLocationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){openPhoneGallery()}
    private val serverLibrary=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==RESULT_OK){val data=result.data?:return@registerForActivityResult;work{val path=data.getStringExtra("path")?:error("Không nhận được ảnh thư viện");val photographer=data.getStringExtra("photographer").orEmpty();val name=data.getStringExtra("name").orEmpty();val lat=data.getDoubleExtra("lat",Double.NaN);val lon=data.getDoubleExtra("lon",Double.NaN);addImage(selectedGroup,Uri.fromFile(File(path)),"Thư viện · $name",note=report.groups[selectedGroup].note,photographer=photographer,gpsHint=lat to lon)}}}
    private val camera=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==RESULT_OK){val data=result.data?:return@registerForActivityResult
        work{val path=data.getStringExtra("path")?:error("Không nhận được ảnh");val raw=data.getStringExtra("raw");val originalMemo=data.getStringExtra("note").orEmpty()
            val memo=if(IncidentRules.isMx(report.groups[selectedGroup])){
                check(data.hasExtra("lat")&&data.hasExtra("lon")){"Ảnh MX chưa có tọa độ GPS"}
                IncidentRules.mxDescription(originalMemo,data.getDoubleExtra("lat",Double.NaN),data.getDoubleExtra("lon",Double.NaN))
            }else originalMemo
            val capturedAt=data.getLongExtra("time",System.currentTimeMillis());report.pendingPhoto=path;report.pendingOriginal=raw.orEmpty();report.pendingStamped=true;report.pendingGroup=selectedGroup;report.pendingDate=capturedAt;report.pendingNote=memo;flush()
            val shooter=PhotoLibrary.photographer();addImage(selectedGroup,Uri.fromFile(File(path)),originalMemo,true,memo,shooter,data.getDoubleExtra("lat",Double.NaN) to data.getDoubleExtra("lon",Double.NaN))
            val details=org.json.JSONObject().put("locationName",titleName).put("lat",data.getDoubleExtra("lat",0.0)).put("lon",data.getDoubleExtra("lon",0.0)).put("accuracy",data.getFloatExtra("accuracy",0f).toDouble()).put("note",memo)
            val warnings=mutableListOf<String>();runCatching{PhotoLibrary.save(this@IncidentActivity,File(path),capturedAt)}.onFailure{warnings.add("album điện thoại: ${it.message}")};runCatching{PhotoLibrary.queueServerUpload(app,File(path),capturedAt,details)}.onFailure{warnings.add("thư viện server: ${it.message}")};AutoSync.wake()
            if(warnings.isNotEmpty())android.widget.Toast.makeText(this@IncidentActivity,"Ảnh đã vào báo cáo; chưa lưu được vào ${warnings.joinToString(" và ")}.",android.widget.Toast.LENGTH_LONG).show();File(path).delete();raw?.let{File(it).delete()}
        }
    }}
    private val photoPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true)work{stampPending()}else message("Ảnh đã giữ lại. Hãy cấp quyền vị trí chính xác rồi bấm Tiếp tục ảnh chờ GPS.")}
    private val permission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true)startGps() else {gpsStatus="Hãy cấp quyền vị trí chính xác trong Cài đặt.";gpsText?.text=gpsStatus}}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);if(ServerSession.token.isEmpty()){startActivity(Intent(this,ServerActivity::class.java));finish();return};placeId=intent.getLongExtra("placeId",0);step=savedInstanceState?.getInt("step")?:0;selectedGroup=savedInstanceState?.getInt("group")?:0
        app.reportOpened(placeId)
        watchServerSession()
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){closeReport()}})
        lifecycleScope.launch{try{ServerSync.drain();FolderStore(app).flatten("Sự cố");FolderStore(app).flatten("Di dời");val place=dao.place(placeId)?:error("Sự cố đã bị xóa");check(ServerSession.allowed(place.kind)){"Bạn chưa được cấp quyền mục này"};check(place.kind=="Sự cố"||place.kind=="Di dời");titleName=place.name
            report=savedInstanceState?.getString("report")?.let{IncidentReport.parse(it)}?:dao.incident(placeId)?.let{IncidentReport.parse(it.value)}?:if(place.kind=="Di dời")IncidentReport.relocation()else IncidentReport(reporter=app.incidentReporter())
            dossierName=place.name
            val stored=savedInstanceState?.getString("dossier")?:dao.incident(placeId)?.value
            dossier=IncidentDossier(IncidentReport(),mutableListOf(IncidentRoute(place.name,stored?.let{IncidentReport.parse(it)}?:report)))
            selectReport((savedInstanceState?.getInt("routeIndex")?:0).coerceIn(0,dossier.routes.lastIndex),savedInstanceState?.getBoolean("sharedMode")?:false)
            if(!SharedReports.shared(app,placeId))report.reporter=ServerSession.user?.optString("display_name")?:report.reporter
            step=(savedInstanceState?.getInt("step")?:0).coerceIn(0,if(report.relocation)2 else 3)
            if(report.relocation&&dao.jobs(placeId).isEmpty())dao.addJob(Job(placeId=placeId,name=place.name))
            otherCause=report.cause.isNotBlank()&&report.cause !in (if(report.relocation)IncidentRules.relocationCauses else IncidentRules.causes)
            existingPhotos=dao.jobs(placeId).flatMap{dao.photos(it.id)}
            report.groups.forEachIndexed{i,g->collapsed.add(i)};ready.complete(Unit);render()
        }catch(e:Exception){ready.completeExceptionally(e);AlertDialog.Builder(this@IncidentActivity).setMessage(e.message).setPositiveButton("Đóng"){_,_->finish()}.show()}}
    }
    override fun onSaveInstanceState(outState:Bundle){if(::dossier.isInitialized){outState.putString("dossier",dossierJson());outState.putInt("routeIndex",routeIndex);outState.putBoolean("sharedMode",sharedMode);outState.putInt("step",step);outState.putInt("group",selectedGroup)};super.onSaveInstanceState(outState)}
    override fun onStop(){photoGpsCancel?.invoke();stopGps();super.onStop()}
    override fun onDestroy(){stopGps();app.reportClosed(placeId);super.onDestroy()}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun save(){
        if(!contributor)report.completed=false
        if(!contributor&&sharedMode)dossier.routes.forEach{it.report.completed=false}
        latest=app.saveReport(placeId,dossierJson());val write=latest!!;savedStatus="Đang lưu…";if(::status.isInitialized)status.text=savedStatus
        lifecycleScope.launch{try{write.await();if(latest===write){savedStatus="Đã lưu bản nháp";status.text=savedStatus}}catch(e:Exception){if(e is CancellationException)throw e;savedStatus="Chưa lưu được. Bấm Lưu để thử lại.";status.text=savedStatus}}
    }
    private suspend fun flush(){latest?.join();val write=app.saveReport(placeId,dossierJson());latest=write;write.await();savedStatus="Đã lưu bản nháp"}
    private fun work(action:suspend ()->Unit){if(busy)return;busy=true;if(::body.isInitialized)setEnabled(body,false);lifecycleScope.launch{try{ready.await();action()}catch(e:Exception){if(e is CancellationException)throw e;message(e.message?:"Không thể thực hiện. Hãy thử lại.")}finally{busy=false;if(!isFinishing&&ready.isCompleted)render()}}}
    private fun setEnabled(v:View,value:Boolean){v.isEnabled=value;if(v is ViewGroup)for(i in 0 until v.childCount)setEnabled(v.getChildAt(i),value)}
    private fun closeReport(){if(!busy)work{stopGps();flush();finish()}}
    private fun message(text:String){AlertDialog.Builder(this).setTitle(if(report.relocation)"Báo cáo di dời" else "Báo cáo sự cố").setMessage(text).setPositiveButton("Đóng",null).show()}
    private fun bg(primary:Boolean=false)=GradientDrawable().apply{setColor(Color.parseColor(if(primary)"#20d3ec" else "#9900364b"));cornerRadius=dp(9).toFloat();setStroke(dp(1),Color.parseColor("#a0e4ee"))}
    private fun label(text:String,size:Int=16,color:Int=Color.WHITE)=TextView(this).apply{this.text=text;textSize=size.toFloat();setTextColor(color);setPadding(0,dp(8),0,dp(8));body.addView(this)}
    private fun section(content:()->Unit){
        val parent=body
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=GradientDrawable().apply{setColor(Color.parseColor("#99002333"));cornerRadius=dp(12).toFloat();setStroke(dp(1),Color.parseColor("#55ffffff"))}}
        parent.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(10),0,dp(10))});body=card
        try{content()}finally{body=parent}
    }
    private fun button(text:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{this.text=text;textSize=17f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setPadding(dp(14),dp(14),dp(14),dp(14));isAllCaps=false;minHeight=dp(52);setTextColor(if(primary)Color.rgb(0,39,51)else Color.WHITE);backgroundTintList=null;background=bg(primary);body.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7);bottomMargin=dp(3)});setOnClickListener{if(!busy)action()}}
    private fun field(title:String,value:String,numeric:Boolean=false,change:(String)->Unit):EditText{
        label(title,14)
        return EditText(this).apply{setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);background=bg();setPadding(dp(12),dp(10),dp(12),dp(10));setText(value);textSize=16f;minHeight=dp(48)
            inputType=if(numeric)InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters=arrayOf(InputFilter.LengthFilter(if(numeric)12 else 1000));body.addView(this)
            addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){change(s.toString());save()}})
        }
    }
    private fun choice(title:String,values:List<String>,action:(String)->Unit){AlertDialog.Builder(this).setTitle(title).setItems(values.toTypedArray()){_,i->action(values[i]);save();render()}.setNegativeButton("Hủy",null).show()}
    private fun dateField(title:String,value:String,time:Boolean,set:(String)->Unit){
        label(title,14);button("▦  "+value.ifBlank{"Chọn ngày"+(if(time)" và giờ" else "")}){
            val format=SimpleDateFormat(if(time)"dd/MM/yyyy HH:mm" else "dd/MM/yyyy",Locale.US).apply{isLenient=false};val c=Calendar.getInstance();runCatching{format.parse(value)}.getOrNull()?.let{c.time=it}
            fun commit(){set(format.format(c.time));save();render()}
            fun showTime(){TimePickerDialog(this,{_,h,m->c.set(Calendar.HOUR_OF_DAY,h);c.set(Calendar.MINUTE,m);commit()},c.get(Calendar.HOUR_OF_DAY),c.get(Calendar.MINUTE),true).show()}
            val picker=DatePickerDialog(this,{_,y,m,d->c.set(Calendar.YEAR,y);c.set(Calendar.MONTH,m);c.set(Calendar.DAY_OF_MONTH,d);if(time)showTime()else commit()},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH))
            picker.setButton(android.content.DialogInterface.BUTTON_NEUTRAL,if(time)"Bây giờ" else "Hôm nay"){_,_->c.time=Date();commit()};picker.show()
        }
    }
    private fun render(){
        val restoreScrollY=reportScroll?.scrollY?:0
        gpsText=null;locationInput=null
        val backdrop=FrameLayout(this).apply{setBackgroundColor(Color.rgb(0,43,62))}
        backdrop.addView(ImageView(this).apply{setImageResource(R.drawable.hp_background_v2);scaleType=ImageView.ScaleType.FIT_CENTER;alpha=0.55f;if(Build.VERSION.SDK_INT>=31)setRenderEffect(RenderEffect.createBlurEffect(2f,2f,Shader.TileMode.CLAMP))},FrameLayout.LayoutParams(-1,-1))
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};backdrop.addView(root,FrameLayout.LayoutParams(-1,-1))
        val scroll=ScrollView(this).apply{setPadding(dp(16),dp(12),dp(16),dp(12))};reportScroll=scroll;body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=GradientDrawable().apply{setColor(Color.parseColor("#dc003044"));cornerRadius=dp(14).toFloat();setStroke(dp(1),Color.parseColor("#a0e4ee"))}};scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply{text="Phiên bản ${packageManager.getPackageInfo(packageName,0).versionName}";setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(8))})
        setContentView(backdrop);if(restoreScrollY>0)scroll.post{scroll.scrollTo(0,restoreScrollY)};applyImeInsetsTo(root)
        root.addView(Button(this).apply{text="← Quay lại";isAllCaps=false;textSize=16f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);gravity=Gravity.START or Gravity.CENTER_VERTICAL;setPadding(dp(20),0,dp(20),0);setOnClickListener{closeReport()}},0,LinearLayout.LayoutParams(-1,dp(48)))
        if(contributor){
            label(titleName,22)
            label("Báo cáo cùng làm • Người lập: "+report.reporter,14)
            status=label(savedStatus,13)
            if(SharedReports.revoked(app,placeId))label("Bạn không còn trong danh sách cùng làm. Ảnh chưa gửi vẫn được giữ trên điện thoại.",14,Color.rgb(255,211,108))
            else if(report.completed)label("Báo cáo đã hoàn thành. Người lập cần mở lại để tiếp tục thêm ảnh.",14)
            else label("Ảnh mới tự gửi vào cùng báo cáo. Thông tin và ảnh đã gửi do người lập quản lý.",14)
            pendingPhotos()
            photos()
            button("Lưu ảnh trên máy"){work{flush()}}
            button("Xuất báo cáo Excel"){export()}
            return
        }
        val names=if(report.relocation)listOf("Thông tin di dời","Cáp & vật tư","Ảnh & mô tả")else listOf("Thông tin sự cố","Cáp & vật tư","Ảnh & mô tả","Kiểm tra & xuất");label("${names[step]}   ${step+1}/${names.size}",22);label(titleName,16).apply{if(report.completed)setTextColor(Color.rgb(255,112,112))};status=label(if(report.completed)"✓ Đã hoàn thành" else savedStatus,13).apply{if(report.completed)setTextColor(Color.rgb(255,112,112))}
        val shortNames=listOf("Thông tin","Cáp / vật tư","Ảnh","Kiểm tra")
        names.indices.toList().chunked(2).forEach{pair->val tabs=LinearLayout(this);body.addView(tabs);pair.forEach{i->tabs.addView(Button(this).apply{text="${i+1}. ${shortNames[i]}";textSize=13f;contentDescription=names[i];isSelected=i==step;isAllCaps=false;backgroundTintList=null;background=bg(i==step);setTextColor(if(i==step)Color.rgb(0,39,51)else Color.WHITE);setOnClickListener{if(!busy){step=i;render()}}},LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})}}
        pendingPhotos()
        if(step==0)button("Người cùng làm"){manageCollaborators()}
        when(step){0->information();1->materials();2->photos();3->review()}
        button("Lưu bản nháp"){work{flush()}}
        if(report.relocation&&step==2){review();button("Hoàn thành",true){AlertDialog.Builder(this).setMessage("Đánh dấu di dời đã hoàn thành?").setNegativeButton("Hủy",null).setPositiveButton("Hoàn thành"){_,_->work{check(report.pendingPhoto.isBlank()){ "Hãy lưu hoặc hủy ảnh chờ GPS trước." };report.completed=true;flush();savedStatus="Đã hoàn thành"}}.show()}}
        if(!report.relocation&&step==3)button(if(report.completed)"Đã hoàn thành" else "Hoàn thành",true){if(!report.completed)AlertDialog.Builder(this).setMessage("Lưu và đánh dấu sự cố đã hoàn thành?").setNegativeButton("Hủy",null).setPositiveButton("Hoàn thành"){_,_->work{check(report.pendingPhoto.isBlank()){ "Hãy lưu hoặc hủy ảnh chờ GPS trước." };report.completed=true;flush();savedStatus="Đã hoàn thành";AutoSync.wake()}}.show()}
        if(step<(if(report.relocation)2 else 3))button(if(step==2)"Kiểm tra báo cáo →" else "Tiếp tục →",true){step++;render()}
    }
    private fun pendingPhotos(){
        if(report.pendingPhoto.isNotBlank()&&(!contributor||canContribute)){
            button("Tiếp tục ảnh chờ GPS"){work{stampPending()}}
            button("Hủy ảnh chờ GPS"){AlertDialog.Builder(this).setMessage("Bỏ ảnh chưa ghi GPS?").setNegativeButton("Giữ ảnh",null).setPositiveButton("Hủy ảnh"){_,_->work{val file=report.pendingPhoto;val raw=report.pendingOriginal;report.pendingPhoto="";report.pendingOriginal="";flush();File(file).delete();if(raw.isNotBlank())File(raw).delete()}}.show()}
        }
    }
    private fun manageCollaborators(){work{
        SharedReports.ensureOwner(app,placeId);flush()
        val place=dao.place(placeId)?:error("Báo cáo không còn")
        ServerSync.upload(app,place){status.text=it}
        val members=SharedReports.members(app,placeId)
        val selected=mutableSetOf<String>()
        val choices=linkedMapOf<String,String>()
        members.optJSONArray("members")?.let{a->for(i in 0 until a.length()){val u=a.getJSONObject(i);selected.add(u.getString("id"));choices[u.getString("id")]=u.optString("display_name")}}
        members.optJSONArray("candidates")?.let{a->for(i in 0 until a.length()){val u=a.getJSONObject(i);choices[u.getString("id")]=u.optString("display_name")}}
        val users=choices.keys.toList()
        AlertDialog.Builder(this@IncidentActivity).setTitle("Người cùng làm")
            .setMultiChoiceItems(users.map{choices[it]?:it}.toTypedArray(),BooleanArray(users.size){users[it] in selected}){_,i,checked->if(checked)selected.add(users[i])else selected.remove(users[i])}
            .setPositiveButton("Lưu"){_,_->work{SharedReports.saveMembers(app,placeId,members.getInt("revision"),selected.toList());savedStatus="Đã cập nhật người cùng làm"}}
            .setNegativeButton("Hủy",null).show()
    }}
    private fun information(){
        button((if(report.locationMode=="manual")"✓ " else "")+"Tự nhập địa điểm",report.locationMode=="manual"){stopGps();report.locationMode="manual";save();render()}
        button((if(report.locationMode=="gps")"✓ " else "")+"Lấy theo định vị",report.locationMode=="gps"){report.locationMode="gps";save();startGps()}
        locationInput=field(if(report.relocation)"Địa điểm di dời" else "Địa điểm sự cố",report.location){if(!applyingLocation)stopGps();report.location=it}
        if(report.locationMode=="gps"){gpsText=label(gpsStatus,13);button(if(watching)"Hủy lấy định vị" else "◎ Lấy lại định vị"){if(watching){stopGps();gpsStatus="Đã hủy lấy định vị";render()}else startGps()}}
        if(report.relocation){
            label("Người lập báo cáo: "+report.reporter)
            dateField("Ngày thực hiện",report.date,false){report.date=it}
            button("Nguyên nhân di dời: "+if(otherCause)"Khác…" else report.cause.ifBlank{"Chọn ▾"}){choice("Nguyên nhân di dời",IncidentRules.relocationCauses+"Khác…"){value->if(value=="Khác…"){otherCause=true;if(report.cause in IncidentRules.relocationCauses)report.cause=""}else{otherCause=false;report.cause=value}}}
            if(otherCause)field("Nhập nguyên nhân khác",report.cause){report.cause=it}
            field("Nội dung di dời",report.solution){report.solution=it}
            return
        }
        field("Khoảng cách đến trạm (km)",report.distance,true){report.distance=it}
        label("Người lập báo cáo: "+report.reporter)
        dateField("Ngày báo cáo",report.date,false){report.date=it}
        if(!sharedMode)dateField("Thời gian bắt đầu",report.received,true){report.received=it}
        if(!sharedMode)dateField("Thời gian kết thúc / khôi phục",report.restored,true){report.restored=it}
        button("Nguyên nhân sự cố: "+if(otherCause)"Khác…" else report.cause.ifBlank{"Chọn ▾"}){choice("Nguyên nhân sự cố",IncidentRules.causes+"Khác…"){value->if(value=="Khác…"){otherCause=true;if(report.cause in IncidentRules.causes)report.cause=""}else{otherCause=false;report.cause=value}}}
        if(otherCause)field("Nhập nguyên nhân khác",report.cause){report.cause=it}
        field("Phương án xử lý",report.solution){report.solution=it}
    }
    private fun cableOptions(selected:String,onCable:(String)->Unit){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};body.addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(7);bottomMargin=dp(3)})
        fun pick(title:String,action:()->Unit){row.addView(Button(this).apply{text=title;textSize=14f;isAllCaps=false;typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);backgroundTintList=null;background=bg(false);minHeight=dp(58);setPadding(dp(7),dp(7),dp(7),dp(7));setOnClickListener{if(!busy)action()}},LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(5)})}
        pick("Chủng loại cáp\n"+selected.ifBlank{"Chọn ▾"}){choice("Chủng loại cáp",IncidentRules.cableTypes,onCable)}
        pick("Khoảng vượt\n"+report.cableSpan.ifBlank{"Chọn ▾"}){choice("Khoảng vượt",listOf("KV100","KV200","KV300","KV500")){report.cableSpan=it}}
    }
    private fun materials(){
        IncidentRules.splitCable(report)
        if(report.relocation){
            cableOptions(report.cable){report.cable=it;report.recoveryCable=it}
            label("Dùng chung cho cáp thu hồi kéo lại và cáp mới.",13)
        }
        button((if(report.recovery)"☑ " else "☐ ")+"Có thu hồi, kéo lại cáp",report.recovery){report.recovery=!report.recovery;save();render()}
        if(report.recovery){
            field("Số mét thu hồi, kéo lại",report.recoveryLength,true){report.recoveryLength=it}
            if(!report.relocation)cableOptions(report.recoveryCable){report.recoveryCable=it}
        }
        button((if(report.newCableEnabled)"☑ " else "☐ ")+"Có kéo cáp mới",report.newCableEnabled){report.newCableEnabled=!report.newCableEnabled;save();render()}
        if(report.newCableEnabled){
            if(!report.relocation)cableOptions(report.cable){report.cable=it}
            var total:TextView?=null
            fun updateTotal(){total?.text="Cáp mới sử dụng: ${IncidentRules.length(report)?.let{IncidentRules.num(it)}?:"—"} m"}
            field("Đầu số cáp mới 1",report.start,true){report.start=it;report.relocationLength="";updateTotal()}
            field("Đầu số cáp mới 2",report.end,true){report.end=it;report.relocationLength="";updateTotal()}
            total=label("Cáp mới sử dụng: ${IncidentRules.length(report)?.let{IncidentRules.num(it)}?:"—"} m",24,Color.CYAN)
            label("Số lớn trừ số nhỏ. Ví dụ: 3067 − 2917 = 150 m.",13)
            if(report.relocationLength.isNotBlank()&&report.start.isBlank()&&report.end.isBlank())label("Đang giữ số mét đã lưu từ bản trước. Nhập hai đầu số để tính lại.",13)
        }
        label("Có thể chọn thu hồi kéo lại và kéo cáp mới cùng lúc. Tổng chiều dài gồm cáp thu hồi và cáp mới; bảng vật tư chỉ tính cáp mới và vật tư mới.",14)
        if(report.relocation||!report.recovery||report.newCableEnabled){
            report.materials.forEachIndexed{i,m->section{label("${i+1}. ${m.name}",19)
                if(m.name=="Măng xông")button("Loại măng xông: "+m.type.ifBlank{"Chọn ▾"}){choice("Loại măng xông",IncidentRules.cableTypes){m.type=it}}
                if(m.name=="Vật tư khác")field("Tên vật tư khác",m.type){m.type=it}
                field("Số lượng",m.quantity,true){m.quantity=it};field("Đơn vị",m.unit){m.unit=it};field("Ghi chú",m.note){m.note=it}
                button("Bỏ vật tư này"){AlertDialog.Builder(this).setMessage("Bỏ ${m.name} khỏi báo cáo?").setNegativeButton("Hủy",null).setPositiveButton("Xóa"){_,_->report.materials.removeAt(i);save();render()}.show()}
            }}
            button("＋ Thêm vật tư ▾"){choice("Chọn vật tư",IncidentRules.materials){name->report.materials.add(IncidentMaterial(name=name,unit=when(name){"D12"->"";"Đai Inox"->"m";"Néo","Treo"->"bộ";else->"cái"}))}}
        }
    }
    private fun photos(){
        label("Ảnh MX được đọc tọa độ ngay trên điện thoại và tự điền một lần vào mô tả hàng ảnh. Nhấn giữ ảnh để tải xuống hoặc xóa.",14)
        if(report.groups.any{it.images.isNotEmpty()}&&(!contributor||canContribute))button("Xóa toàn bộ ảnh trong báo cáo"){removeAllImages()}
        IncidentRules.ordered(report).forEach{(i,g)->
            section{
            button("${IncidentRules.groupTitle(g.name)} • ${g.images.size} ảnh ${if(i in collapsed)"▾" else "▴"}"){if(!collapsed.remove(i))collapsed.add(i);render()}
            if(i !in collapsed)g.images.chunked(2).forEachIndexed{k,pair->
                val strip=LinearLayout(this);body.addView(strip)
                pair.forEachIndexed{j,p->val image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Ảnh ${k*2+j+1}; chạm để xem, nhấn giữ để mở tác vụ";setOnClickListener{if(!busy)viewReportPhoto(p)};setOnLongClickListener{if(!busy)imageOptions(i,k*2+j);true}}
                    strip.addView(image,LinearLayout.LayoutParams(0,dp(150),1f));lifecycleScope.launch{val bitmap=withContext(Dispatchers.IO){runCatching{PhotoRenderer.thumbnail(p.path,300)}.getOrNull()};if(image.isAttachedToWindow)image.setImageBitmap(bitmap)else bitmap?.recycle()}
                }
                if(IncidentRules.isMx(g))pair.forEachIndexed{j,p->
                    val gps=MxCoordinates.fromMetadata(p.metadata)
                    val readState=runCatching{org.json.JSONObject(p.metadata).optString("mxLocalReadState")}.getOrDefault("")
                    label("Ảnh ${k*2+j+1}: "+(gps?.let{"${it.lat}, ${it.lon} • ${it.source}"}?:when(readState){"UNREADABLE"->"Chưa đọc rõ tọa độ — có thể thử đọc lại";"ERROR"->"Chưa đọc xong — có thể thử đọc lại";else->"Chưa đọc tọa độ"}),12)
                }
                if(contributor){
                    pair.forEachIndexed{j,p->
                        val uploader=runCatching{org.json.JSONObject(p.metadata).optString("uploadedByName")}.getOrDefault("")
                        if(uploader.isNotBlank())label("Ảnh ${k*2+j+1} • $uploader",12)
                        if(canContribute&&ServerSync.canRemoveSharedImage(app,placeId,p.path))field("Mô tả ảnh ${k*2+j+1}",p.caption){g.images[k*2+j]=p.copy(caption=it)}
                        else if(p.caption.isNotBlank())label(p.caption,13)
                    }
                    (g.pairNotes.getOrNull(k)?:g.note).takeIf{it.isNotBlank()}?.let{label(it,13)}
                }else{
                    pair.forEachIndexed{j,p->val uploader=runCatching{org.json.JSONObject(p.metadata).optString("uploadedByName")}.getOrDefault("");if(uploader.isNotBlank())label("Ảnh ${k*2+j+1} • $uploader",12);field("Mô tả ảnh ${k*2+j+1}",p.caption){g.images[k*2+j]=p.copy(caption=it)}}
                    field("Mô tả hàng ảnh ${k+1}",g.pairNotes.getOrNull(k)?:g.note){while(g.pairNotes.size<=k)g.pairNotes.add(g.note);g.pairNotes[k]=it}
                }
            }
            if(i !in collapsed&&(!contributor||canContribute))button("＋ Thêm ảnh"){photoOptions(i)}
            if(i !in collapsed&&IncidentRules.isMx(g)&&!contributor&&g.images.any(::needsMxDescription))button("Đọc và điền tọa độ MX"){work{readExistingMx(g)}}
            }
        }
    }
    private fun imageOptions(group:Int,index:Int){
        val g=report.groups[group];val photo=g.images[index]
        val editable=(!contributor||canContribute)&&ServerSync.canRemoveSharedImage(app,placeId,photo.path)
        val actions=mutableListOf<Pair<String,()->Unit>>("Xem ảnh" to {viewReportPhoto(photo)},"Tải xuống" to {PhotoDownloadActivity.save(this,photo.path)})
        if(editable)actions.add("Xóa khỏi báo cáo" to {removeImage(group,index)})
        AlertDialog.Builder(this).setTitle("Ảnh ${index+1}").setItems(actions.map{it.first}.toTypedArray()){_,n->actions[n].second()}.setNegativeButton("Hủy",null).show()
    }
    private fun viewReportPhoto(photo:IncidentImage){PhotoViewer.show(this,photo.path)}
    private fun photoOptions(i:Int){
        if(contributor)try{SharedReports.ensureContributorActive(app,placeId);check(!report.completed){"Báo cáo đã hoàn thành; người lập cần mở lại trước."}}catch(e:Exception){message(e.message.orEmpty());return}
        val options=listOf("Chụp ảnh mới","Chọn từ điện thoại","Chọn từ thư viện")+existingPhotos.map{"Vị trí ${it.seq}. ${it.locationName}"}
        AlertDialog.Builder(this).setTitle("Thêm ảnh").setItems(options.toTypedArray()){_,n->selectedGroup=i
            when(n){0->{if(report.pendingPhoto.isNotBlank()){message("Hãy lưu hoặc hủy ảnh chờ trước.");return@setItems};camera.launch(Intent(this,CaptureActivity::class.java).putExtra("title",titleName).putExtra("note",report.groups[i].note))}
                1->{if(IncidentRules.isMx(report.groups[i])&&Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q&&ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_MEDIA_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                    // Preserve the selected file's original metadata for NAS processing.
                    mediaLocationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }else openPhoneGallery()}
                2->serverLibrary.launch(Intent(this,PhotoLibraryActivity::class.java))
                else->{val p=existingPhotos[n-3];work{val mx=IncidentRules.isMx(report.groups[i]);addImage(i,Uri.fromFile(File(p.path)),if(mx)p.note else "${p.seq}. ${p.locationName}\n${p.lat}, ${p.lon}\n${p.note}",note=if(mx)IncidentRules.mxDescription(p.note,p.lat,p.lon)else "",gpsHint=p.lat to p.lon)}}
            }
        }.setNegativeButton("Hủy",null).show()
    }
    private fun openPhoneGallery(){
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="image/*";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)}
        gallery.launch(intent)
    }
    private fun originalPhotoUri(uri:Uri):Uri{
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q||uri.scheme!="content"||ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_MEDIA_LOCATION)!=PackageManager.PERMISSION_GRANTED)return uri
        return runCatching{
            val mediaUri=if(uri.authority==MediaStore.AUTHORITY)uri else MediaStore.getMediaUri(this,uri)
            mediaUri?.let{MediaStore.setRequireOriginal(it)}?:uri
        }.getOrDefault(uri)
    }
    private suspend fun addImage(i:Int,uri:Uri,caption:String,finishCapture:Boolean=false,note:String="",photographer:String="",gpsHint:Pair<Double,Double>?=null):Boolean{
        if(contributor){SharedReports.ensureContributorActive(app,placeId);check(!report.completed){"Báo cáo đã hoàn thành; ảnh chờ vẫn được giữ."}}
        val g=report.groups[i];val account=app.accountScope
        val prepared=withContext(Dispatchers.IO){
            val raw=File.createTempFile("incident", ".img",cacheDir);val target=File(filesDir,"photos/incident-${UUID.randomUUID()}.jpg");target.parentFile?.mkdirs()
            try{val sourceUri=originalPhotoUri(uri);val input=runCatching{contentResolver.openInputStream(sourceUri)}.getOrNull()?:contentResolver.openInputStream(uri);input?.use{stream->raw.outputStream().use{stream.copyTo(it)}}?:error("Không mở được ảnh")
                val hint=gpsHint?.takeIf{it.first.isFinite()&&it.second.isFinite()&&it.first in -90.0..90.0&&it.second in -180.0..180.0&&(it.first!=0.0||it.second!=0.0)}
                PreparedIncidentPhoto(raw,target,hint)
            }catch(e:Exception){target.delete();raw.delete();throw e}
        }
        var committed=false
        try{
            // Read the selected image once, before committing it. A group note is never GPS evidence.
            val hint=prepared.hint?.let{MxCoordinates.of(it.first,it.second,"Ảnh")}
            val localResult=if(IncidentRules.isMx(g)&&hint==null){
                status.text="Đang đọc tọa độ ảnh MX trên điện thoại…"
                MxLocalReader.read(prepared.raw,File(filesDir,"mx-coordinate-cache"))
            }else null
            val gpsPoint=hint?:localResult?.coordinates
            check(account==app.accountScope){"Tài khoản đã thay đổi. Hãy mở lại báo cáo."}
            check(report.groups.any{it===g}){"Nhóm ảnh đã thay đổi. Hãy thêm ảnh lại."}
            val finalPoint=gpsPoint
            val p=withContext(Dispatchers.IO){
                if(IncidentRules.isMx(g)){
                    val dimensions=MxPhotoUpload.write(prepared.raw,prepared.target)
                    val base=org.json.JSONObject().apply{
                        if(photographer.isNotBlank())put("photographer",photographer)
                        localResult?.let{put("mxLocalReadVersion",MxLocalReader.VERSION);put("mxLocalReadState",it.status.name);put("mxLocalReadSha256",it.sha256)}
                    }.toString()
                    val metadata=(finalPoint?.metadata(base)?:base).takeIf{it!="{}"}.orEmpty()
                    return@withContext IncidentImage(prepared.target.path,dimensions.first,dimensions.second,finalPoint?.captionWithoutCoordinates(caption)?:caption,metadata=metadata)
                }
                val bitmap=PhotoRenderer.thumbnail(prepared.raw.path,1600)?:error("Không đọc được ảnh")
                try{prepared.target.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it))};if(uri.scheme=="file")PhotoRenderer.copyOverlay(File(uri.path!!),prepared.target);if(finalPoint!=null)runCatching{ExifInterface(prepared.target.path).apply{setLatLong(finalPoint.lat,finalPoint.lon);saveAttributes()}};val base=org.json.JSONObject().apply{if(photographer.isNotBlank())put("photographer",photographer)}.toString();val metadata=(finalPoint?.metadata(base)?:base).takeIf{it!="{}"}.orEmpty();IncidentImage(prepared.target.path,bitmap.width,bitmap.height,caption,metadata=metadata)}finally{bitmap.recycle()}
            }
        check(account==app.accountScope&&report.groups.any{it===g}){"Báo cáo hoặc tài khoản đã thay đổi. Hãy thêm ảnh lại."}
        if(contributor){SharedReports.ensureContributorActive(app,placeId);check(!report.completed){"Báo cáo đã hoàn thành."}}
        val hasGps=gpsFromMetadata(p.metadata)!=null
        if(!contributor)report.completed=false;val oldNotes=g.pairNotes.toList();val oldClosure=report.closureLocation;val oldClosure2=report.closureLocation2;val imageIndex=g.images.size;g.images.add(p)
        IncidentRules.addNote(g,note)
        if(IncidentRules.isMx(g)&&gpsPoint!=null){MxCoordinates.applyOnceToImage(g,imageIndex,gpsPoint);report.backfillMxSummaryLocations()}
        val pending=report.pendingPhoto;val original=report.pendingOriginal;if(finishCapture){report.pendingPhoto="";report.pendingOriginal=""}
        try{flush()}catch(e:Exception){g.images.removeAll{it.path==p.path};g.pairNotes.clear();g.pairNotes.addAll(oldNotes);report.closureLocation=oldClosure;report.closureLocation2=oldClosure2;if(finishCapture){report.pendingPhoto=pending;report.pendingOriginal=original};File(p.path).delete();throw e}
        committed=true
        if(IncidentRules.isMx(g))collapsed.remove(i)else collapsed.add(i)
        return hasGps
        }finally{prepared.raw.delete();if(!committed)prepared.target.delete()}
    }
    private fun gpsFromMetadata(value:String):Pair<Double,Double>?=MxCoordinates.fromMetadata(value)?.let{it.lat to it.lon}
    private fun needsMxDescription(image:IncidentImage):Boolean = MxCoordinates.fromMetadata(image.metadata)==null||
        !runCatching{org.json.JSONObject(image.metadata).optBoolean("mxCoordinateDescriptionApplied",false)}.getOrDefault(false)
    private suspend fun readExistingMx(group:IncidentGroup){
        val account=app.accountScope;val currentReport=report;val cache=File(filesDir,"mx-coordinate-cache")
        val paths=group.images.filter(::needsMxDescription).map{it.path}
        for((number,path) in paths.withIndex()){
            check(account==app.accountScope&&report===currentReport&&report.groups.any{it===group}){"Báo cáo đã thay đổi. Hãy mở lại."}
            val current=group.images.firstOrNull{it.path==path}?:continue
            if(!needsMxDescription(current))continue
            val known=MxCoordinates.fromMetadata(current.metadata)
            status.text=if(known==null)"Đang đọc ảnh MX ${number+1}/${paths.size}…" else "Đang điền tọa độ MX ${number+1}/${paths.size}…"
            val result=if(known==null)MxLocalReader.read(File(path),cache,retryUnreadable=true)else null
            check(account==app.accountScope&&report===currentReport&&report.groups.any{it===group}){"Báo cáo đã thay đổi. Hãy mở lại."}
            val index=group.images.indexOfFirst{it.path==path};if(index<0)continue
            val oldImage=group.images[index];val oldNotes=group.pairNotes.toList();val first=report.closureLocation;val second=report.closureLocation2
            if(result!=null){
                val meta=runCatching{org.json.JSONObject(oldImage.metadata)}.getOrDefault(org.json.JSONObject())
                    .put("mxLocalReadVersion",MxLocalReader.VERSION).put("mxLocalReadState",result.status.name).put("mxLocalReadSha256",result.sha256)
                group.images[index]=oldImage.copy(metadata=meta.toString())
            }
            (MxCoordinates.fromMetadata(oldImage.metadata)?:result?.coordinates)?.let{MxCoordinates.applyOnceToImage(group,index,it);report.backfillMxSummaryLocations()}
            try{flush()}catch(error:Exception){group.images[index]=oldImage;group.pairNotes.clear();group.pairNotes.addAll(oldNotes);report.closureLocation=first;report.closureLocation2=second;throw error}
        }
    }
    private suspend fun stampPending(){
        if(report.pendingPhoto.isBlank())return
        if(report.pendingStamped){val source=File(report.pendingPhoto);val raw=report.pendingOriginal;addImage(report.pendingGroup,Uri.fromFile(source),report.pendingNote,true,report.pendingNote);source.delete();if(raw.isNotBlank())File(raw).delete();return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){photoPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return}
        status.text="Đang lấy GPS cho ảnh… Đứng đúng vị trí chụp khi lấy lại GPS."
        val fix=IncidentPhotoGps.get(gps,handler){photoGpsCancel=it};photoGpsCancel=null
        if(!fix.hasAccuracy()||fix.accuracy>50){
            val yes=kotlinx.coroutines.suspendCancellableCoroutine<Boolean>{c->val dialog=AlertDialog.Builder(this).setTitle("Kiểm tra GPS").setMessage("Sai số ${if(fix.hasAccuracy())fix.accuracy.toInt().toString()+" m" else "không rõ"}. Vẫn lưu ảnh?").setNegativeButton("Hủy"){_,_->if(c.isActive)c.resumeWith(Result.success(false))}.setPositiveButton("Vẫn lưu"){_,_->if(c.isActive)c.resumeWith(Result.success(true))}.setOnCancelListener{if(c.isActive)c.resumeWith(Result.success(false))}.show();c.invokeOnCancellation{dialog.dismiss()}}
            if(!yes)return
        }
        val raw=File(report.pendingPhoto);val temp=File.createTempFile("stamp", ".jpg",cacheDir)
        try{
            val date=SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date(report.pendingDate))
            withContext(Dispatchers.IO){IncidentPhotoGps.stamp(raw,temp,titleName,date,fix,report.pendingNote)}
            val mx=IncidentRules.isMx(report.groups[report.pendingGroup])
            addImage(report.pendingGroup,Uri.fromFile(temp),if(mx)report.pendingNote else "$date • ${fix.latitude}, ${fix.longitude}",true,if(mx)IncidentRules.mxDescription(report.pendingNote,fix.latitude,fix.longitude)else report.pendingNote,gpsHint=fix.latitude to fix.longitude);raw.delete()
        }finally{temp.delete()}
    }
    private fun removeImage(i:Int,index:Int){AlertDialog.Builder(this).setTitle("Xóa ảnh khỏi báo cáo?").setMessage("Thay đổi sẽ đồng bộ đến server, người lập và người cùng làm. Ảnh trong thư viện gốc vẫn được giữ.").setNegativeButton("Hủy",null).setPositiveButton("Xóa"){_,_->work{removeImages(mapOf(i to setOf(index)))}}.show()}
    private fun removeAllImages(){
        val count=report.groups.sumOf{it.images.size}
        AlertDialog.Builder(this).setTitle("Xóa toàn bộ $count ảnh?").setMessage("Xóa ảnh khỏi tất cả nhóm của báo cáo này và đồng bộ đến những người cùng làm. Ghi chú thủ công và ảnh trong thư viện gốc vẫn được giữ.")
            .setNegativeButton("Hủy",null).setPositiveButton("Xóa toàn bộ"){_,_->work{removeImages(report.groups.mapIndexed{i,g->i to g.images.indices.toSet()}.toMap())}}.show()
    }
    private suspend fun removeImages(selection:Map<Int,Set<Int>>){
        if(contributor){SharedReports.ensureContributorActive(app,placeId);check(canContribute){"Báo cáo đã hoàn thành; người lập cần mở lại trước."}}
        selection.forEach{(i,indices)->indices.forEach{index->check(ServerSync.canRemoveSharedImage(app,placeId,report.groups[i].images[index].path)){"Ảnh đang chờ đồng bộ; hãy gửi xong rồi thử lại."}}}
        val oldGroups=report.groups.map{it.copy(images=it.images.toMutableList(),pairNotes=it.pairNotes.toMutableList())}
        val oldCompleted=report.completed;val oldFirst=report.closureLocation;val oldSecond=report.closureLocation2
        if(!contributor)report.completed=false
        val removed=selection.flatMap{(i,indices)->ReportPhotoRemoval.remove(report,report.groups[i],indices)}
        try{flush()}catch(e:Exception){
            report.groups.clear();report.groups.addAll(oldGroups);report.completed=oldCompleted;report.closureLocation=oldFirst;report.closureLocation2=oldSecond
            throw e
        }
        // Wait for a pending uploader before physical cleanup; other reports/drafts retain their files.
        runCatching{ServerSync.cleanupReportPhotos(app,removed.flatMap{listOf(it.path,it.originalFile)})}
    }
    private fun reportWarnings(r:IncidentReport)=IncidentRules.warnings(r)
    private fun review(){
        label("${if(report.relocation)"Nội dung" else "Phương án"}: ${IncidentRules.solution(report).ifBlank{"Chưa nhập"}}",17);label("Vật tư: ${IncidentRules.summary(report).ifBlank{"Chưa nhập"}}")
        label("${report.groups.sumOf{it.images.size}} ảnh");label("Kiểm tra nội dung",19);val warnings=reportWarnings(report)
        if(warnings.isEmpty())label("✓ Đã đủ các nội dung kiểm tra",16,Color.rgb(77,224,179))else warnings.forEach{label("! $it",15,Color.rgb(255,211,108))}
        button("Xuất báo cáo Excel gọn",true){if(warnings.isEmpty())export()else AlertDialog.Builder(this).setTitle("Báo cáo còn thiếu").setMessage(warnings.joinToString("\n"){"• $it"}).setNegativeButton("Quay lại bổ sung",null).setPositiveButton("Vẫn xuất file"){_,_->export()}.show()}
    }
    private fun export(){stopGps();work{flush();val snapshot=IncidentReport.parse(report.json());val folder=File(cacheDir,"exports/${UUID.randomUUID()}").apply{mkdirs()};val filename=titleName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().trimEnd('.').take(70).ifBlank{"Bao-cao-su-co"};val file=File(folder,"$filename.xlsx")
        withContext(Dispatchers.IO){IncidentExport.xlsx(file,titleName,snapshot)}
        val uri=FileProvider.getUriForFile(this@IncidentActivity,"$packageName.files",file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri("Báo cáo",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Lưu / chia sẻ báo cáo"))
    }}
    private fun stopGps(){gpsGeneration++;if(watching)runCatching{gps.removeUpdates(this)};watching=false;handler.removeCallbacks(timeout)}
    private fun startGps(){
        stopGps();if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return}
        try{val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{gps.isProviderEnabled(it)};check(providers.isNotEmpty()){"Hãy bật Dịch vụ định vị trong Cài đặt."}
            best=null;originalLocation=report.location;watching=true;gpsStatus="Đang lấy định vị mới…";render();providers.forEach{gps.requestLocationUpdates(it,1000L,0f,this,Looper.getMainLooper())};handler.postDelayed(timeout,30000)
        }catch(e:Exception){stopGps();gpsStatus=e.message?:"Không lấy được vị trí";render()}
    }
    override fun onLocationChanged(location:Location){
        if(!watching||SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos>30_000_000_000L)return
        if(best==null||(location.hasAccuracy()&&location.accuracy<=(best?.accuracy?:Float.MAX_VALUE)))best=location
        if(location.hasAccuracy()&&location.accuracy<=20)finishGps()
    }
    private fun finishGps(){
        val fix=best;stopGps();val token=gpsGeneration
        if(fix==null){gpsStatus="Chưa bắt được vị trí. Ra ngoài trời rồi bấm Lấy lại định vị.";render();return}
        if(report.location!=originalLocation)return
        val coordinates=String.format(Locale.US,"%.6f, %.6f",fix.latitude,fix.longitude)
        report.location=coordinates;save();gpsStatus="Đã lấy tọa độ • sai số ${if(fix.hasAccuracy())"${fix.accuracy.toInt()} m" else "không rõ"}";render()
        lifecycleScope.launch{val address=withContext(Dispatchers.IO){runCatching{if(Geocoder.isPresent())Geocoder(this@IncidentActivity,Locale.getDefault()).getFromLocation(fix.latitude,fix.longitude,1)?.firstOrNull()?.getAddressLine(0)else null}.getOrNull()}
            if(gpsGeneration==token&&report.location==coordinates&&!address.isNullOrBlank()){report.location="$address\n$coordinates";save();applyingLocation=true;locationInput?.setText(report.location);applyingLocation=false}
        }
    }
    override fun onProviderEnabled(provider:String){}
    override fun onProviderDisabled(provider:String){}
    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
}
