package vn.vltelecom

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Share saved JPEG/MP4 through Android's chooser; never address or send to an app directly. */
object CameraPhotoShare {
    private val exports = Mutex()
    private val accountPattern = Regex("[a-f0-9]{32}-[a-f0-9]{32}")
    private val exportPattern = Regex("HP_Camera_[0-9]{8}_[0-9]{6}_[a-f0-9-]{36}\\.(jpg|mp4)")
    private val temporaryPattern = Regex("\\.prepare-[a-f0-9-]{36}\\.tmp")
    private const val RETAIN_EXPORT_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETAIN_TEMP_MS = 24L * 60 * 60 * 1000
    private const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 64L * 1024 * 1024
    private const val CLEANUP_LIMIT = 256

    /**
     * Returns a raw ACTION_SEND Intent. The Activity should verify its selection/session again,
     * then call startActivity(Intent.createChooser(intent, "Chia sẻ ảnh qua")).
     * All copying and validation run on IO; the source image and its watermark are never changed.
     */
    suspend fun prepare(context: Context, source: File, accountScope: String, capturedAt: Long): Intent {
        val app = context.applicationContext as WorkApp
        val token = ServerSession.token
        check(accountPattern.matches(accountScope)) { "Hãy đăng nhập lại trước khi chia sẻ ảnh." }
        fun sameSession() {
            check(token.isNotBlank() && token == ServerSession.token && accountScope == app.accountScope) {
                "Phiên đăng nhập đã thay đổi. Hãy mở lại ảnh để chia sẻ."
            }
        }
        sameSession()
        return withContext(Dispatchers.IO) {
            exports.withLock {
                sameSession()
                currentCoroutineContext().ensureActive()
                val account = File(app.filesDir, "accounts/$accountScope").canonicalFile
                val image = source.canonicalFile
                check(image.toPath().startsWith(account.toPath()) &&
                    Files.isRegularFile(image.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "Ảnh này không thuộc thư viện của tài khoản đang đăng nhập."
                }
                val size = image.length()
                val modified = image.lastModified()
                val video = image.extension.equals("mp4", true)
                val limit = if (video) MAX_VIDEO_BYTES else MAX_PHOTO_BYTES
                check(size in 1..limit) { if (video) "Không đọc được video, hoặc video lớn hơn 64 MB." else "Không đọc được ảnh, hoặc ảnh lớn hơn 10 MB." }
                checkMedia(image, video)

                val cache = app.cacheDir.canonicalFile
                val root = File(cache, "exports/camera-share")
                check(root.canonicalFile.toPath().startsWith(cache.toPath()) && !Files.isSymbolicLink(root.toPath())) {
                    "Không tạo được thư mục chia sẻ ảnh."
                }
                check(root.mkdirs() || root.isDirectory) { "Không tạo được thư mục chia sẻ ảnh." }
                val ownedRoot = root.canonicalFile
                cleanup(ownedRoot, System.currentTimeMillis())
                val directory = File(ownedRoot, accountScope)
                check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == ownedRoot) {
                    "Thư mục chia sẻ ảnh không hợp lệ."
                }
                check(directory.mkdirs() || directory.isDirectory) { "Không tạo được thư mục chia sẻ ảnh." }
                sameSession()
                val now = System.currentTimeMillis()
                val time = capturedAt.takeIf { it in 1..253402300799999L }
                    ?: modified.takeIf { it in 1..253402300799999L } ?: now
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(time))
                val id = UUID.randomUUID().toString()
                val target = File(directory, "HP_Camera_${timestamp}_$id.${if(video) "mp4" else "jpg"}")
                val temporary = File(directory, ".prepare-$id.tmp")
                var complete = false
                try {
                    check(!target.exists() && !temporary.exists()) { "Hãy thử chia sẻ ảnh lại." }
                    image.inputStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                check(copied <= limit && copied <= size) { "Tệp đã thay đổi. Hãy mở lại tệp." }
                                output.write(buffer, 0, count)
                            }
                            check(copied == size) { "Chưa sao chép đủ ảnh để chia sẻ. Hãy thử lại." }
                            output.fd.sync()
                        }
                    }
                    check(image.isFile && image.length() == size && image.lastModified() == modified) {
                        "Ảnh đã thay đổi. Hãy mở lại ảnh để chia sẻ."
                    }
                    checkMedia(temporary, video)
                    sameSession()
                    currentCoroutineContext().ensureActive()
                    check(temporary.renameTo(target)) { "Chưa chuẩn bị được ảnh để chia sẻ. Hãy thử lại." }
                    // Use export time for cleanup, never the possibly old time the photo was taken.
                    target.setLastModified(now)
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", target)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = if (video) "video/mp4" else "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(app.contentResolver, if(video) "Video HP Telecom" else "Ảnh HP Telecom", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    sameSession()
                    currentCoroutineContext().ensureActive()
                    complete = true
                    send
                } finally {
                    temporary.delete()
                    // Once returned, keep the copy: receiving apps can read it after chooser dismissal.
                    if (!complete) target.delete()
                }
            }
        }
    }

    /** Prepare several saved photos for Android's system chooser in one share action. */
    suspend fun prepareMultiple(context: Context, sources: List<Pair<File, Long>>, accountScope: String): Intent {
        require(sources.size in 1..100) { "Chọn từ 1 đến 100 ảnh để chia sẻ." }
        val streams = ArrayList<android.net.Uri>(sources.size)
        for ((file, capturedAt) in sources) {
            val prepared = prepare(context, file, accountScope, capturedAt)
            val uri = prepared.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                ?: error("Chưa chuẩn bị được một trong các ảnh đã chọn.")
            streams.add(uri)
        }
        check(ServerSession.token.isNotEmpty() && (context.applicationContext as WorkApp).accountScope == accountScope) {
            "Phiên đăng nhập đã thay đổi. Hãy mở lại thư viện để chia sẻ."
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
            clipData = android.content.ClipData.newUri(context.contentResolver, "Ảnh HP Telecom", streams.first()).also { clip ->
                streams.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun checkJpeg(file: File) {
        file.inputStream().use { input ->
            check(input.read() == 0xff && input.read() == 0xd8 && input.read() == 0xff) {
                "Chỉ chia sẻ được ảnh JPEG đã lưu trong thư viện."
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg") {
            "Ảnh JPEG không còn hợp lệ. Hãy mở lại ảnh hoặc tải lại từ server."
        }
    }

    private fun checkMedia(file: File, video: Boolean) {
        if (!video) { checkJpeg(file); return }
        val header = ByteArray(12)
        file.inputStream().use { input -> check(input.read(header) == header.size) { "Video chưa lưu hoàn chỉnh." } }
        check(String(header, 4, 4, Charsets.US_ASCII) == "ftyp") { "Tệp không phải video MP4 hợp lệ." }
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.path)
            check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "video/mp4" &&
                (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) > 0L &&
                (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0 &&
                (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0) > 0) {
                "Video MP4 chưa hoàn chỉnh hoặc không đọc được."
            }
        } finally { metadata.release() }
    }

    /** Nonrecursive, bounded cleanup. Never touches originals or another feature's export directory. */
    private fun cleanup(root: File, now: Long) {
        var inspected = 0
        for (directory in root.listFiles().orEmpty()) {
            if (inspected >= CLEANUP_LIMIT) break
            if (!accountPattern.matches(directory.name) ||
                !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                directory.canonicalFile.parentFile != root) continue
            for (file in directory.listFiles().orEmpty()) {
                if (++inspected > CLEANUP_LIMIT) return
                val retention = when {
                    exportPattern.matches(file.name) -> RETAIN_EXPORT_MS
                    temporaryPattern.matches(file.name) -> RETAIN_TEMP_MS
                    else -> continue
                }
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    file.canonicalFile.parentFile != directory.canonicalFile) continue
                val age = now - file.lastModified()
                if (age > retention) runCatching { file.delete() }
            }
        }
    }
}
