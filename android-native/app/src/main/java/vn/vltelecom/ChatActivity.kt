package vn.vltelecom

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chat nhóm theo Team / Đội. Dữ liệu nằm trên server, đường dẫn api/chat. */
class ChatActivity:AppCompatActivity(){
 companion object{@Volatile var visible=false}
 override fun onStart(){super.onStart();visible=true}
 override fun onStop(){visible=false;super.onStop()}
 private val bg=Color.rgb(5,40,55)
 private val accent=Color.rgb(22,212,223)
 private var team=""
 private var multiRoom=false
 private var signature=""
 private lateinit var status:TextView
 private lateinit var list:LinearLayout
 private lateinit var scroll:ScrollView
 private lateinit var input:EditText
 private var sending=false
 private lateinit var backToRooms:OnBackPressedCallback
 private var poll:kotlinx.coroutines.Job?=null
 private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()

 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  backToRooms=object:OnBackPressedCallback(false){override fun handleOnBackPressed(){showRooms()}}
  onBackPressedDispatcher.addCallback(this,backToRooms)
  if(ServerSession.token.isEmpty()){startActivity(Intent(this,ServerActivity::class.java));finish();return}
  showRooms()
 }

 private fun shell(title:String,onBack:()->Unit):LinearLayout{
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(dp(14),dp(14),dp(14),dp(10))}
  val bar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
  bar.addView(Button(this).apply{text="←";isAllCaps=false;textSize=18f;setTextColor(Color.rgb(0,40,55));backgroundTintList=android.content.res.ColorStateList.valueOf(accent);minWidth=dp(48);minimumWidth=dp(48);minHeight=dp(48);contentDescription="Quay lại";setOnClickListener{onBack()}},LinearLayout.LayoutParams(dp(56),dp(48)))
  bar.addView(TextView(this).apply{text=title;textSize=20f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD;setPadding(dp(12),0,0,0);maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
  root.addView(bar,LinearLayout.LayoutParams(-1,-2))
  status=TextView(this).apply{textSize=13f;setTextColor(Color.rgb(160,215,225));setPadding(0,dp(6),0,dp(6))}
  root.addView(status)
  setContentView(root);applyImeInsetsTo(root)
  return root
 }

 private fun leave(){startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish()}

 private fun showRooms(){
  backToRooms.isEnabled=false;team="";signature="";poll?.cancel()
  val root=shell("Chat nhóm"){leave()}
  status.text="Đang tải danh sách…"
  val holder=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  root.addView(ScrollView(this).apply{addView(holder)},LinearLayout.LayoutParams(-1,0,1f))
  lifecycleScope.launch{
   try{
    val rooms=ServerSession.api("chat/rooms").getJSONArray("rooms")
    multiRoom=rooms.length()>1
    if(rooms.length()==0){status.text="Tài khoản chưa được gán Team / Đội nên chưa có nhóm chat. Hãy nhờ quản trị gán đội.";return@launch}
    if(rooms.length()==1){open(rooms.getJSONObject(0).getString("team"));return@launch}
    status.text="Chọn nhóm để chat"
    for(i in 0 until rooms.length()){
     val r=rooms.getJSONObject(i);val last=r.optJSONObject("last")
     val preview=if(last==null)"Chưa có tin nhắn" else if(last.optBoolean("deleted"))"Tin nhắn đã thu hồi" else last.optString("user_name")+": "+last.optString("body")
     holder.addView(Button(this@ChatActivity).apply{text=r.getString("team")+"\n"+preview.take(60);isAllCaps=false;gravity=Gravity.START or Gravity.CENTER_VERTICAL;setTextColor(Color.rgb(0,40,55));backgroundTintList=android.content.res.ColorStateList.valueOf(accent);minHeight=dp(64);setOnClickListener{open(r.getString("team"))}},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)})
    }
   }catch(e:CancellationException){throw e}catch(e:Exception){fail(e)}
  }
 }

 private fun fail(e:Exception){
  status.text=e.message?:"Không kết nối được server"
  if(e is ServerSession.Failure&&e.status==401){ServerSession.token="";ServerSession.user=null;startActivity(Intent(this,ServerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()}
 }

 private fun open(name:String){
  team=name;signature="";backToRooms.isEnabled=multiRoom
  val root=shell(name){if(multiRoom)showRooms() else leave()}
  status.text="Đang tải tin nhắn…"
  list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
  scroll=ScrollView(this).apply{addView(list)}
  root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,0)}
  input=EditText(this).apply{hint="Nhập tin nhắn…";setHintTextColor(Color.LTGRAY);setTextColor(Color.WHITE);maxLines=4;minHeight=dp(48);filters=arrayOf(InputFilter.LengthFilter(2000));inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;setPadding(dp(12),dp(8),dp(12),dp(8));background=GradientDrawable().apply{setColor(Color.parseColor("#99002333"));setStroke(dp(1),Color.parseColor("#89edf4"));cornerRadius=dp(10).toFloat()}}
  row.addView(input,LinearLayout.LayoutParams(0,-2,1f))
  row.addView(Button(this).apply{text="Gửi";isAllCaps=false;setTextColor(Color.rgb(0,40,55));backgroundTintList=android.content.res.ColorStateList.valueOf(accent);minHeight=dp(48);setOnClickListener{send()}},LinearLayout.LayoutParams(-2,dp(48)).apply{leftMargin=dp(8)})
  root.addView(row,LinearLayout.LayoutParams(-1,-2))
  poll?.cancel()
  poll=lifecycleScope.launch{
   repeatOnLifecycle(Lifecycle.State.STARTED){
    while(true){refresh();delay(5000)}
   }
  }
 }

 private suspend fun refresh(){
  val room=team;if(room.isEmpty())return
  try{
   val r=ServerSession.api("chat/messages?team="+URLEncoder.encode(room,"UTF-8")+"&limit=100")
   if(room!=team)return
   val msgs=r.getJSONArray("messages")
   val sig=(0 until msgs.length()).joinToString(","){val m=msgs.getJSONObject(it);m.getLong("id").toString()+(if(m.optBoolean("deleted"))"x" else "")}
   if(sig==signature){status.text="Chat nhóm · tự cập nhật";return}
   val nearBottom=!scroll.canScrollVertically(1)||signature.isEmpty()
   signature=sig;render(msgs)
   status.text=if(msgs.length()==0)"Chưa có tin nhắn. Hãy gửi tin đầu tiên." else "Chat nhóm · tự cập nhật"
   if(nearBottom)scroll.post{scroll.fullScroll(android.view.View.FOCUS_DOWN)}
  }catch(e:CancellationException){throw e}catch(e:Exception){fail(e)}
 }

 private fun render(msgs:org.json.JSONArray){
  list.removeAllViews()
  val me=ServerSession.user?.optString("id")?:""
  val isAdmin=ServerSession.user?.optString("role")=="admin"
  val fmt=SimpleDateFormat("HH:mm dd/MM",Locale.getDefault())
  for(i in 0 until msgs.length()){
   val m=msgs.getJSONObject(i);val mine=m.optString("user_id")==me;val gone=m.optBoolean("deleted")
   val bubble=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));background=GradientDrawable().apply{setColor(if(mine)Color.parseColor("#0b6b78") else Color.parseColor("#123c4d"));cornerRadius=dp(14).toFloat()}}
   if(!mine)bubble.addView(TextView(this).apply{text=m.optString("user_name");textSize=12f;setTextColor(accent);typeface=Typeface.DEFAULT_BOLD})
   bubble.addView(TextView(this).apply{text=if(gone)"Tin nhắn đã thu hồi" else m.optString("body");textSize=16f;setTextColor(if(gone)Color.LTGRAY else Color.WHITE);if(gone)setTypeface(typeface,Typeface.ITALIC);setTextIsSelectable(!gone)})
   bubble.addView(TextView(this).apply{text=fmt.format(Date(m.optLong("created_at")*1000));textSize=11f;setTextColor(Color.rgb(160,215,225));gravity=Gravity.END})
   if(!gone&&(mine||isAdmin))bubble.setOnLongClickListener{AlertDialog.Builder(this).setMessage("Thu hồi tin nhắn này?").setNegativeButton("Hủy",null).setPositiveButton("Thu hồi"){_,_->recall(m.getLong("id"))}.show();true}
   list.addView(bubble,LinearLayout.LayoutParams(-2,-2).apply{gravity=if(mine)Gravity.END else Gravity.START;topMargin=dp(6);if(mine)leftMargin=dp(48) else rightMargin=dp(48)})
  }
 }

 private fun recall(id:Long){
  lifecycleScope.launch{
   try{ServerSession.api("chat/messages/$id","DELETE",JSONObject());signature="";refresh()}
   catch(e:CancellationException){throw e}catch(e:Exception){fail(e)}
  }
 }

 private fun send(){
  val text=input.text.toString().trim()
  if(text.isEmpty()||sending)return
  sending=true
  lifecycleScope.launch{
   try{
    ServerSession.api("chat/messages","POST",JSONObject().put("team",team).put("body",text))
    input.setText("");signature="";refresh()
    scroll.post{scroll.fullScroll(android.view.View.FOCUS_DOWN)}
   }catch(e:CancellationException){throw e}catch(e:Exception){fail(e)}
   finally{sending=false}
  }
 }
}
