package vn.vltelecom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.text.InputFilter
import android.util.AtomicFile
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.max
import kotlin.math.ceil

/** Watermarks for the standalone camera only. Route/incident/relocation stamps are unchanged. */
object CameraStamp {
    enum class DateTimeFormat(val label: String, private val pattern: String) {
        DATE_TIME_SECONDS("Giờ, phút, giây + ngày/tháng/năm", "HH:mm:ss  |  dd/MM/yyyy"),
        DATE_TIME_MINUTES("Giờ, phút + ngày/tháng/năm", "HH:mm  |  dd/MM/yyyy"),
        DATE_ONLY("Chỉ ngày/tháng/năm", "dd/MM/yyyy"),
        TIME_SECONDS("Chỉ giờ, phút, giây", "HH:mm:ss"),
        TIME_MINUTES("Chỉ giờ, phút", "HH:mm");

        fun format(time: Long, zoneId: String): String =
            SimpleDateFormat(pattern, Locale.forLanguageTag("vi-VN"))
                .apply { timeZone = TimeZone.getTimeZone(zoneId) }.format(Date(time))

        companion object {
            // Missing settings in older photos retain their original full date/time stamp.
            fun fromStored(value: String?): DateTimeFormat =
                values().firstOrNull { it.name == value } ?: DATE_TIME_SECONDS
        }
    }

    data class Settings(
        val logo: Boolean = true,
        val date: Boolean = true,
        val location: Boolean = true,
        val coordinates: Boolean = true,
        val altitude: Boolean = true,
        val author: Boolean = true,
        val note: String = "",
        val dateTimeFormat: DateTimeFormat = DateTimeFormat.DATE_TIME_SECONDS
    )

    /** Scalars and an immutable PNG string freeze the template at the moment the shutter is pressed. */
    data class Snapshot(
        val settings: Settings,
        val capturedAt: Long,
        val latitude: Double?,
        val longitude: Double?,
        val altitudeMeters: Double?,
        val address: String,
        val photographer: String,
        val timeZoneId: String,
        internal val logoPng: String
    )

    private const val PREFS = "standalone-camera-stamp"
    private const val MAX_IMPORT_BYTES = 12L * 1024 * 1024
    private data class LogoAsset(val scope: String, val bitmap: Bitmap, val png: String)
    private val logoLock = Any()
    private var cachedLogo: LogoAsset? = null
    private val accountPattern = Regex("[a-f0-9-]{65}")
    private fun app(context: Context) = context.applicationContext as WorkApp
    private fun checkedScope(context: Context): String = app(context).accountScope.also {
        check(accountPattern.matches(it)) { "Hãy đăng nhập để lưu mẫu dấu riêng của bạn." }
    }
    private fun directory(context: Context, scope: String = checkedScope(context)): File {
        check(accountPattern.matches(scope))
        return File(app(context).filesDir, "accounts/$scope/camera-stamp").apply {
            check(mkdirs() || isDirectory) { "Không tạo được thư mục logo." }
        }
    }
    private fun prefs(context: Context) = app(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Settings {
        checkedScope(context)
        val p = prefs(context)
        return Settings(p.getBoolean("logo", true), p.getBoolean("date", true),
            p.getBoolean("location", true), p.getBoolean("coordinates", true),
            p.getBoolean("altitude", true), p.getBoolean("author", true),
            p.getString("note", "").orEmpty().take(180),
            DateTimeFormat.fromStored(p.getString("dateTimeFormat", null)))
    }

    fun save(context: Context, settings: Settings) {
        checkedScope(context)
        prefs(context).edit().putBoolean("logo", settings.logo).putBoolean("date", settings.date)
            .putBoolean("location", settings.location).putBoolean("coordinates", settings.coordinates)
            .putBoolean("altitude", settings.altitude).putBoolean("author", settings.author)
            .putString("note", settings.note.trim().take(180))
            .putString("dateTimeFormat", settings.dateTimeFormat.name).apply()
    }

    /** Run on IO. Copy first, validate and orient, then atomically publish PNG; no URI grant is retained. */
    fun importLogo(context: Context, uri: Uri) {
        val scope = checkedScope(context)
        val dir = directory(context, scope)
        val temporary = File(dir, "import-${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_IMPORT_BYTES) { "Logo tối đa 12 MB. Hãy chọn ảnh PNG, JPG hoặc WebP nhỏ hơn." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Không đọc được logo đã chọn.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth <= 100_000 && bounds.outHeight <= 100_000 &&
                bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) {
                "Chọn logo dạng PNG, JPG hoặc WebP hợp lệ."
            }
            val bitmap = PhotoRenderer.thumbnail(temporary.path, 768) ?: error("Không đọc được hình logo.")
            try {
                check(app(context).accountScope == scope) { "Tài khoản đã thay đổi. Hãy chọn lại logo." }
                val target = AtomicFile(File(dir, "logo.png"))
                val stream = target.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Không lưu được logo." }
                    // A PNG preserves transparency, unlike a JPEG logo cache.
                    target.finishWrite(stream)
                    synchronized(logoLock) { if (cachedLogo?.scope == scope) cachedLogo = null }
                } catch (error: Throwable) { target.failWrite(stream); throw error }
            } finally { bitmap.recycle() }
        } finally { temporary.delete() }
    }

    /** Shared immutable bitmap; old preview Views may still draw it, so never recycle cached assets. */
    private fun logoAsset(context: Context): LogoAsset = synchronized(logoLock) {
        val scope = checkedScope(context)
        cachedLogo?.takeIf { it.scope == scope }?.let { return@synchronized it }
        // Keep only the active account in the cache. Earlier Views/snapshots retain their own immutable value.
        cachedLogo = null
        val logo = File(directory(context, scope), "logo.png")
        val stored = if (logo.isFile) runCatching { AtomicFile(logo).readFully() }.getOrNull() else null
        val bitmap = stored?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.hp_logo)
            ?: error("Không đọc được logo HP Telecom.")
        val encoded = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }
        check(app(context).accountScope == scope) { "Tài khoản đã thay đổi." }
        LogoAsset(scope, bitmap, encoded).also { cachedLogo = it }
    }

    fun snapshot(context: Context, capturedAt: Long, location: Location?, address: String,
        photographer: String, note: String? = null): Snapshot {
        val settings = load(context).let { if (note == null) it else it.copy(note = note.trim().take(180)) }
        val valid = CameraStampLayout.validCoordinates(location?.latitude, location?.longitude)
        val logo = if (settings.logo) logoAsset(context).png else ""
        return Snapshot(settings, capturedAt, if (valid) location?.latitude else null,
            if (valid) location?.longitude else null,
            location?.takeIf { valid && it.hasAltitude() && it.altitude.isFinite() }?.altitude,
            address.trim().take(500), photographer.trim().take(180), TimeZone.getDefault().id, logo)
    }

    private fun decodeLogo(snapshot: Snapshot): Bitmap? = if (snapshot.logoPng.isBlank()) null else
        Base64.decode(snapshot.logoPng, Base64.NO_WRAP).let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    fun lines(snapshot: Snapshot): List<String> = buildList {
        val s = snapshot.settings
        if (s.date) add(s.dateTimeFormat.format(snapshot.capturedAt, snapshot.timeZoneId))
        if (s.location && snapshot.address.isNotBlank()) add(snapshot.address)
        if (s.coordinates && CameraStampLayout.validCoordinates(snapshot.latitude, snapshot.longitude))
            add(String.format(Locale.US, "Tọa độ: %.6f°, %.6f°", snapshot.latitude, snapshot.longitude))
        if (s.altitude && snapshot.altitudeMeters != null && snapshot.altitudeMeters.isFinite())
            add(String.format(Locale.forLanguageTag("vi-VN"), "Độ cao: %.1f m", snapshot.altitudeMeters))
        if (s.author && snapshot.photographer.isNotBlank()) add("Người chụp: ${snapshot.photographer}")
        if (s.note.isNotBlank()) add("Ghi chú: ${s.note}")
    }

    /** The caller can replace this small preview when the GPS fix or chosen template changes. */
    fun preview(context: Context, settings: Settings, time: Long, location: Location?,
        address: String, photographer: String, note: String? = null,
        photoWidthPx: Int = 0, photoHeightPx: Int = 0): View {
        val density = context.resources.displayMetrics.density
        val width = context.resources.displayMetrics.widthPixels
        val photoWidth = if (photoWidthPx > 0) photoWidthPx else max(1, width - (24 * density).toInt())
        val photoHeight = if (photoHeightPx > 0) photoHeightPx else photoWidth * 4 / 3
        val logo = if (settings.logo) logoAsset(context).bitmap else null
        val valid = CameraStampLayout.validCoordinates(location?.latitude, location?.longitude)
        val value = Snapshot(settings.copy(note = note ?: settings.note), time,
            if (valid) location?.latitude else null, if (valid) location?.longitude else null,
            location?.takeIf { valid && it.hasAltitude() && it.altitude.isFinite() }?.altitude,
            address.trim(), photographer, TimeZone.getDefault().id, "")
        return object : View(context) {
            private var drawing = CameraStamp.layout(photoWidth, photoHeight, value, logo)
            init {
                contentDescription = ((if (settings.logo) listOf("Logo trên ảnh") else emptyList()) + lines(value)).joinToString(". ")
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                isClickable = false
            }
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val measuredWidth = resolveSize(photoWidth, widthMeasureSpec).coerceAtLeast(1)
                val scaledHeight = (photoHeight.toFloat() * measuredWidth / photoWidth).toInt().coerceAtLeast(1)
                drawing = CameraStamp.layout(measuredWidth, scaledHeight, value, logo)
                setMeasuredDimension(measuredWidth, resolveSize(ceil(drawing.totalHeight).toInt(), heightMeasureSpec))
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.save()
                canvas.translate(0f, height - drawing.photoHeight.toFloat())
                CameraStamp.draw(canvas, drawing, logo)
                canvas.restore()
            }
        }
    }

    private data class StampRow(val text: String, val size: Float, val bold: Boolean)
    private data class StampDrawing(val photoWidth: Int, val photoHeight: Int, val margin: Float,
        val rows: List<StampRow>, val textHeight: Float, val logoSize: CameraStampLayout.Size?, val gap: Float) {
        val totalHeight: Float get() = if (rows.isEmpty() && logoSize == null) 0f else
            margin + textHeight + (logoSize?.height ?: 0f) + gap + photoWidth * .004f
    }

    private fun layout(width: Int, height: Int, snapshot: Snapshot, logo: Bitmap?): StampDrawing {
        val margin = width * .035f
        val measure = Paint(Paint.ANTI_ALIAS_FLAG)
        fun wrap(baseSize: Float): List<StampRow> = buildList {
            lines(snapshot).forEachIndexed { paragraphIndex, paragraph ->
                val bold = paragraphIndex == 0 && snapshot.settings.date
                val size = baseSize * if (bold) 1.4f else 1f
                measure.textSize = size
                measure.typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
                paragraph.lines().forEach { line ->
                    var rest = line
                    while (rest.isNotEmpty()) {
                        var count = measure.breakText(rest, true, width - 2 * margin, null).coerceAtLeast(1)
                        // A wrap must not split an emoji's UTF-16 surrogate pair.
                        if (count < rest.length && count > 1 && rest[count - 1].isHighSurrogate()) count--
                        if (count < rest.length) {
                            val space = rest.lastIndexOf(' ', count - 1)
                            if (space > count / 2) count = space + 1
                        }
                        add(StampRow(rest.take(count).trimEnd(), size, bold))
                        rest = rest.drop(count).trimStart()
                    }
                }
            }
        }
        fun textHeight(rows: List<StampRow>) = rows.sumOf { (it.size * 1.3f).toDouble() }.toFloat()
        var baseSize = width / 34f
        var rows = wrap(baseSize)
        while (textHeight(rows) > height * .48f && baseSize > width / 100f) {
            baseSize *= .9f
            rows = wrap(baseSize)
        }
        val logoSize = logo?.let { CameraStampLayout.logoSize(width, height, it.width, it.height) }
        return StampDrawing(width, height, margin, rows, textHeight(rows), logoSize,
            if (logoSize != null && rows.isNotEmpty()) margin * .35f else 0f)
    }

    private fun draw(canvas: Canvas, value: StampDrawing, logo: Bitmap?) {
        val shadow = value.photoWidth / 720f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(2.5f * shadow, 0f, 1.5f * shadow, Color.BLACK)
        }
        if (logo != null && value.logoSize != null) {
            val bottom = value.photoHeight - value.margin - value.textHeight - value.gap
            canvas.drawBitmap(logo, null, RectF(value.margin, bottom - value.logoSize.height,
                value.margin + value.logoSize.width, bottom), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        var lineTop = value.photoHeight - value.margin - value.textHeight
        value.rows.forEach { row ->
            paint.textSize = row.size
            paint.typeface = Typeface.create(Typeface.DEFAULT, if (row.bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(row.text, value.margin, lineTop - paint.fontMetrics.ascent, paint)
            lineTop += row.size * 1.3f
        }
    }

    fun showSettings(activity: AppCompatActivity, onChooseLogo: () -> Unit, onChanged: () -> Unit) {
        val scope = checkedScope(activity)
        val settings = load(activity)
        val d = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((20*d).toInt(), 0, (20*d).toInt(), (16*d).toInt()) }
        val logoRow = LinearLayout(activity)
        val logo = CheckBox(activity).apply { text = "Logo"; isChecked = settings.logo; minHeight = (48*d).toInt() }
        logoRow.addView(logo, LinearLayout.LayoutParams(0, -2, 1f))
        val choose = Button(activity).apply { text = "Đổi logo"; isAllCaps = false; contentDescription = "Chọn logo PNG, JPG hoặc WebP" }
        logoRow.addView(choose); body.addView(logoRow)
        body.addView(TextView(activity).apply { text = "Logo giữ đúng tỷ lệ, rộng tối đa 20% khung ảnh."; textSize = 13f })
        fun check(label: String, enabled: Boolean) = CheckBox(activity).apply {
            text = label; isChecked = enabled; minHeight = (48*d).toInt(); body.addView(this)
        }
        val date = check("Hiện ngày / giờ chụp", settings.date)
        body.addView(TextView(activity).apply { text = "Kiểu ngày / giờ"; textSize = 14f })
        val formats = DateTimeFormat.values()
        val datePreview = TextView(activity).apply { textSize = 14f; setPadding(0, (4*d).toInt(), 0, (8*d).toInt()) }
        val previewTime = System.currentTimeMillis()
        val previewZone = TimeZone.getDefault().id
        val formatPicker = Spinner(activity).apply {
            contentDescription = "Chọn kiểu ngày giờ trên ảnh"
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, formats.map { it.label }) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).apply {
                        if (this is TextView) { isSingleLine = false; textSize = 15f }
                    }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(formats.indexOf(settings.dateTimeFormat))
        }
        fun refreshDatePreview() {
            formatPicker.isEnabled = date.isChecked
            formatPicker.alpha = if (date.isChecked) 1f else .45f
            datePreview.text = if (date.isChecked) "Xem trước: " +
                formats[formatPicker.selectedItemPosition.coerceIn(formats.indices)].format(previewTime, previewZone)
                else "Không hiện ngày / giờ trên ảnh"
        }
        formatPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refreshDatePreview() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        date.setOnCheckedChangeListener { _, _ -> refreshDatePreview() }
        body.addView(formatPicker, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4*d).toInt() })
        formatPicker.minimumHeight = (48*d).toInt()
        body.addView(datePreview)
        refreshDatePreview()
        val address = check("Địa chỉ từ GPS", settings.location)
        val gps = check("Tọa độ GPS", settings.coordinates)
        val altitude = check("Độ cao khi GPS có dữ liệu", settings.altitude)
        val author = check("Người chụp", settings.author)
        val note = EditText(activity).apply { setText(settings.note); hint = "Ghi chú trên ảnh"; contentDescription = hint
            textSize = 16f; filters = arrayOf(InputFilter.LengthFilter(180)); maxLines = 3 }
        body.addView(note)
        fun commit() {
            check(app(activity).accountScope == scope) { "Tài khoản đã thay đổi." }
            save(activity, Settings(logo.isChecked, date.isChecked, address.isChecked, gps.isChecked,
                altitude.isChecked, author.isChecked, note.text.toString(),
                formats[formatPicker.selectedItemPosition.coerceIn(formats.indices)]))
            onChanged()
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Mẫu dấu trên ảnh")
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ -> if (app(activity).accountScope == scope) commit() }.create()
        dialog.setOnShowListener { dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        choose.setOnClickListener { if (app(activity).accountScope == scope) { commit(); dialog.dismiss(); onChooseLogo() } }
        dialog.show()
    }

    /** Render only from the frozen snapshot, including when a later template/logo has been selected. */
    fun render(context: Context, raw: File, dest: File, snapshot: Snapshot) {
        val source = PhotoRenderer.thumbnail(raw.path, 1800) ?: error("Không đọc được ảnh chụp.")
        val result = source.copy(Bitmap.Config.ARGB_8888, true) ?: error("Không tạo được ảnh có dấu.")
        val logo = if (snapshot.settings.logo) decodeLogo(snapshot) else null
        try {
            val canvas = Canvas(result)
            draw(canvas, layout(source.width, source.height, snapshot, logo), logo)
            dest.parentFile?.mkdirs()
            val atomic = AtomicFile(dest)
            val stream = atomic.startWrite()
            try { check(result.compress(Bitmap.CompressFormat.JPEG, 94, stream)) { "Không ghi được ảnh." }; atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
            remember(raw, dest, snapshot)
        } finally { logo?.recycle(); result.recycle(); source.recycle() }
    }

    internal fun encode(snapshot: Snapshot): JSONObject = JSONObject().put("schema", 1)
        .put("capturedAt", snapshot.capturedAt).put("latitude", snapshot.latitude ?: JSONObject.NULL)
        .put("longitude", snapshot.longitude ?: JSONObject.NULL).put("altitudeMeters", snapshot.altitudeMeters ?: JSONObject.NULL)
        .put("address", snapshot.address).put("photographer", snapshot.photographer).put("timeZoneId", snapshot.timeZoneId)
        .put("logoPng", snapshot.logoPng).put("settings", JSONObject().put("logo", snapshot.settings.logo)
            .put("date", snapshot.settings.date).put("location", snapshot.settings.location)
            .put("coordinates", snapshot.settings.coordinates).put("altitude", snapshot.settings.altitude)
            .put("author", snapshot.settings.author).put("note", snapshot.settings.note)
            .put("dateTimeFormat", snapshot.settings.dateTimeFormat.name))

    internal fun decode(value: JSONObject): Snapshot {
        check(value.getInt("schema") == 1)
        val s = value.getJSONObject("settings")
        fun number(key: String): Double? = if (value.isNull(key)) null else value.getDouble(key).takeIf { it.isFinite() }
        return Snapshot(Settings(s.getBoolean("logo"), s.getBoolean("date"), s.getBoolean("location"),
            s.getBoolean("coordinates"), s.getBoolean("altitude"), s.getBoolean("author"), s.getString("note"),
            DateTimeFormat.fromStored(s.optString("dateTimeFormat"))),
            value.getLong("capturedAt"), number("latitude"), number("longitude"), number("altitudeMeters"),
            value.getString("address"), value.getString("photographer"), value.getString("timeZoneId"), value.getString("logoPng"))
    }

    private fun remember(raw: File, dest: File, snapshot: Snapshot) {
        val original = File(dest.path + ".original.jpg")
        if (raw.canonicalFile != original.canonicalFile) raw.copyTo(original, overwrite = true)
        val metadata = AtomicFile(File(dest.path + ".camera-stamp.json"))
        val output = metadata.startWrite()
        try { output.write(encode(snapshot).toString().toByteArray(Charsets.UTF_8)); metadata.finishWrite(output) }
        catch (error: Throwable) { metadata.failWrite(output); throw error }
        ExifInterface(dest).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_ARTIST, snapshot.photographer)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone(snapshot.timeZoneId) }.format(Date(snapshot.capturedAt)))
            if (CameraStampLayout.validCoordinates(snapshot.latitude, snapshot.longitude)) setLatLong(snapshot.latitude!!, snapshot.longitude!!)
            snapshot.altitudeMeters?.let { setAltitude(it) }
            setAttribute(ExifInterface.TAG_USER_COMMENT, PhotoTextEncoding.asciiJson(JSONObject().put("hpCameraStamp", 1).toString()))
            saveAttributes()
        }
    }

    fun originalFor(source: File): File? = File(source.path + ".original.jpg").takeIf {
        it.isFile && File(source.path + ".camera-stamp.json").isFile
    }

    fun regenerate(context: Context, source: File, target: File): Boolean {
        val original = originalFor(source) ?: return false
        val saved = decode(JSONObject(String(AtomicFile(File(source.path + ".camera-stamp.json")).readFully(), Charsets.UTF_8)))
        render(context, original, target, saved)
        return true
    }
}
