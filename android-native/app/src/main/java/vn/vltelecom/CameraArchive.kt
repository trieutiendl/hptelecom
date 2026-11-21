package vn.vltelecom

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CameraPhoto(
    val id: String,
    val account: String,
    val path: String,
    val rawPath: String,
    val capturedAt: Long,
    val photographer: String,
    val username: String,
    val locationName: String,
    val address: String,
    val note: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val galleryUri: String = "",
    val serverQueued: Boolean = false,
    val lastError: String = "",
    val deletedAt: Long = 0L
)

/** Camera photos belong to an account, independently of every report and route. */
object CameraArchive {
    private val writes = Mutex()
    private val validId = Regex("[a-f0-9-]{36}")
    private val validAccount = Regex("[a-f0-9-]{65}")

    private fun accountDirectory(app: WorkApp, account: String): File {
        require(validAccount.matches(account)) { "Tài khoản chụp ảnh không hợp lệ." }
        return File(app.filesDir, "accounts/$account")
    }

    private fun directory(app: WorkApp, account: String) = File(accountDirectory(app, account), "camera-library")

    private fun recordFile(app: WorkApp, account: String, id: String): File {
        require(validId.matches(id)) { "Mã ảnh Camera không hợp lệ." }
        return File(directory(app, account), "$id.json")
    }

    private fun ownedPhoto(app: WorkApp, account: String, path: String): File {
        val file = File(path).canonicalFile
        val root = File(accountDirectory(app, account), "photos").canonicalFile
        check(file.toPath().startsWith(root.toPath()) && file.isFile && file.length() > 0) {
            "Không tìm thấy ảnh Camera đã lưu trên điện thoại."
        }
        return file
    }

    // Called immediately by the result callback, before any suspend point. The camera has
    // already saved its image; this small atomic receipt makes it recoverable after restart.
    fun recordCapture(app: WorkApp, account: String, photographer: String, username: String, data: Intent): CameraPhoto {
        val source = ownedPhoto(app, account, data.getStringExtra("path") ?: error("Camera chưa trả về ảnh."))
        val id = UUID.nameUUIDFromBytes(source.path.toByteArray(Charsets.UTF_8)).toString()
        read(app, account, id)?.let { return it }
        val raw = data.getStringExtra("raw").orEmpty().takeIf { it.isNotBlank() }
            ?.let { ownedPhoto(app, account, it).path }.orEmpty()
        val lat = data.getDoubleExtra("lat", Double.NaN)
        val lon = data.getDoubleExtra("lon", Double.NaN)
        check(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            "Ảnh chưa có tọa độ GPS hợp lệ. Ảnh gốc vẫn còn trên điện thoại."
        }
        val photo = CameraPhoto(
            id, account, source.path, raw,
            data.getLongExtra("time", source.lastModified()).takeIf { it > 0 } ?: System.currentTimeMillis(),
            photographer, username, data.getStringExtra("name").orEmpty(), data.getStringExtra("address").orEmpty(),
            data.getStringExtra("note").orEmpty(), lat, lon,
            data.getFloatExtra("accuracy", 0f).takeIf { it.isFinite() && it >= 0 } ?: 0f
        )
        write(app, photo)
        return photo
    }

    private fun all(app: WorkApp, account: String): List<CameraPhoto> = directory(app, account)
        .listFiles { file -> file.extension == "json" && validId.matches(file.nameWithoutExtension) }
        .orEmpty().mapNotNull { file -> read(app, account, file.nameWithoutExtension) }

    fun list(app: WorkApp, account: String): List<CameraPhoto> = all(app, account)
        .filter { it.deletedAt == 0L }.sortedByDescending { it.capturedAt }

    fun listTrash(app: WorkApp, account: String): List<CameraPhoto> = all(app, account)
        .filter { it.deletedAt > 0L }.sortedByDescending { it.deletedAt }

    /** Refresh one receipt after a gallery retry or sync without scanning the whole library. */
    fun find(app: WorkApp, account: String, id: String): CameraPhoto? = read(app, account, id)

    private fun read(app: WorkApp, account: String, id: String): CameraPhoto? = runCatching {
        val value = JSONObject(String(AtomicFile(recordFile(app, account, id)).readFully(), Charsets.UTF_8))
        check(value.getString("id") == id && value.getString("account") == account)
        CameraPhoto(id, account, value.getString("path"), value.optString("rawPath"), value.getLong("capturedAt"),
            value.getString("photographer"), value.optString("username"), value.optString("locationName"),
            value.optString("address"), value.optString("note"), value.getDouble("lat"), value.getDouble("lon"),
            value.optDouble("accuracy", 0.0).toFloat(), value.optString("galleryUri"),
            value.optBoolean("serverQueued"), value.optString("lastError"), value.optLong("deletedAt", 0L).coerceAtLeast(0L))
    }.getOrNull()

    private fun write(app: WorkApp, photo: CameraPhoto) {
        val file = recordFile(app, photo.account, photo.id)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "Không tạo được thư viện Camera." }
        val value = JSONObject().put("id", photo.id).put("account", photo.account).put("path", photo.path)
            .put("rawPath", photo.rawPath).put("capturedAt", photo.capturedAt).put("photographer", photo.photographer)
            .put("username", photo.username).put("locationName", photo.locationName).put("address", photo.address)
            .put("note", photo.note).put("lat", photo.lat).put("lon", photo.lon).put("accuracy", photo.accuracy.toDouble())
            .put("galleryUri", photo.galleryUri).put("serverQueued", photo.serverQueued).put("lastError", photo.lastError)
            .put("deletedAt", photo.deletedAt)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun source(app: WorkApp, photo: CameraPhoto): File = ownedPhoto(app, photo.account, photo.path)

    private fun requireOwnAccount(app: WorkApp, account: String) {
        check(app.accountScope == account && ServerSession.token.isNotEmpty() &&
            ServerSession.user?.optString("role")?.let { it != "viewer" } == true) {
            "Hãy đăng nhập tài khoản chụp ảnh để thay đổi thư viện."
        }
    }

    /** Only hide the app's receipt. Report copies, MediaStore and NAS are independent. */
    suspend fun trash(app: WorkApp, account: String, id: String): CameraPhoto = setTrashed(app, account, id, true)

    suspend fun restore(app: WorkApp, account: String, id: String): CameraPhoto = setTrashed(app, account, id, false)

    suspend fun updateNote(app: WorkApp, account: String, id: String, note: String): CameraPhoto = writes.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            requireOwnAccount(app, account)
            val photo = read(app, account, id) ?: error("Không tìm thấy ảnh trong thư viện của bạn.")
            check(photo.deletedAt == 0L) { "Hãy khôi phục ảnh trước khi chỉnh sửa." }
            val updated = photo.copy(note = note.trim().take(2000))
            requireOwnAccount(app, account)
            write(app, updated)
            updated
        }
    }

    private suspend fun setTrashed(app: WorkApp, account: String, id: String, trashed: Boolean): CameraPhoto = writes.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            requireOwnAccount(app, account)
            val photo = read(app, account, id) ?: error("Không tìm thấy ảnh trong thư viện của bạn.")
            if (!trashed) source(app, photo) // A missing original cannot be restored by its receipt alone.
            val updated = photo.copy(deletedAt = if (trashed) photo.deletedAt.takeIf { it > 0L } ?: System.currentTimeMillis() else 0L)
            requireOwnAccount(app, account)
            write(app, updated)
            updated
        }
    }

    fun waitingForServer(app: WorkApp, photo: CameraPhoto): Boolean = photo.serverQueued &&
        File(accountDirectory(app, photo.account), "photo-library-pending/${photo.id}.json").exists()

    /** Resume durable camera receipts after interruption, without requiring the gallery to open. */
    suspend fun recoverPending(app: WorkApp): Int {
        val account = app.accountScope
        if (ServerSession.token.isEmpty() || ServerSession.user?.optString("role")?.let { it != "viewer" } != true || !validAccount.matches(account)) return 0
        val albumAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val pending = withContext(Dispatchers.IO) {
            list(app, account).filter { !it.serverQueued || (it.galleryUri.isBlank() && albumAllowed) }
        }
        var failed = 0
        for (photo in pending) {
            currentCoroutineContext().ensureActive()
            if (app.accountScope != account || ServerSession.token.isEmpty() || ServerSession.user?.optString("role")?.let { it != "viewer" } != true) break
            try {
                if (publish(app, app, photo).lastError.isNotBlank()) failed++
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed++ } // A missing/damaged source must not block other receipts.
        }
        return failed
    }

    /** Retry only destinations without receipts. Keep the local source even when both succeed. */
    suspend fun publish(context: Context, app: WorkApp, original: CameraPhoto): CameraPhoto = writes.withLock {
        withContext(NonCancellable) {
            check(app.accountScope == original.account && ServerSession.token.isNotEmpty()) { "Tài khoản đã thay đổi. Ảnh vẫn lưu riêng cho người chụp." }
            // Always reload the durable receipt after taking the lock. A stale callback must
            // never recreate a deleted receipt or republish a photo moved to the app's trash.
            var photo = withContext(Dispatchers.IO) { read(app, original.account, original.id) ?: error("Không tìm thấy biên nhận ảnh Camera.") }
            if (photo.deletedAt > 0L) return@withContext photo
            val errors = mutableListOf<String>()
            val source = withContext(Dispatchers.IO) { source(app, photo) }
            if (photo.galleryUri.isBlank()) {
                try {
                    val uri: Uri = PhotoLibrary.save(context.applicationContext, source, photo.capturedAt, photographerName = photo.photographer)
                    photo = photo.copy(galleryUri = uri.toString())
                    withContext(Dispatchers.IO) { write(app, photo) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { errors.add("Album điện thoại: ${error.message}") }
            }
            if (!photo.serverQueued) {
                try {
                    check(app.accountScope == photo.account && ServerSession.token.isNotEmpty()) { "Hãy đăng nhập lại tài khoản chụp ảnh." }
                    val details = JSONObject().put("cameraArchiveId", photo.id).put("source", "camera")
                        .put("locationName", photo.locationName).put("address", photo.address)
                        .put("lat", photo.lat).put("lon", photo.lon).put("accuracy", photo.accuracy.toDouble()).put("note", photo.note)
                    PhotoLibrary.queueServerUpload(app, source, photo.capturedAt, details, accountScope = photo.account, uploadId = photo.id)
                    photo = photo.copy(serverQueued = true)
                    withContext(Dispatchers.IO) { write(app, photo) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { errors.add("Thư viện server: ${error.message}") }
            }
            photo = photo.copy(lastError = errors.joinToString("\n"))
            withContext(Dispatchers.IO) { write(app, photo) }
            photo
        }
    }
}
