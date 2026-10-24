package vn.vltelecom
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Authorization is checked by normal API requests; an idle screen performs no polling. */
fun AppCompatActivity.watchServerSession(){
 var redirecting=false
 lifecycleScope.launch{
  repeatOnLifecycle(Lifecycle.State.STARTED){
   ServerSession.invalidSessionToken.collect{invalid->
    if(redirecting)return@collect
    val current=ServerSession.token
    if(current.isEmpty()||invalid!=null&&invalid==current){
     redirecting=true
     ServerSession.token="";ServerSession.user=null
     try{(application as WorkApp).flushReport()}catch(e:Exception){if(e is CancellationException){redirecting=false;throw e}}
     if(ServerSession.token.isNotEmpty()){redirecting=false;return@collect}
     startActivity(Intent(this@watchServerSession,ServerActivity::class.java).putExtra("session-reason","Phiên này đã bị đăng xuất vì tài khoản vừa đăng nhập trên điện thoại khác. Báo cáo chưa đồng bộ vẫn còn lưu trên máy này.").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
     finish()
    }
   }
  }
 }
}
