package vn.vltelecom

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LibraryPhoto(val id: String, val name: String, val photographer: String, val username: String, val capturedAt: Long, val locationName: String = "", val lat: Double = 0.0, val lon: Double = 0.0, val accuracy: Float = 0f, val note: String = "", val ownerId: String = "", val deletedAt: Long = 0L)

object PhotoLibrary {
    fun photographer(): String {
        val user = ServerSession.user
        return user?.optString("display_name").orEmpty().ifBlank {
            user?.optString("username").orEmpty()
        }.ifBlank { "HP Telecom" }
    }

    suspend fun save(context: Context, source: File, capturedAt: Long = System.currentTimeMillis(), photographerName: String = photographer()): Uri = withContext(Dispatchers.IO) {
        check(source.isFile && source.length() > 0) { "Không tìm thấy ảnh để lưu vào thư viện." }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                "Cần quyền lưu ảnh vào thư viện trên phiên bản Android này."
            }
        }

        val person = photographerName
        val safePerson = safeName(person)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(capturedAt))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "HP_${safePerson}_${timestamp}_${UUID.randomUUID().toString().take(8)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, capturedAt)
            put(MediaStore.Images.Media.DESCRIPTION, "Người chụp: $person")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HP Telecom/$safePerson")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HP Telecom/$safePerson")
                check(directory.mkdirs() || directory.isDirectory) { "Không tạo được album HP Telecom." }
                put(MediaStore.Images.Media.DATA, File(directory, "HP_${safePerson}_${timestamp}_${UUID.randomUUID().toString().take(8)}.jpg").absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Không tạo được ảnh trong thư viện điện thoại.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Không mở được ảnh trong thư viện điện thoại.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    suspend fun queueServerUpload(app: WorkApp, source: File, capturedAt: Long, details: JSONObject = JSONObject(), accountScope: String = app.accountScope, uploadId: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        check(accountScope == app.accountScope && Regex("[a-f0-9]{32}-[a-f0-9]{32}").matches(accountScope)) { "Tài khoản đã thay đổi. Hãy mở lại Camera." }
        require(UUID.fromString(uploadId).toString() == uploadId) { "Mã ảnh chờ gửi không hợp lệ." }
        check(source.isFile && source.length() in 1..(10L * 1024 * 1024)) { "Ảnh cần gửi vào thư viện server phải dưới 10 MB." }
        val id = uploadId
        val directory = File(app.filesDir, "accounts/$accountScope/photo-library-pending").apply { check(mkdirs() || isDirectory) { "Không tạo được thư mục ảnh chờ gửi." } }
        val image = File(directory, "$id.jpg")
        val meta = File(directory, "$id.json")
        if (meta.isFile) return@withContext
        try {
            source.inputStream().use { input -> image.outputStream().use { input.copyTo(it) } }
            check(accountScope == app.accountScope) { "Tài khoản đã thay đổi. Ảnh vẫn được giữ trên điện thoại." }
            val name = "HP_" + safeName(details.optString("locationName").ifBlank { "Camera" }) + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(capturedAt)) + ".jpg"
            val atomic = AtomicFile(meta)
            val stream = atomic.startWrite()
            try {
                stream.write(JSONObject().put("capturedAt", capturedAt).put("name", name).put("details", details).toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) { atomic.failWrite(stream); throw e }
        } catch (e: Exception) {
            image.delete(); meta.delete(); throw e
        }
        if (accountScope == app.accountScope) AutoSync.wake()
    }

    suspend fun flushServerUploads(app: WorkApp): Int {
        if (ServerSession.token.isEmpty()) return 0
        val pinned = ServerSession.pin(app)
        val directory = File(app.filesDir, "accounts/${pinned.account}/photo-library-pending")
        val entries = withContext(Dispatchers.IO) { directory.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty() }
        var sent = 0
        var inTrash = 0
        for (meta in entries) {
            if (!ServerSession.matches(app, pinned)) break
            val values = withContext(Dispatchers.IO) { JSONObject(meta.readText(Charsets.UTF_8)) }
            val image = File(directory, meta.nameWithoutExtension + ".jpg")
            check(image.isFile && image.length() in 1..(10L * 1024 * 1024)) { "Ảnh chờ gửi lên thư viện không còn hợp lệ." }
            val encoded = withContext(Dispatchers.IO) { Base64.encodeToString(image.readBytes(), Base64.NO_WRAP) }
            try {
                val acknowledgement = ServerSession.apiPinned(app, pinned, "mobile/library", JSONObject().put("name", values.optString("name", image.name)).put("captured_at", values.optLong("capturedAt")).put("details", values.optJSONObject("details")?:JSONObject()).put("base64", encoded))
                check(Regex("[a-f0-9]{32}").matches(acknowledgement.optString("id"))) { "Server chưa xác nhận đã lưu ảnh. Ảnh vẫn chờ trên điện thoại." }
            } catch (error: ServerSession.Failure) {
                if (error.status == 409 && error.code == "PHOTO_IN_TRASH") {
                    // Never resurrect NAS trash during automatic backup or remove this durable receipt.
                    // Other photos must still upload even when one old receipt refers to NAS trash.
                    inTrash++
                    continue
                }
                throw error
            }
            if (!ServerSession.matches(app, pinned)) break
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { if (meta.delete()) image.delete() }
            sent++
        }
        if (inTrash > 0 && ServerSession.matches(app, pinned)) throw ServerSession.Failure(409,
            "$inTrash ảnh đang ở Thùng rác NAS. Hãy khôi phục trong Thư viện NAS nếu muốn đồng bộ lại; các ảnh khác đã tiếp tục gửi.", "PHOTO_IN_TRASH")
        return sent
    }

    suspend fun listServerPhotos(app: WorkApp, pinned: ServerSession.Pinned, trash: Boolean = false): List<LibraryPhoto> = withContext(Dispatchers.IO) {
        parseServerPhotos(ServerSession.apiPinned(app, pinned, if (trash) "mobile/library/trash" else "mobile/library", method = "GET", maxResponseBytes = 4 * 1024 * 1024))
    }

    internal fun parseServerPhotos(response: JSONObject): List<LibraryPhoto> {
        val rows = response.optJSONArray("photos") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.optString("id")
                if (Regex("[a-f0-9]{32}").matches(id)) {
                    val details = row.optJSONObject("details") ?: JSONObject()
                    add(LibraryPhoto(id, row.optString("name"), row.optString("photographer"), row.optString("username"), row.optLong("captured_at"), details.optString("locationName"), details.optDouble("lat"), details.optDouble("lon"), details.optDouble("accuracy").toFloat(), details.optString("note"), row.optString("owner"), row.optLong("deleted_at")))
                }
            }
        }
    }

    fun canManagePhoto(photo: LibraryPhoto, userId: String, role: String): Boolean =
        userId.isNotBlank() && (role == "admin" || role in setOf("staff", "dispatcher") && photo.ownerId.isNotBlank() && photo.ownerId == userId)

    suspend fun setServerTrash(app: WorkApp, pinned: ServerSession.Pinned, photo: LibraryPhoto, trashed: Boolean) {
        require(Regex("[a-f0-9]{32}").matches(photo.id)) { "Mã ảnh không hợp lệ." }
        check(ServerSession.matches(app, pinned)) { "Tài khoản đã thay đổi. Hãy mở lại thư viện." }
        val user = ServerSession.user
        check(canManagePhoto(photo, user?.optString("id").orEmpty(), user?.optString("role").orEmpty())) { "Chỉ người chụp hoặc quản trị được quản lý ảnh này." }
        val result = ServerSession.apiPinned(app, pinned, "mobile/library/${photo.id}/${if (trashed) "trash" else "restore"}", JSONObject())
        check(result.optString("id") == photo.id && result.has("trashed") && result.optBoolean("trashed") == trashed) { "Server chưa xác nhận thay đổi. Hãy tải lại thư viện." }
        if (!trashed && ServerSession.matches(app, pinned)) AutoSync.wake()
    }

    suspend fun setServerTrashBulk(app: WorkApp, pinned: ServerSession.Pinned, photos: List<LibraryPhoto>, trashed: Boolean) {
        require(photos.size in 1..500 && photos.map { it.id }.distinct().size == photos.size) { "Danh sách ảnh cần cập nhật không hợp lệ." }
        check(ServerSession.matches(app, pinned)) { "Tài khoản đã thay đổi. Hãy mở lại thư viện." }
        val user = ServerSession.user
        check(photos.all { canManagePhoto(it, user?.optString("id").orEmpty(), user?.optString("role").orEmpty()) }) {
            "Chỉ người chụp hoặc quản trị được quản lý các ảnh đã chọn."
        }
        val ids = org.json.JSONArray().apply { photos.forEach { put(it.id) } }
        val result = ServerSession.apiPinned(app, pinned, "mobile/library/bulk", JSONObject().put("ids", ids).put("trashed", trashed))
        check(result.optInt("count", -1) == photos.size && result.optBoolean("trashed") == trashed) {
            "Server chưa xác nhận tất cả ảnh. Hãy tải lại thư viện để kiểm tra."
        }
        if (!trashed && ServerSession.matches(app, pinned)) AutoSync.wake()
    }

    suspend fun editServerPhoto(app: WorkApp, pinned: ServerSession.Pinned, photo: LibraryPhoto, name: String, note: String): LibraryPhoto = withContext(Dispatchers.IO) {
        require(Regex("[a-f0-9]{32}").matches(photo.id)) { "Mã ảnh không hợp lệ." }
        check(ServerSession.matches(app, pinned)) { "Tài khoản đã thay đổi. Hãy mở lại thư viện." }
        val user = ServerSession.user
        check(canManagePhoto(photo, user?.optString("id").orEmpty(), user?.optString("role").orEmpty())) { "Chỉ người chụp hoặc quản trị được chỉnh sửa ảnh này." }
        val result = ServerSession.apiPinned(app, pinned, "mobile/library/${photo.id}", JSONObject().put("name", name.trim().take(150)).put("note", note.trim().take(2000)), method = "PUT")
        check(result.optString("id") == photo.id) { "Server chưa xác nhận thay đổi ảnh." }
        photo.copy(name = result.optString("name", photo.name), note = result.optJSONObject("details")?.optString("note").orEmpty())
    }

    suspend fun downloadServerPhoto(app: WorkApp, id: String, accountScope: String = app.accountScope, pinned: ServerSession.Pinned = ServerSession.pin(app)): File = withContext(Dispatchers.IO) {
        require(Regex("[a-f0-9]{32}").matches(id)) { "Mã ảnh không hợp lệ." }
        check(accountScope == app.accountScope && Regex("[a-f0-9]{32}-[a-f0-9]{32}").matches(accountScope)) { "Tài khoản đã thay đổi. Hãy mở lại thư viện." }
        check(pinned.account == accountScope) { "Tài khoản đã thay đổi. Hãy mở lại thư viện." }
        val bytes = ServerSession.bytesPinned(app, pinned, "mobile/library/$id")
        check(accountScope == app.accountScope && ServerSession.matches(app, pinned)) { "Tài khoản đã thay đổi. Hãy mở lại thư viện." }
        check(bytes.size in 1..(10 * 1024 * 1024)) { "Ảnh thư viện server vượt dung lượng cho phép." }
        val target = File(app.filesDir, "accounts/$accountScope/photos/library-$id.jpg")
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        target
    }

    private fun safeName(value: String): String = value
        .trim()
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim(' ', '.')
        .take(60)
        .ifBlank { "HP_Telecom" }
}
