package vn.vltelecom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.CamcorderProfile
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** Standalone video mode. The preview has no watermark that the MP4 does not actually contain. */
@Suppress("DEPRECATION")
class VideoCaptureActivity : AppCompatActivity(), SurfaceHolder.Callback, LocationListener {
    private enum class State { IDLE, STARTING, RECORDING, FINALIZING }
    private data class Recording(val recorder: MediaRecorder, val camera: Camera, val file: File,
        val capturedAt: Long, val startedElapsed: Long, val location: Location?, val generation: Int)
    private val app get() = application as WorkApp
    private var account = ""
    private var photographer = ""
    private var username = ""
    private var state = State.IDLE
    private var camera: Camera? = null
    private var cameraId = -1
    private var facing = Camera.CameraInfo.CAMERA_FACING_BACK
    private var profile: CamcorderProfile? = null
    private var rotationHint = 90
    private var previewRatio = 9f / 16f
    private var foreground = false
    private var ready = false
    private var askedPermissions = false
    private var generation = 0
    private var recording: Recording? = null
    private var fix: Location? = null
    private lateinit var surface: SurfaceView
    private lateinit var previewArea: FrameLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var shutter: ImageButton
    private lateinit var flash: ImageButton
    private lateinit var switch: ImageButton
    private lateinit var library: Button
    private lateinit var photoMode: Button
    private lateinit var timer: TextView
    private lateinit var quality: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    // Once stopped, a clip can finish its durable receipt even if this Activity is destroyed.
    private val finalizer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gps by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!foreground || !sameAccount()) return@registerForActivityResult
        if (has(Manifest.permission.CAMERA)) openCamera()
        if (!has(Manifest.permission.CAMERA) || !has(Manifest.permission.RECORD_AUDIO))
            status.text = "Cần quyền Máy ảnh và Micro để quay video có âm thanh. Nhấn nút quay để cấp quyền."
        refreshControls()
    }
    private val tick = object : Runnable {
        override fun run() {
            val active = recording ?: return
            if (state != State.RECORDING) return
            val elapsed = (SystemClock.elapsedRealtime() - active.startedElapsed).coerceAtLeast(0)
            timer.text = "● ${clock(elapsed)} / 02:00"
            if (elapsed >= MAX_DURATION_MS) stopRecording("Đã đạt giới hạn 2 phút")
            else handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ServerSession.token.isBlank() || app.accountScope == "locked") {
            startActivity(Intent(this, ServerActivity::class.java)); finish(); return
        }
        account = savedInstanceState?.getString("videoAccount") ?: app.accountScope
        photographer = savedInstanceState?.getString("videoPhotographer") ?: PhotoLibrary.photographer()
        username = savedInstanceState?.getString("videoUsername") ?: ServerSession.user?.optString("username").orEmpty()
        facing = savedInstanceState?.getInt("videoFacing") ?: Camera.CameraInfo.CAMERA_FACING_BACK
        askedPermissions = savedInstanceState?.getBoolean("videoAskedPermissions") ?: false
        if (!sameAccount()) { finish(); return }
        watchServerSession()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        window.statusBarColor = Color.WHITE; window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        setContentView(root)
        applyImeInsetsTo(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))
        header.addView(icon("back", "Quay lại chế độ ảnh") { leave() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        flash = icon("flash-off", "Bật đèn quay video") { toggleLight() }
        header.addView(flash, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER }
        brand.addView(ImageView(this).apply { setImageResource(R.drawable.hp_icon); contentDescription = "HP Telecom" }, LinearLayout.LayoutParams(dp(32), dp(32)))
        brand.addView(label("Video", 18f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), 0, 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -1, 1f))
        switch = icon("switch", "Đổi camera trước / sau") { switchCamera() }
        header.addView(switch, LinearLayout.LayoutParams(dp(48), dp(48)))
        timer = label("00:00 / 02:00", 22f).apply { gravity = Gravity.CENTER; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(182, 27, 35)) }
        root.addView(timer, LinearLayout.LayoutParams(-1, dp(34)))
        quality = label("Video có âm thanh · tối đa 2 phút / 64 MB", 12f).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(7)) }
        root.addView(quality, LinearLayout.LayoutParams(-1, -2))
        previewArea = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(previewArea, LinearLayout.LayoutParams(-1, 0, 1f))
        previewFrame = FrameLayout(this); previewArea.addView(previewFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        surface = SurfaceView(this); previewFrame.addView(surface, FrameLayout.LayoutParams(-1, -1)); surface.holder.addCallback(this)
        previewArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        footer.addView(label("Tự lưu Thư viện và xếp hàng gửi Server", 14f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(220, 243, 250)) }
        }, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) }
        footer.addView(controls, LinearLayout.LayoutParams(-1, -2))
        library = Button(this).apply { text = "Thư viện"; isAllCaps = false; textSize = 14f; setTextColor(Color.rgb(23, 47, 58)); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { if (state == State.IDLE && sameAccount()) startActivity(Intent(this@VideoCaptureActivity, StandaloneCameraActivity::class.java).putExtra("browseOnly", true)) }
        }
        controls.addView(library, LinearLayout.LayoutParams(0, dp(64), 1f))
        shutter = ImageButton(this).apply { contentDescription = "Bắt đầu quay video"; setOnClickListener { if (state == State.RECORDING) stopRecording("Đã dừng quay") else if (state == State.IDLE) startRecording() } }
        controls.addView(shutter, LinearLayout.LayoutParams(dp(82), dp(82)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        photoMode = Button(this).apply { text = "Ảnh"; isAllCaps = false; textSize = 14f; setTextColor(Color.rgb(23, 47, 58)); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { leave() } }
        controls.addView(photoMode, LinearLayout.LayoutParams(0, dp(64), 1f))
        status = label("Video lưu theo tài khoản $photographer", 12f).apply { gravity = Gravity.CENTER; maxLines = 3 }
        footer.addView(status, LinearLayout.LayoutParams(-1, -2))
        ready = true; refreshControls()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(24, 46, 56)) }
    private fun icon(kind: String, name: String, action: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(CameraControlIcon(kind)); contentDescription = name; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() }
    }
    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun sameAccount() = account.isNotBlank() && account != "locked" && account == app.accountScope && ServerSession.token.isNotBlank() && ServerSession.user?.optString("role") != "viewer"
    private fun clock(milliseconds: Long): String = String.format(Locale.US, "%02d:%02d", milliseconds / 60000, milliseconds / 1000 % 60)
    private fun needPermissions() {
        val required = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = required.filterNot { has(it) }
        if (missing.isNotEmpty()) { askedPermissions = true; permissions.launch(missing.toTypedArray()) }
    }

    private fun selectProfile(id: Int, parameters: Camera.Parameters): CamcorderProfile {
        val sizes = parameters.supportedVideoSizes ?: parameters.supportedPreviewSizes
        return listOf(CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_LOW)
            .mapNotNull { quality -> runCatching { if (CamcorderProfile.hasProfile(id, quality)) CamcorderProfile.get(id, quality) else null }.getOrNull() }
            .firstOrNull { candidate -> candidate.videoFrameWidth > 0 && candidate.videoFrameHeight > 0 &&
                candidate.videoFrameWidth <= 1280 && candidate.videoFrameHeight <= 720 &&
                sizes.any { it.width == candidate.videoFrameWidth && it.height == candidate.videoFrameHeight } }
            ?: error("Camera này chưa có cấu hình quay video phù hợp.")
    }

    private fun openCamera() {
        if (!ready || !foreground || state != State.IDLE || camera != null || !sameAccount() || !surface.holder.surface.isValid || !has(Manifest.permission.CAMERA)) return
        try {
            cameraId = (0 until Camera.getNumberOfCameras()).firstOrNull { val info = Camera.CameraInfo(); Camera.getCameraInfo(it, info); info.facing == facing }
                ?: if (Camera.getNumberOfCameras() > 0) 0 else error("Không tìm thấy camera.")
            val opened = Camera.open(cameraId); camera = opened
            val info = Camera.CameraInfo(); Camera.getCameraInfo(cameraId, info); facing = info.facing
            val degrees = when (windowManager.defaultDisplay.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val front = facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            val display = if (front) (360 - (info.orientation + degrees) % 360) % 360 else (info.orientation - degrees + 360) % 360
            rotationHint = if (front) (info.orientation + degrees) % 360 else (info.orientation - degrees + 360) % 360
            opened.setDisplayOrientation(display)
            val parameters = opened.parameters
            val selected = selectProfile(cameraId, parameters); profile = selected
            val ratio = selected.videoFrameWidth.toFloat() / selected.videoFrameHeight
            val preview = parameters.supportedPreviewSizes.minByOrNull { kotlin.math.abs(it.width.toFloat() / it.height - ratio) * 10000 + kotlin.math.abs(it.width - selected.videoFrameWidth) } ?: error("Không có khung xem trước.")
            parameters.setPreviewSize(preview.width, preview.height); parameters.setRecordingHint(true)
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in (parameters.supportedFocusModes ?: emptyList())) parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            opened.parameters = parameters
            previewRatio = if (display % 180 == 90) preview.height.toFloat() / preview.width else preview.width.toFloat() / preview.height
            fitPreview(); opened.setPreviewDisplay(surface.holder); opened.startPreview(); refreshControls()
        } catch (error: Exception) { closePreview(); if (ready) status.text = "Chưa mở được video: ${error.message}" }
    }

    private fun fitPreview() {
        if (!::previewArea.isInitialized || previewArea.width <= 0 || previewArea.height <= 0) return
        val width = kotlin.math.min(previewArea.width.toFloat(), previewArea.height * previewRatio).toInt().coerceAtLeast(1)
        val height = (width / previewRatio).toInt().coerceAtLeast(1)
        if (previewFrame.layoutParams.width != width || previewFrame.layoutParams.height != height)
            previewFrame.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    private fun refreshControls() {
        if (!ready) return
        val idle = state == State.IDLE
        val parameters = if (idle) runCatching { camera?.parameters }.getOrNull() else null
        val modes = parameters?.supportedFlashModes ?: emptyList()
        val light = Camera.Parameters.FLASH_MODE_TORCH in modes && Camera.Parameters.FLASH_MODE_OFF in modes
        flash.isEnabled = idle && light; flash.alpha = if (flash.isEnabled) 1f else .35f
        val lit = parameters?.flashMode == Camera.Parameters.FLASH_MODE_TORCH
        flash.setImageDrawable(CameraControlIcon(if (lit) "flash" else "flash-off")); flash.contentDescription = if (!light) "Đèn không khả dụng khi đang quay hoặc camera không hỗ trợ" else if (lit) "Tắt đèn quay video" else "Bật đèn quay video"
        val opposite = if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        switch.isEnabled = idle && (0 until Camera.getNumberOfCameras()).any { val info = Camera.CameraInfo(); Camera.getCameraInfo(it, info); info.facing == opposite }
        switch.alpha = if (switch.isEnabled) 1f else .35f
        library.isEnabled = idle; photoMode.isEnabled = state != State.STARTING
        shutter.isEnabled = idle || state == State.RECORDING
        shutter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), Color.rgb(196, 35, 42)) }
        shutter.setPadding(dp(if (state == State.RECORDING) 24 else 12), dp(if (state == State.RECORDING) 24 else 12), dp(if (state == State.RECORDING) 24 else 12), dp(if (state == State.RECORDING) 24 else 12))
        shutter.setImageDrawable(GradientDrawable().apply { shape = if (this@VideoCaptureActivity.state == State.RECORDING) GradientDrawable.RECTANGLE else GradientDrawable.OVAL; cornerRadius = dp(5).toFloat(); setColor(Color.rgb(210, 36, 45)) })
        shutter.contentDescription = if (state == State.RECORDING) "Dừng và lưu video" else "Bắt đầu quay video"
        val format = profile?.let { if (it.videoFrameHeight >= 720) "HD 720p" else "${it.videoFrameHeight}p" } ?: "Video"
        quality.text = "$format · ${if (has(Manifest.permission.RECORD_AUDIO)) "âm thanh bật" else "cần quyền Micro"} · tối đa 2 phút / 64 MB"
    }

    private fun switchCamera() {
        if (state != State.IDLE || !switch.isEnabled) return
        closePreview(); facing = if (facing == Camera.CameraInfo.CAMERA_FACING_BACK) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK; openCamera()
    }
    private fun toggleLight() {
        if (state != State.IDLE || !flash.isEnabled) return
        runCatching { val c = camera ?: return; val p = c.parameters; p.flashMode = if (p.flashMode == Camera.Parameters.FLASH_MODE_TORCH) Camera.Parameters.FLASH_MODE_OFF else Camera.Parameters.FLASH_MODE_TORCH; c.parameters = p }
            .onFailure { status.text = "Camera chưa bật được đèn." }
        refreshControls()
    }

    private fun startRecording() {
        if (state != State.IDLE || !foreground || !sameAccount()) return
        if (!has(Manifest.permission.CAMERA) || !has(Manifest.permission.RECORD_AUDIO)) { needPermissions(); return }
        if (camera == null) openCamera()
        val activeCamera = camera ?: return
        val selected = profile ?: return
        val directory = File(app.filesDir, "accounts/$account/photos")
        val output = File(directory, "video-${UUID.randomUUID()}.mp4")
        val next = MediaRecorder()
        state = State.STARTING; status.text = "Đang chuẩn bị quay…"; refreshControls()
        try {
            check(directory.mkdirs() || directory.isDirectory) { "Không tạo được thư mục video." }
            check(StatFs(directory.path).availableBytes >= MAX_FILE_BYTES + 16L * 1024 * 1024) { "Cần ít nhất 80 MB trống để quay video." }
            activeCamera.unlock(); next.setCamera(activeCamera)
            next.setAudioSource(MediaRecorder.AudioSource.MIC); next.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setVideoEncoder(MediaRecorder.VideoEncoder.H264); next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setVideoSize(selected.videoFrameWidth, selected.videoFrameHeight)
            next.setVideoFrameRate(selected.videoFrameRate.coerceIn(15, 30))
            next.setVideoEncodingBitRate(selected.videoBitRate.coerceIn(350_000, 2_500_000))
            next.setAudioChannels(1); next.setAudioSamplingRate(44100); next.setAudioEncodingBitRate(96_000)
            next.setOrientationHint(rotationHint); next.setMaxDuration(MAX_DURATION_MS.toInt()); next.setMaxFileSize(MAX_FILE_BYTES - 512 * 1024)
            next.setOutputFile(output.path); next.setPreviewDisplay(surface.holder.surface)
            val currentGeneration = ++generation
            next.setOnInfoListener { active, what, _ ->
                if (recording?.recorder === active && recording?.generation == currentGeneration && state == State.RECORDING &&
                    (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED))
                    stopRecording(if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) "Đã đạt giới hạn 2 phút" else "Đã đạt giới hạn 64 MB")
            }
            next.setOnErrorListener { active, _, _ -> if (recording?.recorder === active && recording?.generation == currentGeneration) stopRecording("Camera gặp lỗi, đang kiểm tra video") }
            next.prepare()
            check(sameAccount()) { "Tài khoản đã thay đổi." }
            next.start()
            val location = fix?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= 120_000_000_000L }?.let { Location(it) }
            recording = Recording(next, activeCamera, output, System.currentTimeMillis(), SystemClock.elapsedRealtime(), location, currentGeneration)
            state = State.RECORDING; status.text = "Đang quay · nhấn nút vuông để dừng và lưu"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            refreshControls(); handler.post(tick)
        } catch (error: Exception) {
            runCatching { next.reset() }; runCatching { next.release() }; runCatching { activeCamera.lock() }
            state = State.IDLE; recording = null; closePreview()
            // A start failure has no completed clip or user media receipt.
            if (output.isFile && output.length() == 0L) output.delete()
            status.text = "Chưa bắt đầu quay: ${error.message}"; refreshControls(); if (foreground) openCamera()
        }
    }

    private fun stopRecording(reason: String) {
        if (state != State.RECORDING) return
        val active = recording ?: return
        recording = null; camera = null; state = State.FINALIZING
        handler.removeCallbacks(tick); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "$reason · đang lưu video…"; refreshControls()
        finalizer.launch {
            var receipt: CameraVideo? = null
            try {
                val recorded = withContext(Dispatchers.IO) {
                    var stopError: Exception? = null
                    try { active.recorder.stop() } catch (error: Exception) { stopError = error }
                    finally { releaseRecorder(active) }
                    val metadata = MediaMetadataRetriever()
                    val duration = try {
                        check(active.file.isFile && active.file.length() in 1..MAX_FILE_BYTES) { "Video chưa được lưu hoàn chỉnh." }
                        metadata.setDataSource(active.file.path)
                        check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "Video chưa có khung hình hợp lệ." }
                        (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0).also { check(it > 0) { stopError?.message ?: "Video quá ngắn. Hãy quay lại ít nhất 1 giây." } }
                    } finally { metadata.release() }
                    CameraVideoArchive.recordCapture(app, account, active.file.path, active.capturedAt, duration, photographer, username, active.location?.latitude, active.location?.longitude)
                }
                receipt = recorded
                if (active.generation == generation) state = State.IDLE
                if (alive() && active.generation == generation) {
                    timer.text = "${clock(recorded.durationMs)} / 02:00"; status.text = "Đã lưu video · đang lưu album và xếp hàng gửi Server…"
                    refreshControls(); if (foreground) openCamera()
                }
                if (sameAccount()) {
                    val saved = CameraVideoArchive.publish(applicationContext, app, recorded)
                    if (alive() && active.generation == generation)
                        status.text = if (saved.lastError.isBlank()) "Đã lưu video vào Thư viện · Server chờ đồng bộ" else "Video còn phần chưa sao lưu · mở Thư viện để thử lại"
                }
            } catch (error: Exception) {
                if (active.generation == generation) state = State.IDLE
                if (alive() && active.generation == generation) {
                    status.text = if (receipt != null) "Video đã lưu · sao lưu chưa hoàn tất" else "Chưa lưu được video hoàn chỉnh. Hãy quay lại."
                    refreshControls(); if (foreground) { openCamera(); AlertDialog.Builder(this@VideoCaptureActivity)
                        .setMessage(if (receipt != null) "Video vẫn giữ trong Thư viện. Chưa hoàn tất sao lưu: ${error.message}" else "Video chưa có trong Thư viện: ${error.message}\nTệp đã ghi vẫn giữ trên điện thoại. Hãy quay lại.")
                        .setPositiveButton("Đóng", null).show() }
                }
            }
        }
    }

    private fun releaseRecorder(active: Recording) {
        runCatching { active.recorder.setOnInfoListener(null); active.recorder.setOnErrorListener(null) }
        runCatching { active.recorder.reset() }; runCatching { active.recorder.release() }
        runCatching { active.camera.lock() }; runCatching { active.camera.stopPreview() }; runCatching { active.camera.release() }
    }
    private fun alive() = !isFinishing && !isDestroyed && sameAccount()
    private fun leave() { if (state == State.RECORDING) stopRecording("Đã dừng quay"); if (state != State.STARTING) finish() }
    private fun closePreview() {
        if (state == State.RECORDING || state == State.FINALIZING) return
        val c = camera; camera = null
        runCatching { c?.stopPreview() }; runCatching { c?.release() }
    }
    private fun startLocation() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        runCatching { gps.removeUpdates(this); fix = null
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { gps.isProviderEnabled(it) }.forEach { gps.requestLocationUpdates(it, 1000, 1f, this) }
        }
    }
    override fun onLocationChanged(location: Location) { if (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos <= 30_000_000_000L) fix = Location(location) }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun surfaceCreated(holder: SurfaceHolder) { openCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (state == State.RECORDING) stopRecording("Đã dừng quay"); closePreview() }
    override fun onResume() { super.onResume(); foreground = true; if (account.isNotBlank() && !sameAccount()) { finish(); return }; if (ready) { if (has(Manifest.permission.CAMERA)) openCamera(); if (!askedPermissions && (!has(Manifest.permission.CAMERA) || !has(Manifest.permission.RECORD_AUDIO))) needPermissions(); startLocation() } }
    override fun onPause() { foreground = false; if (state == State.RECORDING) stopRecording("Đã dừng khi rời màn hình quay"); closePreview(); runCatching { gps.removeUpdates(this) }; super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(tick); if (state == State.RECORDING) stopRecording("Đã dừng quay"); closePreview(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("videoAccount", account); outState.putString("videoPhotographer", photographer); outState.putString("videoUsername", username); outState.putInt("videoFacing", facing); outState.putBoolean("videoAskedPermissions", askedPermissions); super.onSaveInstanceState(outState) }

    private companion object {
        const val MAX_DURATION_MS = 120_000L
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
