package vn.vltelecom

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A virtualized, account-owned library. Report selection never moves or deletes the original. */
class StandaloneCameraActivity : AppCompatActivity() {
    private val app get() = application as WorkApp
    private var account = ""
    private lateinit var grid: GridView
    private lateinit var adapter: CameraGalleryAdapter
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var headerTitle: TextView
    private lateinit var empty: TextView
    private lateinit var months: Spinner
    private var monthKeys = listOf("")
    private var selectedMonth = ""
    private var trashMode = false
    private var selectionMode = false
    private val selectedPhotoIds = linkedSetOf<String>()
    private var changingMonths = false
    private var busy = false
    private var loadGeneration = 0
    private var loadJob: Job? = null
    private var media: List<CameraGalleryItem> = emptyList()
    private var visibleMedia: List<CameraGalleryItem> = emptyList()
    private val recoveryAttempts = mutableSetOf<String>()
    private var viewer: Dialog? = null
    private var player: VideoView? = null
    private var syncMessage = ""
    private val dates = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("vi-VN"))
    private val monthFormat = SimpleDateFormat("MM/yyyy", Locale.forLanguageTag("vi-VN"))
    private val pickForReport get() = intent.getBooleanExtra("pickForReport", false)
    private val albumPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) publish(pending()) else status.text = "Tệp vẫn giữ trong ứng dụng. Cần quyền lưu trữ để ghi album điện thoại."
    }
    private fun sameAccount() = account.isNotBlank() && account == app.accountScope && ServerSession.token.isNotEmpty()
    private fun canUse() = sameAccount() && ServerSession.user?.optString("role")?.let { it != "viewer" } == true
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(18,43,56)) }
    private fun button(value: String, description: String = value, action: () -> Unit) = Button(this).apply {
        text = value; contentDescription = description; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setTextColor(Color.rgb(0,90,116)); setBackgroundColor(Color.TRANSPARENT); setPadding(dp(6),dp(6),dp(6),dp(6)); setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ServerSession.token.isEmpty() || app.accountScope == "locked") { startActivity(Intent(this,ServerActivity::class.java)); finish(); return }
        account = savedInstanceState?.getString("cameraAccount") ?: app.accountScope
        selectedMonth = savedInstanceState?.getString("cameraMonth").orEmpty()
        trashMode = !pickForReport && savedInstanceState?.getBoolean("cameraTrash", false) == true
        if (!canUse()) { finish(); return }; watchServerSession()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(244,248,250)) }; setContentView(root)
        applyImeInsetsTo(root)
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(8),0) }
        header.addView(button("‹","Quay lại"){finish()},LinearLayout.LayoutParams(dp(48),dp(52)))
        headerTitle=label(if(pickForReport)"Chọn ảnh báo cáo" else if(trashMode)"Thùng rác ảnh" else "Thư viện của tôi",21f).apply{typeface=Typeface.DEFAULT_BOLD}
        header.addView(headerTitle,LinearLayout.LayoutParams(0,-2,1f))
        val more=button("⋮","Tùy chọn thư viện"){}; more.setOnClickListener{showMenu(more)}; header.addView(more,LinearLayout.LayoutParams(dp(48),dp(52))); root.addView(header)
        count=label("Đang đọc thư viện…",14f).apply{setPadding(dp(16),0,dp(16),dp(8))}; root.addView(count)
        months=Spinner(this).apply{contentDescription="Lọc thư viện theo tháng";minimumHeight=dp(48)}
        root.addView(months,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=dp(12);rightMargin=dp(12)})
        status=label(libraryHint(),13f).apply{
            setPadding(dp(16),dp(4),dp(16),dp(8));maxLines=2
            setOnClickListener{AlertDialog.Builder(this@StandaloneCameraActivity).setTitle("Trạng thái thư viện").setMessage(text.toString()+if(syncMessage.isBlank())"" else "\n\n$syncMessage").setPositiveButton("Đóng",null).show()}
        };root.addView(status)
        val area=FrameLayout(this);root.addView(area,LinearLayout.LayoutParams(-1,0,1f))
        grid=GridView(this).apply{numColumns=GridView.AUTO_FIT;columnWidth=dp(104);stretchMode=GridView.STRETCH_COLUMN_WIDTH;horizontalSpacing=dp(6);verticalSpacing=dp(6);setPadding(dp(12),dp(4),dp(12),dp(16));clipToPadding=false}
        adapter=CameraGalleryAdapter(this,app,lifecycleScope,::sameAccount,::showMedia,{selectionMode&&!pickForReport},{item->item.photo?.id in selectedPhotoIds});grid.adapter=adapter;area.addView(grid,FrameLayout.LayoutParams(-1,-1))
        empty=label("Chưa có ảnh hoặc video.",16f).apply{gravity=Gravity.CENTER;setPadding(dp(24),dp(24),dp(24),dp(24))};area.addView(empty,FrameLayout.LayoutParams(-1,-1));grid.emptyView=empty
        months.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){if(!changingMonths){val key=monthKeys.getOrNull(position).orEmpty();if(key!=selectedMonth){selectedMonth=key;filterMedia(false)}}}
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        }
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){AutoSync.status.collect{syncMessage=it;if(!busy)load()}}};load()
    }
    private fun showMenu(anchor:View){
        if(!canUse())return
        val menu=PopupMenu(this,anchor)
        if(selectionMode){
            val photos=selectedPhotos()
            menu.menu.add(0,9,0,"Chọn tất cả ảnh đang xem")
            if(!trashMode&&photos.isNotEmpty())menu.menu.add(0,10,1,"Chia sẻ ${photos.size} ảnh")
            if(!trashMode&&photos.size==1)menu.menu.add(0,13,2,"Chỉnh sửa ghi chú")
            if(photos.isNotEmpty())menu.menu.add(0,11,2,if(trashMode)"Khôi phục ${photos.size} ảnh" else "Xóa ${photos.size} ảnh")
            menu.menu.add(0,12,4,"Bỏ chọn")
            menu.setOnMenuItemClickListener{
                if(!canUse())return@setOnMenuItemClickListener true
                when(it.itemId){
                    9->{visibleMedia.filter{item->item.photo!=null}.forEach{item->selectedPhotoIds.add(item.photo!!.id)};filterMedia(true)}
                    10->shareSelectedPhotos()
                    11->trashSelectedPhotos()
                    13->editPhotoNote(photos.singleOrNull())
                    12->{selectionMode=false;selectedPhotoIds.clear();filterMedia(true)}
                };true
            };menu.show();return
        }
        if(!intent.getBooleanExtra("browseOnly",false)&&!pickForReport){menu.menu.add(0,1,0,"Chụp ảnh").isEnabled=!busy;menu.menu.add(0,2,1,"Quay video").isEnabled=!busy}
        if(!pickForReport)menu.menu.add(0,3,2,"Thư viện trên server")
        if(!pickForReport)menu.menu.add(0,9,3,"Chọn nhiều ảnh")
        if(!trashMode){menu.menu.add(0,4,3,"Đồng bộ ngay");menu.menu.add(0,5,4,"Thử lại phần chưa lưu").isEnabled=!busy&&pending().isNotEmpty()}
        menu.menu.add(0,6,5,"Trạng thái sao lưu");menu.menu.add(0,7,6,"Dùng ảnh trong báo cáo")
        if(!pickForReport)menu.menu.add(0,8,7,if(trashMode)"Thư viện của tôi" else "Thùng rác ảnh").isEnabled=!busy
        menu.setOnMenuItemClickListener{
            if(!canUse())return@setOnMenuItemClickListener true
            when(it.itemId){
                1->startActivity(Intent(this,CaptureActivity::class.java).putExtra("standalone",true).putExtra("title","HP Telecom"))
                2->startActivity(Intent(this,VideoCaptureActivity::class.java))
                3->startActivity(Intent(this,PhotoLibraryActivity::class.java).putExtra("browseOnly",true))
                9->{selectionMode=true;selectedPhotoIds.clear();status.text="Chạm vào ảnh để chọn nhiều ảnh.";filterMedia(true)}
                4->{AutoSync.syncNow();load();status.text="Đang yêu cầu đồng bộ ảnh và video…"}
                5->if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q&&pending().isNotEmpty()&&ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)albumPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)else publish(pending())
                6->AlertDialog.Builder(this).setTitle("Trạng thái sao lưu").setMessage(syncMessage.ifBlank{status.text.toString()}).setPositiveButton("Đóng",null).show()
                7->AlertDialog.Builder(this).setTitle("Dùng ảnh của bạn trong báo cáo").setMessage("Mở báo cáo cần làm, chọn Thêm ảnh từ thư viện → Ảnh Camera trên điện thoại. Ảnh vẫn được giữ trong thư viện sau khi thêm vào báo cáo.").setPositiveButton("Đóng",null).show()
                8->if(!busy){trashMode=!trashMode;selectedMonth="";viewer?.dismiss();media=emptyList();visibleMedia=emptyList();adapter.submit(emptyList());headerTitle.text=if(trashMode)"Thùng rác ảnh" else "Thư viện của tôi";status.text=libraryHint();filterMedia(false);load()}
            };true
        };menu.show()
    }
    private fun libraryHint()=if(trashMode)"Ảnh chỉ ẩn khỏi ứng dụng. Chạm ảnh để khôi phục." else if(pickForReport)"Chọn ảnh của bạn để đưa vào báo cáo." else "Chạm ảnh để xem; dùng ⋮ để chọn nhiều ảnh, xóa hoặc chia sẻ."
    private fun selectedPhotos()=visibleMedia.mapNotNull{it.photo}.filter{it.id in selectedPhotoIds}
    private fun editPhotoNote(photo:CameraPhoto?){
        if(photo==null||trashMode||!selectionMode||!canUse())return
        val input=EditText(this).apply{setText(photo.note);hint="Ghi chú ảnh";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=3;setPadding(dp(18),dp(12),dp(18),dp(12))}
        val dialog=AlertDialog.Builder(this).setTitle("Chỉnh sửa ghi chú ảnh").setView(input).setNegativeButton("Hủy",null).setPositiveButton("Lưu"){_,_->
            if(!canUse()||busy)return@setPositiveButton
            busy=true;status.text="Đang lưu ghi chú ảnh…"
            lifecycleScope.launch{try{CameraArchive.updateNote(app,account,photo.id,input.text.toString());if(canUse())status.text="Đã lưu ghi chú trong thư viện trên điện thoại."}
                catch(e:CancellationException){throw e}catch(e:Exception){if(canUse())status.text="Chưa lưu được ghi chú: ${e.message}"}
                finally{busy=false;if(!isFinishing&&!isDestroyed)load()}}
        }.create()
        dialog.setOnShowListener{dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)}
        dialog.show()
    }
    private fun togglePhotoSelection(item:CameraGalleryItem){
        val photo=item.photo?:return
        if(!selectionMode||pickForReport||!canUse())return
        if(!selectedPhotoIds.add(photo.id))selectedPhotoIds.remove(photo.id)
        status.text="Đã chọn ${selectedPhotoIds.size} ảnh. Mở ⋮ để chia sẻ hoặc xóa."
        filterMedia(true)
    }
    private fun trashSelectedPhotos(){
        val photos=selectedPhotos();if(photos.isEmpty()||busy||!canUse())return
        val restoring=trashMode
        AlertDialog.Builder(this).setTitle(if(restoring)"Khôi phục ảnh đã chọn?" else "Xóa ảnh đã chọn?")
            .setMessage(if(restoring)"Khôi phục ${photos.size} ảnh vào thư viện ứng dụng?" else "Chuyển ${photos.size} ảnh vào Thùng rác ứng dụng? Bản trong báo cáo, album điện thoại và NAS vẫn được giữ.")
            .setNegativeButton("Hủy",null).setPositiveButton(if(restoring)"Khôi phục" else "Chuyển vào Thùng rác"){_,_->
                if(!canUse()||busy)return@setPositiveButton
                busy=true;status.text=if(restoring)"Đang khôi phục ảnh…" else "Đang chuyển ảnh vào Thùng rác…"
                lifecycleScope.launch{
                    try{for(photo in photos){if(!canUse())break;if(restoring)CameraArchive.restore(app,account,photo.id)else CameraArchive.trash(app,account,photo.id)}
                        if(canUse()){selectedPhotoIds.clear();selectionMode=false;status.text=if(restoring)"Đã khôi phục ${photos.size} ảnh." else "Đã chuyển ${photos.size} ảnh vào Thùng rác ứng dụng. Bản sao khác vẫn được giữ."}
                    }catch(e:CancellationException){throw e}catch(e:Exception){if(canUse())status.text="Chưa cập nhật hết ảnh: ${e.message}"}
                    finally{busy=false;if(!isFinishing&&!isDestroyed)load()}
                }
            }.show()
    }
    private fun shareSelectedPhotos(){
        val photos=selectedPhotos();if(photos.isEmpty()||trashMode||busy||!canUse())return
        busy=true;status.text="Đang chuẩn bị ${photos.size} ảnh để chia sẻ…"
        lifecycleScope.launch{
            try{
                val sources=withContext(Dispatchers.IO){photos.map{photo->CameraArchive.source(app,photo) to photo.capturedAt}}
                val send=CameraPhotoShare.prepareMultiple(this@StandaloneCameraActivity,sources,account)
                if(canUse()&&!isFinishing&&!isDestroyed)startActivity(Intent.createChooser(send,"Chia sẻ ảnh"))
            }catch(e:CancellationException){throw e}catch(e:Exception){if(canUse())Toast.makeText(this@StandaloneCameraActivity,"Chưa chia sẻ được ảnh: ${e.message}",Toast.LENGTH_LONG).show()}
            finally{busy=false;if(canUse()&&!isFinishing&&!isDestroyed)status.text="Đã chọn ${selectedPhotoIds.size} ảnh."}
        }
    }
    private fun pending()=if(trashMode)emptyList() else media.filter{item->item.photo?.let{it.deletedAt==0L&&(it.galleryUri.isBlank()||!it.serverQueued)}?:item.video!!.let{it.galleryUri.isBlank()||!it.serverQueued}}
    private fun load(){
        if(!sameAccount())return
        loadJob?.cancel();val generation=++loadGeneration
        loadJob=lifecycleScope.launch{
            try{
                val showTrash=trashMode
                val entries=withContext(Dispatchers.IO){
                    if(showTrash)CameraArchive.listTrash(app,account).map{CameraGalleryItem(photo=it)}
                    else (CameraArchive.list(app,account).map{CameraGalleryItem(photo=it)}+(if(pickForReport)emptyList() else CameraVideoArchive.list(app,account).map{CameraGalleryItem(video=it)})).sortedByDescending{it.capturedAt}
                }
                if(generation!=loadGeneration||!sameAccount())return@launch
                if(entries!=media){
                    media=entries;val keys=listOf("")+entries.map{monthFormat.format(Date(it.capturedAt))}.distinct()
                    if(selectedMonth !in keys)selectedMonth=""
                    if(keys!=monthKeys||months.adapter==null){monthKeys=keys;changingMonths=true;months.adapter=ArrayAdapter(this@StandaloneCameraActivity,android.R.layout.simple_spinner_dropdown_item,keys.map{if(it.isEmpty())"Tất cả các tháng" else "Tháng $it"});months.setSelection(keys.indexOf(selectedMonth));changingMonths=false}
                    filterMedia(true)
                }else if(months.adapter==null){months.adapter=ArrayAdapter(this@StandaloneCameraActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Tất cả các tháng"));filterMedia(true)}
                if(!trashMode&&!busy&&canUse()){val incomplete=pending().filter{it.id !in recoveryAttempts&&(it.photo?.lastError?:it.video!!.lastError).isBlank()};if(incomplete.isNotEmpty())publish(incomplete)}
            }catch(e:CancellationException){throw e}catch(e:Exception){if(sameAccount())status.text="Chưa đọc được thư viện: ${e.message}"}
        }
    }
    private fun filterMedia(preservePosition:Boolean){
        val first=grid.firstVisiblePosition;val top=grid.getChildAt(0)?.top?:0
        visibleMedia=media.filter{selectedMonth.isBlank()||monthFormat.format(Date(it.capturedAt))==selectedMonth};if(selectionMode)selectedPhotoIds.retainAll(visibleMedia.mapNotNull{it.photo?.id}.toSet());adapter.submit(visibleMedia)
        if(preservePosition&&first<visibleMedia.size)grid.setSelectionFromTop(first,top)else grid.setSelection(0)
        count.text="${visibleMedia.count{!it.isVideo}} ảnh"+if(trashMode)" trong Thùng rác" else if(pickForReport)" · của ${PhotoLibrary.photographer()}" else " · ${visibleMedia.count{it.isVideo}} video"+(if(selectionMode)" · Đã chọn ${selectedPhotoIds.size}" else "")
        empty.text=if(media.isEmpty()){if(trashMode)"Thùng rác chưa có ảnh." else "Chưa có ${if(pickForReport)"ảnh" else "ảnh hoặc video"}. Quay lại Camera để chụp."}else "Không có tệp trong tháng này."
    }
    private fun publish(items:List<CameraGalleryItem>){
        if(trashMode||busy||!canUse()||items.isEmpty())return
        busy=true;recoveryAttempts.addAll(items.map{it.id})
        lifecycleScope.launch{var failed=0
            try{for((index,item)in items.withIndex()){
                if(!canUse())break;status.text="Đang lưu ${index+1}/${items.size}…"
                val error=item.photo?.let{CameraArchive.publish(applicationContext,app,it).lastError}?:CameraVideoArchive.publish(applicationContext,app,item.video!!).lastError
                if(error.isNotBlank())failed++
            };if(sameAccount())status.text=if(failed==0)"Đã lưu thư viện và xếp hàng gửi server." else "$failed tệp còn phần chưa lưu. Chọn ⋮ → Thử lại phần chưa lưu."
            }catch(e:CancellationException){throw e}catch(e:Exception){if(sameAccount())status.text="Tệp vẫn giữ trên máy. Chưa hoàn tất: ${e.message}"}
            finally{busy=false;if(!isFinishing&&!isDestroyed)load()}
        }
    }
    private fun showMedia(initial:CameraGalleryItem){
        if(!canUse())return
        if(selectionMode&&initial.photo!=null){togglePhotoSelection(initial);return}
        viewer?.dismiss();val items=visibleMedia.toList();var index=items.indexOfFirst{it.id==initial.id};if(index<0)return
        val dialog=Dialog(this).apply{requestWindowFeature(Window.FEATURE_NO_TITLE)};viewer=dialog
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(8,18,24));setPadding(dp(8),dp(8),dp(8),dp(12))}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(button("‹","Đóng ảnh đang xem"){dialog.dismiss()}.apply{setTextColor(Color.WHITE)},LinearLayout.LayoutParams(dp(52),dp(48)))
        val title=label("",18f).apply{setTextColor(Color.WHITE)};header.addView(title,LinearLayout.LayoutParams(0,-2,1f));root.addView(header)
        val frame=FrameLayout(this);root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        val image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Ảnh đang xem"};val video=VideoView(this);player=video
        frame.addView(image,FrameLayout.LayoutParams(-1,-1));frame.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        val details=label("",13f).apply{setTextColor(Color.WHITE);maxLines=4;setPadding(dp(8),dp(8),dp(8),dp(8))};root.addView(details)
        val navigation=LinearLayout(this);val previous=button("‹ Trước"){}.apply{setTextColor(Color.WHITE)};val next=button("Sau ›"){}.apply{setTextColor(Color.WHITE)}
        navigation.addView(previous,LinearLayout.LayoutParams(0,dp(48),1f));navigation.addView(next,LinearLayout.LayoutParams(0,dp(48),1f));root.addView(navigation)
        val share=button("Chia sẻ","Chia sẻ qua ứng dụng trên điện thoại"){}.apply{setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(0,116,174))};root.addView(share,LinearLayout.LayoutParams(-1,dp(52)))
        val use=button("Dùng ảnh này trong báo cáo"){}.apply{setTextColor(Color.WHITE);visibility=if(pickForReport&&!trashMode)View.VISIBLE else View.GONE};root.addView(use,LinearLayout.LayoutParams(-1,dp(52)))
        val trashAction=button(if(trashMode)"Khôi phục ảnh" else "Xóa ảnh"){}.apply{setTextColor(if(trashMode)Color.rgb(76,220,233)else Color.rgb(255,153,142))};root.addView(trashAction,LinearLayout.LayoutParams(-1,dp(48)))
        var currentFile:File?=null;var generation=0;var previewJob:Job?=null;var shareJob:Job?=null
        fun display(){
            val activeGeneration=++generation;val item=items[index]
            previewJob?.cancel();shareJob?.cancel();video.stopPlayback();image.setImageDrawable(null);currentFile=null;share.isEnabled=false;share.text="Chia sẻ";use.isEnabled=false
            share.visibility=if(trashMode)View.GONE else View.VISIBLE
            trashAction.visibility=if(item.isVideo)View.GONE else View.VISIBLE;trashAction.isEnabled=true
            title.text="${if(item.isVideo)"Video" else "Ảnh"} ${index+1} / ${items.size}";details.text="${dates.format(Date(item.capturedAt))}\n${item.photographer}"+(item.photo?.note?.takeIf{it.isNotBlank()}?.let{"\n$it"}?:"")+(if(trashMode)"\nĐã chuyển vào Thùng rác: ${dates.format(Date(item.photo!!.deletedAt))}"else "")
            previous.isEnabled=index>0;next.isEnabled=index<items.lastIndex;image.visibility=if(item.isVideo)View.GONE else View.VISIBLE;video.visibility=if(item.isVideo)View.VISIBLE else View.GONE
            previewJob=lifecycleScope.launch{var decoded:Bitmap?=null;var retained=false;try{
                val file=withContext(Dispatchers.IO){
                    if(item.photo!=null){
                        val receipt=CameraArchive.find(app,account,item.photo.id)?:error("Không tìm thấy ảnh trong thư viện.")
                        check((receipt.deletedAt>0L)==trashMode){"Trạng thái ảnh đã thay đổi. Hãy mở lại thư viện."}
                        CameraArchive.source(app,receipt)
                    }else item.source(app)
                }
                val bitmap=if(!item.isVideo)withContext(Dispatchers.IO){(PhotoRenderer.thumbnail(file.path,1400)?:error("Không đọc được ảnh")).also{decoded=it}}else null
                if(!sameAccount()||!dialog.isShowing||generation!=activeGeneration)return@launch
                currentFile=file;share.isEnabled=!trashMode;use.isEnabled=!item.isVideo&&!trashMode
                if(item.isVideo){
                    video.setMediaController(MediaController(this@StandaloneCameraActivity).apply{setAnchorView(video)})
                    video.setVideoURI(FileProvider.getUriForFile(this@StandaloneCameraActivity,"$packageName.files",file))
                    video.setOnPreparedListener{if(dialog.isShowing&&generation==activeGeneration&&sameAccount())video.seekTo(1)}
                    video.setOnErrorListener{_,_,_->if(dialog.isShowing&&generation==activeGeneration&&sameAccount())details.text="Không phát được video này trên điện thoại. Bạn vẫn có thể chia sẻ tệp.";true}
                }else{image.setImageBitmap(bitmap);retained=true}
            }catch(e:CancellationException){throw e}catch(e:Exception){if(dialog.isShowing&&generation==activeGeneration&&sameAccount())details.text="Chưa mở được tệp: ${e.message}"}finally{if(!retained)decoded?.recycle()}}
        }
        previous.setOnClickListener{if(index>0){index--;display()}};next.setOnClickListener{if(index<items.lastIndex){index++;display()}}
        share.setOnClickListener{
            val item=items[index];val file=currentFile?:return@setOnClickListener;val activeGeneration=generation;if(!canUse()||trashMode||busy)return@setOnClickListener
            video.pause();share.isEnabled=false;share.text="Đang chuẩn bị…"
            shareJob=lifecycleScope.launch{try{
                val send=CameraPhotoShare.prepare(this@StandaloneCameraActivity,file,account,item.capturedAt)
                if(canUse()&&!isFinishing&&!isDestroyed&&dialog.isShowing&&generation==activeGeneration)startActivity(Intent.createChooser(send,"Chia sẻ qua ứng dụng"))
            }catch(e:CancellationException){throw e}catch(e:Exception){if(sameAccount()&&dialog.isShowing)Toast.makeText(this@StandaloneCameraActivity,"Chưa chia sẻ được: ${e.message}",Toast.LENGTH_LONG).show()}
            finally{if(dialog.isShowing&&generation==activeGeneration){share.isEnabled=currentFile!=null&&!trashMode;share.text="Chia sẻ"}}}
        }
        use.setOnClickListener{
            val photo=items[index].photo?:return@setOnClickListener;val file=currentFile?:return@setOnClickListener
            if(pickForReport&&canUse()&&!trashMode&&!busy&&photo.deletedAt==0L){
                setResult(RESULT_OK,Intent().putExtra("path",file.path).putExtra("id",photo.id).putExtra("name",file.name).putExtra("photographer",photo.photographer).putExtra("username",photo.username).putExtra("capturedAt",photo.capturedAt).putExtra("locationName",photo.locationName).putExtra("lat",photo.lat).putExtra("lon",photo.lon).putExtra("accuracy",photo.accuracy).putExtra("note",photo.note));dialog.dismiss();finish()
            }
        }
        trashAction.setOnClickListener{
            val photo=items[index].photo?:return@setOnClickListener
            val activeGeneration=generation
            if(!canUse())return@setOnClickListener
            if(busy){Toast.makeText(this,"Đang lưu thư viện. Vui lòng thử lại sau khi hoàn tất.",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            val restoring=trashMode
            fun changeTrash(){
                if(!canUse()||busy||!dialog.isShowing||generation!=activeGeneration)return
                busy=true;generation++;loadGeneration++;loadJob?.cancel();previewJob?.cancel();shareJob?.cancel()
                previous.isEnabled=false;next.isEnabled=false;share.isEnabled=false;use.isEnabled=false;trashAction.isEnabled=false
                trashAction.text=if(restoring)"Đang khôi phục…" else "Đang chuyển vào Thùng rác…"
                lifecycleScope.launch{
                    try{
                        if(restoring)CameraArchive.restore(app,account,photo.id)else CameraArchive.trash(app,account,photo.id)
                        if(canUse()&&!isFinishing&&!isDestroyed){
                            recoveryAttempts.remove("photo:${photo.id}")
                            status.text=if(restoring)"Đã khôi phục ảnh vào thư viện ứng dụng." else "Đã chuyển ảnh vào Thùng rác ứng dụng. Bản ở báo cáo, album và NAS vẫn giữ nguyên."
                            dialog.dismiss()
                        }
                    }catch(e:CancellationException){throw e}catch(e:Exception){
                        if(canUse()&&!isFinishing&&!isDestroyed){Toast.makeText(this@StandaloneCameraActivity,"Chưa ${if(restoring)"khôi phục" else "chuyển vào Thùng rác"} được ảnh: ${e.message}",Toast.LENGTH_LONG).show();if(dialog.isShowing){trashAction.text=if(restoring)"Khôi phục ảnh" else "Xóa ảnh";display()}}
                    }finally{
                        busy=false
                        if(canUse()&&!isFinishing&&!isDestroyed){if(dialog.isShowing)trashAction.isEnabled=true;load()}
                    }
                }
            }
            if(restoring)changeTrash()
            else AlertDialog.Builder(this).setTitle("Xóa ảnh khỏi thư viện ứng dụng?")
                .setMessage("Chuyển ảnh vào Thùng rác của ứng dụng. Ảnh trong báo cáo, album điện thoại và NAS vẫn giữ nguyên.")
                .setNegativeButton("Hủy",null).setPositiveButton("Chuyển vào Thùng rác"){_,_->changeTrash()}.show()
        }
        dialog.setContentView(root);dialog.setOnDismissListener{generation++;previewJob?.cancel();shareJob?.cancel();video.stopPlayback();image.setImageDrawable(null);if(viewer===dialog){viewer=null;player=null}}
        dialog.show();dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK));dialog.window?.setLayout(-1,-1);display()
    }
    override fun onResume(){super.onResume();if(account.isNotBlank()&&!canUse()){finish();return};if(::grid.isInitialized&&!busy)load()}
    override fun onPause(){player?.pause();super.onPause()}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("cameraAccount",account);outState.putString("cameraMonth",selectedMonth);outState.putBoolean("cameraTrash",trashMode);super.onSaveInstanceState(outState)}
    override fun onDestroy(){viewer?.dismiss();if(::adapter.isInitialized)adapter.dispose();super.onDestroy()}
}
