package vn.vltelecom

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CameraVideo(
    val id: String, val account: String, val path: String, val capturedAt: Long,
    val photographer: String, val username: String, val durationMs: Long,
    val galleryUri: String = "", val serverQueued: Boolean = false, val lastError: String = "",
    val lat: Double? = null, val lon: Double? = null,
    val serverId: String = "", val pendingGalleryUri: String = ""
)

/** A durable local recording exists before either album publication or NAS upload is attempted. */
object CameraVideoArchive {
    private val recordsLock = Any()
    private val publishes = Mutex()
    private val accountPattern = Regex("[a-f0-9]{32}-[a-f0-9]{32}")
    internal fun accountDirectory(app: WorkApp, account: String): File {
        require(accountPattern.matches(account)) { "Tài khoản video không hợp lệ." }
        return File(app.filesDir, "accounts/$account")
    }
    private fun directory(app: WorkApp, account: String) = File(accountDirectory(app, account), "camera-videos")
    private fun recordFile(app: WorkApp, account: String, id: String): File {
        require(VideoUploadRules.validId(id)) { "Mã video không hợp lệ." }
        return File(directory(app, account), "$id.json")
    }
    private fun ownedFile(app: WorkApp, account: String, path: String): File {
        val root = File(accountDirectory(app, account), "photos").canonicalFile
        val file = File(path).canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file.extension.equals("mp4", true) &&
            file.isFile && file.length() in 1..VideoUploadRules.MAX_BYTES) {
            "Không tìm thấy video MP4 hợp lệ trên điện thoại (tối đa 64 MB)."
        }
        return file
    }
    fun source(app: WorkApp, video: CameraVideo): File = ownedFile(app, video.account, video.path)

    fun recordCapture(app: WorkApp, account: String, path: String, capturedAt: Long, durationMs: Long,
        photographer: String, username: String, lat: Double? = null, lon: Double? = null): CameraVideo = synchronized(recordsLock) {
        val file = ownedFile(app, account, path)
        require(capturedAt > 0 && durationMs in 1..86_400_000L) { "Thông tin thời gian video không hợp lệ." }
        val id = UUID.nameUUIDFromBytes(file.path.toByteArray(Charsets.UTF_8)).toString()
        find(app, account, id)?.let { return@synchronized it }
        val gps = CameraStampLayout.validCoordinates(lat, lon)
        CameraVideo(id, account, file.path, capturedAt, photographer, username, durationMs,
            lat = if (gps) lat else null, lon = if (gps) lon else null).also { write(app, it) }
    }

    fun list(app: WorkApp, account: String): List<CameraVideo> = directory(app, account)
        .listFiles { file -> file.extension == "json" && VideoUploadRules.validId(file.nameWithoutExtension) }
        .orEmpty().mapNotNull { find(app, account, it.nameWithoutExtension) }.sortedByDescending { it.capturedAt }

    fun find(app: WorkApp, account: String, id: String): CameraVideo? = synchronized(recordsLock) {
        runCatching {
            val value = JSONObject(String(AtomicFile(recordFile(app, account, id)).readFully(), Charsets.UTF_8))
            check(value.getString("id") == id && value.getString("account") == account)
            fun optionalDouble(key: String) = if (value.isNull(key)) null else value.optDouble(key).takeIf { it.isFinite() }
            CameraVideo(id, account, value.getString("path"), value.getLong("capturedAt"), value.getString("photographer"),
                value.optString("username"), value.getLong("durationMs"), value.optString("galleryUri"),
                value.optBoolean("serverQueued"), value.optString("lastError"), optionalDouble("lat"), optionalDouble("lon"),
                value.optString("serverId"), value.optString("pendingGalleryUri"))
        }.getOrNull()
    }

    private fun write(app: WorkApp, video: CameraVideo) {
        val file = recordFile(app, video.account, video.id)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "Không tạo được sổ video Camera." }
        val json = JSONObject().put("id", video.id).put("account", video.account).put("path", video.path)
            .put("capturedAt", video.capturedAt).put("photographer", video.photographer).put("username", video.username)
            .put("durationMs", video.durationMs).put("galleryUri", video.galleryUri).put("serverQueued", video.serverQueued)
            .put("lastError", video.lastError).put("lat", video.lat ?: JSONObject.NULL).put("lon", video.lon ?: JSONObject.NULL)
            .put("serverId", video.serverId).put("pendingGalleryUri", video.pendingGalleryUri)
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    internal fun update(app: WorkApp, original: CameraVideo, change: (CameraVideo) -> CameraVideo): CameraVideo = synchronized(recordsLock) {
        change(find(app, original.account, original.id) ?: original).also { write(app, it) }
    }
    private fun active(app: WorkApp, video: CameraVideo) = app.accountScope == video.account && ServerSession.token.isNotEmpty()
    private fun safeName(value: String) = value.trim().replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim(' ', '.').take(60).ifBlank { "HP_Telecom" }

    private fun saveAlbum(context: Context, app: WorkApp, original: CameraVideo): CameraVideo {
        var video = find(app, original.account, original.id) ?: original
        if (video.galleryUri.isNotBlank()) return video
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) check(ContextCompat.checkSelfPermission(context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "Cần quyền lưu video vào thư viện trên phiên bản Android này."
        }
        val person = safeName(video.username.ifBlank { video.photographer })
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(video.capturedAt))
        val name = "HP_${person}_${stamp}_${video.id.take(8)}.mp4"
        val resolver = context.contentResolver
        var uri = video.pendingGalleryUri.takeIf { it.startsWith("content://media/") }?.let(Uri::parse)
        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_TAKEN, video.capturedAt); put(MediaStore.Video.Media.DURATION, video.durationMs)
                put(MediaStore.Video.Media.DESCRIPTION, "Người quay: ${video.photographer}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/HP Telecom/$person")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "HP Telecom/$person")
                    check(folder.mkdirs() || folder.isDirectory) { "Không tạo được album video HP Telecom." }
                    put(MediaStore.Video.Media.DATA, File(folder, name).absolutePath)
                }
            }
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Không tạo được video trong thư viện.")
            val receipt = uri.toString()
            try { video = update(app, video) { it.copy(pendingGalleryUri = receipt) } }
            catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
        }
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> source(app, video).inputStream().use { it.copyTo(output, 64 * 1024) } }
                ?: error("Không mở được video trong thư viện.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) check(resolver.update(uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) > 0) {
                "Thư viện chưa xác nhận lưu xong video."
            }
            val receipt = uri.toString()
            return update(app, video) { it.copy(galleryUri = receipt, pendingGalleryUri = "") }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            update(app, video) { it.copy(pendingGalleryUri = "") }
            throw error
        }
    }

    suspend fun publish(context: Context, app: WorkApp, original: CameraVideo): CameraVideo = publishes.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            check(active(app, original)) { "Tài khoản đã thay đổi. Video vẫn lưu riêng cho người quay." }
            var video = find(app, original.account, original.id) ?: original
            val errors = mutableListOf<String>()
            var newlyQueued = false
            try { video = saveAlbum(context.applicationContext, app, video) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { errors.add("Album điện thoại: ${error.message}") }
            if (video.serverId.isBlank()) {
                try {
                    check(active(app, video)) { "Hãy đăng nhập lại tài khoản quay video." }
                    val alreadyQueued = VideoUploadQueue.exists(app, video)
                    VideoUploadQueue.enqueue(app, video)
                    newlyQueued = !alreadyQueued
                    video = update(app, video) { it.copy(serverQueued = true) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { errors.add("Thư viện server: ${error.message}") }
            }
            val previousServerError = video.lastError.lines().firstOrNull { it.startsWith("Thư viện server:") }
            if (video.serverQueued && video.serverId.isBlank() && errors.none { it.startsWith("Thư viện server:") } && previousServerError != null)
                errors.add(previousServerError)
            update(app, video) { current -> current.copy(lastError = errors.filterNot {
                current.serverId.isNotBlank() && it.startsWith("Thư viện server:")
            }.joinToString("\n")) }.also {
                // Recovery must not schedule itself forever when album permission is missing.
                if (newlyQueued && active(app, it) && it.serverId.isBlank()) AutoSync.wake()
            }
        }
    }

    fun waitingForServer(app: WorkApp, video: CameraVideo): Boolean {
        val latest = find(app, video.account, video.id) ?: video
        return latest.serverQueued && latest.serverId.isBlank()
    }

    suspend fun recoverPending(app: WorkApp): Int {
        val account = app.accountScope
        if (ServerSession.token.isEmpty() || !accountPattern.matches(account)) return 0
        val videos = withContext(Dispatchers.IO) { list(app, account) }
        var incomplete = 0
        for (video in videos) {
            if (!active(app, video)) break
            if (video.galleryUri.isBlank() || !video.serverQueued || video.serverId.isBlank() && !VideoUploadQueue.exists(app, video)) {
                val updated = publish(app, app, video)
                if (updated.galleryUri.isBlank() || !updated.serverQueued) incomplete++
            }
        }
        return incomplete
    }

    suspend fun flushServerUploads(app: WorkApp): Int = VideoUploadQueue.flush(app)
}
