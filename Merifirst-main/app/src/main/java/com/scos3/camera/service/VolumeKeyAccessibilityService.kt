package com.scos3.camera.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/**
 * Accessibility service that enables system-wide Volume Up and Volume Down hardware
 * button capture without showing the system volume slider, as well as zero-prompt
 * full-screen screenshots across all applications.
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        Log.d(TAG, "VolumeKeyAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        Log.d(TAG, "VolumeKeyAccessibilityService interrupted")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "onKeyEvent ACTION_DOWN keyCode=$keyCode")
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                VolumeKeyDispatcher.dispatchVolumeDown()
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                VolumeKeyDispatcher.dispatchVolumeUp()
            }
        }
        return true
    }

    override fun onDestroy() {
        if (instance?.get() === this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SCOS3-VolA11y"
        private var instance: WeakReference<VolumeKeyAccessibilityService>? = null

        fun isRunning(): Boolean = instance?.get() != null

        fun takeCleanScreenshot(onBitmap: (Bitmap?) -> Unit) {
            val service = instance?.get()
            if (service == null) {
                onBitmap(null)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            val copyBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            onBitmap(copyBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "takeScreenshot failed with code $errorCode")
                            onBitmap(null)
                        }
                    }
                )
            } else {
                onBitmap(null)
            }
        }
    }
}
