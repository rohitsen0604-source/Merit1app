package com.scos3.camera.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/**
 * SC OS3 — Phase B3: global Volume-button control and Screen UI Capture for the floating camera UI.
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        Log.d(TAG, "onServiceConnected configured flags=${serviceInfo?.flags}")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent) {
        // Intentionally empty: window-content/event data is not processed.
    }

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // Only ever inspect the two volume keys; everything else passes through.
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }
        val active = VolumeKeyDispatcher.isVolumeControlActive()
        Log.d(TAG, "onKeyEvent key=$keyCode action=${event.action} repeat=${event.repeatCount} active=$active")
        // SC OS3 inactive: the mandatory pass-through so Android volume works.
        if (!active) return false
        // Consume DOWN/UP/repeat keys so the system volume is never also changed
        // while SC OS3 is active, but trigger a command only once per physical
        // press (no auto-repeat duplicates).
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            VolumeKeyDispatcher.dispatchVolumeDown()
        } else {
            VolumeKeyDispatcher.dispatchVolumeUp()
        }
    }

    /** Takes a screenshot of the phone screen UI and passes Bitmap to callback */
    fun takeScreenCapture(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayId = Display.DEFAULT_DISPLAY
            val executor = ContextCompat.getMainExecutor(this)
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val buffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hwBitmap = if (colorSpace != null) {
                            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        } else {
                            Bitmap.wrapHardwareBuffer(buffer, null)
                        }
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        if (swBitmap !== hwBitmap) {
                            hwBitmap?.recycle()
                        }
                        buffer.close()
                        Log.d(TAG, "takeScreenCapture success: ${swBitmap?.width}x${swBitmap?.height}")
                        callback(swBitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot hardwareBuffer", e)
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed with error code $errorCode")
                    callback(null)
                }
            })
        } else {
            callback(null)
        }
    }

    companion object {
        private const val TAG = "SCOS3-VOL"

        @Volatile
        var instance: VolumeKeyAccessibilityService? = null
            private set

        /** True when the user has enabled this service in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, VolumeKeyAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val shortForm = expected.flattenToShortString()
            return enabled.split(':').any {
                it.equals(shortForm, ignoreCase = true)
            }
        }
    }
}