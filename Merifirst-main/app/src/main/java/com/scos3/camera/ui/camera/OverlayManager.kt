package com.scos3.camera.ui.camera

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.scos3.camera.R
import com.scos3.camera.databinding.OverlayLowerBinding
import com.scos3.camera.databinding.OverlayUpperBinding

/**
 * Manages the TWO bounded SC OS3 overlay sections as REAL [WindowManager]
 * windows of type [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 *
 *  - [upper]: camera preview + upper controls (BLACK/SWITCH/SIZE/ZOOM/SETTING/
 *    HELP/status), pinned to the TOP of the screen.
 *  - [lower]: capture/action controls (BURST/CAPTURE/AUTO/FACE,
 *    MINIMIZE/EXIT), pinned to the BOTTOM of the screen.
 *
 * Window flags:
 *  - FLAG_NOT_FOCUSABLE  – the sections never steal input focus from the app
 *    underneath, so key events keep going to the foreground app.
 *  - FLAG_NOT_TOUCH_MODAL – touches outside the two bounded windows fall through
 *    to the underlying app untouched. Only the bands themselves receive input.
 *
 * The layout XML files contain the exact same UI hierarchy that previously
 * lived in activity_main.xml.  The only difference is the window type;
 * everything visual and functional is shared.
 *
 * NOTE: both layouts are inflated with a [ContextThemeWrapper] carrying
 * Theme.SCOS3. The MaterialButtonToggleGroup in the lower section requires a
 * MaterialComponents theme, otherwise it crashes with a ThemeEnforcement
 * exception at inflation time.
 */
class OverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Inflate with the Activity-hosted LayoutInflater. It carries BOTH the
    // MaterialComponents theme (Theme.SCOS3.Transparent) and the AppCompat
    // view-inflater factory. MaterialButtonToggleGroup child <Button>s only
    // become MaterialButton when that factory is present; a ContextThemeWrapper
    // around the Application context has no inflater factory, so the toggle
    // group rejects its children at runtime ("Child views must be of type
    // MaterialButton") and crashes.
    private val overlayInflater: LayoutInflater = LayoutInflater.from(context)

    // ---------- upper section (inflated + bound) ----------
    val upper: OverlayUpperBinding =
        OverlayUpperBinding.inflate(overlayInflater, null, false)

    // ---------- lower section (inflated + bound) ----------
    val lower: OverlayLowerBinding =
        OverlayLowerBinding.inflate(overlayInflater, null, false)

    private var showing: Boolean = false

    /** True only when BOTH sections are currently on screen. */
    fun isShowing(): Boolean = showing

    /** True only when the system can draw over other apps. */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    /** Add both sections; safe to call repeatedly. */
    fun show() {
        if (showing) return
        windowManager.addView(upper.root, upperLayoutParams())
        windowManager.addView(lower.root, lowerLayoutParams())
        showing = true
    }

    /** Remove both sections (MINIMIZE / EXIT / secondary‑UI hand‑off). */
    fun hide() {
        if (!showing) return
        runCatching { windowManager.removeView(upper.root) }
        runCatching { windowManager.removeView(lower.root) }
        showing = false
    }

    /** Posts a runnable on the UI thread while the sections are on screen. */
    fun post(action: () -> Unit) {
        upper.root.post { action() }
    }

    private fun upperLayoutParams(): WindowManager.LayoutParams {
        val params = baseLayoutParams()
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.y = systemInsetTop()
        return params
    }

    private fun lowerLayoutParams(): WindowManager.LayoutParams {
        val params = baseLayoutParams()
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.y = systemInsetBottom()
        return params
    }

    private fun baseLayoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun systemInsetTop(): Int = try {
        val resId = appContext.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        if (resId > 0) appContext.resources.getDimensionPixelSize(resId) else 0
    } catch (_: Exception) {
        0
    }

    private fun systemInsetBottom(): Int = try {
        val resId = appContext.resources.getIdentifier(
            "navigation_bar_height", "dimen", "android"
        )
        if (resId > 0) appContext.resources.getDimensionPixelSize(resId) else 0
    } catch (_: Exception) {
        0
    }
}