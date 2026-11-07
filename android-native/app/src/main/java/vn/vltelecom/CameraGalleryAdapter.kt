package vn.vltelecom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CameraGalleryItem(val photo: CameraPhoto? = null, val video: CameraVideo? = null) {
    init { require((photo == null) != (video == null)) }
    val id: String get() = photo?.let { "photo:${it.id}" } ?: "video:${video!!.id}"
    val capturedAt: Long get() = photo?.capturedAt ?: video!!.capturedAt
    val photographer: String get() = photo?.photographer ?: video!!.photographer
    val isVideo: Boolean get() = video != null
    fun source(app: WorkApp): File = photo?.let { CameraArchive.source(app, it) }
        ?: CameraVideoArchive.source(app, video!!)
}

/** GridView recycles cells; only visible thumbnails are decoded, with bounded concurrency/cache. */
internal class CameraGalleryAdapter(
    private val context: Context,
    private val app: WorkApp,
    private val scope: CoroutineScope,
    private val sameAccount: () -> Boolean,
    private val open: (CameraGalleryItem) -> Unit,
    private val selectionMode: () -> Boolean = { false },
    private val isSelected: (CameraGalleryItem) -> Boolean = { false }
) : BaseAdapter() {
    private var items: List<CameraGalleryItem> = emptyList()
    private val loads = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Do not recycle on eviction: an attached ImageView can still be displaying this bitmap.
    }
    private val jobs = mutableSetOf<Job>()
    private val dates = SimpleDateFormat("dd/MM HH:mm", Locale.forLanguageTag("vi-VN"))
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private class Cell(val root: FrameLayout, val image: ImageView, val caption: TextView, val badge: TextView, val selection: TextView) {
        var boundId = ""
        var job: Job? = null
    }

    fun submit(values: List<CameraGalleryItem>) { items = values; notifyDataSetChanged() }
    fun dispose() { jobs.toList().forEach { it.cancel() }; jobs.clear(); cache.evictAll() }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position)
        val cell = (convertView?.tag as? Cell) ?: run {
            val frame = object : FrameLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY))
                }
            }.apply { setBackgroundColor(Color.rgb(215, 228, 233)); layoutParams = AbsListView.LayoutParams(-1, dp(112)) }
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            val caption = TextView(context).apply {
                setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(4)); setBackgroundColor(Color.argb(165, 0, 0, 0))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            val badge = TextView(context).apply {
                setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(5), dp(3), dp(5), dp(3))
                setBackgroundColor(Color.argb(160, 0, 0, 0)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            val selection = TextView(context).apply {
                gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 18f
                setBackgroundColor(Color.argb(190, 0, 116, 174)); visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(selection, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.START).apply { setMargins(dp(5), dp(5), 0, 0) })
            Cell(frame, image, caption, badge, selection).also { frame.tag = it }
        }
        cell.job?.cancel(); cell.boundId = item.id
        cell.image.setImageDrawable(null)
        cell.caption.text = dates.format(Date(item.capturedAt))
        cell.badge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        val seconds = (item.video?.durationMs ?: 0L) / 1000
        cell.badge.text = String.format(Locale.US, "▶ %d:%02d", seconds / 60, seconds % 60)
        val selectable = item.photo != null && selectionMode()
        val selected = selectable && isSelected(item)
        cell.selection.visibility = if (selectable) View.VISIBLE else View.GONE
        cell.selection.text = if (selected) "✓" else "○"
        cell.root.foreground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(if (selected) 3 else 0), if (selected) Color.rgb(255, 205, 45) else Color.TRANSPARENT)
        }
        cell.root.contentDescription = (if (item.isVideo) "Video " else "Ảnh ") + cell.caption.text + ", ${item.photographer}" + if (selectable) if (selected) ", đã chọn" else ", chưa chọn" else ""
        cell.root.isFocusable = true
        cell.root.setOnClickListener { if (sameAccount()) open(item) }
        val existing = cache.get(item.id)
        if (existing != null) cell.image.setImageBitmap(existing)
        else {
            val job = scope.launch {
                var decoded: Bitmap? = null
                var retained = false
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        loads.withPermit {
                            if (!sameAccount()) return@withPermit null
                            runCatching {
                                val file = item.source(app)
                                if (item.isVideo) {
                                    @Suppress("DEPRECATION")
                                    val original = ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
                                    original?.let {
                                        if (it.width <= 320 && it.height <= 320) it else {
                                            val ratio = 320f / maxOf(it.width, it.height)
                                            Bitmap.createScaledBitmap(it, (it.width * ratio).toInt().coerceAtLeast(1), (it.height * ratio).toInt().coerceAtLeast(1), true).also { small -> if (small !== it) it.recycle() }
                                        }
                                    }
                                } else PhotoRenderer.thumbnail(file.path, 320)
                            }.getOrNull().also { decoded = it }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!sameAccount()) return@launch
                    if (bitmap != null) { cache.put(item.id, bitmap); retained = true }
                    if (cell.boundId == item.id) {
                        if (bitmap != null) cell.image.setImageBitmap(bitmap)
                        else cell.caption.text = "Không đọc được tệp"
                    }
                } finally {
                    // Prompt cancellation can discard a withContext result when a cell is reused.
                    if (!retained) decoded?.recycle()
                }
            }
            cell.job = job; jobs.add(job)
            job.invokeOnCompletion { cell.root.post { jobs.remove(job) } }
        }
        return cell.root
    }
}
