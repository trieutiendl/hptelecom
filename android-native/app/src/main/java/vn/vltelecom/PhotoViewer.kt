package vn.vltelecom

import android.app.Dialog
import android.graphics.Color
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

object PhotoViewer {
 fun show(activity:AppCompatActivity,path:String){
  val dialog=Dialog(activity);val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(0,24,35));fitsSystemWindows=true}
  root.addView(Button(activity).apply{text="‹ Đóng ảnh";isAllCaps=false;setOnClickListener{dialog.dismiss()}})
  root.addView(Button(activity).apply{text="Tải ảnh xuống";isAllCaps=false;setOnClickListener{PhotoDownloadActivity.save(activity,path)}})
  var scale=1f;var lastX=0f;var lastY=0f
  val image=ImageView(activity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Ảnh đã lưu. Dùng hai ngón để phóng to; nhấn giữ để tải xuống."}
  val frame=FrameLayout(activity);frame.addView(image,FrameLayout.LayoutParams(-1,-1));root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
  val detector=ScaleGestureDetector(activity,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(d:ScaleGestureDetector):Boolean{scale=(scale*d.scaleFactor).coerceIn(1f,4f);image.scaleX=scale;image.scaleY=scale;if(scale==1f){image.translationX=0f;image.translationY=0f};return true}})
  val hold=GestureDetector(activity,object:GestureDetector.SimpleOnGestureListener(){
   override fun onDown(e:MotionEvent)=true
   override fun onLongPress(e:MotionEvent){if(!detector.isInProgress)PhotoDownloadActivity.save(activity,path)}
  })
  image.setOnTouchListener{_,e->detector.onTouchEvent(e);hold.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_DOWN){lastX=e.x;lastY=e.y};if(e.actionMasked==MotionEvent.ACTION_MOVE&&!detector.isInProgress&&e.pointerCount==1&&scale>1){image.translationX=(image.translationX+(e.x-lastX)).coerceIn(-image.width*(scale-1)/2,image.width*(scale-1)/2);image.translationY=(image.translationY+(e.y-lastY)).coerceIn(-image.height*(scale-1)/2,image.height*(scale-1)/2)};lastX=e.x;lastY=e.y;true}
  dialog.setContentView(root);dialog.show();dialog.window?.setLayout(-1,-1)
  val load=activity.lifecycleScope.launch{val bitmap=withContext(Dispatchers.IO){runCatching{PhotoRenderer.thumbnail(path,2200)}.getOrNull()};if(dialog.isShowing){if(bitmap==null){Toast.makeText(activity,"Không đọc được ảnh",Toast.LENGTH_LONG).show();dialog.dismiss()}else image.setImageBitmap(bitmap)}else bitmap?.recycle()}
  dialog.setOnDismissListener{load.cancel();image.setImageDrawable(null)}
 }
}
