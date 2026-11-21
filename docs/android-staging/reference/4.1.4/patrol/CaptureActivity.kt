package vn.vltelecom

import android.Manifest
import android.content.Intent
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.location.*
import android.os.*
import android.view.*
import android.widget.*
import android.text.InputType
import android.text.InputFilter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import java.text.SimpleDateFormat

@Suppress("DEPRECATION")
class CaptureActivity:AppCompatActivity(),SurfaceHolder.Callback,LocationListener {
    override fun getFilesDir():java.io.File=(application as WorkApp).accountFilesDir
    override fun getSharedPreferences(name:String,mode:Int)= (application as WorkApp).getSharedPreferences(name,mode)

 private var camera:Camera?=null
 private var cameraUiReady=false
 private lateinit var surface:SurfaceView
 private lateinit var gpsText:TextView
 private lateinit var dateText:TextView
 private var addressLookup:kotlinx.coroutines.Deferred<String>?=null
 private var addressFix:Location?=null
 private lateinit var addressText:TextView
 private lateinit var proposal:EditText
 private lateinit var infoButton:Button
 private var assessment=""
 private lateinit var note:EditText
 private lateinit var seq:EditText
 private lateinit var name:EditText
 private lateinit var shutter:ImageButton
 private lateinit var brightness:SeekBar
 private lateinit var brightnessLabel:TextView
 private lateinit var resetBrightness:Button
 private lateinit var lightButton:Button
 private var exposure=0
 private lateinit var seek:SeekBar
 private lateinit var photoOverlay:LinearLayout
 private var textColor=Color.WHITE
 private var showDate=true
 private var fix:Location?=null
 private var taking=false
 private var zoom=0f
 private var standaloneFacing=Camera.CameraInfo.CAMERA_FACING_BACK
 private var openedCameraId=-1
 private var displayOrientation=90
 private var previewRatio=3f/4f
 private var resolvedAddress=""
 private var lastClockSecond=""
 private var latestArchive:CameraPhoto?=null
 private var backupStatusGeneration=0
 private var cameraForeground=false
 private var waitingForJpeg=false
 private var captureGeneration=0
 private val cameraWrites=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private lateinit var previewArea:FrameLayout
 private lateinit var previewFrame:FrameLayout
 private lateinit var stampHolder:FrameLayout
 private lateinit var focusView:CameraFocusView
 private lateinit var cameraStatus:TextView
 private lateinit var cameraHint:TextView
 private lateinit var flashControl:ImageButton
 private lateinit var switchControl:ImageButton
 private lateinit var zoomControl:Button
 private lateinit var libraryControl:LinearLayout
 private lateinit var templateControl:LinearLayout
 private lateinit var galleryThumbnail:ImageView
 private val clockHandler=Handler(Looper.getMainLooper())
 private val clockTick=object:Runnable{override fun run(){if(standalone&&::stampHolder.isInitialized&&!isFinishing){val second=SimpleDateFormat("yyyyMMddHHmmss",Locale.US).format(Date());if(second!=lastClockSecond){lastClockSecond=second;refreshStandaloneStamp()};clockHandler.postDelayed(this,1000)}}}
 private val chooseLogo=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
  if(uri!=null&&standalone&&sameCaptureAccount())lifecycleScope.launch{
   try{withContext(Dispatchers.IO){check(sameCaptureAccount()){ "Tài khoản đã thay đổi" };CameraStamp.importLogo(this@CaptureActivity,uri)};if(sameCaptureAccount()){CameraStamp.save(this@CaptureActivity,CameraStamp.load(this@CaptureActivity).copy(logo=true));refreshStandaloneStamp();Toast.makeText(this@CaptureActivity,"Đã đổi logo cho ảnh chụp tiếp theo",Toast.LENGTH_SHORT).show()}}
   catch(e:CancellationException){throw e}catch(e:Exception){if(sameCaptureAccount())AlertDialog.Builder(this@CaptureActivity).setMessage("Chưa đổi được logo: ${e.message}").setPositiveButton("Đóng",null).show()}
  }
 }
 private val gps by lazy{getSystemService(LOCATION_SERVICE) as LocationManager}
 private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera()
  else AlertDialog.Builder(this).setMessage("Chưa có quyền Máy ảnh. Mở Cài đặt → Quyền → Máy ảnh và cho phép HP Telecom.").setPositiveButton("Mở cài đặt"){_,_->startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))}.setNegativeButton("Đóng",null).show()
  startGps()
 }
 private val repair get()=intent.getBooleanExtra("repair",false)
 private val route get()=intent.getBooleanExtra("route",false)
 private val standalone get()=intent.getBooleanExtra("standalone",false)
 private var captureScope=""
 private var capturePhotographer=""
 private var captureUsername=""
 private val titleName get()=intent.getStringExtra("title")?:""
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun text(value:String)=TextView(this).apply{text=value;setTextColor(Color.WHITE);textSize=13f;setShadowLayer(2f,0f,1f,Color.BLACK)}
 private fun edit(value:String,hintText:String,numeric:Boolean=false)=EditText(this).apply{setText(value);hint=hintText;contentDescription=hintText;setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);setShadowLayer(2f,0f,1f,Color.BLACK);setBackgroundColor(Color.TRANSPARENT);textSize=13f;setPadding(0,0,0,0);inputType=if(numeric)InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;filters=arrayOf(InputFilter.LengthFilter(if(numeric)8 else 180))}
 override fun onCreate(saved:Bundle?){
  super.onCreate(saved)
  if(ServerSession.token.isEmpty()){startActivity(Intent(this,ServerActivity::class.java));finish();return}
  if(standalone&&(application as WorkApp).accountScope=="locked"){startActivity(Intent(this,ServerActivity::class.java));finish();return}
  if(standalone&&ServerSession.user?.optString("role")=="viewer"){finish();return}
  captureScope=saved?.getString("captureScope")?:(application as WorkApp).accountScope
  capturePhotographer=saved?.getString("capturePhotographer")?:PhotoLibrary.photographer()
  captureUsername=saved?.getString("captureUsername")?:ServerSession.user?.optString("username").orEmpty()
  if(captureScope!=(application as WorkApp).accountScope){finish();return}
  watchServerSession()
  onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(!taking)finish()}})
  if(standalone){createStandaloneCamera(saved);return}
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(0,24,35))};setContentView(root)
  applyImeInsetsTo(root)
  val header=LinearLayout(this);root.addView(header)
  header.addView(Button(this).apply{text="‹ Quay lại";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);gravity=Gravity.START or Gravity.CENTER_VERTICAL;setOnClickListener{if(!taking)finish()}},LinearLayout.LayoutParams(0,dp(44),1f))
  header.addView(Button(this).apply{text="⚙";contentDescription="Tùy chọn chụp ảnh";setTextColor(Color.WHITE);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{if(!taking)showOptions()}},LinearLayout.LayoutParams(dp(56),dp(44)))
  val scroll=ScrollView(this);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0)};scroll.addView(body)
  val detector=ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:ScaleGestureDetector):Boolean{setZoom(zoom+(d.scaleFactor-1f)*0.25f);return true}})
  val frame=object:FrameLayout(this){override fun onInterceptTouchEvent(e:MotionEvent):Boolean{if(e.pointerCount>1&&!taking){parent.requestDisallowInterceptTouchEvent(true);detector.onTouchEvent(e);return true};return false};override fun onTouchEvent(e:MotionEvent):Boolean{detector.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)parent.requestDisallowInterceptTouchEvent(false);return true}}
  body.addView(frame,LinearLayout.LayoutParams(-1,((resources.displayMetrics.widthPixels-dp(24))*4/3)))
  surface=SurfaceView(this);frame.addView(surface,FrameLayout.LayoutParams(-1,-1));surface.holder.addCallback(this)
  val overlay=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8))};frame.addView(overlay,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
  photoOverlay=overlay
  overlay.addView(text(if(standalone)"HP Telecom · Camera" else "Tuyến: $titleName"))
  if(standalone)overlay.addView(text("Người chụp: $capturePhotographer"))
  seq=edit(saved?.getString("seq")?:intent.getStringExtra("seq").orEmpty(),"STT",true);name=edit(saved?.getString("name")?:intent.getStringExtra("name").orEmpty(),"Tên vị trí")
  if(route||repair){if(repair){seq.keyListener=null;name.keyListener=null};val row=LinearLayout(this);row.addView(text("STT "));row.addView(seq,LinearLayout.LayoutParams(dp(45),-2));row.addView(text(" • Vị trí: "));row.addView(name,LinearLayout.LayoutParams(0,-2,1f));overlay.addView(row)}
  if(standalone){val row=LinearLayout(this);row.addView(text("Vị trí: "));row.addView(name,LinearLayout.LayoutParams(0,-2,1f));overlay.addView(row)}
  showDate=saved?.getBoolean("showDate")?:intent.getBooleanExtra("showDate",getPreferences(MODE_PRIVATE).getBoolean("showDate",true))
  textColor=saved?.getInt("textColor")?:intent.getIntExtra("textColor",getPreferences(MODE_PRIVATE).getInt("textColor",Color.WHITE))
  dateText=text("Ngày chụp: "+SimpleDateFormat(if(standalone)"dd/MM/yyyy HH:mm" else "dd/MM/yyyy",Locale.US).format(Date()));overlay.addView(dateText);dateText.visibility=if(showDate)View.VISIBLE else View.GONE
  gpsText=text("Lat: Chờ GPS | Long: Chờ GPS");overlay.addView(gpsText)
  val noteRow=LinearLayout(this);noteRow.addView(text("Ghi chú: "));note=edit(saved?.getString("note")?:intent.getStringExtra("note").orEmpty(),"Chạm để nhập ghi chú…");if(route){note.filters=appendTypedElement<InputFilter>(note.filters,InputFilter.AllCaps(Locale.forLanguageTag("vi-VN")));note.setText(note.text.toString().uppercase(Locale.forLanguageTag("vi-VN")))};noteRow.addView(note,LinearLayout.LayoutParams(0,-2,1f));overlay.addView(noteRow)
  addressText=text("Địa chỉ GPS: Đang lấy…");proposal=edit(saved?.getString("proposal")?:intent.getStringExtra("proposal").orEmpty(),"Phương án / kiến nghị sửa chữa")
  if(standalone)overlay.addView(addressText)
  assessment=saved?.getString("assessment")?:intent.getStringExtra("assessment").orEmpty()
  if(route){
   fun clickable(v:View){v.isFocusable=false;v.isFocusableInTouchMode=false;v.setOnClickListener{showInformation()};if(v is ViewGroup)for(i in 0 until v.childCount)clickable(v.getChildAt(i))};clickable(overlay)
   infoButton=Button(this).apply{isAllCaps=false;setOnClickListener{showInformation()}};body.addView(infoButton);updateInformation()
  }

  body.addView(text("Kéo thanh hoặc dùng hai ngón để phóng to / thu nhỏ").apply{gravity=Gravity.CENTER;setPadding(0,dp(10),0,0)})
  seek=SeekBar(this).apply{max=1000;contentDescription="Phóng to / thu nhỏ camera";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(v:SeekBar?,p:Int,user:Boolean){if(user)setZoom(p/1000f)};override fun onStartTrackingTouch(v:SeekBar?){scroll.requestDisallowInterceptTouchEvent(true)};override fun onStopTrackingTouch(v:SeekBar?){scroll.requestDisallowInterceptTouchEvent(false)}})};body.addView(seek,LinearLayout.LayoutParams(-1,dp(48)))
  brightnessLabel=text("Độ sáng camera");body.addView(brightnessLabel)
  brightness=SeekBar(this).apply{contentDescription="Điều chỉnh độ sáng camera";isEnabled=false;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(v:SeekBar?,p:Int,user:Boolean){if(user){val c=camera?:return;adjustExposure(p+c.parameters.minExposureCompensation)}}
   override fun onStartTrackingTouch(v:SeekBar?){scroll.requestDisallowInterceptTouchEvent(true)}
   override fun onStopTrackingTouch(v:SeekBar?){scroll.requestDisallowInterceptTouchEvent(false)}
  })};body.addView(brightness,LinearLayout.LayoutParams(-1,dp(48)))
  val lighting=LinearLayout(this)
  resetBrightness=Button(this).apply{text="Sáng mặc định";isAllCaps=false;setOnClickListener{adjustExposure(0)}}
  lightButton=Button(this).apply{text="Bật đèn chụp đêm";isAllCaps=false;setOnClickListener{toggleLight()}}
  lighting.addView(resetBrightness,LinearLayout.LayoutParams(0,dp(52),1f));lighting.addView(lightButton,LinearLayout.LayoutParams(0,dp(52),1f));body.addView(lighting)
  refreshLighting()
  applyTextColor()
  val footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(12))};root.addView(footer)
  shutter=ImageButton(this).apply{contentDescription="Chụp ảnh";background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(Color.WHITE);setStroke(dp(5),Color.rgb(90,140,155))};setOnClickListener{take()}};footer.addView(shutter,LinearLayout.LayoutParams(dp(76),dp(76)));footer.addView(text("Chụp ảnh"))
  cameraUiReady=true
  requestAccess()
 }
 private fun sameCaptureAccount()=captureScope==(application as WorkApp).accountScope&&ServerSession.token.isNotEmpty()&&ServerSession.user?.optString("role")!="viewer"
 private fun cameraLabel(value:String,size:Float=14f)=TextView(this).apply{text=value;textSize=size;setTextColor(Color.rgb(20,38,48))}
 private fun cameraIcon(kind:String,description:String,callback:()->Unit)=ImageButton(this).apply{
  setImageDrawable(CameraControlIcon(kind));contentDescription=description;setPadding(dp(12),dp(12),dp(12),dp(12));setBackgroundColor(Color.TRANSPARENT);setOnClickListener{if(!taking)callback()}
 }
 private fun createStandaloneCamera(saved:Bundle?){
  standaloneFacing=saved?.getInt("standaloneFacing")?:Camera.CameraInfo.CAMERA_FACING_BACK
  exposure=saved?.getInt("cameraExposure")?:0
  // These fields remain the shared capture metadata contract; the stamp editor owns visible inputs.
  seq=edit("","STT",true);name=edit("","Tên vị trí");note=edit("","Ghi chú");proposal=edit("","Phương án")
  gpsText=text("Đang lấy vị trí GPS…");addressText=text("Đang lấy địa chỉ…");dateText=text("");photoOverlay=LinearLayout(this)
  window.statusBarColor=Color.WHITE;window.navigationBarColor=Color.WHITE
  androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.WHITE)};setContentView(root)
  applyImeInsetsTo(root)
  val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),0,dp(4),0)};root.addView(header,LinearLayout.LayoutParams(-1,dp(58)))
  header.addView(cameraIcon("back","Quay về màn hình trước"){finish()},LinearLayout.LayoutParams(dp(48),dp(48)))
  flashControl=cameraIcon("flash-off","Bật đèn camera"){toggleLight()};header.addView(flashControl,LinearLayout.LayoutParams(dp(48),dp(48)))
  val brand=LinearLayout(this).apply{gravity=Gravity.CENTER}
  brand.addView(ImageView(this).apply{setImageResource(R.drawable.hp_icon);contentDescription="Logo HP Telecom";scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(dp(34),dp(34)))
  brand.addView(cameraLabel("HP Telecom",16f).apply{typeface=Typeface.DEFAULT_BOLD;setPadding(dp(6),0,0,0)},LinearLayout.LayoutParams(-2,-2))
  header.addView(brand,LinearLayout.LayoutParams(0,-1,1f))
  switchControl=cameraIcon("switch","Đổi camera trước / sau"){switchCamera()};header.addView(switchControl,LinearLayout.LayoutParams(dp(48),dp(48)))
  previewArea=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)};root.addView(previewArea,LinearLayout.LayoutParams(-1,0,1f))
  previewFrame=FrameLayout(this).apply{clipChildren=true};previewArea.addView(previewFrame,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
  surface=SurfaceView(this);previewFrame.addView(surface,FrameLayout.LayoutParams(-1,-1));surface.holder.addCallback(this)
  stampHolder=FrameLayout(this);previewFrame.addView(stampHolder,FrameLayout.LayoutParams(-1,-1))
  focusView=CameraFocusView(this).apply{onFocus={x,y->focusAt(x,y)};onExposure={adjustExposure(it)};onPinchZoom={factor->setZoom(zoom+(factor-1f)*0.25f)}}
  previewFrame.addView(focusView,FrameLayout.LayoutParams(-1,-1))
  zoomControl=Button(this).apply{
   text="1.0×";textSize=13f;isAllCaps=false;setTextColor(Color.WHITE);setPadding(0,0,0,0);minWidth=0;minHeight=0
   contentDescription="Đổi mức phóng to camera"
   background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.argb(90,0,0,0));setStroke(dp(1),Color.WHITE)}
   setOnClickListener{if(!taking){val p=runCatching{camera?.parameters}.getOrNull();if(p?.isZoomSupported==true){val doubleZoom=p.zoomRatios.indexOfFirst{it>=200}.takeIf{it>=0}?:p.maxZoom;setZoom(if(p.zoom==0&&p.maxZoom>0)doubleZoom.toFloat()/p.maxZoom else 0f)}}}
  }
  previewFrame.addView(zoomControl,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=dp(12)})
  cameraHint=TextView(this).apply{text="Chạm ảnh để lấy nét, chỉnh sáng ☀ / ☾";textSize=12f;setTextColor(Color.WHITE);setShadowLayer(2f,0f,1f,Color.BLACK);gravity=Gravity.CENTER;setPadding(dp(10),dp(6),dp(10),dp(6))}
  previewFrame.addView(cameraHint,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
  previewArea.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->layoutStandalonePreview()}
  val footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10))};root.addView(footer,LinearLayout.LayoutParams(-1,-2))
  val backup=cameraLabel("Tự lưu Thư viện & Server",15f).apply{typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setPadding(dp(8),dp(10),dp(8),dp(10));background=GradientDrawable().apply{cornerRadius=dp(12).toFloat();setColor(Color.rgb(218,243,252))}}
  footer.addView(backup,LinearLayout.LayoutParams(-1,-2))
  val buttons=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(4))};footer.addView(buttons,LinearLayout.LayoutParams(-1,-2))
  fun tool(kind:String,label:String,callback:()->Unit):Pair<LinearLayout,ImageView>{
   val icon=ImageView(this).apply{setImageDrawable(CameraControlIcon(kind));setPadding(dp(6),dp(6),dp(6),dp(6));scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
   val tool=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;contentDescription=label;isFocusable=true;isClickable=true;setPadding(dp(4),dp(6),dp(4),dp(6));setOnClickListener{if(!taking)callback()}}
   tool.addView(icon,LinearLayout.LayoutParams(dp(48),dp(48)));tool.addView(cameraLabel(label,14f).apply{gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,dp(28)))
   return tool to icon
  }
  val library=tool("gallery","Thư viện"){if(sameCaptureAccount())startActivity(Intent(this,StandaloneCameraActivity::class.java).putExtra("browseOnly",true))};libraryControl=library.first;galleryThumbnail=library.second
  buttons.addView(libraryControl,LinearLayout.LayoutParams(0,dp(88),1f))
  shutter=ImageButton(this).apply{contentDescription="Chụp ảnh";background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.WHITE);setStroke(dp(7),Color.rgb(11,22,29))};setOnClickListener{take()}}
  buttons.addView(shutter,LinearLayout.LayoutParams(dp(84),dp(84)).apply{leftMargin=dp(14);rightMargin=dp(14)})
  val template=tool("template","Mẫu dấu"){showStandaloneTemplate()};templateControl=template.first;buttons.addView(templateControl,LinearLayout.LayoutParams(0,dp(88),1f))
  cameraStatus=cameraLabel("Ảnh lưu theo tài khoản đang đăng nhập",12f).apply{gravity=Gravity.CENTER;setPadding(0,dp(4),0,dp(2));setTextColor(Color.rgb(66,88,99));maxLines=3}
  footer.addView(cameraStatus,LinearLayout.LayoutParams(-1,-2))
  val modes=LinearLayout(this).apply{gravity=Gravity.CENTER}
  modes.addView(cameraLabel("Ảnh ●",16f).apply{gravity=Gravity.CENTER;setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(dp(100),dp(44)))
  modes.addView(Button(this).apply{text="Video";isAllCaps=false;setTextColor(Color.rgb(20,38,48));setBackgroundColor(Color.TRANSPARENT);setOnClickListener{if(!taking&&sameCaptureAccount())startActivity(Intent(this@CaptureActivity,VideoCaptureActivity::class.java))}},LinearLayout.LayoutParams(dp(100),dp(44)))
  footer.addView(modes,LinearLayout.LayoutParams(-1,-2))
  cameraUiReady=true;refreshStandaloneStamp();refreshLighting();requestAccess()
  lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){AutoSync.status.collect{refreshStandaloneBackupStatus()}}}
 }
 private fun showStandaloneTemplate(){
  if(taking||!sameCaptureAccount())return
  CameraStamp.showSettings(this,{if(sameCaptureAccount())chooseLogo.launch(arrayOf("image/*"))},{if(sameCaptureAccount())refreshStandaloneStamp()})
 }
 private fun refreshStandaloneStamp(){
  if(!standalone||!::stampHolder.isInitialized||!sameCaptureAccount())return
  stampHolder.removeAllViews()
  val current=fix?.takeIf{SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos<=120_000_000_000L}
  val settings=CameraStamp.load(this)
  note.setText(settings.note)
  val stamp=CameraStamp.preview(this,settings,System.currentTimeMillis(),current,resolvedAddress,capturePhotographer,photoWidthPx=previewFrame.width,photoHeightPx=previewFrame.height)
  stampHolder.addView(stamp,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
 }
 private fun layoutStandalonePreview(){
  if(!standalone||!::previewArea.isInitialized||previewArea.width<=0||previewArea.height<=0)return
  val w=previewArea.width;val h=previewArea.height
  val fitWidth=kotlin.math.min(w.toFloat(),h*previewRatio).toInt().coerceAtLeast(1);val fitHeight=(fitWidth/previewRatio).toInt().coerceAtLeast(1)
  if(previewFrame.layoutParams.width!=fitWidth||previewFrame.layoutParams.height!=fitHeight){previewFrame.layoutParams=FrameLayout.LayoutParams(fitWidth,fitHeight,Gravity.CENTER);previewFrame.post{refreshStandaloneStamp()}}
 }
 private fun switchCamera(){
  if(!standalone||taking)return
  val wanted=if(standaloneFacing==Camera.CameraInfo.CAMERA_FACING_BACK)Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
  if((0 until Camera.getNumberOfCameras()).none{val info=Camera.CameraInfo();Camera.getCameraInfo(it,info);info.facing==wanted})return
  closeCamera();standaloneFacing=wanted;exposure=0;zoom=0f;startCamera()
 }
 private fun focusAt(x:Float,y:Float){
  if(taking||!standalone)return
  val c=camera?:return
  try{
   c.cancelAutoFocus();val p=c.parameters
   val bounds=CameraFocusMath.area(x,y,displayOrientation,standaloneFacing==Camera.CameraInfo.CAMERA_FACING_FRONT)
   val region=Camera.Area(Rect(bounds[0],bounds[1],bounds[2],bounds[3]),1000)
   if(p.maxNumFocusAreas>0)p.focusAreas=listOf(region)
   if(p.maxNumMeteringAreas>0)p.meteringAreas=listOf(region)
   val supportsAuto=Camera.Parameters.FOCUS_MODE_AUTO in (p.supportedFocusModes?:emptyList())
   if(supportsAuto)p.focusMode=Camera.Parameters.FOCUS_MODE_AUTO
   c.parameters=p
   if(supportsAuto)c.autoFocus{success,active->if(camera===active&&!taking)focusView.showFocusResult(success)}
   else{focusView.showFocusResult(true);cameraHint.text=if(p.maxNumFocusAreas>0)"Đã chọn vùng lấy nét" else "Camera dùng tiêu cự cố định"}
  }catch(_:Exception){focusView.showFocusResult(false);cameraHint.text="Camera chưa lấy nét được tại đây"}
 }
 private fun updateInformation(){if(::infoButton.isInitialized)infoButton.text="Thông tin vị trí · "+assessment.ifBlank{"Chọn OK / NOK"}+" ›"}
 private fun showInformation(){
  if(taking||!route)return
  val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12));setBackgroundColor(Color.rgb(7,55,74))}
  fun field(label:String,value:String,numeric:Boolean=false):EditText{form.addView(text(label).apply{setPadding(0,dp(12),0,dp(6))});return edit(value,label,numeric).apply{setPadding(dp(10),dp(10),dp(10),dp(10));setBackgroundColor(Color.rgb(0,37,53));form.addView(this)}}
  form.addView(text("Tuyến: $titleName"));val order=field("Số thứ tự",seq.text.toString(),true);val position=field("Tên vị trí",name.text.toString());position.filters=arrayOf(InputFilter.LengthFilter(120));val memo=field("Ghi chú / Mô tả báo cáo",note.text.toString());memo.filters=appendTypedElement<InputFilter>(memo.filters,InputFilter.AllCaps(Locale.forLanguageTag("vi-VN")));memo.setText(memo.text.toString().uppercase(Locale.forLanguageTag("vi-VN")))
  form.addView(text("Đánh giá vị trí"));val choices=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL};val ok=View.generateViewId();val nok=View.generateViewId();for((id,label)in listOf(ok to "OK — Đạt",nok to "NOK — Chưa đạt"))choices.addView(RadioButton(this).apply{this.id=id;text=label;setTextColor(Color.WHITE)});if(assessment=="OK")choices.check(ok)else if(assessment=="NOK")choices.check(nok);form.addView(choices)
  val plan=field("Kiến nghị / Giải pháp",proposal.text.toString());val date=CheckBox(this).apply{text="Hiện ngày chụp trên ảnh";setTextColor(Color.WHITE);isChecked=showDate};form.addView(date);form.addView(text(addressText.text.toString()))
  val scroll=ScrollView(this).apply{addView(form)}
  val dialog=AlertDialog.Builder(this).setTitle("Thông tin vị trí").setView(scroll).setNegativeButton("Hủy",null).setPositiveButton("Lưu",null).create()
  dialog.setOnShowListener{dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   if(order.text.toString().toIntOrNull()?.let{it>0}!=true){order.error="Nhập số thứ tự lớn hơn 0";return@setOnClickListener};if(position.text.isBlank()){position.error="Nhập tên vị trí";return@setOnClickListener};if(choices.checkedRadioButtonId !in listOf(ok,nok)){Toast.makeText(this,"Chọn OK hoặc NOK",Toast.LENGTH_SHORT).show();return@setOnClickListener}
   seq.setText(order.text.toString());name.setText(position.text.toString().trim());note.setText(memo.text.toString().trim());proposal.setText(plan.text.toString().trim());assessment=if(choices.checkedRadioButtonId==ok)"OK" else "NOK";showDate=date.isChecked;dateText.visibility=if(showDate)View.VISIBLE else View.GONE;getPreferences(MODE_PRIVATE).edit().putBoolean("showDate",showDate).apply();updateInformation();dialog.dismiss()
  }};dialog.show()
 }
 private fun applyTextColor(){
  fun apply(view:View){if(view is TextView){view.setTextColor(textColor);view.setShadowLayer(2f,0f,1f,if(textColor==Color.BLACK)Color.WHITE else Color.BLACK)};if(view is android.view.ViewGroup)for(i in 0 until view.childCount)apply(view.getChildAt(i))};apply(photoOverlay)
 }
 private fun showOptions(){
  AlertDialog.Builder(this).setTitle("Tùy chọn chụp ảnh").setItems(arrayOf("Lấy lại GPS",if(showDate)"☑ Hiện ngày tháng — chạm để ẩn" else "☐ Ẩn ngày tháng — chạm để hiện","Đổi màu chữ")){_,which->when(which){
   0->requestAccess()
   1->{showDate=!showDate;dateText.visibility=if(showDate)View.VISIBLE else View.GONE;getPreferences(MODE_PRIVATE).edit().putBoolean("showDate",showDate).apply()}
   2->{val names=arrayOf("Trắng","Vàng","Đen","Đỏ","Xanh");val colors=intArrayOf(Color.WHITE,Color.YELLOW,Color.BLACK,Color.rgb(255,64,64),Color.rgb(0,229,255));AlertDialog.Builder(this).setTitle("Màu chữ trên ảnh").setSingleChoiceItems(names,colors.indexOf(textColor)){dialog,i->textColor=colors[i];applyTextColor();getPreferences(MODE_PRIVATE).edit().putInt("textColor",textColor).apply();dialog.dismiss()}.setNegativeButton("Hủy",null).show()}
  }}.setNegativeButton("Đóng",null).show()
 }
 private fun requestAccess(){val required=buildList{add(Manifest.permission.CAMERA);add(Manifest.permission.ACCESS_FINE_LOCATION);add(Manifest.permission.ACCESS_COARSE_LOCATION);if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q)add(Manifest.permission.WRITE_EXTERNAL_STORAGE)};val needed=required.filter{it!=Manifest.permission.ACCESS_COARSE_LOCATION};if(needed.any{ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED})permissions.launch(required.toTypedArray())else{startCamera();startGps()}}
 override fun onSaveInstanceState(out:Bundle){out.putString("captureScope",captureScope);out.putString("capturePhotographer",capturePhotographer);out.putString("captureUsername",captureUsername);out.putInt("standaloneFacing",standaloneFacing);out.putInt("cameraExposure",exposure);if(::seq.isInitialized){out.putString("seq",seq.text.toString());out.putString("name",name.text.toString());out.putString("note",note.text.toString());out.putString("proposal",proposal.text.toString());out.putString("assessment",assessment);out.putBoolean("showDate",showDate);out.putInt("textColor",textColor)};super.onSaveInstanceState(out)}
 private fun startCamera(){
  if(!cameraUiReady||camera!=null||!surface.holder.surface.isValid||ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED||(standalone&&(!cameraForeground||taking)))return
  try{val id=(0 until Camera.getNumberOfCameras()).firstOrNull{val i=Camera.CameraInfo();Camera.getCameraInfo(it,i);i.facing==(if(standalone)standaloneFacing else Camera.CameraInfo.CAMERA_FACING_BACK)}?:if(standalone&&Camera.getNumberOfCameras()>0)0 else error("Thiết bị không có camera sau")
   val c=Camera.open(id);camera=c;val info=Camera.CameraInfo();Camera.getCameraInfo(id,info)
   openedCameraId=id
   val degrees=if(standalone)when(windowManager.defaultDisplay.rotation){Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}else 0
   val front=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT
   if(standalone)standaloneFacing=info.facing
   displayOrientation=if(standalone&&front)(360-(info.orientation+degrees)%360)%360 else (info.orientation-degrees+360)%360
   c.setDisplayOrientation(displayOrientation)
   val p=c.parameters
   val size=p.supportedPictureSizes.filter{ kotlin.math.abs(it.width.toFloat()/it.height-4f/3)<0.03f }.filter{it.width<=4000}.maxByOrNull{it.width}?:p.supportedPictureSizes.minBy{it.width}
   p.setPictureSize(size.width,size.height);p.supportedPreviewSizes.filter{kotlin.math.abs(it.width.toFloat()/it.height-4f/3)<0.03f}.minByOrNull{kotlin.math.abs(it.width-1280)}?.let{p.setPreviewSize(it.width,it.height)}
   if(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in (p.supportedFocusModes?:emptyList()))p.focusMode=Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
   p.setRotation(if(standalone&&front)(info.orientation+degrees)%360 else (info.orientation-degrees+360)%360);p.jpegQuality=95;c.parameters=p
   if(standalone){val preview=p.previewSize;previewRatio=if(displayOrientation%180==90)preview.height.toFloat()/preview.width else preview.width.toFloat()/preview.height;layoutStandalonePreview()}
   c.setPreviewDisplay(surface.holder);c.startPreview();setZoom(0f);adjustExposure(exposure)
  }catch(e:Exception){closeCamera();AlertDialog.Builder(this).setMessage("Không mở được camera: ${e.message}").setPositiveButton("Đóng",null).show()}
 }
 private fun refreshLighting(){
  if(standalone){refreshStandaloneControls();return}
  if(!::brightness.isInitialized)return
  val p=runCatching{camera?.parameters}.getOrNull()
  val supported=p!=null&&p.maxExposureCompensation>p.minExposureCompensation
  brightness.isEnabled=supported&&!taking;resetBrightness.isEnabled=brightness.isEnabled
  if(supported&&p!=null){brightness.max=p.maxExposureCompensation-p.minExposureCompensation;brightness.progress=p.exposureCompensation-p.minExposureCompensation;brightnessLabel.text=String.format(Locale.US,"Độ sáng: %+.1f EV",p.exposureCompensation*p.exposureCompensationStep)}
  else brightnessLabel.text=if(p==null)"Đang chờ camera" else "Camera không hỗ trợ chỉnh sáng"
  val modes=p?.supportedFlashModes?:emptyList()
  lightButton.isEnabled=!taking&&Camera.Parameters.FLASH_MODE_TORCH in modes&&Camera.Parameters.FLASH_MODE_OFF in modes
  lightButton.text=if(p==null)"Đang chờ camera" else if(Camera.Parameters.FLASH_MODE_TORCH !in modes)"Máy không hỗ trợ đèn" else if(p.flashMode==Camera.Parameters.FLASH_MODE_TORCH)"Tắt đèn chụp đêm" else "Bật đèn chụp đêm"
  if(::seek.isInitialized)seek.isEnabled=!taking&&p?.isZoomSupported==true
 }
 private fun refreshStandaloneControls(){
  if(!::focusView.isInitialized)return
  val p=runCatching{camera?.parameters}.getOrNull()
  focusView.setBusy(taking)
  focusView.setExposure(p?.minExposureCompensation?:0,p?.maxExposureCompensation?:0,p?.exposureCompensation?:0,p?.exposureCompensationStep?:0f,p!=null)
  val modes=p?.supportedFlashModes?:emptyList();val hasLight=Camera.Parameters.FLASH_MODE_TORCH in modes&&Camera.Parameters.FLASH_MODE_OFF in modes
  flashControl.isEnabled=!taking&&hasLight;flashControl.alpha=if(hasLight)1f else .35f
  val lit=p?.flashMode==Camera.Parameters.FLASH_MODE_TORCH
  flashControl.setImageDrawable(CameraControlIcon(if(lit)"flash" else "flash-off"))
  flashControl.contentDescription=if(!hasLight)"Camera này không có đèn" else if(lit)"Tắt đèn camera" else "Bật đèn camera"
  val opposite=if(standaloneFacing==Camera.CameraInfo.CAMERA_FACING_BACK)Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
  val canSwitch=(0 until Camera.getNumberOfCameras()).any{val info=Camera.CameraInfo();Camera.getCameraInfo(it,info);info.facing==opposite}
  switchControl.isEnabled=!taking&&canSwitch;switchControl.alpha=if(canSwitch)1f else .35f
  switchControl.contentDescription=if(standaloneFacing==Camera.CameraInfo.CAMERA_FACING_BACK)"Đổi sang camera trước" else "Đổi sang camera sau"
  zoomControl.isEnabled=!taking&&p?.isZoomSupported==true&&p.maxZoom>0
  val ratio=if(p?.isZoomSupported==true)p.zoomRatios.getOrNull(p.zoom)?.div(100f)?:1f else 1f
  zoomControl.text=String.format(Locale.US,"%.1f×",ratio);zoomControl.alpha=if(zoomControl.isEnabled)1f else .4f
  shutter.isEnabled=!taking;libraryControl.isEnabled=!taking;templateControl.isEnabled=!taking
 }
 private fun adjustExposure(value:Int){
  if(taking)return
  val c=camera?:return
  runCatching{val p=c.parameters;val next=value.coerceIn(p.minExposureCompensation,p.maxExposureCompensation);p.exposureCompensation=next;c.parameters=p;exposure=next}
   .onFailure{Toast.makeText(this,"Không chỉnh được độ sáng trên camera này",Toast.LENGTH_SHORT).show()}
  refreshLighting()
 }
 private fun toggleLight(){
  if(taking)return
  val c=camera?:return
  runCatching{val p=c.parameters;p.flashMode=if(p.flashMode==Camera.Parameters.FLASH_MODE_TORCH)Camera.Parameters.FLASH_MODE_OFF else Camera.Parameters.FLASH_MODE_TORCH;c.parameters=p}
   .onFailure{Toast.makeText(this,"Không bật được đèn camera",Toast.LENGTH_SHORT).show()}
  refreshLighting()
 }
 private fun setZoom(value:Float){if(taking)return;val c=camera?:return;runCatching{val p=c.parameters;if(::seek.isInitialized)seek.isEnabled=p.isZoomSupported;if(p.isZoomSupported){zoom=value.coerceIn(0f,1f);p.zoom=(zoom*p.maxZoom).toInt();c.parameters=p;if(::seek.isInitialized)seek.progress=(zoom*1000).toInt()}};if(standalone)refreshStandaloneControls()}
 private fun startGps(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){gpsText.text="Cần cấp quyền vị trí chính xác";if(standalone&&::cameraHint.isInitialized){cameraHint.text="Cấp quyền vị trí chính xác để chụp ảnh";refreshStandaloneStamp()};return};runCatching{gps.removeUpdates(this);fix=null;addressFix=null;if(standalone){resolvedAddress="";addressLookup=null;cameraHint.text="Đang lấy GPS · chạm ảnh để lấy nét, chỉnh sáng";refreshStandaloneStamp()};if((route||standalone)&&::addressText.isInitialized)addressText.text="Địa chỉ GPS: Đang lấy…";gpsText.text="Lat: Đang lấy GPS | Long: Đang lấy GPS";val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{gps.isProviderEnabled(it)};check(providers.isNotEmpty()){"Hãy bật Vị trí trong Cài đặt"};providers.forEach{gps.requestLocationUpdates(it,1000,0f,this)}}.onFailure{gpsText.text=it.message;if(standalone)cameraHint.text=it.message?:"Chưa lấy được GPS"}}
 override fun onLocationChanged(location:Location){if(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos>30_000_000_000L)return;if(fix==null||location.accuracy<=fix!!.accuracy||SystemClock.elapsedRealtimeNanos()-fix!!.elapsedRealtimeNanos>30_000_000_000L){fix=Location(location);gpsText.text=String.format(Locale.US,"Lat: %.6f | Long: %.6f",location.latitude,location.longitude);if((route||standalone)&&(addressFix==null||addressFix!!.distanceTo(location)>20))lookupAddress(location);if(standalone){cameraHint.text="Chạm ảnh để lấy nét, chỉnh sáng ☀ / ☾";refreshStandaloneStamp()}}}
 private fun lookupAddress(location:Location){addressFix=Location(location);addressText.text="Địa chỉ GPS: Đang lấy…";if(standalone)resolvedAddress="";val request=lifecycleScope.async(Dispatchers.IO){runCatching{if(Geocoder.isPresent())Geocoder(this@CaptureActivity,Locale("vi","VN")).getFromLocation(location.latitude,location.longitude,1)?.firstOrNull()?.getAddressLine(0).orEmpty() else ""}.getOrDefault("")};addressLookup=request;lifecycleScope.launch{val value=withTimeoutOrNull(5000){request.await()}.orEmpty();if(addressLookup===request){addressText.text=if(value.isBlank())"Chưa lấy được địa chỉ; vẫn lưu GPS." else "Địa chỉ GPS: $value";if(standalone&&sameCaptureAccount()){resolvedAddress=value;refreshStandaloneStamp()}}}}
 private fun take(){if(taking)return;if(camera==null){requestAccess();return};val f=fix;if(f==null||SystemClock.elapsedRealtimeNanos()-f.elapsedRealtimeNanos>120_000_000_000L){startGps();AlertDialog.Builder(this).setMessage("Chờ lấy GPS mới trước khi chụp.").setPositiveButton("Đóng",null).show();return};if(route&&(seq.text.toString().toIntOrNull()?.let{it>0}!=true||name.text.isBlank())){name.error="Nhập STT và tên vị trí";return}
  if(route&&assessment.isBlank()){AlertDialog.Builder(this).setMessage("Chọn OK hoặc NOK trước khi chụp.").setPositiveButton("Đóng",null).show();return}
  if(!f.hasAccuracy()||f.accuracy>50)AlertDialog.Builder(this).setMessage("Sai số GPS ${f.accuracy.toInt()} m. Vẫn chụp?").setNegativeButton("Chờ thêm",null).setPositiveButton("Vẫn chụp"){_,_->capture(f)}.show()else capture(f)
 }
 private fun capture(location:Location){
  if(standalone){captureStandalone(location);return}
  val app=application as WorkApp
  if(captureScope!=app.accountScope||ServerSession.token.isEmpty()){finish();return}
  if(taking)return;taking=true;shutter.isEnabled=false;refreshLighting()
  val photoDirectory=File(app.accountFilesDir,"photos")
  val decision=assessment;val plan=proposal.text.toString();val lookup=addressLookup;val title=titleName;val memo=note.text.toString().trim();val sequence=seq.text.toString();val position=name.text.toString();val dateVisible=showDate;val ink=textColor;val time=System.currentTimeMillis()
  try{camera?.takePicture(null,null,Camera.PictureCallback{bytes,_->lifecycleScope.launch{
   val raw=File(photoDirectory,"raw-${UUID.randomUUID()}.jpg");val stamped=File(photoDirectory,"photo-${UUID.randomUUID()}.jpg")
   var archived=false
   try{
    val result=withContext(NonCancellable+Dispatchers.IO){
     raw.parentFile?.mkdirs();raw.writeBytes(bytes)
     val address=if((route||standalone)&&lookup!=null)withTimeoutOrNull(5000){lookup.await()}.orEmpty()else ""
     if(standalone){
      val lines=listOfNotNull("HP Telecom · Camera","Người chụp: $capturePhotographer",position.takeIf{it.isNotBlank()}?.let{"Vị trí: $it"},if(dateVisible)"Ngày chụp: "+SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.US).format(Date(time))else null,address.takeIf{it.isNotBlank()},String.format(Locale.US,"Lat: %.6f | Long: %.6f",location.latitude,location.longitude),memo.takeIf{it.isNotBlank()}?.let{"Ghi chú: $it"})
      PhotoRenderer.overlay(raw,stamped,lines,ink);PhotoRenderer.remember(raw,stamped,lines,ink)
      androidx.exifinterface.media.ExifInterface(stamped).apply{setLatLong(location.latitude,location.longitude);setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ARTIST,capturePhotographer);saveAttributes()}
     }else if(repair)PhotoRenderer.overlay(raw,stamped,listOfNotNull("Tuyến: $title","STT $sequence • Vị trí: $position",if(dateVisible)"Ngày chụp: "+SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date(time))else null,String.format(Locale.US,"Lat: %.6f | Long: %.6f",location.latitude,location.longitude),"Ghi chú: $memo"),ink)else if(!route)IncidentPhotoGps.stamp(raw,stamped,title,SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date(time)),location,memo,dateVisible,ink)
     Intent().putExtra("address",address).putExtra("proposal",plan).putExtra("assessment",decision).putExtra("raw",raw.path).putExtra("path",if(route)raw.path else stamped.path).putExtra("note",memo).putExtra("seq",sequence).putExtra("name",position).putExtra("showDate",dateVisible).putExtra("textColor",ink).putExtra("lat",location.latitude).putExtra("lon",location.longitude).putExtra("accuracy",location.accuracy).putExtra("time",time).also{if(standalone){CameraArchive.recordCapture(app,captureScope,capturePhotographer,captureUsername,it);archived=true}}
    }
    if(captureScope!=app.accountScope){finish();return@launch}
    setResult(RESULT_OK,result);finish()
   }catch(e:Exception){if(!archived&&!standalone){raw.delete();stamped.delete();File(stamped.path+".original.jpg").delete()};if(e is CancellationException)throw e;taking=false;shutter.isEnabled=true;refreshLighting();runCatching{camera?.startPreview()};AlertDialog.Builder(this@CaptureActivity).setMessage("Chưa lưu được ảnh: ${e.message}").setPositiveButton("Đóng",null).show()}
  }})}catch(e:Exception){taking=false;shutter.isEnabled=true;refreshLighting();AlertDialog.Builder(this).setMessage(e.message).setPositiveButton("Đóng",null).show()}
 }
 private fun captureStandalone(location:Location){
  if(taking)return
  if(!sameCaptureAccount()){finish();return}
  if(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos>120_000_000_000L){startGps();Toast.makeText(this,"Cần lấy GPS mới trước khi chụp",Toast.LENGTH_SHORT).show();return}
  val active=camera?:return
  val app=application as WorkApp
  val locationAtShutter=Location(location)
  val capturedAt=System.currentTimeMillis()
  val addressAtShutter=resolvedAddress
  val addressRequest=addressLookup
  val frozen=try{CameraStamp.snapshot(this,capturedAt,locationAtShutter,addressAtShutter,capturePhotographer)}catch(error:Exception){AlertDialog.Builder(this).setMessage("Chưa đọc được mẫu dấu: ${error.message}").setPositiveButton("Đóng",null).show();return}
  val photoDirectory=File(app.filesDir,"accounts/$captureScope/photos")
  val generation=++captureGeneration
  val jpegTimeout=Runnable{
   if(generation==captureGeneration&&waitingForJpeg){waitingForJpeg=false;taking=false;closeCamera()
    if(!isDestroyed&&!isFinishing&&sameCaptureAccount()){cameraStatus.text="Camera chưa trả ảnh. Hãy chụp lại.";if(cameraForeground)startCamera()}
   }
  }
  taking=true;waitingForJpeg=true;cameraStatus.text="Đang chụp và lưu ảnh…";refreshStandaloneControls();clockHandler.postDelayed(jpegTimeout,15000)
  try{
   active.takePicture(null,null,Camera.PictureCallback{bytes,_->
    clockHandler.removeCallbacks(jpegTimeout)
    if(generation==captureGeneration){waitingForJpeg=false;if(!cameraForeground)closeCamera()}
    // This write survives an Activity being stopped/destroyed after JPEG delivery.
    // The account directory and watermark are frozen before the shutter fires.
    cameraWrites.launch{
     val raw=File(photoDirectory,"raw-${UUID.randomUUID()}.jpg")
     val stamped=File(photoDirectory,"photo-${UUID.randomUUID()}.jpg")
     var receiptWritten=false
     try{
      val receipt=withContext(Dispatchers.IO){
       check(photoDirectory.mkdirs()||photoDirectory.isDirectory){"Không tạo được thư mục ảnh"}
       raw.outputStream().use{stream->stream.write(bytes);stream.fd.sync()}
       // Geocoder is optional. A cancelled old lookup must never cancel a saved photo.
       val address=addressAtShutter.ifBlank{if(addressRequest!=null)withTimeoutOrNull(4000){runCatching{addressRequest.await()}.getOrDefault("")}.orEmpty() else ""}
       CameraStamp.render(applicationContext,raw,stamped,frozen.copy(address=address))
       val result=Intent().putExtra("raw",raw.path).putExtra("path",stamped.path).putExtra("time",capturedAt)
        .putExtra("address",address).putExtra("note",frozen.settings.note).putExtra("name","")
        .putExtra("lat",locationAtShutter.latitude).putExtra("lon",locationAtShutter.longitude).putExtra("accuracy",locationAtShutter.accuracy)
       CameraArchive.recordCapture(app,captureScope,capturePhotographer,captureUsername,result).also{receiptWritten=true}
      }
      if(generation==captureGeneration)taking=false
      if(generation==captureGeneration&&!isDestroyed&&!isFinishing&&sameCaptureAccount()){
       latestArchive=receipt;refreshStandaloneControls();cameraStatus.text="Đã lưu ảnh · đang lưu Thư viện và xếp hàng gửi Server…"
       if(cameraForeground){if(camera==null)startCamera()else runCatching{camera?.startPreview()}}
       updateGalleryThumbnail(receipt)
      }
      // publish() retries missing destinations and preserves the receipt on every failure.
      if(sameCaptureAccount()){
       val published=CameraArchive.publish(applicationContext,app,receipt)
       if(!isDestroyed&&!isFinishing&&sameCaptureAccount()&&latestArchive?.id==published.id){latestArchive=published;refreshStandaloneBackupStatus()}
      }
     }catch(error:Exception){
      if(generation==captureGeneration)taking=false
      if(generation==captureGeneration&&!isDestroyed&&!isFinishing&&sameCaptureAccount()){
       refreshStandaloneControls();if(cameraForeground){if(camera==null)startCamera()else runCatching{camera?.startPreview()}}
       cameraStatus.text=if(receiptWritten)"Ảnh đã lưu · mở Thư viện để thử lại phần sao lưu" else "Chưa lưu xong ảnh · kiểm tra dung lượng rồi chụp lại"
       val recovery=if(receiptWritten)"Ảnh vẫn giữ trong Thư viện Camera. Mở Thư viện để thử lại phần sao lưu." else "Ảnh chưa có trong Thư viện. Hãy kiểm tra dung lượng trống rồi chụp lại. Tệp ảnh đã ghi vẫn được giữ trên điện thoại."
       if(cameraForeground)AlertDialog.Builder(this@CaptureActivity).setMessage("Chưa hoàn tất lưu ảnh: ${error.message}\n$recovery").setPositiveButton("Đóng",null).show()
      }
     }
    }
   })
  }catch(error:Exception){clockHandler.removeCallbacks(jpegTimeout);waitingForJpeg=false;taking=false;refreshStandaloneControls();cameraStatus.text="Chưa chụp được ảnh";AlertDialog.Builder(this).setMessage("Chưa chụp được ảnh: ${error.message}").setPositiveButton("Đóng",null).show()}
 }
 private fun updateGalleryThumbnail(photo:CameraPhoto){
  lifecycleScope.launch{
   val bitmap=withContext(Dispatchers.IO){runCatching{PhotoRenderer.thumbnail(photo.path,180)}.getOrNull()}
   if(sameCaptureAccount()&&latestArchive?.id==photo.id){galleryThumbnail.setPadding(0,0,0,0);galleryThumbnail.setImageBitmap(bitmap);galleryThumbnail.scaleType=ImageView.ScaleType.CENTER_CROP}
   else bitmap?.recycle()
  }
 }
 private fun refreshStandaloneBackupStatus(){
  val photo=latestArchive?:return
  if(!standalone||!::cameraStatus.isInitialized||!sameCaptureAccount()||taking)return
  val app=application as WorkApp
  val generation=++backupStatusGeneration
  lifecycleScope.launch{
   val (current,waiting)=withContext(Dispatchers.IO){val saved=CameraArchive.find(app,photo.account,photo.id)?:photo;saved to CameraArchive.waitingForServer(app,saved)}
   if(generation!=backupStatusGeneration||!sameCaptureAccount()||latestArchive?.id!=photo.id||taking)return@launch
   latestArchive=current
   val album=if(current.galleryUri.isNotBlank())"Đã lưu Thư viện" else "Chưa lưu album điện thoại"
   val server=when{!current.serverQueued->"đang xếp hàng gửi Server";waiting->"Server đang chờ đồng bộ";else->"đã gửi Server"}
   cameraStatus.text=if(current.lastError.isBlank())"$album · $server" else "$album · có phần chưa lưu, mở Thư viện để thử lại"
  }
 }
 override fun surfaceCreated(h:SurfaceHolder){startCamera()}
 override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,height:Int){}
 override fun surfaceDestroyed(h:SurfaceHolder){if(!standalone||!waitingForJpeg)closeCamera()}
 private fun closeCamera(){val c=camera;camera=null
  runCatching{val p=c?.parameters;if(p!=null&&Camera.Parameters.FLASH_MODE_OFF in (p.supportedFlashModes?:emptyList())){p.flashMode=Camera.Parameters.FLASH_MODE_OFF;c.parameters=p}}
  runCatching{c?.stopPreview()};runCatching{c?.release()};refreshLighting()
 }
 override fun onResume(){super.onResume();cameraForeground=true;if(standalone&&captureScope.isNotBlank()&&!sameCaptureAccount()){finish();return};if(::surface.isInitialized){startCamera();if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startGps()};if(standalone){clockHandler.removeCallbacks(clockTick);clockHandler.post(clockTick);refreshStandaloneBackupStatus()}}
 override fun onPause(){cameraForeground=false;clockHandler.removeCallbacks(clockTick);runCatching{gps.removeUpdates(this)};if(!standalone||!waitingForJpeg)closeCamera();super.onPause()}
 override fun onDestroy(){clockHandler.removeCallbacks(clockTick);if(!waitingForJpeg)closeCamera();super.onDestroy()}
 override fun onProviderEnabled(p:String){}
 override fun onProviderDisabled(p:String){}
 @Deprecated("Legacy") override fun onStatusChanged(p:String?,s:Int,e:Bundle?){}
}
