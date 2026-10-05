package com.scos3.camera.settings

/**
 * Application-level settings model. Backed by SharedPreferences so values
 * survive process death and are readable from both the UI and the foreground
 * service. This is the data structure that later phases (Gmail, Burst, Face)
 * will extend.
 */
data class AppSettings(
    val resolution: CameraResolution? = null,
    val quality: JpegQuality = JpegQuality.HIGH,
    val intervalMs: Long = 2000L,
    val defaultLens: CameraControllerLens = CameraControllerLens.BACK,
    val emailEnabled: Boolean = false,
    val gallerySaveEnabled: Boolean = true,
    val gmail: GmailSettings = GmailSettings(),
) {
    data class CameraResolution(val width: Int, val height: Int) {
        val megapixels: Int get() = (width * height) / 1_000_000
        override fun toString(): String = "${width}x${height}"
    }

    enum class JpegQuality(val key: Int) {
        LOW(70),
        STANDARD(85),
        HIGH(95),
    }

    enum class CameraControllerLens { BACK, FRONT, SCREEN }

    data class GmailSettings(
        val senderEmail: String = "",
        val senderAppPassword: String = "",
        val receiverEmail: String = "",
    )
}
