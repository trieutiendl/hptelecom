package vn.vltelecom

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps scrollable screens above the software keyboard on edge-to-edge Android versions. */
internal fun Activity.applyImeInsetsTo(
    view: View,
    onImeVisibilityChanged: ((Boolean) -> Unit)? = null,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val left = view.paddingLeft
    val top = view.paddingTop
    val right = view.paddingRight
    val bottom = view.paddingBottom
    var lastImeVisible: Boolean? = null
    ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        target.setPadding(
            left + bars.left,
            top + bars.top,
            right + bars.right,
            bottom + maxOf(bars.bottom, ime.bottom),
        )
        val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
        if (imeVisible != lastImeVisible) {
            lastImeVisible = imeVisible
            onImeVisibilityChanged?.invoke(imeVisible)
        }
        insets
    }
    ViewCompat.requestApplyInsets(view)
}
