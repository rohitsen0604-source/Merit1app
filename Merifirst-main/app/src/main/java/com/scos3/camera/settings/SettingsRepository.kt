package com.scos3.camera.settings

import android.content.Context
import android.content.SharedPreferences
import com.scos3.camera.settings.AppSettings.CameraResolution
import com.scos3.camera.settings.AppSettings.CameraControllerLens
import com.scos3.camera.settings.AppSettings.GmailSettings
import com.scos3.camera.settings.AppSettings.JpegQuality

/**
 * Persists [AppSettings]. Non-sensitive values live in SharedPreferences;
 * Gmail credentials live in [SecurePrefs] (Android Keystore encrypted) and
 * never appear in plain SharedPreferences, logs or BuildConfig.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secure = SecurePrefs(context)

    fun load(): AppSettings = AppSettings(
        resolution = readResolution(),
        quality = JpegQuality.entries.firstOrNull { it.key == prefs.getInt(KEY_QUALITY, JpegQuality.HIGH.key) }
            ?: JpegQuality.HIGH,
        intervalMs = prefs.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS),
        defaultLens = if (prefs.getString(KEY_LENS, null) == "BACK") CameraControllerLens.BACK else CameraControllerLens.FRONT,
        emailEnabled = prefs.getBoolean(KEY_EMAIL_ENABLED, false),
        gallerySaveEnabled = prefs.getBoolean(KEY_GALLERY_SAVE_ENABLED, true),
        gmail = GmailSettings(
            senderEmail = secure.getString(KEY_GMAIL_SENDER) ?: "",
            senderAppPassword = secure.getString(KEY_GMAIL_APP_PASSWORD) ?: "",
            receiverEmail = secure.getString(KEY_GMAIL_RECEIVER) ?: "",
        ),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_RESOLUTION, settings.resolution?.toString())
            .putInt(KEY_QUALITY, settings.quality.key)
            .putLong(KEY_INTERVAL_MS, settings.intervalMs)
            .putString(KEY_LENS, settings.defaultLens.name)
            .putBoolean(KEY_EMAIL_ENABLED, settings.emailEnabled)
            .putBoolean(KEY_GALLERY_SAVE_ENABLED, settings.gallerySaveEnabled)
            .apply()
        secure.putString(KEY_GMAIL_SENDER, settings.gmail.senderEmail.trim())
        secure.putString(KEY_GMAIL_APP_PASSWORD, settings.gmail.senderAppPassword)
        secure.putString(KEY_GMAIL_RECEIVER, settings.gmail.receiverEmail.trim())
    }

    /**
     * Lightweight check used by the capture pipeline (runs on the capture
     * executor for every photo). Reads only the persisted SharedPreferences
     * boolean, never decoding the secure Gmail credentials.
     */
    fun isEmailEnabled(): Boolean = prefs.getBoolean(KEY_EMAIL_ENABLED, false)

    /**
     * Lightweight check used by the capture pipeline for every photo.
     * True = save through MediaStore (Gallery), false = app-private storage.
     */
    fun isGallerySaveEnabled(): Boolean = prefs.getBoolean(KEY_GALLERY_SAVE_ENABLED, true)

    /** True when all three Gmail fields are present. Used by the email queue. */
    fun gmailConfigured(): Boolean {
        val g = load().gmail
        return g.senderEmail.isNotBlank() && g.senderAppPassword.isNotBlank() && g.receiverEmail.isNotBlank()
    }

    /** Removes stored Gmail credentials. */
    fun clearGmail() {
        secure.remove(KEY_GMAIL_SENDER)
        secure.remove(KEY_GMAIL_APP_PASSWORD)
        secure.remove(KEY_GMAIL_RECEIVER)
    }

    private fun readResolution(): CameraResolution? =
        prefs.getString(KEY_RESOLUTION, null)?.split("x")?.let {
            if (it.size == 2) runCatching { CameraResolution(it[0].toInt(), it[1].toInt()) }.getOrNull() else null
        }

    companion object {
        private const val PREFS_NAME = "scos3_settings"

        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_QUALITY = "quality"
        private const val KEY_INTERVAL_MS = "interval_ms"
        private const val KEY_LENS = "default_lens"
        private const val KEY_EMAIL_ENABLED = "email_enabled"
        private const val KEY_GALLERY_SAVE_ENABLED = "gallery_save_enabled"
        private const val KEY_GMAIL_SENDER = "gmail_sender"
        private const val KEY_GMAIL_APP_PASSWORD = "gmail_app_password"
        private const val KEY_GMAIL_RECEIVER = "gmail_receiver"

        const val DEFAULT_INTERVAL_MS = 2000L
    }
}
