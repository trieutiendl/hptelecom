package vn.vltelecom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Inverse of the legacy Camera preview transform: sensor mirror, then display rotation. */
object CameraFocusMath {
    fun area(xFraction: Float, yFraction: Float, displayOrientation: Int, mirror: Boolean, halfSize: Int = 120): IntArray {
        val x = xFraction.coerceIn(0f, 1f) * 2000f - 1000f
        val y = yFraction.coerceIn(0f, 1f) * 2000f - 1000f
        val rotated = when ((displayOrientation % 360 + 360) % 360) {
            90 -> y to -x
            180 -> -x to -y
            270 -> -y to x
            else -> x to y
        }
        val half = halfSize.coerceIn(1, 1000)
        val cx = ((if (mirror) -rotated.first else rotated.first).toInt()).coerceIn(-1000 + half, 1000 - half)
        val cy = rotated.second.toInt().coerceIn(-1000 + half, 1000 - half)
        return intArrayOf(cx - half, cy - half, cx + half, cy + half)
    }
}

/** Tap-to-focus plus a native, accessible vertical exposure control. No photo filters. */
class CameraFocusView(context: Context) : FrameLayout(context) {
    var onFocus: (Float, Float) -> Unit = { _, _ -> }
    var onExposure: (Int) -> Unit = {}
    var onPinchZoom: (Float) -> Unit = {}
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val ring = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        override fun onDraw(canvas: Canvas) {
            val r = width.coerceAtMost(height) / 2f - dp(3)
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            canvas.drawCircle(width / 2f, height / 2f, dp(5).toFloat(), paint)
        }
    }
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.argb(80, 0, 0, 0)) }
    }
    private val valueLabel = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; setShadowLayer(2f, 0f, 1f, Color.BLACK)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var exposureMin = 0
    private var exposureStep = 0f
    private var canExpose = false
    private var busy = false
    private var pinching = false
    private var dragging = false
    private val exposure = SeekBar(context).apply {
        rotation = -90f; contentDescription = "Độ sáng camera. Tăng để sáng hơn, giảm để tối hơn."
        progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && canExpose && !busy) onExposure(exposureMin + value)
            }
            override fun onStartTrackingTouch(view: SeekBar?) { dragging = true; removeCallbacks(hide); parent?.requestDisallowInterceptTouchEvent(true) }
            override fun onStopTrackingTouch(view: SeekBar?) { dragging = false; parent?.requestDisallowInterceptTouchEvent(false); scheduleHide() }
        })
    }
    private val unavailable = TextView(context).apply {
        text = "Camera không hỗ trợ chỉnh sáng"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(180, 0, 0, 0)) }
    }
    private val hide = Runnable { if (!dragging) { ring.visibility = INVISIBLE; controls.visibility = INVISIBLE; unavailable.visibility = INVISIBLE } }
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinching = true; hide.run(); return !busy }
        override fun onScale(detector: ScaleGestureDetector): Boolean { if (!busy) onPinchZoom(detector.scaleFactor); return true }
    })
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (busy || pinching || width <= 0 || height <= 0) return true
            showAt(event.x, event.y)
            onFocus(event.x.coerceIn(0f, width.toFloat()) / width, event.y.coerceIn(0f, height.toFloat()) / height)
            performClick(); return true
        }
    })

    init {
        isClickable = true; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ring, LayoutParams(dp(84), dp(84)))
        fun icon(name: String) = ImageView(context).apply {
            setImageDrawable(CameraControlIcon(name, Color.WHITE)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        controls.addView(icon("sun"), LinearLayout.LayoutParams(dp(28), dp(28)))
        val track = FrameLayout(context)
        track.addView(exposure, LayoutParams(dp(144), dp(48), Gravity.CENTER))
        controls.addView(track, LinearLayout.LayoutParams(dp(52), dp(148)))
        controls.addView(icon("moon"), LinearLayout.LayoutParams(dp(24), dp(24)))
        controls.addView(valueLabel, LinearLayout.LayoutParams(-1, dp(22)))
        addView(controls, LayoutParams(dp(56), dp(232)))
        addView(unavailable, LayoutParams(dp(180), -2, Gravity.CENTER))
        hide.run()
    }

    fun setExposure(min: Int, max: Int, value: Int, step: Float, available: Boolean) {
        exposureMin = min; exposureStep = step; canExpose = available && max > min
        exposure.max = (max - min).coerceAtLeast(0); exposure.progress = (value - min).coerceIn(0, exposure.max)
        exposure.isEnabled = canExpose && !busy
        valueLabel.text = String.format(Locale.US, "%+.1f", value * exposureStep)
        androidx.core.view.ViewCompat.setStateDescription(exposure, if (canExpose) String.format(Locale.US, "%+.1f EV", value * exposureStep) else "Không hỗ trợ")
        if (!canExpose) controls.visibility = INVISIBLE
    }

    fun setBusy(value: Boolean) { busy = value; exposure.isEnabled = canExpose && !busy; if (busy) hide.run() }

    fun showFocusResult(success: Boolean) {
        ring.paint.color = if (success) Color.WHITE else Color.rgb(255, 210, 79); ring.invalidate()
    }

    private fun showAt(x: Float, y: Float) {
        ring.paint.color = Color.WHITE
        ring.x = (x - dp(42)).coerceIn(0f, (width - dp(84)).coerceAtLeast(0).toFloat())
        ring.y = (y - dp(42)).coerceIn(0f, (height - dp(84)).coerceAtLeast(0).toFloat())
        ring.visibility = VISIBLE; ring.invalidate()
        if (canExpose) {
            controls.x = (if (x - dp(104) >= 0) x - dp(104) else x + dp(48)).coerceIn(0f, (width - dp(56)).coerceAtLeast(0).toFloat())
            controls.y = (y - dp(116)).coerceIn(0f, (height - dp(232)).coerceAtLeast(0).toFloat())
            controls.visibility = VISIBLE; unavailable.visibility = INVISIBLE
        } else unavailable.visibility = VISIBLE
        scheduleHide()
    }

    private fun scheduleHide() { removeCallbacks(hide); postDelayed(hide, 5000) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        scale.onTouchEvent(event); if (!pinching) taps.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(false)
        else if (event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { removeCallbacks(hide); super.onDetachedFromWindow() }
}

/** Small vector-style controls kept sharp at all device densities. */
class CameraControlIcon(private val kind: String, private val ink: Int = Color.rgb(15, 27, 36)) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val p = Path()
        when (kind) {
            "back" -> { p.moveTo(15f, 4f); p.lineTo(7f, 12f); p.lineTo(15f, 20f); canvas.drawPath(p, paint) }
            "flash", "flash-off" -> {
                p.moveTo(13f, 2f); p.lineTo(5f, 13f); p.lineTo(11f, 13f); p.lineTo(10f, 22f); p.lineTo(19f, 10f); p.lineTo(13f, 10f); p.close(); canvas.drawPath(p, paint)
                if (kind == "flash-off") canvas.drawLine(3f, 3f, 21f, 21f, paint)
            }
            "switch" -> {
                canvas.drawRoundRect(3f, 6f, 21f, 20f, 3f, 3f, paint); canvas.drawCircle(12f, 13f, 3.5f, paint)
                p.moveTo(7f, 6f); p.lineTo(9f, 3f); p.lineTo(15f, 3f); p.lineTo(17f, 6f); canvas.drawPath(p, paint)
                canvas.drawLine(1f, 10f, 1f, 4f, paint); canvas.drawLine(1f, 4f, 6f, 4f, paint)
                canvas.drawLine(23f, 16f, 23f, 22f, paint); canvas.drawLine(23f, 22f, 18f, 22f, paint)
            }
            "gallery" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 3f, 3f, paint); canvas.drawCircle(8f, 8f, 1.5f, paint)
                p.moveTo(4f, 18f); p.lineTo(9f, 13f); p.lineTo(13f, 17f); p.lineTo(17f, 11f); p.lineTo(21f, 16f); canvas.drawPath(p, paint)
            }
            "template" -> {
                canvas.drawRoundRect(3f, 3f, 21f, 21f, 3f, 3f, paint)
                for (y in listOf(8f, 12f, 16f)) canvas.drawLine(7f, y, if (y == 16f) 12f else 17f, y, paint)
            }
            "sun" -> {
                canvas.drawCircle(12f, 12f, 4f, paint)
                for (i in 0..7) { val a = Math.PI * i / 4; canvas.drawLine(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(), 12f + 10f * cos(a).toFloat(), 12f + 10f * sin(a).toFloat(), paint) }
            }
            "moon" -> {
                p.moveTo(17.8f, 17.3f); p.cubicTo(7f, 21f, 2f, 9f, 11f, 3f); p.cubicTo(7.5f, 10f, 11f, 17f, 17.8f, 17.3f); p.close(); canvas.drawPath(p, paint)
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
