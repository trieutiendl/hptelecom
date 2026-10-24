package vn.vltelecom

import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.InputType
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoLibraryActivity : AppCompatActivity() {
    private val app get() = application as WorkApp
    private lateinit var list: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var useButton: Button
    private lateinit var shareButton: Button
    private lateinit var manageButton: Button
    private lateinit var reloadButton: Button
    private lateinit var trashButton: Button
    private lateinit var title: TextView
    private lateinit var selectionInfo: TextView
    private lateinit var moreButton: Button
    private lateinit var pinned: ServerSession.Pinned
    private var previewBitmap: Bitmap? = null
    private var showingTrash = false
    private var selectionMode = false
    private var allPhotos: List<LibraryPhoto> = emptyList()
    private val selectedPhotos = linkedMapOf<String, LibraryPhoto>()
    private var selected: LibraryPhoto? = null
    private var selectedFile: java.io.File? = null
    private var accountScope = ""
    private var sessionToken = ""
    private var selectionGeneration = 0
    private var selectionJob: Job? = null
    private var loadJob: Job? = null
    private var shareJob: Job? = null
    private var mutationJob: Job? = null
    private val browseOnly get() = intent.getBooleanExtra("browseOnly", false)
    private val localCameraPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null && sameSession()) {
            setResult(RESULT_OK, result.data)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ServerSession.token.isEmpty()) { finish(); return }
        accountScope = app.accountScope
        sessionToken = ServerSession.token
        pinned = ServerSession.pin(app)
        watchServerSession()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(0, 30, 42)); setPadding(18, 18, 18, 18) }
        setContentView(root)
        applyImeInsetsTo(root)
        title = TextView(this).apply { text = if (browseOnly) "Thư viện ảnh trên NAS" else "Chọn ảnh từ thư viện"; textSize = 23f; setTextColor(Color.WHITE) }
        root.addView(title)
        val bar = LinearLayout(this)
        root.addView(bar)
        bar.addView(Button(this).apply { text = "‹ Quay lại"; isAllCaps = false; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        reloadButton = Button(this).apply { text = "↻ Tải lại"; isAllCaps = false; setOnClickListener { load() } }
        bar.addView(reloadButton, LinearLayout.LayoutParams(0, -2, 1f))
        moreButton = Button(this).apply { text = "⋮"; contentDescription = "Tác vụ thư viện NAS"; isAllCaps = false; setOnClickListener { showMenu() } }
        bar.addView(moreButton, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), -2))
        trashButton = Button(this).apply {
            text = "Thùng rác NAS"; isAllCaps = false
            visibility = if (browseOnly) View.VISIBLE else View.GONE
            setOnClickListener { if (mutationJob?.isActive != true && sameSession()) { showingTrash = !showingTrash; load() } }
        }
        if (!browseOnly) root.addView(Button(this).apply {
            text = "Ảnh Camera trên điện thoại"; isAllCaps = false
            setOnClickListener {
                if (sameSession()) localCameraPicker.launch(Intent(this@PhotoLibraryActivity, StandaloneCameraActivity::class.java)
                    .putExtra("pickForReport", true))
            }
        })
        preview = ImageView(this).apply { adjustViewBounds = true; contentDescription = "Ảnh đang chọn trong thư viện server" }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        selectionInfo = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 14f; setPadding(8, 8, 8, 8) }
        root.addView(selectionInfo)
        useButton = Button(this).apply { text = "Dùng ảnh này trong báo cáo"; isAllCaps = false; isEnabled = false; setOnClickListener { returnSelection() } }
        if (browseOnly) useButton.visibility = View.GONE
        val actions = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        root.addView(actions)
        actions.addView(useButton, LinearLayout.LayoutParams(-1, -2))
        shareButton = Button(this).apply {
            text = "Chia sẻ ảnh"; isAllCaps = false; isEnabled = false
            contentDescription = "Chia sẻ ảnh đang chọn"
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { shareSelection() }
        }
        shareButton.visibility = View.GONE
        manageButton = Button(this).apply {
            text = "Xóa trên NAS"; isAllCaps = false; isEnabled = false
            visibility = if (browseOnly) View.VISIBLE else View.GONE
            setOnClickListener { confirmMutation() }
        }
        manageButton.visibility = View.GONE
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        load()
    }

    private fun load() {
        if (mutationJob?.isActive == true || !sameSession()) return
        loadJob?.cancel()
        selectionJob?.cancel()
        shareJob?.cancel()
        shareJob = null
        val generation = ++selectionGeneration
        val trash = showingTrash
        selected = null
        selectedFile = null
        useButton.isEnabled = false
        shareButton.isEnabled = false
        shareButton.text = "Chia sẻ ảnh"
        manageButton.isEnabled = false
        manageButton.text = if (trash) "Khôi phục trên NAS" else "Xóa trên NAS"
        trashButton.text = if (trash) "Ảnh đang lưu trên NAS" else "Thùng rác NAS"
        if (browseOnly) title.text = if (trash) "Thùng rác ảnh trên NAS" else "Thư viện ảnh trên NAS"
        preview.visibility = if (trash) View.GONE else View.VISIBLE
        shareButton.visibility = if (trash) View.GONE else View.VISIBLE
        selectionInfo.text = if (trash) "Chọn ảnh để khôi phục. Ảnh trong Thùng rác không được đưa vào báo cáo." else "Chọn một ảnh để xem."
        clearPreview()
        list.replaceViews(TextView(this).apply { text = "Đang tải thư viện…"; setTextColor(Color.WHITE) })
        loadJob = lifecycleScope.launch {
            try {
                val photos = PhotoLibrary.listServerPhotos(app, pinned, trash)
                if (generation != selectionGeneration || !sameSession()) return@launch
                list.removeAllViews()
                if (photos.isEmpty()) list.addView(TextView(this@PhotoLibraryActivity).apply { text = if (trash) "Thùng rác NAS đang trống." else "Chưa có ảnh. Ảnh chụp mới sẽ tự đồng bộ vào đây."; setTextColor(Color.LTGRAY); setPadding(8, 16, 8, 16) })
                allPhotos = photos;selectedPhotos.clear()
                for (photo in photos) list.addView(photoRow(photo), LinearLayout.LayoutParams(-1, -2))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation != selectionGeneration || !sameSession()) return@launch
                list.replaceViews(TextView(this@PhotoLibraryActivity).apply { text = if (e is ServerSession.Failure && e.status == 404) "NAS hiện tại chưa hỗ trợ mục này. Cần cập nhật NAS lên bản mới." else "Chưa tải được thư viện: ${e.message}"; setTextColor(Color.WHITE) })
            }
        }
    }

    private fun photoRow(photo: LibraryPhoto) = Button(this).apply {
        val checked = photo.id in selectedPhotos
        text = (if (selectionMode) if (checked) "✓  " else "○  " else "") + "${photo.name}\n${photo.photographer} · ${photo.username}\n${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("vi", "VN")).format(java.util.Date(photo.capturedAt))}"
        isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        if (checked) setTextColor(Color.rgb(255, 216, 79))
        setOnClickListener { if (selectionMode) toggle(photo) else select(photo) }
    }

    private fun showMenu() {
        if (mutationJob?.isActive == true || !sameSession()) return
        val menu = PopupMenu(this, moreButton)
        if (selectionMode) {
            menu.menu.add("Chọn tất cả ảnh đang xem").setOnMenuItemClickListener { allPhotos.forEach { selectedPhotos[it.id] = it }; renderRows(); true }
            if (!showingTrash && selectedPhotos.isNotEmpty()) menu.menu.add("Chia sẻ ${selectedPhotos.size} ảnh").setOnMenuItemClickListener { shareSelection(); true }
            if (selectedPhotos.size == 1 && selectedPhotos.values.firstOrNull()?.let(::canManage) == true && !showingTrash) menu.menu.add("Chỉnh sửa ảnh").setOnMenuItemClickListener { editSelection(); true }
            if (selectedPhotos.isNotEmpty() && selectedPhotos.values.all(::canManage)) menu.menu.add(if (showingTrash) "Khôi phục ${selectedPhotos.size} ảnh" else "Xóa ${selectedPhotos.size} ảnh").setOnMenuItemClickListener { confirmMutation(); true }
            menu.menu.add("Bỏ chọn").setOnMenuItemClickListener { selectionMode = false; selectedPhotos.clear(); renderRows(); selectionInfo.text = if (showingTrash) "Chọn ảnh để khôi phục. Ảnh trong Thùng rác không được đưa vào báo cáo." else "Chọn một ảnh để xem."; true }
        } else {
            if (!showingTrash && selected != null) {
                menu.menu.add("Chia sẻ ảnh").setOnMenuItemClickListener { shareSelection(); true }
                if (canManage(selected!!)) {
                    menu.menu.add("Chỉnh sửa ảnh").setOnMenuItemClickListener { editSelection(); true }
                    menu.menu.add("Xóa ảnh trên NAS").setOnMenuItemClickListener { confirmMutation(); true }
                }
            } else if (showingTrash && selected?.let(::canManage) == true) {
                menu.menu.add("Khôi phục ảnh trên NAS").setOnMenuItemClickListener { confirmMutation(); true }
            }
            if (!showingTrash) menu.menu.add("Chọn nhiều ảnh").setOnMenuItemClickListener { selectionMode = true; selectedPhotos.clear(); renderRows(); selectionInfo.text = "Chạm ảnh để chọn nhiều ảnh; mở ⋮ để chia sẻ hoặc xóa."; true }
            menu.menu.add(if (showingTrash) "Ảnh đang lưu trên NAS" else "Thùng rác NAS").setOnMenuItemClickListener { if (sameSession()) { showingTrash = !showingTrash; load() }; true }
            menu.menu.add("↻ Tải lại").setOnMenuItemClickListener { load(); true }
        }
        menu.show()
    }

    private fun renderRows() { list.removeAllViews(); for (photo in allPhotos) list.addView(photoRow(photo), LinearLayout.LayoutParams(-1, -2)) }

    private fun toggle(photo: LibraryPhoto) {
        if (photo.id in selectedPhotos) selectedPhotos.remove(photo.id) else selectedPhotos[photo.id] = photo
        renderRows(); selectionInfo.text = "Đã chọn ${selectedPhotos.size} ảnh. Mở ⋮ để chia sẻ hoặc xóa."
    }

    private fun select(photo: LibraryPhoto) {
        if (mutationJob?.isActive == true || !sameSession()) return
        selectionJob?.cancel()
        shareJob?.cancel()
        shareJob = null
        val generation = ++selectionGeneration
        selected = null; selectedFile = null; useButton.isEnabled = false
        shareButton.isEnabled = false; shareButton.text = "Chia sẻ ảnh"
        manageButton.isEnabled = false
        clearPreview()
        selectionInfo.text = "${photo.name}\nNgười chụp: ${photo.photographer.ifBlank { photo.username }}"
        if (showingTrash) {
            selected = photo
            manageButton.isEnabled = canManage(photo)
            return
        }
        selectionJob = lifecycleScope.launch {
            var decoded: Bitmap? = null
            try {
                val file = PhotoLibrary.downloadServerPhoto(app, photo.id, accountScope, pinned)
                withContext(Dispatchers.IO) { decoded = PhotoRenderer.thumbnail(file.path, 1400) ?: error("Không đọc được ảnh.") }
                val bitmap = decoded ?: error("Không đọc được ảnh.")
                if (generation != selectionGeneration || !sameSession()) return@launch
                preview.setImageBitmap(bitmap)
                previewBitmap = bitmap; decoded = null
                selected = photo; selectedFile = file; useButton.isEnabled = true; shareButton.isEnabled = true
                manageButton.isEnabled = canManage(photo)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation != selectionGeneration || !sameSession()) return@launch
                selectedFile = null; clearPreview()
                Toast.makeText(this@PhotoLibraryActivity, "Chưa mở được ảnh: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                decoded?.recycle()
            }
        }
    }

    private fun shareSelection() {
        if (showingTrash || mutationJob?.isActive == true || !sameSession() || shareJob?.isActive == true || isFinishing || isDestroyed) return
        val photos = if (selectionMode) selectedPhotos.values.toList() else listOfNotNull(selected)
        if (photos.isEmpty()) return
        val photo = photos.singleOrNull()
        val file = if (photo != null) selectedFile ?: return else null
        val generation = selectionGeneration
        shareButton.isEnabled = false
        selectionInfo.text = if (photos.size > 1) "Đang chuẩn bị ${photos.size} ảnh để chia sẻ…" else "Đang chuẩn bị ảnh để chia sẻ…"
        shareJob = lifecycleScope.launch {
            try {
                val send = if (photos.size == 1 && !selectionMode) CameraPhotoShare.prepare(this@PhotoLibraryActivity, file!!, accountScope, photo!!.capturedAt) else {
                    val sources = photos.map { p -> PhotoLibrary.downloadServerPhoto(app, p.id, accountScope, pinned) to p.capturedAt }
                    CameraPhotoShare.prepareMultiple(this@PhotoLibraryActivity, sources, accountScope)
                }
                if (generation != selectionGeneration || (!selectionMode && (selected?.id != photo?.id || selectedFile != file)) ||
                    isFinishing || isDestroyed || !sameSession()) return@launch
                startActivity(Intent.createChooser(send, if (photos.size > 1) "Chia sẻ ảnh" else "Chia sẻ ảnh"))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation != selectionGeneration || isFinishing || isDestroyed || !sameSession()) return@launch
                Toast.makeText(this@PhotoLibraryActivity, "Chưa chia sẻ được ảnh: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                // An older request must never re-enable sharing for a new, still-loading selection.
                if (generation == selectionGeneration && (selectionMode || selected?.id == photo?.id && selectedFile == file) &&
                    !isFinishing && !isDestroyed && sameSession()) {
                    shareJob = null
                    shareButton.text = "Chia sẻ ảnh"
                    shareButton.isEnabled = !showingTrash && mutationJob?.isActive != true && (selectionMode && selectedPhotos.isNotEmpty() || selectedFile != null)
                }
            }
        }
    }

    private fun returnSelection() {
        if (browseOnly || showingTrash || !sameSession()) return
        val photo = selected ?: return
        val file = selectedFile ?: return
        setResult(RESULT_OK, Intent().putExtra("path", file.path).putExtra("id", photo.id).putExtra("name", photo.name).putExtra("photographer", photo.photographer).putExtra("username", photo.username).putExtra("capturedAt", photo.capturedAt).putExtra("locationName", photo.locationName).putExtra("lat", photo.lat).putExtra("lon", photo.lon).putExtra("accuracy", photo.accuracy).putExtra("note", photo.note))
        finish()
    }

    private fun canManage(photo: LibraryPhoto): Boolean = browseOnly && PhotoLibrary.canManagePhoto(
        photo, ServerSession.user?.optString("id").orEmpty(), ServerSession.user?.optString("role").orEmpty())

    private fun confirmMutation() {
        if (selectionMode) {
            val photos = selectedPhotos.values.toList()
            if (photos.isEmpty() || mutationJob?.isActive == true || !sameSession() || !photos.all(::canManage)) return
            val generation = selectionGeneration; val restore = showingTrash
            AlertDialog.Builder(this)
                .setTitle(if (restore) "Khôi phục ảnh đã chọn?" else "Xóa ảnh đã chọn trên NAS?")
                .setMessage(if (restore) "Khôi phục ${photos.size} ảnh vào thư viện NAS?" else "Chuyển ${photos.size} ảnh vào Thùng rác NAS? Bản trên điện thoại và các bản sao trong báo cáo vẫn được giữ.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton(if (restore) "Khôi phục" else "Chuyển vào Thùng rác") { _, _ ->
                    if (generation == selectionGeneration && showingTrash == restore && sameSession()) mutateMany(photos, generation, restore)
                }.show()
            return
        }
        val photo = selected ?: return
        if (!browseOnly || mutationJob?.isActive == true || !sameSession() || !canManage(photo)) return
        val generation = selectionGeneration
        val restore = showingTrash
        AlertDialog.Builder(this)
            .setTitle(if (restore) "Khôi phục ảnh trên NAS?" else "Xóa ảnh trên NAS?")
            .setMessage(if (restore) "Ảnh sẽ trở lại Thư viện NAS và có thể được chọn vào báo cáo." else "Ảnh sẽ được chuyển vào Thùng rác NAS và có thể khôi phục. Ảnh trên điện thoại và các bản sao đã đưa vào báo cáo vẫn được giữ.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton(if (restore) "Khôi phục" else "Chuyển vào Thùng rác") { _, _ ->
                if (generation == selectionGeneration && selected?.id == photo.id && showingTrash == restore && sameSession()) mutate(photo, generation, restore)
            }.show()
    }

    private fun mutateMany(photos: List<LibraryPhoto>, generation: Int, restore: Boolean) {
        if (mutationJob?.isActive == true || !photos.all(::canManage)) return
        shareJob?.cancel(); shareJob = null
        selectionInfo.text = if (restore) "Đang khôi phục ảnh…" else "Đang chuyển ảnh vào Thùng rác NAS…"
        reloadButton.isEnabled = false; trashButton.isEnabled = false
        mutationJob = lifecycleScope.launch {
            try {
                PhotoLibrary.setServerTrashBulk(app, pinned, photos, !restore)
                if (generation != selectionGeneration || !sameSession() || isFinishing || isDestroyed) return@launch
                Toast.makeText(this@PhotoLibraryActivity, if (restore) "Đã khôi phục ${photos.size} ảnh trên NAS." else "Đã chuyển ${photos.size} ảnh vào Thùng rác NAS.", Toast.LENGTH_LONG).show()
                selectionMode = false; selectedPhotos.clear(); mutationJob = null; load()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == selectionGeneration && sameSession() && !isFinishing && !isDestroyed) Toast.makeText(this@PhotoLibraryActivity, "Chưa cập nhật được ảnh: ${error.message}. Hãy tải lại để kiểm tra.", Toast.LENGTH_LONG).show()
            } finally {
                if (!isFinishing && !isDestroyed && sameSession()) {
                    mutationJob = null; reloadButton.isEnabled = true; trashButton.isEnabled = true
                    if (showingTrash) load()
                }
            }
        }
    }

    private fun editSelection() {
        val photo = if (selectionMode) selectedPhotos.values.singleOrNull() else selected
        if (photo == null || !canManage(photo) || !sameSession()) return
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val name = EditText(this).apply { setText(photo.name); hint = "Tên ảnh"; setSingleLine(true) }
        val note = EditText(this).apply { setText(photo.note); hint = "Ghi chú"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2; gravity = Gravity.TOP }
        fields.addView(name); fields.addView(note)
        AlertDialog.Builder(this).setTitle("Chỉnh sửa thông tin ảnh").setView(fields).setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu", null).create().also { dialog ->
                dialog.setOnShowListener { dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!sameSession()) return@setOnClickListener
                    val newName = name.text.toString().trim(); val newNote = note.text.toString().trim()
                    if (newName.isBlank()) { name.error = "Nhập tên ảnh"; return@setOnClickListener }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    lifecycleScope.launch {
                        try {
                            PhotoLibrary.editServerPhoto(app, pinned, photo, newName, newNote)
                            if (sameSession() && !isFinishing && !isDestroyed) { Toast.makeText(this@PhotoLibraryActivity, "Đã lưu thông tin ảnh trên NAS.", Toast.LENGTH_SHORT).show(); dialog.dismiss(); load() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (sameSession() && !isFinishing && !isDestroyed) { Toast.makeText(this@PhotoLibraryActivity, "Chưa lưu được: ${error.message}", Toast.LENGTH_LONG).show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                        }
                    }
                } }
                dialog.show()
            }
    }

    private fun mutate(photo: LibraryPhoto, generation: Int, restore: Boolean) {
        if (mutationJob?.isActive == true || !canManage(photo)) return
        shareJob?.cancel(); shareJob = null
        shareButton.isEnabled = false; useButton.isEnabled = false; manageButton.isEnabled = false
        reloadButton.isEnabled = false; trashButton.isEnabled = false
        manageButton.text = "Đang cập nhật…"
        mutationJob = lifecycleScope.launch {
            try {
                PhotoLibrary.setServerTrash(app, pinned, photo, !restore)
                if (generation != selectionGeneration || !sameSession() || isFinishing || isDestroyed) return@launch
                Toast.makeText(this@PhotoLibraryActivity, if (restore) "Đã khôi phục ảnh trên NAS." else "Đã chuyển ảnh vào Thùng rác NAS.", Toast.LENGTH_LONG).show()
                mutationJob = null
                load()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation != selectionGeneration || !sameSession() || isFinishing || isDestroyed) return@launch
                val message = if (error is ServerSession.Failure && error.status == 404) "Không tìm thấy ảnh có thể quản lý, hoặc NAS chưa hỗ trợ tính năng này. Hãy tải lại thư viện và kiểm tra bản cập nhật NAS." else "Chưa cập nhật được ảnh: ${error.message}. Hãy tải lại để kiểm tra."
                Toast.makeText(this@PhotoLibraryActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                if (!isFinishing && !isDestroyed && sameSession()) {
                    mutationJob = null
                    reloadButton.isEnabled = true; trashButton.isEnabled = true
                    manageButton.text = if (showingTrash) "Khôi phục trên NAS" else "Xóa trên NAS"
                    manageButton.isEnabled = selected?.let { canManage(it) } == true
                    shareButton.text = "Chia sẻ ảnh"
                    shareButton.isEnabled = !showingTrash && selectedFile != null
                    useButton.isEnabled = !showingTrash && selectedFile != null
                }
            }
        }
    }

    private fun clearPreview() { preview.setImageDrawable(null); previewBitmap?.recycle(); previewBitmap = null }
    override fun onDestroy() { super.onDestroy(); if (::preview.isInitialized) clearPreview() }

    private fun LinearLayout.replaceViews(view: android.view.View) { removeAllViews(); addView(view) }
    private fun sameSession(): Boolean {
        val valid = app.accountScope == accountScope && ServerSession.token == sessionToken && (!::pinned.isInitialized || ServerSession.matches(app, pinned))
        if (!valid) finish()
        return valid
    }
}
