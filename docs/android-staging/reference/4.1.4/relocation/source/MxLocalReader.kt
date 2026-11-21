package vn.vltelecom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** Offline, one operation per original image. Results never write report notes themselves. */
object MxLocalReader {
    const val VERSION = 2
    enum class Status { READABLE, UNREADABLE, ERROR }
    data class Outcome(val coordinates: MxCoordinates?, val status: Status, val sha256: String, val cached: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<String, Deferred<Outcome>>()
    private val scanLock = Mutex()
    private val directExecutor = Executor { it.run() }

    /** cacheDirectory must be account-specific. retryUnreadable is only for an explicit user retry. */
    suspend fun read(file: File, cacheDirectory: File, retryUnreadable: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        var ownedSnapshot: File? = null
        var hash = ""
        try {
            hash = MxLocalCache.sha256(file)
            currentCoroutineContext().ensureActive()
            MxLocalCache(cacheDirectory).get(hash)?.let { saved ->
                if (saved.point != null || !retryUnreadable) {
                    val point = saved.point?.let { MxCoordinates.of(it.lat, it.lon, saved.source) }
                    return@withContext Outcome(point, if (point == null) Status.UNREADABLE else Status.READABLE, hash, true)
                }
            }
            // Import owns its temporary original only until read() returns/cancels. The shared job
            // therefore takes a private snapshot before entering the sequential OCR queue.
            check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Không tạo được bộ nhớ đọc MX." }
            val snapshot = File.createTempFile("mx-input-", ".image", cacheDirectory)
            ownedSnapshot = snapshot
            file.inputStream().use { input ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MxLocalCache.MAX_FILE_BYTES) { "Ảnh lớn hơn 64 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(MxLocalCache.sha256(snapshot) == hash) { "Ảnh MX thay đổi trong khi đọc." }
            currentCoroutineContext().ensureActive()
            val key = "${cacheDirectory.absolutePath}:$hash"
            val deferred = synchronized(inFlight) {
                inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) {
                    try { scanLock.withLock { readOnce(snapshot, cacheDirectory, hash, retryUnreadable) } }
                    finally {
                        snapshot.delete()
                        synchronized(inFlight) { inFlight.remove(key) }
                    }
                }.also { inFlight[key] = it; ownedSnapshot = null }
            }
            deferred.start()
            // An existing single-flight operation already owns its own input.
            ownedSnapshot?.delete()
            ownedSnapshot = null
            deferred.await()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Outcome(null, Status.ERROR, hash)
        } finally { ownedSnapshot?.delete() }
    }

    private suspend fun readOnce(file: File, cacheDirectory: File, hash: String, retryUnreadable: Boolean): Outcome {
        val cache = MxLocalCache(cacheDirectory)
        cache.get(hash)?.let { saved ->
            if (saved.point != null || !retryUnreadable) {
                val coordinates = saved.point?.let { MxCoordinates.of(it.lat, it.lon, saved.source) }
                return Outcome(coordinates, if (coordinates != null) Status.READABLE else Status.UNREADABLE, hash, true)
            }
        }
        return try {
            val outcome = withTimeout(40000) {
                val exif = runCatching { ExifInterface(file.path) }.getOrNull()
                val exifPoint = exif?.latLong?.let { MxCoordinates.of(it[0], it[1], "EXIF") }
                if (exifPoint != null) return@withTimeout Outcome(exifPoint, Status.READABLE, hash)
                val bitmap = decode(file, exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    ?: ExifInterface.ORIENTATION_NORMAL)
                var ownedSession: Session? = null
                try {
                    val session = Session(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
                    ownedSession = session
                    val evidence = MxReadEvidence()
                    suspend fun scan(input: Bitmap): Boolean {
                        evidence.add(MxLocalParser.candidates(session.recognize(input)))
                        return evidence.result() != null
                    }
                    // One overview detects multiple printed coordinate pairs. Read stamp crops
                    // from the original file, not from this downsampled overview bitmap.
                    scan(variant(bitmap, gray = false))
                    var complete = false
                    val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        ?: ExifInterface.ORIENTATION_NORMAL
                    @Suppress("DEPRECATION")
                    val regions = runCatching { BitmapRegionDecoder.newInstance(file.path, false) }.getOrNull()
                    try {
                        if (regions != null) {
                            for (rect in MxImageRegions.stamps(regions.width, regions.height)) {
                                currentCoroutineContext().ensureActive()
                                val crop = runCatching {
                                    regions.decodeRegion(Rect(rect.left, rect.top, rect.right, rect.bottom),
                                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                                }.getOrNull() ?: continue
                                val upright = orient(crop, orientation)
                                try {
                                    complete = scan(variant(upright, gray = false))
                                    if (!complete) complete = scan(variant(upright, gray = true))
                                } finally { upright.recycle() }
                                if (complete) break
                            }
                        }
                    } finally { regions?.recycle() }
                    if (!complete) complete = scan(variant(bitmap, gray = true))
                    if (!complete) {
                        val height = (bitmap.height * 0.34).roundToInt().coerceAtLeast(1)
                        for (top in listOf(0, bitmap.height - height).distinct()) {
                            val band = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                            try {
                                complete = scan(variant(band, gray = true, invert = true, enlarge = true))
                            } finally { if (band !== bitmap) band.recycle() }
                            if (complete) break
                        }
                    }
                    val point = evidence.result()
                    val coordinates = point?.let { MxCoordinates.of(it.lat, it.lon, "ocr") }
                    Outcome(coordinates, if (coordinates == null) Status.UNREADABLE else Status.READABLE, hash)
                } finally { ownedSession?.closeWhenIdle(); bitmap.recycle() }
            }
            // A caller can cancel navigation; the shared operation still completes and saves its result once.
            // Cache I/O failure must not discard otherwise usable coordinates.
            runCatching { cache.put(hash, MxLocalCache.Entry(outcome.coordinates?.let { MxLocalParser.Point(it.lat, it.lon) }, outcome.coordinates?.source.orEmpty())) }
            outcome
        } catch (_: TimeoutCancellationException) {
            Outcome(null, Status.ERROR, hash)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Outcome(null, Status.ERROR, hash)
        }
    }

    private fun decode(file: File, orientation: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 120_000_000L)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IllegalArgumentException("Không mở được ảnh MX.")
        return orient(bitmap, orientation)
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        finally { bitmap.recycle() }
    }

    /** Every ML Kit request owns its own input, so a timed-out callback cannot access a recycled source. */
    private fun variant(source: Bitmap, gray: Boolean, invert: Boolean = false, enlarge: Boolean = false): Bitmap {
        val scale = if (enlarge) (2000f / source.width).coerceIn(1f, 2f) else 1f
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (gray) {
            val colors = ColorMatrix().apply { setSaturation(0f) }
            if (invert) colors.postConcat(ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)))
            paint.colorFilter = ColorMatrixColorFilter(colors)
        }
        Canvas(target).drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), paint)
        return target
    }

    private class Session(private val recognizer: TextRecognizer) {
        private var active = false
        private var closing = false
        suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
            synchronized(this) { active = true }
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(directExecutor) { task ->
                    bitmap.recycle()
                    synchronized(this) { active = false; if (closing) recognizer.close() }
                    if (continuation.isActive) {
                        if (task.isSuccessful) continuation.resume(task.result.text)
                        else continuation.resumeWithException(task.exception ?: IllegalStateException("Không đọc được ảnh MX."))
                    }
                }
            } catch (error: Exception) {
                bitmap.recycle()
                synchronized(this) { active = false; if (closing) recognizer.close() }
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        fun closeWhenIdle() = synchronized(this) { closing = true; if (!active) recognizer.close() }
    }
}
