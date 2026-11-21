package vn.vltelecom

import android.util.AtomicFile
import android.util.Base64
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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Only a 1 MiB chunk is encoded at a time. A new start request resumes at the server's durable offset. */
internal object VideoUploadQueue {
    private val uploadLock = Mutex()
    private fun directory(app: WorkApp, account: String) = File(CameraVideoArchive.accountDirectory(app, account), "video-library-pending")
    private fun entry(app: WorkApp, video: CameraVideo) = File(directory(app, video.account), "${video.id}.json")
    fun exists(app: WorkApp, video: CameraVideo) = entry(app, video).isFile

    /** Called on IO, after the original MP4 and camera receipt already exist. */
    fun enqueue(app: WorkApp, video: CameraVideo) {
        val target = entry(app, video)
        if (target.isFile || video.serverId.isNotBlank()) return
        check(app.accountScope == video.account && ServerSession.token.isNotEmpty()) { "Tài khoản đã thay đổi. Video vẫn được giữ trên máy." }
        val file = CameraVideoArchive.source(app, video)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val details = JSONObject().put("source", "camera-video").put("cameraArchiveId", video.id)
        if (CameraStampLayout.validCoordinates(video.lat, video.lon)) details.put("lat", video.lat).put("lon", video.lon)
        val name = "HP_Video_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(video.capturedAt))}_${video.id.take(8)}.mp4"
        val metadata = JSONObject().put("upload_id", video.id).put("account", video.account).put("path", file.path)
            .put("name", name).put("captured_at", video.capturedAt).put("duration_ms", video.durationMs)
            .put("size", file.length()).put("sha256", hash).put("details", details)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "Không tạo được hàng đợi video." }
        val atomic = AtomicFile(target); val output = atomic.startWrite()
        try { output.write(metadata.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    private fun read(file: File) = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))

    suspend fun flush(app: WorkApp): Int = uploadLock.withLock {
        if (ServerSession.token.isBlank()) return@withLock 0
        val session = ServerSession.pin(app)
        val entries = withContext(Dispatchers.IO) { directory(app, session.account)
            .listFiles { file -> file.extension == "json" && VideoUploadRules.validId(file.nameWithoutExtension) }
            .orEmpty().sortedBy { it.name } }
        var sent = 0
        for (pending in entries) {
            currentCoroutineContext().ensureActive()
            if (!ServerSession.matches(app, session)) break
            val video = withContext(Dispatchers.IO) { CameraVideoArchive.find(app, session.account, pending.nameWithoutExtension) }
                ?: continue // Preserve unknown receipts for recovery instead of deleting a user's recording.
            try {
                val metadata = withContext(Dispatchers.IO) { read(pending) }
                check(metadata.getString("account") == session.account && metadata.getString("upload_id") == video.id) {
                    "Hàng đợi video không thuộc tài khoản đang đăng nhập."
                }
                val source = withContext(Dispatchers.IO) { CameraVideoArchive.source(app, video) }
                val total = metadata.getLong("size")
                check(source.canonicalPath == File(metadata.getString("path")).canonicalPath && source.length() == total &&
                    total in 1..VideoUploadRules.MAX_BYTES && Regex("[a-f0-9]{64}").matches(metadata.getString("sha256"))) {
                    "Video chờ gửi đã thay đổi. Bản gốc vẫn được giữ trên điện thoại."
                }
                val startBody = JSONObject().put("upload_id", video.id).put("name", metadata.getString("name"))
                    .put("captured_at", metadata.getLong("captured_at")).put("duration_ms", metadata.getLong("duration_ms"))
                    .put("size", total).put("sha256", metadata.getString("sha256")).put("details", metadata.getJSONObject("details"))
                var start = ServerSession.apiPinned(app, session, "mobile/videos/start", startBody)
                check(start.optString("upload_id") == video.id) { "Server trả về sai phiên gửi video." }
                var offset = VideoUploadRules.resumeOffset(total, start.getLong("offset"))
                var completed = start.takeIf { it.optBoolean("complete") }
                if (completed == null) {
                    val serverChunk = start.optInt("chunk_size", VideoUploadRules.CHUNK_BYTES)
                    check(serverChunk in 1..VideoUploadRules.CHUNK_BYTES) { "Server yêu cầu kích thước video chưa được hỗ trợ." }
                    var conflicts = 0
                    while (offset < total) {
                        currentCoroutineContext().ensureActive()
                        check(ServerSession.matches(app, session)) { "Tài khoản hoặc kết nối đã thay đổi." }
                        val count = minOf(serverChunk, VideoUploadRules.nextChunk(total, offset))
                        val encoded = withContext(Dispatchers.IO) {
                            RandomAccessFile(source, "r").use { input ->
                                input.seek(offset)
                                val chunk = ByteArray(count)
                                input.readFully(chunk)
                                Base64.encodeToString(chunk, Base64.NO_WRAP)
                            }
                        }
                        try {
                            val acknowledgement = ServerSession.apiPinned(app, session, "mobile/videos/chunk",
                                JSONObject().put("upload_id", video.id).put("offset", offset).put("base64", encoded))
                            check(acknowledgement.optString("upload_id") == video.id) { "Server xác nhận sai phiên video." }
                            offset = VideoUploadRules.acknowledged(total, offset, count, acknowledgement.getLong("offset"))
                        } catch (error: ServerSession.Failure) {
                            if (error.status != 409 || ++conflicts > 2) throw error
                            // Another acknowledged retry can advance the durable offset; ask the server, never guess it.
                            start = ServerSession.apiPinned(app, session, "mobile/videos/start", startBody)
                            check(start.optString("upload_id") == video.id) { "Server trả về sai phiên gửi video." }
                            offset = VideoUploadRules.resumeOffset(total, start.getLong("offset"))
                            if (start.optBoolean("complete")) { completed = start; break }
                        }
                    }
                    if (completed == null) completed = ServerSession.apiPinned(app, session, "mobile/videos/finish", JSONObject().put("upload_id", video.id))
                }
                val receipt = completed ?: error("Server chưa xác nhận video hoàn chỉnh.")
                val serverId = receipt.optString("id")
                check(VideoUploadRules.complete(total, receipt.optLong("offset", -1), receipt.optBoolean("complete"), serverId)) {
                    "Server chưa xác nhận lưu đủ video. Video vẫn chờ gửi trên điện thoại."
                }
                check(ServerSession.matches(app, session)) { "Phiên đăng nhập đã thay đổi. Giữ video để kiểm tra lại." }
                withContext(NonCancellable + Dispatchers.IO) {
                    CameraVideoArchive.update(app, video) { current -> current.copy(serverQueued = true, serverId = serverId,
                        lastError = current.lastError.lines().filterNot { it.startsWith("Thư viện server:") }.joinToString("\n")) }
                    check(pending.delete() || !pending.exists()) { "Đã gửi video nhưng chưa dọn được hàng đợi." }
                }
                sent++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val detail = if (error is ServerSession.Failure && error.status == 404)
                    "NAS chưa hỗ trợ video. Cần cập nhật server; video vẫn chờ gửi trên điện thoại."
                else error.message ?: "Chưa gửi được video; giữ bản gốc trên điện thoại."
                withContext(NonCancellable + Dispatchers.IO) {
                    CameraVideoArchive.update(app, video) { current -> current.copy(lastError =
                        (current.lastError.lines().filter { it.isNotBlank() && !it.startsWith("Thư viện server:") } + "Thư viện server: $detail").joinToString("\n")) }
                }
                if (error is ServerSession.Failure) throw ServerSession.Failure(error.status, detail)
                throw IllegalStateException(detail, error)
            }
        }
        sent
    }
}
