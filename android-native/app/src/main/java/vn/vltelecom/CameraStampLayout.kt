package vn.vltelecom

import kotlin.math.min

/** Geometry shared by the live watermark and the saved photograph. No screen-density units. */
object CameraStampLayout {
    data class Size(val width: Float, val height: Float)

    fun logoSize(photoWidth: Int, photoHeight: Int, logoWidth: Int, logoHeight: Int): Size {
        require(photoWidth > 0 && photoHeight > 0 && logoWidth > 0 && logoHeight > 0)
        val scale = min(photoWidth * .20f / logoWidth, photoHeight * .20f / logoHeight)
        return Size(logoWidth * scale, logoHeight * scale)
    }

    fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}
