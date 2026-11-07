package vn.vltelecom

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** System save picker works without broad storage permission on every supported Android. */
class PhotoDownloadActivity:AppCompatActivity(){
    private val picker=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        val uri=result.data?.data?.takeIf{result.resultCode==RESULT_OK}
        if(uri==null){finish();return@registerForActivityResult}
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO){
                    val source=source()
                    contentResolver.openOutputStream(uri,"w")?.use{out->source.inputStream().use{it.copyTo(out)}}?:error("Không mở được nơi lưu ảnh")
                }
                Toast.makeText(this@PhotoDownloadActivity,"Đã lưu ảnh vào nơi bạn chọn",Toast.LENGTH_LONG).show()
            }catch(e:Exception){Toast.makeText(this@PhotoDownloadActivity,"Chưa lưu được ảnh: ${e.message}",Toast.LENGTH_LONG).show()}
            finally{finish()}
        }
    }
    private fun source():File {
        val file=File(intent.getStringExtra("path")?:error("Không có ảnh")).canonicalFile
        val root=(application as WorkApp).accountFilesDir.canonicalFile
        check(file.toPath().startsWith(root.toPath())&&file.isFile&&file.length()>0){"Ảnh không còn trên điện thoại"}
        return file
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null)try{
            val extension=MxPhotoUpload.extension(source())
            picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
                addCategory(Intent.CATEGORY_OPENABLE)
                type=if(extension=="png")"image/png" else "image/jpeg"
                putExtra(Intent.EXTRA_TITLE,"HP-Telecom-${System.currentTimeMillis()}.$extension")
            })
        }
        catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show();finish()}
    }
    companion object {
        fun save(activity:AppCompatActivity,path:String){activity.startActivity(Intent(activity,PhotoDownloadActivity::class.java).putExtra("path",path))}
    }
}
