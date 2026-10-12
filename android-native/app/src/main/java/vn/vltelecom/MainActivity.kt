package vn.vltelecom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.*
import android.net.Uri
import android.os.*
import android.provider.Settings
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
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.UUID

class MainActivity:AppCompatActivity(),LocationListener {
    override fun getFilesDir():java.io.File=(application as WorkApp).accountFilesDir
    override fun getSharedPreferences(name:String,mode:Int)= (application as WorkApp).getSharedPreferences(name,mode)

    private val model get()=(application as WorkApp).model
    private val gps by lazy{getSystemService(LOCATION_SERVICE) as LocationManager}
    private val handler=Handler(Looper.getMainLooper())
    private var watchingGps=false
    private var gpsLabel:TextView?=null
    private var syncLabel:TextView?=null
    private lateinit var body:LinearLayout
    private var imageLoad:kotlinx.coroutines.Job?=null
    private val gpsTimeout=Runnable{stopGps();gpsLabel?.text="Chưa bắt được GPS. Bản nháp đã được giữ. Hãy ra ngoài trời rồi bấm Lấy GPS."}
    private val locationPermission=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true)startGps(false)
        else message("Chưa có quyền vị trí chính xác. Bản nháp vẫn được lưu; bạn có thể cấp quyền trong Cài đặt ứng dụng.")
    }
    private var openCameraOnEdit=false
    private val serverLibrary=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->if(result.resultCode==RESULT_OK){val data=result.data?:return@registerForActivityResult;val path=data.getStringExtra("path")?:return@registerForActivityResult;val photo=LibraryPhoto(data.getStringExtra("id").orEmpty(),data.getStringExtra("name").orEmpty(),data.getStringExtra("photographer").orEmpty(),data.getStringExtra("username").orEmpty(),data.getLongExtra("capturedAt",0),data.getStringExtra("locationName").orEmpty(),data.getDoubleExtra("lat",0.0),data.getDoubleExtra("lon",0.0),data.getFloatExtra("accuracy",0f),data.getStringExtra("note").orEmpty());model.addLibraryPhoto(photo,File(path))}}
    private var cableWorkFilter="inspection"
    private var reportCompletionViewKind=""
    private var showCompletedReports=false
    private var reportMonthFilterKind=""
    private var selectedReportMonth:String?=null
    private var selectedFolders:MutableSet<Long>?=null
    private var selectedPhotos:MutableSet<Long>?=null
    private var selectionJob=0L
    private val camera=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        if(result.resultCode==RESULT_OK){val data=result.data;val d=model.drafts.read();if(data!=null&&d!=null){
            if(d.photoId==0L&&d.rawPath.isNotBlank()&&d.rawPath!=data.getStringExtra("raw"))File(d.rawPath).delete()
            model.drafts.save(d.copy(address=data.getStringExtra("address"),proposal=data.getStringExtra("proposal"),assessment=data.getStringExtra("assessment"),rawPath=data.getStringExtra("raw")?:d.rawPath,capturePending=false,capturedAt=data.getLongExtra("time",System.currentTimeMillis()),seq=data.getStringExtra("seq")?:d.seq,name=data.getStringExtra("name")?:d.name,note=data.getStringExtra("note")?:d.note,showDate=data.getBooleanExtra("showDate",true),textColor=data.getIntExtra("textColor",Color.WHITE),lat=data.getDoubleExtra("lat",0.0),lon=data.getDoubleExtra("lon",0.0),accuracy=data.getFloatExtra("accuracy",0f),fixTime=data.getLongExtra("time",System.currentTimeMillis())))
            model.resumeDraft()
        }}
    }
    private val createFile=registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")){uri->if(uri!=null){val p=getPreferences(MODE_PRIVATE).getString("export",null);if(p!=null)model.saveExport(Uri.parse(uri.toString()),p)}}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(ServerSession.token.isEmpty()){startActivity(Intent(this,ServerActivity::class.java));finish();return}
        watchServerSession()
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),77)
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){AutoSync.status.collect{syncLabel?.text=it}}}
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    render(state)
                    if(!state.busy&&state.repairPhotoId>0){model.clearRepair();startActivity(Intent(this@MainActivity,CableRepairActivity::class.java).putExtra("photo",state.repairPhotoId))}
                    state.message?.let {
                        message(it)
                        model.clearMessage()
                    }
                    state.batchWarning?.let { warning ->
                        if(!state.busy) {model.state.value=model.state.value.copy(batchWarning=null);AlertDialog.Builder(this@MainActivity).setTitle("Kiểm tra trước khi xuất").setMessage(warning).setNegativeButton("Hủy"){_,_->model.confirmBatch(false)}.setPositiveButton("Vẫn xuất"){_,_->model.confirmBatch(true)}.setOnCancelListener{model.confirmBatch(false)}.show()}
                    }
                    state.exportPath?.let {
                        showExport(File(it))
                        model.consumeExport()
                    }
                }
            }
        }
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun message(text:String){AlertDialog.Builder(this).setTitle("HP Telecom").setMessage(text).setPositiveButton("Đóng",null).show()}
    private fun label(text:String,size:Int=16)=TextView(this).apply{this.text=text;textSize=size.toFloat();setTextColor(Color.WHITE);setPadding(0,dp(6),0,dp(6));body.addView(this)}
    private fun button(text:String,enabled:Boolean=!model.state.value.busy,action:()->Unit)=Button(this).apply{this.text=text;textSize=17f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setPadding(dp(13),dp(13),dp(13),dp(13));isAllCaps=false;minHeight=dp(52);setTextColor(Color.WHITE);backgroundTintList=null;val outline=android.graphics.drawable.GradientDrawable().apply {setColor(Color.argb(166,0,54,75));setStroke(dp(1),Color.rgb(194,242,255));cornerRadius=dp(14).toFloat()};background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(55,255,255,255)),outline,null);alpha=if(enabled)1f else 0.45f;isEnabled=enabled;body.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)});setOnClickListener{if(!model.state.value.busy)action()}}
    private fun input(title:String,value:String,numeric:Boolean=false,readOnly:Boolean=false,uppercase:Boolean=false,change:(String)->Unit){
        label(title,14);body.addView(EditText(this).apply{
            setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);backgroundTintList=null;background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.parseColor("#99002333"));cornerRadius=dp(10).toFloat();setStroke(dp(1),Color.parseColor("#a4bcc5"))};setPadding(dp(12),dp(12),dp(12),dp(12));textSize=16f;minHeight=dp(48)
            inputType=if(numeric)InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(if(uppercase)value.uppercase(java.util.Locale.forLanguageTag("vi-VN")) else value);isEnabled=!model.state.value.busy && !readOnly
            filters=if(uppercase)arrayOf(InputFilter.AllCaps(java.util.Locale.forLanguageTag("vi-VN")),InputFilter.LengthFilter(2000)) else arrayOf(InputFilter.LengthFilter(if(numeric)10 else 2000))
            addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){change(s.toString())};override fun afterTextChanged(s:Editable?){} })
        })
    }
    private fun changeDraft(f:(Draft)->Draft){model.drafts.read()?.let{model.drafts.save(f(it))}}
    private fun render(s:UiState){
        imageLoad?.cancel();gpsLabel=null;syncLabel=null
        if(s.screen!=Screen.EDIT)stopGps()
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val backdrop=FrameLayout(this).apply { setBackgroundColor(Color.rgb(0,43,62)) }
        backdrop.addView(ImageView(this).apply { setImageResource(R.drawable.hp_background_v2); alpha=if(s.screen==Screen.HOME)0.95f else 0.65f; if(s.screen!=Screen.HOME&&Build.VERSION.SDK_INT>=31)setRenderEffect(android.graphics.RenderEffect.createBlurEffect(2f,2f,android.graphics.Shader.TileMode.CLAMP)); scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,-1))
        backdrop.addView(root,FrameLayout.LayoutParams(-1,-1))
        val scroll=ScrollView(this).apply { setPadding(dp(20),dp(12),dp(20),dp(12)) }
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(8),0,dp(20));setBackgroundColor(Color.TRANSPARENT) }
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TextView(this).apply {
            text="Phiên bản ${packageManager.getPackageInfo(packageName,0).versionName}"
            textSize=12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190,0,35,52))
            gravity=Gravity.CENTER
            setPadding(dp(12),dp(8),dp(12),dp(10))
        },LinearLayout.LayoutParams(-1,-2))
        setContentView(backdrop)
        applyImeInsetsTo(root)
        if(s.screen!=Screen.HOME)root.addView(Button(this).apply{text="← Quay lại";isAllCaps=false;textSize=16f;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);gravity=Gravity.START or Gravity.CENTER_VERTICAL;setPadding(dp(20),0,dp(20),0);setOnClickListener{back()}},0,LinearLayout.LayoutParams(-1,dp(48)))
        if(s.screen==Screen.HOME){body.addView(ImageView(this).apply{setImageResource(R.drawable.hp_logo);contentDescription="Logo HP Telecom";scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(dp(190),dp(190)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        }
        if(s.busy){body.addView(ProgressBar(this),LinearLayout.LayoutParams(dp(32),dp(32)));label("Đang xử lý, vui lòng chờ…",14)}
        when(s.screen){
            Screen.HOME->{reportCompletionViewKind="";showCompletedReports=false;reportMonthFilterKind="";selectedReportMonth=null;homeCards();syncLabel=label(AutoSync.status.value,13);button("☁ Server / gửi và tải báo cáo"){startActivity(Intent(this,ServerActivity::class.java))};button("💬 Chat nhóm"){startActivity(Intent(this,ChatActivity::class.java))};if(model.drafts.read()!=null)button("Tiếp tục bản nháp chưa lưu"){model.resumeDraft()}}
            Screen.PLACES->{label(ServerSession.displayKind(s.kind),22)
                if(s.kind in listOf("Sự cố","Di dời")){
                    if(reportCompletionViewKind!=s.kind){reportCompletionViewKind=s.kind;showCompletedReports=false}
                    if(reportMonthFilterKind!=s.kind){reportMonthFilterKind=s.kind;selectedReportMonth=null}
                }else{reportCompletionViewKind="";showCompletedReports=false;reportMonthFilterKind="";selectedReportMonth=null}
                val routeChoices=listOf("inspection" to "Kiểm tra","maintenance" to "Bảo dưỡng")+if(s.places.any{s.routeTypes[it.id] !in listOf("inspection","maintenance")})listOf("" to "Chưa phân loại") else emptyList()
                if(routeChoices.none{it.first==cableWorkFilter})cableWorkFilter="inspection"
                val reportKind=s.kind in listOf("Sự cố","Di dời")
                val reportMonthChoices=if(reportKind)ReportMonthSelection.choices(s.places.map{s.reportMonths[it.id].orEmpty()}) else emptyList()
                if(reportKind&&selectedReportMonth !in reportMonthChoices)selectedReportMonth=null
                val monthPlaces=if(reportKind)s.places.filter{ReportMonthSelection.matches(s.reportMonths[it.id].orEmpty(),selectedReportMonth)} else s.places
                val completedCount=monthPlaces.count{s.reportCompleted[it.id]==true}
                val visiblePlaces=if(s.kind=="Tuyến cáp")s.places.filter{val type=s.routeTypes[it.id].orEmpty();if(cableWorkFilter.isEmpty())type !in listOf("inspection","maintenance") else type==cableWorkFilter} else if(reportKind)monthPlaces.filter{ReportCompletion.belongsInView(s.reportCompleted[it.id]==true,showCompletedReports)} else s.places
                if(reportKind)button("Tháng: ${ReportMonthSelection.label(selectedReportMonth)} (${monthPlaces.size}) ▾"){
                    val labels=reportMonthChoices.map{ReportMonthSelection.label(it)}.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Chọn tháng").setSingleChoiceItems(labels,reportMonthChoices.indexOf(selectedReportMonth)){dialog,index->selectedReportMonth=reportMonthChoices[index];selectedFolders=null;dialog.dismiss();render(model.state.value)}.setNegativeButton("Đóng",null).show()
                }
                if(s.kind=="Tuyến cáp")button("${routeChoices.first{it.first==cableWorkFilter}.second} (${visiblePlaces.size}) ▾"){
                    AlertDialog.Builder(this).setTitle("Loại công việc").setSingleChoiceItems(routeChoices.map{it.second}.toTypedArray(),routeChoices.indexOfFirst{it.first==cableWorkFilter}){dialog,index->cableWorkFilter=routeChoices[index].first;selectedFolders=null;dialog.dismiss();render(model.state.value)}.setNegativeButton("Đóng",null).show()
                }
                if(reportKind&&(completedCount>0||showCompletedReports))button(if(showCompletedReports)"← Đang làm (${monthPlaces.size-completedCount})" else "✓ Đã hoàn thành ($completedCount)") {showCompletedReports=!showCompletedReports;selectedFolders=null;render(model.state.value)}
                val selection=selectedFolders
                if(selection==null){if(s.kind=="Tuyến cáp"){label("Tuyến do quản trị tạo và giao trên server.",14);button("Nhận tuyến được giao"){model.places(s.kind)}}else button("+ Thêm ${s.kind}"){askName("Tên ${s.kind}",model::createPlace)};label("Nhấn giữ để chọn nhiều, sao chép, sửa hoặc xóa",13)}
                else{selection.retainAll(visiblePlaces.map{it.id}.toSet());label("Đã chọn ${selection.size}",16);button(if(selection.size==visiblePlaces.size)"Bỏ chọn tất cả" else "Chọn tất cả"){if(selection.size==visiblePlaces.size)selection.clear()else selection.addAll(visiblePlaces.map{it.id});render(s)};button("Xuất Excel đã chọn",selection.isNotEmpty()&&!s.busy){model.exportFolders(visiblePlaces.filter{it.id in selection},false)};if(s.kind=="Tuyến cáp")button("Xuất KMZ đã chọn",selection.isNotEmpty()&&!s.busy){model.exportFolders(visiblePlaces.filter{it.id in selection},true)};if(s.kind=="Tuyến cáp")button("Xuất Excel + KMZ đã chọn",selection.isNotEmpty()&&!s.busy){model.exportFolders(visiblePlaces.filter{it.id in selection},false,both=true)};button("Xóa ${selection.size} thư mục",selection.isNotEmpty()&&!s.busy&&selection.none{SharedReports.contributor(application as WorkApp,it)}){confirm("Xóa ${selection.size} thư mục cùng toàn bộ ảnh, thông tin và bản nháp bên trong?"){val items=visiblePlaces.filter{it.id in selection};selectedFolders=null;model.deleteFolders(items)}};button("Hủy chọn"){selectedFolders=null;render(s)}}
                if(visiblePlaces.isEmpty())label(when{reportKind&&showCompletedReports->if(selectedReportMonth==null)"Chưa có báo cáo đã hoàn thành." else "Tháng đã chọn chưa có báo cáo đã hoàn thành.";reportKind&&selectedReportMonth!=null->"Tháng đã chọn chưa có báo cáo.";else->"Chưa có mục nào trong danh sách này."})
                if(s.kind=="Tuyến cáp")visiblePlaces.groupBy{s.routeMonths[it.id].orEmpty()}.toSortedMap(compareByDescending<String>{it.isNotBlank()}.thenByDescending{it}).forEach{(month,rows)->label(if(month.length==7)"Tháng ${month.substring(5)}/${month.substring(0,4)}" else "Chưa rõ tháng",17);rows.forEach{placeButton(it)}} else if(reportKind&&selectedReportMonth==null)visiblePlaces.groupBy{s.reportMonths[it.id].orEmpty()}.toSortedMap(compareByDescending<String>{it.isNotBlank()}.thenByDescending{it}).forEach{(month,rows)->label(ReportMonthSelection.label(month),17);rows.forEach{placeButton(it)}} else visiblePlaces.forEach{placeButton(it)}}
            Screen.JOBS->{label(s.place?.name ?: "Công việc",22);label("Các bộ ảnh đã lưu trước đây",14);s.jobs.forEach{j->button("${if(j.completed)"✓" else "○"} ${j.name}"){model.photos(j.id)}.apply{if(j.completed)setTextColor(completedColor)}}}
            Screen.PHOTOS->{val j=s.job?:return;if(SharedReports.contributor(application as WorkApp,j.placeId)){label(s.place?.name.orEmpty(),22);button("Mở báo cáo cùng làm / thêm ảnh"){startActivity(Intent(this,IncidentActivity::class.java).putExtra("placeId",j.placeId))};return};val reportKind=s.kind in listOf("Sự cố","Di dời");val completed=if(reportKind)s.reportCompleted[j.placeId]?:j.completed else j.completed;label(j.name,22).apply{if(completed)setTextColor(completedColor)};getSharedPreferences("server-links",MODE_PRIVATE).getString("assignment-note-${s.place?.id}","")?.takeIf{it.isNotBlank()}?.let{label(it,14)};label("${s.place?.name} • ${if(completed)"Đã hoàn thành" else "Đang thực hiện"}")
                val draft=model.drafts.read();if(draft!=null)button("Tiếp tục bản nháp đang có"){model.resumeDraft()}
                if(s.kind!="Sự cố"&&!j.completed)button("+ Thêm ảnh",!s.busy){
                    val existing=model.drafts.read()
                    if(existing==null){openCameraOnEdit=true;model.newDraft()}
                    else if(existing.jobId==j.id){openCameraOnEdit=existing.rawPath.isBlank();model.resumeDraft()}
                    else AlertDialog.Builder(this).setMessage("Còn bản nháp chưa lưu ở tuyến khác. Mở bản nháp để lưu hoặc xóa rồi chụp tuyến này.").setPositiveButton("Mở bản nháp"){_,_->model.resumeDraft()}.setNegativeButton("Đóng",null).show()
                }
                if(s.kind=="Sự cố")button("Báo cáo sự cố / Excel gọn"){startActivity(Intent(this,IncidentActivity::class.java).putExtra("placeId",j.placeId))}
                if(s.jobs.size>1)button("Các bộ ảnh đã lưu trước đây"){model.savedJobs()}
                if(!reportKind)button(if(j.completed)"Mở lại" else "✓ Hoàn thành"){model.complete()}
                if(!j.completed&&s.kind=="Tuyến cáp")button("Thêm ảnh từ thư viện",!s.busy){serverLibrary.launch(Intent(this,PhotoLibraryActivity::class.java))}
                if(s.kind=="Tuyến cáp")button("⋮ Xuất / Chuyển ảnh",!s.busy){
                    val actions=mutableListOf("Xuất Excel có ảnh","Xuất tuyến KMZ")
                    if(s.photos.isNotEmpty()&&selectedPhotos==null)actions.add("Chọn nhiều ảnh để chuyển")
                    AlertDialog.Builder(this).setTitle("Xuất / Chuyển ảnh").setItems(actions.toTypedArray()){_,which->
                        when(which){0->model.export(false);1->model.export(true);2->{selectedPhotos=mutableSetOf();render(model.state.value)}}
                    }.setNegativeButton("Đóng",null).show()
                }else{button("Xuất Excel có ảnh"){model.export(false)};button("Xuất tuyến KMZ"){model.export(true)}}
                if(s.kind=="Tuyến cáp"&&ServerSession.user?.optString("role")=="admin")button("Ngày tháng toàn tuyến · Hiện / Ẩn / Đổi ngày",!s.busy&&s.photos.isNotEmpty()){
                    AlertDialog.Builder(this).setTitle("Ngày tháng toàn tuyến").setItems(arrayOf("Hiện ngày tháng","Ẩn ngày tháng","Đổi ngày toàn tuyến")){_,which->
                        when(which){
                            0->model.setRoutePhotoDates(true)
                            1->model.setRoutePhotoDates(false)
                            2->{val now=java.util.Calendar.getInstance();android.app.DatePickerDialog(this,{_,year,month,day->
                                val chosen=java.util.Calendar.getInstance().apply{set(year,month,day,12,0,0);set(java.util.Calendar.MILLISECOND,0)}
                                AlertDialog.Builder(this).setTitle("Đổi ngày toàn tuyến").setMessage("Áp dụng và hiện ngày $day/${month+1}/$year trên tất cả ảnh của tuyến ${s.place?.name.orEmpty()}?")
                                    .setPositiveButton("Áp dụng"){_,_->model.setRoutePhotoDates(true,chosen.timeInMillis)}.setNegativeButton("Hủy",null).show()
                            },now.get(java.util.Calendar.YEAR),now.get(java.util.Calendar.MONTH),now.get(java.util.Calendar.DAY_OF_MONTH)).show()}
                        }
                    }.setNegativeButton("Hủy",null).show()
                }
                label("${s.photos.size} ảnh • Theo STT",14)
                label("Nhấn giữ vị trí để sửa hoặc xóa",14)
                if(selectionJob!=j.id){selectedPhotos=null;selectionJob=j.id}
                val photoSelection=selectedPhotos
                if(s.kind=="Tuyến cáp"){
                    if(photoSelection!=null){
                        photoSelection.retainAll(s.photos.map{it.id}.toSet())
                        label("Đã chọn ${photoSelection.size} ảnh",16)
                        button(if(photoSelection.size==s.photos.size)"Bỏ chọn tất cả" else "Chọn tất cả"){
                            if(photoSelection.size==s.photos.size)photoSelection.clear()else photoSelection.addAll(s.photos.map{it.id});render(model.state.value)
                        }
                        button("Chuyển sang tuyến khác",photoSelection.isNotEmpty()&&!s.busy){choosePhotoDestination(photoSelection.toSet())}
                        button("Hủy chọn"){selectedPhotos=null;render(model.state.value)}
                    }
                }
                s.photos.forEach{p->val prefix=photoSelection?.let{if(p.id in it)"☑ " else "☐ "}.orEmpty()
                    val item=button("$prefix${p.seq}. ${p.locationName}\n${p.lat}, ${p.lon}\n${p.note}"){
                        if(photoSelection==null)viewPhoto(p)else{if(!photoSelection.remove(p.id))photoSelection.add(p.id);render(model.state.value)}
                    };item.setOnLongClickListener{if(!model.state.value.busy&&photoSelection==null)photoMenu(p);true}
                }
            }
            Screen.EDIT->{editor(s);if(openCameraOnEdit&&!s.busy){openCameraOnEdit=false;takePhoto()}}
        }
    }
    private fun homeCards() {
        val accent = ContextCompat.getColor(this, R.color.brand_accent)
        val hintColor = ContextCompat.getColor(this, R.color.brand_hint)
        label("BẮT ĐẦU CÔNG VIỆC", 14).setTextColor(accent)
        val items = listOf(
            "Sự cố" to "Báo cáo và xử lý",
            "Tuyến cáp" to "Ảnh, GPS, KMZ",
            "Di dời" to "Theo dõi di dời",
            "Camera" to "Chụp ảnh · Thư viện"
        )
        items.filter { (title, _) ->
            if (title == "Camera") ServerSession.user?.optString("role") != "viewer"
            else ServerSession.allowed(title)
        }.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            body.addView(row)
            pair.forEach { (title, hint) ->
                val cardBackground = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#dc003044"))
                    setStroke(dp(1), accent)
                    cornerRadius = dp(16).toFloat()
                }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(12), dp(12))
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                            Color.argb(48, Color.red(accent), Color.green(accent), Color.blue(accent))
                        ), cardBackground, null
                    )
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${ServerSession.displayKind(title)}, $hint"
                    setOnClickListener {
                        if (title == "Camera") {
                            startActivity(Intent(this@MainActivity, CaptureActivity::class.java)
                                .putExtra("standalone", true).putExtra("title", "HP Telecom"))
                        } else {
                            selectedFolders = null
                            model.places(title)
                        }
                    }
                }
                val iconSize = dp(if (title == "Camera") 88 else 64)
                val iconArea = FrameLayout(this)
                iconArea.addView(ImageView(this).apply {
                    setImageResource(when (title) {
                        "Sự cố" -> R.drawable.home_incident
                        "Tuyến cáp" -> R.drawable.home_cable
                        "Camera" -> R.drawable.home_camera
                        else -> R.drawable.home_relocation
                    })
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.START or Gravity.CENTER_VERTICAL))
                card.addView(iconArea, LinearLayout.LayoutParams(-1, dp(88)))
                val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                titleRow.addView(TextView(this).apply {
                    text = ServerSession.displayKind(title)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(0, -2, 1f))
                titleRow.addView(TextView(this).apply {
                    text = "›"
                    setTextColor(hintColor)
                    textSize = 30f
                })
                card.addView(titleRow)
                card.addView(TextView(this).apply {
                    text = hint
                    setTextColor(hintColor)
                    textSize = 12f
                })
                row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(3), dp(5), dp(3), dp(5))
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }
    private val completedColor get()=Color.rgb(255,112,112)
    private fun placeButton(p:Place) {
        val shared=SharedReports.contributor(application as WorkApp,p.id)
        val item=button((selectedFolders?.let{if(p.id in it)"☑ " else "☐ "}?:"")+p.name+(if(shared)" • Cùng làm" else "")){
            val selection=selectedFolders
            if(selection!=null){if(!selection.remove(p.id))selection.add(p.id);render(model.state.value)}
            else if(p.kind in listOf("Di dời","Sự cố"))startActivity(Intent(this,IncidentActivity::class.java).putExtra("placeId",p.id))else model.jobs(p)
        }
        lifecycleScope.launch {
            val dao=(application as WorkApp).db.dao()
            val complete=if(p.kind=="Tuyến cáp")dao.jobs(p.id).let{it.isNotEmpty()&&it.all{job->job.completed}}
                else dao.incident(p.id)?.let{org.json.JSONObject(it.value).optBoolean("completed")}==true
            item.setTextColor(if(complete)completedColor else Color.WHITE)
            if(complete)item.text="${item.text}  ✓"
            if(p.kind=="Tuyến cáp"){
                val count=dao.jobs(p.id).sumOf{dao.photos(it.id).size}
                item.text="${item.text}\n$count vị trí • ${if(complete)"Đã hoàn thành" else "Đang thực hiện"}"
            }
        }
        item.setOnLongClickListener {
            if(shared){if(!model.state.value.busy&&selectedFolders==null){selectedFolders=mutableSetOf(p.id);render(model.state.value)};return@setOnLongClickListener true}
            if(!model.state.value.busy&&selectedFolders==null){
                val actions=when {
                    p.kind=="Tuyến cáp"->arrayOf("Chọn nhiều","Xóa")
                    else->arrayOf("Chọn nhiều","Sao chép","Sửa","Xóa")
                }
                AlertDialog.Builder(this).setTitle(p.name).setItems(actions){_,selected->
                    if(p.kind=="Tuyến cáp")when(selected){0->{selectedFolders=mutableSetOf(p.id);render(model.state.value)};1->confirm("Xóa ${p.name} cùng toàn bộ ảnh và thông tin bên trong?"){model.deleteRoute(p)}}
                    else when(selected){
                        0->{selectedFolders=mutableSetOf(p.id);render(model.state.value)}
                        1->model.copyFolder(p)
                        2->askName("Sửa tên",{model.renamePlace(p,it)},p.name)
                        3->confirm("Xóa ${p.name} cùng toàn bộ ảnh và thông tin bên trong?"){model.deleteRoute(p)}
                    }
                }.setNegativeButton("Hủy",null).show()
            }
            true
        }
    }
    private fun editor(s:UiState){
        val d=model.drafts.read()?:return;val completed=s.job?.completed==true && d.photoId==0L
        label(s.job?.name?:"Ảnh hiện trường",22)
        label("Thông tin đang nhập được tự giữ thành bản nháp.",14)
        if(d.rawPath.isNotEmpty() && File(d.rawPath).length()>0){
            val preview=ImageView(this).apply{adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Ảnh hiện trường"};body.addView(preview,LinearLayout.LayoutParams(-1,dp(240)))
            imageLoad=lifecycleScope.launch{val bitmap=withContext(Dispatchers.IO){runCatching{PhotoRenderer.thumbnail(d.rawPath)}.getOrNull()};if(isActive)preview.setImageBitmap(bitmap) else bitmap?.recycle()}
        }else if(!completed)button("Mở máy ảnh / Chụp ảnh"){takePhoto()}
        if(d.rawPath.isNotBlank()&&!completed)button("Chụp lại ảnh"){takePhoto()}
        input("STT",d.seq,true,completed){value->changeDraft{it.copy(seq=value)}}
        input("Tên vị trí chụp hình",d.name,readOnly=completed){value->changeDraft{it.copy(name=value)}}
        input("Ghi chú (chứa MX hoặc DP để đánh dấu KMZ)",d.note,readOnly=completed,uppercase=true){value->changeDraft{it.copy(note=value)}}
        if(s.kind=="Tuyến cáp"){
            label("Đánh giá vị trí",16)
            val assessmentChoices=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL;isEnabled=!s.busy}
            val okId=View.generateViewId();val nokId=View.generateViewId()
            for((id,title)in listOf(okId to "OK — Đạt",nokId to "NOK — Chưa đạt"))assessmentChoices.addView(RadioButton(this).apply{this.id=id;text=title;setTextColor(Color.WHITE);isEnabled=!s.busy})
            when(d.assessment){"OK"->assessmentChoices.check(okId);"NOK"->assessmentChoices.check(nokId)}
            assessmentChoices.setOnCheckedChangeListener{_,id->if(id==okId||id==nokId)changeDraft{it.copy(assessment=if(id==okId)"OK" else "NOK")}}
            body.addView(assessmentChoices)
            if(d.photoId!=0L)label("Có thể đổi OK / NOK và lưu thông tin bằng ảnh hiện có, không cần chụp lại.",14)
        }
        val dateToggle=CheckBox(this).apply{text="Hiện ngày tháng trên ảnh";setTextColor(Color.WHITE);isChecked=d.showDate;setOnCheckedChangeListener{_,checked->changeDraft{it.copy(showDate=checked)}}};body.addView(dateToggle)
        gpsLabel=label(gpsText(d))
        if(!completed){
            button("Lấy GPS tại vị trí hiện tại"){startGps(true)}
            button("Mở cài đặt Vị trí"){startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}
            button("Lưu ảnh / Hoàn thành thông tin"){val current=model.drafts.read();if(current!=null && current.lat!=null && current.accuracy>50f)AlertDialog.Builder(this).setTitle("Sai số GPS ±${current.accuracy.toInt()} m").setMessage("Lưu tọa độ hiện tại hay chờ tín hiệu tốt hơn?").setNegativeButton("Chờ thêm"){_,_->startGps(true)}.setPositiveButton("Lưu"){_,_->model.save()}.show() else model.save()}
        }
        button("Giữ bản nháp và quay lại"){model.photos(d.jobId)}
        button("Xóa bản nháp"){confirm("Xóa thông tin đang nhập? Ảnh đã lưu trước đó vẫn giữ nguyên."){model.discard()}}
        if(!completed && !s.busy && d.lat==null && d.rawPath.isNotEmpty() && !d.capturePending && !watchingGps)startGps(false)
    }
    private fun takePhoto(){
        val d=model.drafts.read()?:run{message("Không còn bản nháp. Quay lại tuyến và bấm Thêm ảnh.");return};stopGps()
        try{camera.launch(Intent(this,CaptureActivity::class.java).putExtra("proposal",d.proposal).putExtra("assessment",d.assessment).putExtra("route",true).putExtra("title",model.state.value.place?.name?:model.state.value.job?.name.orEmpty()).putExtra("seq",d.seq).putExtra("name",d.name).putExtra("note",d.note).putExtra("showDate",d.showDate).putExtra("textColor",d.textColor))}catch(e:Exception){message("Chưa mở được máy ảnh: "+(e.message?:"Hãy thử lại"))}
    }
    private fun gpsText(d:Draft)=if(d.lat!=null)"Lat: ${d.lat}\nLong: ${d.lon}\nSai số GPS: ±${d.accuracy.toInt()} m" else "Chưa có GPS. Bạn có thể giữ bản nháp."
    private fun startGps(requestPermission:Boolean){
        if(watchingGps)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){gpsLabel?.text="Cần quyền vị trí chính xác. Bấm Lấy GPS để cấp quyền.";if(requestPermission)locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));return}
        if(!gps.allProviders.contains(LocationManager.GPS_PROVIDER)){gpsLabel?.text="Thiết bị này không có GPS vệ tinh.";return}
        if(!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)){gpsLabel?.text="GPS đang tắt. Bật Vị trí rồi bấm Lấy GPS.";return}
        try{gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500,0f,this);watchingGps=true;gpsLabel?.text="Đang tìm GPS vệ tinh… Tối đa 60 giây. Hãy đứng ngoài trời.";handler.postDelayed(gpsTimeout,60000)}catch(e:SecurityException){gpsLabel?.text="Quyền vị trí đã bị thu hồi. Bấm Lấy GPS để thử lại."}
    }
    private fun stopGps(){handler.removeCallbacks(gpsTimeout);if(watchingGps)runCatching{gps.removeUpdates(this)};watchingGps=false}
    override fun onLocationChanged(location: android.location.Location) {
        if (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 30_000_000_000L || !location.hasAccuracy()) return
        changeDraft { it.copy(lat = location.latitude, lon = location.longitude, accuracy = location.accuracy, fixTime = location.time) }
        model.drafts.read()?.let { gpsLabel?.text = gpsText(it) }
        if (location.accuracy <= 20) stopGps()
    }
    override fun onProviderEnabled(provider:String){}
    override fun onProviderDisabled(provider:String){stopGps();gpsLabel?.text="GPS đã tắt. Bản nháp vẫn được giữ."}
    @Deprecated("Legacy Android callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
    override fun onStop(){stopGps();super.onStop()}
    private fun back(){val s=model.state.value;if(s.busy)return;when(s.screen){Screen.HOME->finish();Screen.PLACES->{selectedFolders=null;model.home()};Screen.JOBS->model.places(s.kind);Screen.PHOTOS->{if(selectedPhotos!=null){selectedPhotos=null;render(s)}else model.places(s.kind)};Screen.EDIT->s.job?.let{model.photos(it.id)}}}
    private fun askName(title:String,action:(String)->Unit,initial:String=""){val input=EditText(this).apply{filters=arrayOf(InputFilter.LengthFilter(160));setText(initial)};val dialog=AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Hủy",null).setPositiveButton(if(initial.isEmpty())"Tạo" else "Lưu",null).create();dialog.setOnShowListener{dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val n=input.text.toString().trim();if(n.isBlank())input.error="Vui lòng nhập tên" else{dialog.dismiss();action(n)}}};dialog.show()}
    private fun confirm(title:String,action:()->Unit){AlertDialog.Builder(this).setMessage(title).setNegativeButton("Hủy",null).setPositiveButton("Đồng ý"){_,_->action()}.show()}
    private fun choosePhotoDestination(ids:Set<Long>){
        val source=model.state.value.place?:return
        lifecycleScope.launch {
            try {
                val choices=(application as WorkApp).db.dao().places("Tuyến cáp").filter{it.id!=source.id}
                if(choices.isEmpty()){message("Chưa có tuyến đích. Hãy nhận tuyến được giao trước.");return@launch}
                AlertDialog.Builder(this@MainActivity).setTitle("Chuyển ${ids.size} ảnh đến tuyến")
                    .setItems(choices.map{it.name}.toTypedArray()){_,index->val target=choices[index]
                        confirm("Chuyển ${ids.size} ảnh sang ${target.name}? Ảnh được bỏ khỏi tuyến cũ, cập nhật tên và STT. Hai tuyến sẽ chuyển về đang thực hiện."){
                            if(model.state.value.place?.id==source.id){selectedPhotos=null;model.movePhotos(ids,target.id)}
                        }
                    }.setNegativeButton("Hủy",null).show()
            }catch(e:Exception){message(e.message?:"Không tải được danh sách tuyến")}
        }
    }
    private fun viewPhoto(p:Photo){PhotoViewer.show(this,p.path)}
    private fun photoMenu(p:Photo){
        AlertDialog.Builder(this).setTitle("${p.seq}. ${p.locationName}")
            .setItems(arrayOf("Xóa","Sửa ảnh / STT","Sửa thông tin · OK / NOK","Tải ảnh xuống")){_,which->if(which==3)PhotoDownloadActivity.save(this,p.path) else if(which==2)cablePhotoDetails(p) else if(which==1)model.edit(p) else {
                AlertDialog.Builder(this).setTitle("Xóa vị trí ${p.seq}?")
                    .setMessage("Xóa ảnh và thông tin? Mục này sẽ mở lại để bổ sung. Có thể dùng lại STT đã xóa.")
                    .setNegativeButton("Hủy",null).setPositiveButton("Xóa"){_,_->model.delete(p)}.show()
            }}.setNegativeButton("Hủy",null).show()
    }

    private fun cablePhotoDetails(p:Photo){startActivity(Intent(this,CableRepairActivity::class.java).putExtra("photo",p.id))}

    private fun showExport(file:File){getPreferences(MODE_PRIVATE).edit().putString("export",file.path).apply();AlertDialog.Builder(this).setTitle("Đã tạo ${file.extension.uppercase()}").setItems(arrayOf("Lưu file vào điện thoại","Chia sẻ file")){_,index->
        if(index==0)createFile.launch(file.name) else try{val uri=FileProvider.getUriForFile(this,"$packageName.files",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=if(file.extension=="zip")"application/zip" else if(file.extension=="kmz")"application/vnd.google-earth.kmz" else "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";putExtra(Intent.EXTRA_STREAM,uri);clipData=android.content.ClipData.newRawUri("Báo cáo",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Chia sẻ báo cáo"))}catch(e:Exception){message("Không mở được ứng dụng chia sẻ. Hãy chọn Lưu file.")}
    }.show()}
}
