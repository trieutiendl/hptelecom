package vn.vltelecom

import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class ServerActivity:AppCompatActivity(){
 private val app get()=application as WorkApp
 private lateinit var body:LinearLayout;private lateinit var message:TextView
 private var busy=false
 private lateinit var registrationBack:OnBackPressedCallback
 private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
 private fun label(text:String,size:Float=16f)=TextView(this).apply{this.text=text;textSize=size;setTextColor(Color.WHITE);setPadding(0,16,0,12);body.addView(this)}
 private fun button(text:String,action:()->Unit){body.addView(Button(this).apply{this.text=text;isAllCaps=false;setTextColor(Color.rgb(0,40,55));backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(22,212,223));minHeight=dp(50);setOnClickListener{if(!busy)action()}})}
 private fun field(title:String,value:String="",password:Boolean=false,uri:Boolean=false):EditText {label(title);return EditText(this).apply{setText(value);setSingleLine();setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);setPadding(dp(12),dp(10),dp(12),dp(10));minHeight=dp(48);background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.parseColor("#99002333"));setStroke(dp(1),Color.parseColor("#89edf4"));cornerRadius=dp(10).toFloat()};inputType=InputType.TYPE_CLASS_TEXT or when{password->InputType.TYPE_TEXT_VARIATION_PASSWORD;uri->InputType.TYPE_TEXT_VARIATION_URI;else->InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD};body.addView(this)}}
 private fun shell(title:String){body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,55,28,40);setBackgroundColor(Color.rgb(5,40,55))};val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(Color.rgb(5,40,55))};scroll.addView(body);setContentView(scroll);applyImeInsetsTo(scroll);label(title,24f);message=label("")}
 private fun work(action:suspend ()->Unit){if(busy)return;busy=true;message.text="Đang xử lý…";lifecycleScope.launch{try{action();message.text="Hoàn tất."}catch(e:Exception){message.text=e.message?:"Không kết nối được server";if(e is ServerSession.Failure&&e.status==401){ServerSession.token="";ServerSession.user=null;signedOut()}}finally{busy=false}}}
 private fun signedOut(){startActivity(Intent(this,ServerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()}
 private fun main(){startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);registrationBack=object:OnBackPressedCallback(false){override fun handleOnBackPressed(){if(!busy)login()}};onBackPressedDispatcher.addCallback(this,registrationBack);if(ServerSession.token.isEmpty()){if(ServerSession.offlineAccount(app)!=null){loginShell();work{ServerSession.restoreOffline(app);main()}}else login()}else panel()}
 private fun login(){
  registrationBack.isEnabled=false
  loginShell();intent.getStringExtra("session-reason")?.let{label(it,14f)}
  label("Tài khoản chỉ dùng trên một điện thoại tại một thời điểm. Đăng nhập trên điện thoại khác sẽ đăng xuất phiên cũ.",14f)
  val serverAddress=app.getSharedPreferences("server-settings",Context.MODE_PRIVATE).getString("url","")?.takeIf{it.isNotBlank()}?:ServerDiscovery.defaultUrl
  label("Ứng dụng sẽ tự kết nối máy chủ HP Telecom.",13f)
  val saved=RememberLogin.read(this)?.takeIf{it.optString("url") in listOf(serverAddress,ServerDiscovery.defaultUrl)}
  val user=field("Tên đăng nhập",saved?.optString("user")?:"");val pass=field("Mật khẩu",saved?.optString("pass")?:"",password=true)
  val remember=CheckBox(this).apply{text="Nhớ tài khoản và mật khẩu";setTextColor(Color.WHITE);buttonTintList=android.content.res.ColorStateList.valueOf(Color.CYAN);minHeight=dp(48);isChecked=saved!=null;setOnCheckedChangeListener{_,checked->if(!checked)try{RememberLogin.save(this@ServerActivity,null)}catch(e:Exception){message.text="Chưa xóa được thông tin đã nhớ. Vui lòng thử lại.";isChecked=true}}};body.addView(remember)
  ServerSession.offlineAccount(app)?.let{cached->button("Dùng offline · "+cached.getJSONObject("user").optString("display_name")){work{ServerSession.restoreOffline(app);main()}}}
  button("Đăng nhập"){work{ServerSession.login(app,serverAddress,user.text.toString(),pass.text.toString());try{RememberLogin.save(this@ServerActivity,if(remember.isChecked)JSONObject().put("url",ServerSession.url).put("user",user.text.toString()).put("pass",pass.text.toString())else null)}catch(e:Exception){Toast.makeText(this@ServerActivity,"Đã đăng nhập nhưng chưa lưu được mật khẩu.",Toast.LENGTH_LONG).show()};pass.setText("");main()}}
  button("Tạo tài khoản"){pass.setText("");registrationForm(serverAddress)}
 }

 private fun registrationForm(serverAddress:String){
  registrationBack.isEnabled=true
  shell("Tạo tài khoản")
  label("Gửi yêu cầu đến quản trị. Bạn chỉ đăng nhập được sau khi tài khoản được phê duyệt và cấp quyền.",14f)
  val name=field("Họ tên").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS}
  val username=field("Tên đăng nhập")
  val password=field("Mật khẩu (10–200 ký tự)",password=true).apply{isSaveEnabled=false;importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO}
  val team=field("Team / Đội đề nghị")
  val phone=field("Số điện thoại").apply{inputType=InputType.TYPE_CLASS_PHONE}
  val email=field("Email").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS}
  for(input in listOf(name,username,password,team,phone,email))input.isSaveEnabled=false
  label("Team / Đội bạn nhập là thông tin đề nghị. Quản trị sẽ quyết định đội và quyền báo cáo được cấp.",13f)
  button("Gửi đăng ký"){
   val payload=try{Registration.payload(name.text.toString(),username.text.toString(),password.text.toString(),team.text.toString(),phone.text.toString(),email.text.toString())}catch(e:IllegalArgumentException){message.text=e.message;return@button}
   busy=true;message.text="Đang gửi yêu cầu…"
   lifecycleScope.launch{
    try{
     val result=Registration.submit(app,serverAddress,payload)
     password.setText("")
     (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(password.windowToken,0)
     shell("Đã gửi yêu cầu")
     message.text=result
     label("Chờ quản trị phê duyệt, sau đó quay lại đăng nhập bằng tài khoản đã đăng ký.",15f)
     button("Quay lại đăng nhập"){login()}
    }catch(e:CancellationException){throw e}catch(e:Exception){message.text=e.message?:"Chưa gửi được đăng ký. Vui lòng thử lại."}
    finally{payload.remove("password");busy=false}
   }
  }
  button("Quay lại đăng nhập"){password.setText("");login()}
 }

 private fun loginShell(){
  val root=FrameLayout(this).apply{setBackgroundColor(Color.parseColor("#002b3e"))}
  root.addView(ImageView(this).apply{setImageResource(R.drawable.hp_background_v2);scaleType=ImageView.ScaleType.FIT_CENTER;alpha=0.95f;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO},FrameLayout.LayoutParams(-1,-1))
  val scroll=ScrollView(this).apply{isFillViewport=true}
  val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;setPadding(dp(22),dp(28),dp(22),dp(28))}
  body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(24));background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.parseColor("#d9002b3e"));setStroke(dp(1),Color.parseColor("#89edf4"));cornerRadius=dp(22).toFloat()}}
  container.addView(body,LinearLayout.LayoutParams(-1,-2));scroll.addView(container);root.addView(scroll);setContentView(root)
  applyImeInsetsTo(scroll) { visible ->
   container.gravity=android.view.Gravity.CENTER_HORIZONTAL or (if(visible) android.view.Gravity.TOP else android.view.Gravity.CENTER_VERTICAL)
   if(visible)scroll.post{currentFocus?.let{focused->focused.requestRectangleOnScreen(Rect(0,0,focused.width,focused.height),true)}}
  }
  body.addView(ImageView(this).apply{setImageResource(R.drawable.hp_logo);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Logo HP Telecom"},LinearLayout.LayoutParams(dp(160),dp(160)).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL})
  label("HP Telecom",24f).gravity=android.view.Gravity.CENTER
  message=label("",14f)
 }

 private fun panel(){
  shell("HP Telecom Server")
  label(ServerSession.user?.optString("display_name")?:"")
  button("← Quay lại ứng dụng"){main()}
  button("Đồng bộ"){AutoSync.start(app);AutoSync.syncNow()}
  button("Đăng xuất"){AlertDialog.Builder(this).setMessage("Đăng xuất? Dữ liệu đã lưu vẫn giữ riêng cho tài khoản này.").setNegativeButton("Hủy",null).setPositiveButton("Đăng xuất"){_,_->work{AutoSync.stop();runCatching{ServerSession.logout()};signedOut()}}.show()}
  label("Thay đổi đã lưu tự gửi sau khoảng 10 giây khi có mạng. Nhận cập nhật từ người cùng làm khi mở ứng dụng/danh sách, quay khỏi báo cáo hoặc bấm Đồng bộ. Không kiểm tra liên tục khi không có thay đổi. Mở báo cáo để xuất Excel hoặc KMZ.",14f)
  AutoSync.start(app)
  lifecycleScope.launch {AutoSync.status.collect {message.text=it}}
 }
}
