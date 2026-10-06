package com.scos3.camera.ui.camera

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import android.util.Log
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.scos3.camera.R
import com.scos3.camera.camera.CameraController
import com.scos3.camera.camera.CameraController.Lens
import com.scos3.camera.capture.CaptureResult
import com.scos3.camera.email.EmailManager
import com.scos3.camera.email.EmailManagerProvider
import com.scos3.camera.settings.AppSettings.CameraControllerLens
import com.scos3.camera.settings.SettingsRepository
import com.scos3.camera.service.CaptureForegroundService
import com.scos3.camera.service.VolumeKeyDispatcher
import com.scos3.camera.ui.diagnostics.DiagnosticsActivity
import com.scos3.camera.ui.settings.SettingsActivity
import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SC OS3 — Phase B1: REAL two-part overlay presentation.
 *
 * Tapping the SC OS3 icon does NOT show a full-screen (translucent) Activity UI.
 * Instead [OverlayManager] immediately presents TWO bounded
 * TYPE_APPLICATION_OVERLAY windows over the currently running application:
 *   - [OverlayManager.upper]: camera preview + BLACK/SWITCH/SIZE/ZOOM/SETTING/
 *     HELP/status controls, pinned to the top.
 *   - [OverlayManager.lower]: BURST/CAPTURE/AUTO/FACE and
 *     MINIMIZE/EXIT, pinned to the bottom.
 * The underlying app stays visible and usable everywhere between/around the two
 * windows, and every existing button cross-references the exact same handlers
 * that previously ran the full-screen UI (nothing was rewritten).
 *
 * Camera ownership:
 *  - Idle overlay: the single [CameraController] is created here but bound to
 *    the [CaptureForegroundService] lifecycle (the persistent owner), so the
 *    preview keeps producing frames while this Activity sits in the back stack
 *    and a camera-type foreground service keeps the CAMERA while-in-use grant
 *    active over the underlying app. There is exactly ONE camera owner.
 *  - AUTO active: [CaptureForegroundService] owns a capture-only controller;
 *    this Activity releases the preview controller so the two never overlap.
 *
 * Secondary UI (Settings / Help / Diagnostics / permission dialogs) hides the
 * two sections first and restores them on return so those windows are never
 * occluded by the overlay bands.
 *
 * Launch order (Phase B2 fix): permission checks -> start/ensure the camera
 * foreground service from onStart -> the service calls startForeground() with
 * FOREGROUND_SERVICE_TYPE_CAMERA -> bindCamera() binds the preview controller
 * to the service lifecycle only once the service is actually started -> both
 * overlay windows shown -> moveTaskToBack behind the current app. The Activity
 * never becomes the camera owner; the started foreground service is the single
 * long-lived owner, so the CAMERA while-in-use grant keeps the preview alive
 * over the underlying app.
 *
 * Volume buttons: the existing Activity.onKeyDown mechanism is preserved
 * verbatim (see [onKeyDown]). Because the real overlay keeps this Activity
 * backgrounded while another app has focus, global volume capture is provided
 * by the opt-in AccessibilityService (Phase B3, [VolumeKeyDispatcher] +
 * VolumeKeyAccessibilityService): while the overlay is ACTIVE it forwards
 * Volume Down/Up here, and the moment SC OS3 is INACTIVE it passes the keys
 * back to Android unchanged.
 */
class MainActivity : AppCompatActivity() {

    private var overlayManager: OverlayManager? = null
    private val upper get() = overlayManager?.upper
    private val lower get() = overlayManager?.lower

    private var controller: CameraController? = null
    private var settingsRepo: SettingsRepository? = null
    private var emailManager: EmailManager? = null
    private var captureEngine: com.scos3.camera.capture.CaptureEngine? = null
    private var screenCaptureMode = false

    private var service: CaptureForegroundService? = null
    private var serviceBound = false
    private var serviceConnectRequested = false
    private var serviceStateKnown = false
    private var capturing = false
    private var bursting = false
    private var hasRunAutoCapture = false
    private var activityStarted = false
    private var activityResumed = false
    private var cameraBindJob: Job? = null
    private var autoScreenJob: Job? = null
    private var pendingAutoStart: AutoStartRequest? = null
    private var deferredAutoStart: AutoStartRequest? = null

    // The two sections are hidden while Settings / Help / Diagnostics / a
    // permission dialog owns the screen; they are restored on return.
    private var secondaryUiActive = false
    private var pendingOnResume: (() -> Unit)? = null
    private var launchingDiagnostics = false

    private var lastZoomRatio = 1f
    private var lastAutoLens = CameraController.Lens.BACK
    private var lastAutoUltrawide = false
    private var lastAutoZoom = 1f

    // Phase C: BLACK preview blackout + FACE auto capture.
    private var blackActive = false
    private var faceModeEnabled = false

    // B3: SC OS3 registers with the app-level volume dispatcher while its two
    // overlay sections are on screen. The AccessibilityService then forwards
    // Volume presses here when SC OS3 is ACTIVE.
    private val volumeListener = object : VolumeKeyDispatcher.VolumeCommandListener {
        override fun onVolumeDown() = onVolumeDownPressed()
        override fun onVolumeUp() = onVolumeUpPressed()
    }

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (isDestroyed || isFinishing) return@registerForActivityResult
        if (granted) {
            upper?.permissionCard?.visibility = View.GONE
            faceModeEnabled = false
            controller?.setFaceMode(false, null)
            lower?.btnFace?.setText(R.string.btn_face)
            if (overlayManager?.canDrawOverlays() != true) {
                launcherOverlayPermission()
            } else {
                showOverlayUi()
            }
        } else {
            upper?.permissionText?.text = getString(R.string.permission_denied_message)
            upper?.permissionButton?.text = getString(R.string.permission_retry)
        }
        refreshCaptureUi()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Do NOT start the foreground service from this callback. The result is
        // delivered during the activity's resume transition (lifecycle state is
        // still STARTED here, onResume has not run yet). Starting a camera FGS
        // from that window is a documented crash source on Android 14+:
        //  - startForegroundService() can throw ForegroundServiceStartNotAllowed
        //    Exception because the app is not yet considered in the foreground;
        //  - a camera-type startForeground() can throw SecurityException because
        //    the CAMERA while-in-use grant is not yet active.
        // Both kill the process. Instead, defer to onResume, where the window is
        // visible and the while-in-use grant is guaranteed active.
        val pending = pendingAutoStart
        pendingAutoStart = null
        if (pending != null) {
            deferredAutoStart = pending
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isDestroyed || isFinishing) return@registerForActivityResult
        if (overlayManager?.canDrawOverlays() == true) {
            if (!hasCameraPermission()) {
                requestCameraPermission()
            } else {
                showOverlayUi()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_overlay_title)
                .setMessage(R.string.permission_overlay_message)
                .setPositiveButton(R.string.permission_overlay_open) { _, _ ->
                    launcherOverlayPermission()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    Toast.makeText(this, R.string.permission_overlay_denied, Toast.LENGTH_LONG).show()
                }
                .show()
        }
    }

    private val settingsActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        secondaryUiActive = false
        if (overlayManager?.canDrawOverlays() == true) {
            overlayManager?.show()
            registerVolumeControl()
            applySettings()
            refreshCaptureUi()
            overlayManager?.post { if (!isDestroyed && !isFinishing) moveTaskToBack(true) }
        }
    }

    private var pendingScreenCaptureResult: ((CaptureResult) -> Unit)? = null
    private var screenCaptureRequestedOnce = false
    private var pendingMediaProjectionData: Pair<Int, Intent>? = null

    private fun hasMediaProjection(): Boolean = false

    private fun getRealScreenMetrics(): Triple<Int, Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val density = resources.configuration.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun ensureScreenCapturePermission() {
        // Disabled: screen capture / MediaProjection is completely removed
    }

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // No-op
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            serviceConnectRequested = false
            val bound = (binder as CaptureForegroundService.LocalBinder).service
            service = bound
            serviceBound = true
            serviceStateKnown = true
            if (isDestroyed || isFinishing) return
            bound.addListener(statusListener)
            capturing = bound.isCapturing()
            // In the overlay architecture the camera-type foreground service is
            // the persistent camera owner while the sections are on screen, so a
            // parked service is never retired here (notifications stay until the
            // user explicitly EXITs).
            refreshCameraOwnership()
            refreshCaptureUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceConnectRequested = false
            service?.removeListener(statusListener)
            service = null
            serviceBound = false
        }
    }

    private val statusListener = object : CaptureForegroundService.CaptureStatusListener {
        override fun onCaptureStatus(intervalMs: Long, captureCount: Int, isCapturing: Boolean) {
            if (capturing != isCapturing) {
                capturing = isCapturing
                if (!capturing && overlayManager?.isShowing() == true && hasCameraPermission()) {
                    // AUTO stopped while the sections are on screen: re-arm the
                    // camera-type foreground service so the preview regains the
                    // while-in-use grant. Ignored silently when the system
                    // refuses (activity not in the foreground); the preview then
                    // restarts when the Activity next comes forward.
                    CaptureForegroundService.showOverlay(this@MainActivity)
                }
                refreshCameraOwnership()
            }
            refreshCaptureUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        settingsRepo = SettingsRepository(this)
        emailManager = EmailManagerProvider.get(this)
        captureEngine = com.scos3.camera.capture.CaptureEngine(this)

        val om = OverlayManager(this)
        overlayManager = om
        wireCallbacks(om)

        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else if (!om.canDrawOverlays()) {
            launcherOverlayPermission()
        } else {
            // Ensure default lens is not SCREEN; default to front if needed
            val repo = settingsRepo
            if (repo?.load()?.defaultLens == CameraControllerLens.SCREEN) {
                repo.save(repo.load().copy(defaultLens = CameraControllerLens.FRONT))
            }
            showOverlayUi()
        }
    }

    private fun launcherOverlayPermission() {
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun wireCallbacks(om: OverlayManager) {
        val u = om.upper
        val l = om.lower

        l.btnCapture.setOnClickListener {
            onCapturePressed()
        }
        l.btnAuto.setOnClickListener { toggleAutoCapture() }
        u.btnSwitch.setOnClickListener { switchCamera() }
        u.btnSetting.setOnClickListener { onSettingPressed() }
        u.btnHelp.setOnClickListener { onHelpPressed() }
        u.btnBlack.setOnClickListener { toggleBlackOverlay() }
        l.btnBurst.setOnClickListener { startBurst() }
        l.btnFace.setOnClickListener { toggleFaceMode() }
        l.btnMinimize.setOnClickListener { onMinimizePressed() }
        l.btnExit.setOnClickListener { onExitPressed() }
        u.permissionButton.setOnClickListener {
            requestCameraPermission()
        }
        u.previewView.setOnTouchListener(::onPreviewTouch)

        setupZoomSeekBar()
        setupSizeSlider()
        setupDragHandle()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        // Guard against duplicate binds (e.g. rapid task switches / permission
        // dialogs covering the activity) so a single service owns the camera.
        if (!serviceBound && !serviceConnectRequested) {
            serviceConnectRequested = true
            bindService(
                Intent(this, CaptureForegroundService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        }
        // Start/retain the camera-type foreground service as soon as the Activity
        // is on its way to the foreground, so startForeground() runs and the
        // CAMERA while-in-use grant is active by the time moveTaskToBack parks
        // this task behind the underlying app. Previously this was only reachable
        // from onCreate (activityResumed == false, so the service was never
        // started and merely bound — leaving the camera open while the app was
        // backgrounded, which the system then closes).
        ensureOverlayService()
        registerVolumeControl()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        emailManager?.attemptDrain()

        val pending = pendingOnResume
        pendingOnResume = null
        if (pending != null) {
            pending()
            return
        }

        if (secondaryUiActive) {
            // Secondary UI (Settings / Diagnostics) is active on top.
            // Do NOT restore overlay UI and do NOT call moveTaskToBack.
            return
        }

        if (overlayManager?.canDrawOverlays() == true) {
            overlayManager?.show()
            registerVolumeControl()
        }
        consumeDeferredAutoStart()
        applySettings()
        refreshCaptureUi()

        if (hasCameraPermission() && overlayManager?.canDrawOverlays() == true) {
            overlayManager?.post { if (!isDestroyed && !isFinishing) moveTaskToBack(true) }
        }
    }

    /** Starts an AUTO session that was deferred from the notification-permission callback. */
    private fun consumeDeferredAutoStart() {
        val pending = deferredAutoStart
        if (pending == null || isDestroyed || isFinishing) return
        deferredAutoStart = null
        startAutoCapture(pending)
    }

    override fun onStop() {
        activityResumed = false
        activityStarted = false
        serviceConnectRequested = false
        super.onStop()
    }

    override fun onDestroy() {
        pendingAutoStart = null
        deferredAutoStart = null
        pendingOnResume = null
        cameraBindJob?.cancel()
        unregisterVolumeControl()
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        service?.removeListener(statusListener)
        service = null
        controller?.release()
        controller = null
        overlayManager?.hide()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        secondaryUiActive = false
        applySettings()
        refreshCaptureUi()
        showOverlayUi()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showOverlayUi() {
        if (isDestroyed || isFinishing) return
        val om = overlayManager ?: return
        if (!om.canDrawOverlays()) return
        om.show()
        registerVolumeControl()
        applySettings()
        val cameraGranted = hasCameraPermission()
        if (cameraGranted) {
            upper?.permissionCard?.visibility = View.GONE
            om.post { if (!isDestroyed && !isFinishing) moveTaskToBack(true) }
        } else {
            upper?.permissionCard?.visibility = View.VISIBLE
            setStatus(getString(R.string.permission_required_message))
            requestCameraPermission()
        }
    }

    /** Re-adds the two sections after a secondary UI / permission flow finished. */
    private fun restoreOverlayUi() {
        if (isDestroyed || isFinishing) return
        secondaryUiActive = false
        launchingDiagnostics = false
        consumeDeferredAutoStart()
        if (hasCameraPermission()) {
            upper?.permissionCard?.visibility = View.GONE
        }
        overlayManager?.show()
        registerVolumeControl()
        applySettings()
        refreshCaptureUi()
        overlayManager?.post { if (!isDestroyed && !isFinishing) moveTaskToBack(true) }
    }

    /**
     * Runs [action] while the sections are hidden and the Activity window is in
     * front (Settings/Help/Diagnostics and permission dialogs must not be
     * occluded by the overlay bands). The sections return on the next resume.
     */
    private fun runWithOverlayHidden(action: () -> Unit) {
        secondaryUiActive = true
        overlayManager?.hide()
        unregisterVolumeControl()
        if (activityResumed) {
            action()
        } else {
            pendingOnResume = action
            reorderTaskFront()
        }
    }

    /** Runs [action] with the Activity window in front; the sections stay visible. */
    private fun runWhenResumed(action: () -> Unit) {
        if (activityResumed) {
            action()
        } else {
            pendingOnResume = action
            reorderTaskFront()
        }
    }

    private fun reorderTaskFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    // ======================== Camera ownership ========================

    /**
     * Keeps the camera-type foreground service running while the sections are
     * visible, so the CAMERA while-in-use grant stays active over the
     * underlying app. Best effort: refused silently when the Activity has not
     * yet reached onStart (a visible window is required to start a camera FGS).
     */
    private fun ensureOverlayService() {
        if (capturing) return
        if (!hasCameraPermission()) return
        if (service?.isStarted() == true) return
        if (!activityStarted) return
        CaptureForegroundService.showOverlay(this)
    }

    /** Applies persisted resolution/quality to the controller on the next bind. */
    private fun applyStoredResolutionAndQuality() {
        val settings = settingsRepo?.load() ?: return
        controller?.setRequestedResolution(
            settings.resolution?.let { Size(it.width, it.height) }
        )
        controller?.setRequestedQuality(settings.quality.key)
    }

    private fun setScreenMode(enabled: Boolean) {
        screenCaptureMode = false
        upper?.btnSwitch?.text = getString(R.string.btn_switch)
        upper?.previewView?.visibility = View.VISIBLE
        upper?.blackOverlay?.visibility = if (blackActive) View.VISIBLE else View.GONE
        upper?.screenModeCard?.visibility = View.GONE
        val current = controller?.currentLens() ?: Lens.FRONT
        setStatus(getString(if (current == Lens.FRONT) R.string.lens_front else R.string.lens_back))
    }

    /** Applies the persisted settings (lens, resolution, quality) to the UI and controller. */
    private fun applySettings() {
        val settings = settingsRepo?.load() ?: return
        setScreenMode(false)
        val target = if (settings.defaultLens == CameraControllerLens.BACK) Lens.BACK else Lens.FRONT
        if (hasCameraPermission() && !capturing) {
            ensureOverlayService()
            if (controller == null) {
                val instance = CameraController(applicationContext)
                controller = instance
                instance.switchLensTo(target)
                applyStoredResolutionAndQuality()
                bindCamera()
            } else {
                controller?.switchLensTo(target)
                applyStoredResolutionAndQuality()
                bindCamera()
            }
        }
    }

    private fun startCamera() {
        if (capturing) return
        screenCaptureMode = false
        ensureOverlayService()
        val instance = CameraController(applicationContext)
        controller = instance
        val settings = settingsRepo?.load()
        val target = if (settings?.defaultLens == CameraControllerLens.BACK) Lens.BACK else Lens.FRONT
        instance.switchLensTo(target)
        applyStoredResolutionAndQuality()
        bindCamera()
    }

    /**
     * Binds the idle preview controller to the FOREGROUND SERVICE lifecycle
     * (not this Activity), so the preview keeps running while this Activity
     * stays in the back task behind the underlying app. Retries briefly until
     * the service reaches its started state.
     */
    private fun bindCamera(attempt: Int = 0) {
        if (screenCaptureMode) return
        val instance = controller ?: return
        val preview = upper?.previewView ?: return
        val owner = service
        if (owner == null || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (attempt >= 60 || isDestroyed || isFinishing) return
            upper?.root?.postDelayed({
                if (!isDestroyed && !isFinishing && controller === instance && !screenCaptureMode) bindCamera(attempt + 1)
            }, 120L)
            return
        }
        cameraBindJob?.cancel()
        cameraBindJob = lifecycleScope.launch {
            runCatching {
                upper?.previewView?.visibility = View.VISIBLE
                upper?.screenModeCard?.visibility = View.GONE
                instance.initialize()
                instance.setFaceMode(faceModeEnabled, this@MainActivity::onFaceDetected)
                instance.bindToLifecycle(owner, preview, displayRotation())
                instance.setZoomRatio(lastZoomRatio)
                syncZoomUi()
                val current = instance.currentLens()
                setStatus(getString(if (current == Lens.FRONT) R.string.lens_front else R.string.lens_back))
                upper?.focusRing?.postDelayed({
                    if (!blackActive && !isDestroyed && !isFinishing) {
                        upper?.focusRing?.startFocusAnimation()
                    }
                }, 400L)
            }.onFailure {
                setStatus(getString(R.string.error_camera_init))
            }
        }
    }

    private fun refreshCameraOwnership() {
        if (!hasCameraPermission()) return
        if (overlayManager?.isShowing() != true) return
        if (controller == null && !capturing) {
            applySettings()
        }
    }

    private fun switchCamera() {
        if (capturing) {
            setStatus(getString(R.string.status_capture_running))
            return
        }
        val current = controller?.currentLens() ?: Lens.FRONT
        if (current == Lens.FRONT) {
            // Switch to back camera
            settingsRepo?.let { repo ->
                repo.save(repo.load().copy(defaultLens = CameraControllerLens.BACK))
            }
            if (controller == null) {
                startCamera()
            } else {
                controller?.switchLensTo(Lens.BACK)
                bindCamera()
            }
            Toast.makeText(this, "📷 Back Camera Active", Toast.LENGTH_SHORT).show()
        } else {
            // Switch to front camera
            settingsRepo?.let { repo ->
                repo.save(repo.load().copy(defaultLens = CameraControllerLens.FRONT))
            }
            if (controller == null) {
                startCamera()
            } else {
                controller?.switchLensTo(Lens.FRONT)
                bindCamera()
            }
            Toast.makeText(this, "🤳 Front Camera Active", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCapturePressed() {
        captureSingle(autofocus = true)
    }

    private fun captureScreenUi(onResult: (CaptureResult) -> Unit) {
        if (isDestroyed || isFinishing) return
        onResult(CaptureResult.Failure(IllegalStateException("Screen capture is disabled")))
    }

    private var lastCaptureTime = 0L

    private fun captureSingle(autofocus: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureTime < 500L) return
        lastCaptureTime = now

        if (capturing || bursting) return
        if (autofocus && !blackActive) {
            upper?.focusRing?.startFocusAnimation()
        }
        setStatus(getString(R.string.status_waiting_camera))
        val onResult: (CaptureResult) -> Unit = { result ->
            controller?.onFaceCaptureFinished()
            when (result) {
                is CaptureResult.Success -> {
                    setStatus(getString(R.string.status_saved, result.displayName))
                    Toast.makeText(this, getString(R.string.status_saved, result.displayName), Toast.LENGTH_SHORT).show()
                }
                is CaptureResult.Failure -> setStatus(getString(R.string.error_capture, result.error.message.orEmpty()))
            }
        }
        val instance = controller
        if (instance == null) {
            setStatus(getString(R.string.error_capture, "Camera not ready"))
            startCamera()
            return
        }
        if (autofocus) instance.focusAndCapture(onResult) else instance.captureSingle(onResult)
    }

    // ======================== Phase C: BLACK ========================

    /**
     * BLACK toggles a full-black view over the preview panel. The camera
     * session (Preview/ImageCapture/ImageAnalysis) is left untouched, so
     * CAPTURE/BURST/AUTO/SWITCH/ZOOM/SIZE keep working while hidden.
     */
    private fun toggleBlackOverlay() {
        blackActive = !blackActive
        upper?.blackOverlay?.visibility = if (blackActive) View.VISIBLE else View.GONE
        upper?.btnBlack?.setText(if (blackActive) R.string.btn_black_on else R.string.btn_black)
        setStatus(getString(if (blackActive) R.string.status_black_on else R.string.status_black_off))
    }

    // ======================== Phase C: FACE ========================

    private fun toggleFaceMode() {
        faceModeEnabled = !faceModeEnabled
        val instance = controller
        if (instance != null) {
            instance.setFaceMode(faceModeEnabled, this::onFaceDetected)
        }
        lower?.btnFace?.setText(if (faceModeEnabled) R.string.btn_face_on else R.string.btn_face)
        setStatus(getString(if (faceModeEnabled) R.string.status_face_on else R.string.status_face_off))
    }

    /**
     * Called from the ML Kit analysis thread when a face is present and the
     * cooldown / in-flight gate is open. Hopping to the main thread and reusing
     * the EXISTING single-photo pipeline (MediaStore save + email queue + status).
     */
    private fun onFaceDetected() {
        upper?.root?.post {
            if (isDestroyed || isFinishing) return@post
            if (!faceModeEnabled) return@post
            if (capturing || bursting || controller == null || !hasCameraPermission()) {
                // No capture started; release the gate so a later window can fire.
                controller?.onFaceCaptureFinished()
                return@post
            }
            captureSingle()
        }
    }

    private fun startBurst() {
        if (!hasCameraPermission()) {
            runWithOverlayHidden { requestCameraPermission() }
            return
        }
        if (capturing) {
            setStatus(getString(R.string.status_capture_running))
            return
        }
        // Second tap while a burst is running cancels it.
        if (bursting) {
            controller?.stopBurst()
            finishBurst()
            setStatus(getString(R.string.status_burst_cancelled))
            return
        }
        val instance = controller
        if (instance == null) {
            setStatus(getString(R.string.error_capture, "Camera not ready"))
            return
        }
        bursting = true
        var saved = 0
        lower?.btnCapture?.isEnabled = false
        lower?.btnAuto?.isEnabled = false
        upper?.btnSwitch?.isEnabled = false
        upper?.sizeSeekBar?.isEnabled = false
        upper?.zoomSeekBar?.isEnabled = false
        setBurstLabel(R.string.btn_burst_stop)
        setStatus(getString(R.string.status_burst_starting, BURST_COUNT))
        instance.startBurst(BURST_COUNT, BURST_DELAY_MS) { shot ->
            if (shot.result is CaptureResult.Success) {
                saved++
            }
            if (shot.index >= shot.count) {
                finishBurst()
                setStatus(getString(R.string.status_burst_done, saved))
            } else {
                setStatus(getString(R.string.status_burst_progress, shot.index, shot.count))
            }
        }
    }

    private fun finishBurst() {
        bursting = false
        setBurstLabel(R.string.btn_burst)
        refreshCaptureUi()
    }

    /** Updates the BURST button label. */
    private fun setBurstLabel(textRes: Int) {
        lower?.btnBurst?.setText(textRes)
    }

    private fun onExitPressed() {
        if (bursting) {
            controller?.stopBurst()
            bursting = false
        }
        // Remove the two overlay sections first so no orphan overlay remains.
        overlayManager?.hide()
        unregisterVolumeControl()
        // Stop the foreground service whenever it is running (AUTO capture or
        // the idle overlay foreground) so its notification is removed and no
        // background component is left running.
        if (serviceBound || capturing) {
            CaptureForegroundService.stopCapture(this)
        }
        controller?.release()
        controller = null
        finish()
    }

    /**
     * MINIMIZE hides the two overlay sections and goes back to the underlying
     * app (NOT exit). The camera-type foreground service stays running — for an
     * AUTO session it is the active capture owner; otherwise it remains the
     * persistent owner behind the notification — so capture is never lost and
     * the notification restores the sections on tap.
     */
    private fun onMinimizePressed() {
        if (!capturing) {
            controller?.release()
            controller = null
        }
        overlayManager?.hide()
        moveTaskToBack(true)
    }

    private var lastAutoToggleTime = 0L

    private fun toggleAutoCapture() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoToggleTime < 800L) {
            Log.d("SCOS3-VOL", "toggleAutoCapture debounced (ignoring duplicate event within 800ms)")
            return
        }
        lastAutoToggleTime = now

        if (!hasCameraPermission()) {
            runWithOverlayHidden { requestCameraPermission() }
            return
        }
        if (bursting) {
            setStatus(getString(R.string.status_burst_running))
            return
        }
        if (capturing) {
            stopAutoCapture()
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            runWithOverlayHidden { maybeRequestNotificationsAndStartAuto() }
        } else {
            maybeRequestNotificationsAndStartAuto()
        }
    }

    private fun selectedIntervalMs(): Long =
        settingsRepo?.load()?.intervalMs ?: SettingsRepository.DEFAULT_INTERVAL_MS

    /**
     * Requests POST_NOTIFICATIONS lazily, only when the user opts into AUTO
     * capture. If not granted the FGS still runs (its notification is exempt);
     * the AUTO session always proceeds.
     */
    private fun maybeRequestNotificationsAndStartAuto() {
        val request = AutoStartRequest(
            intervalMs = selectedIntervalMs(),
            lens = controller?.currentLens() ?: CameraController.Lens.FRONT,
            ultrawide = controller?.zoomSourceIsWide() ?: false,
            zoom = controller?.currentZoomRatio() ?: 1f,
        )
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingAutoStart = request
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAutoCapture(request)
        }
    }

    private fun startAutoCapture(request: AutoStartRequest) {
        hasRunAutoCapture = true
        lastAutoLens = request.lens
        lastAutoUltrawide = request.ultrawide
        lastAutoZoom = request.zoom
        setStatus(getString(R.string.status_capture_starting))

        CaptureForegroundService.showOverlay(this)

        capturing = true
        refreshCaptureUi()

        if (!blackActive) {
            upper?.focusRing?.startFocusAnimation()
        }

        val instance = controller
        if (instance == null) {
            setStatus(getString(R.string.error_capture, "Camera not ready"))
            capturing = false
            refreshCaptureUi()
            return
        }

        var count = 0
        instance.startIntervalCapture(request.intervalMs) { result ->
            when (result) {
                is CaptureResult.Success -> {
                    count++
                    setStatus(getString(R.string.status_capture_active, request.intervalMs / 1000L, count))
                    service?.updateCaptureNotification(request.intervalMs, count)
                }
                is CaptureResult.Failure -> {
                    Log.e("SCOS3", "Interval capture failed: ${result.error.message}")
                }
            }
        }
    }

    private fun stopAutoCapture() {
        autoScreenJob?.cancel()
        autoScreenJob = null
        controller?.stopIntervalCapture()
        capturing = false
        refreshCaptureUi()
        val current = controller?.currentLens() ?: Lens.FRONT
        setStatus(getString(if (current == Lens.FRONT) R.string.lens_front else R.string.lens_back))
        service?.updateCaptureNotification(0L, 0)
    }

    private fun refreshCaptureUi() {
        if (isDestroyed || isFinishing) return
        if (bursting) return
        if (capturing) {
            lower?.btnAuto?.setText(R.string.stop_auto_capture)
            lower?.btnAuto?.isEnabled = true
            upper?.btnSwitch?.isEnabled = false
            upper?.sizeSeekBar?.isEnabled = true
            upper?.zoomSeekBar?.isEnabled = true
            lower?.btnCapture?.isEnabled = false
            lower?.btnFace?.isEnabled = false
            setStatus(
                getString(
                    R.string.status_capture_active,
                    selectedIntervalMs() / 1000L,
                    service?.captureCount() ?: 0,
                )
            )
        } else {
            lower?.btnAuto?.setText(R.string.btn_auto)
            lower?.btnAuto?.isEnabled = true
            upper?.btnSwitch?.isEnabled = true
            upper?.sizeSeekBar?.isEnabled = true
            lower?.btnCapture?.isEnabled = true
            lower?.btnFace?.isEnabled = true
            syncZoomUi()
            setStatus(
                getString(
                    if (hasRunAutoCapture) R.string.status_capture_stopped else R.string.status_ready
                )
            )
        }
    }

    // ======================== Zoom (right slider) ========================

    private fun setupZoomSeekBar() {
        upper?.zoomSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val instance = controller ?: return
                    val (minR, maxR) = instance.sliderRange()
                    if (maxR <= minR) return
                    val ratio = minR + (maxR - minR) * (progress / 100f)
                    lastZoomRatio = ratio
                    instance.setZoomRatio(ratio)
                    updateZoomLabel()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        // Tap the zoom readout to toggle wide <-> 1.0x.
        upper?.zoomText?.setOnClickListener {
            val instance = controller ?: return@setOnClickListener
            lastZoomRatio = instance.toggleWide()
            syncZoomSliderPosition(instance)
            updateZoomLabel()
        }
    }

    private fun updateZoomLabel() {
        val ratio = controller?.currentZoomRatio() ?: 1f
        upper?.zoomText?.text = String.format("%.1fx", ratio)
    }

    private fun syncZoomSliderPosition(instance: CameraController) {
        val (minR, maxR) = instance.sliderRange()
        if (maxR <= minR) return
        val ratio = instance.currentZoomRatio()
        val frac = ((ratio - minR) / (maxR - minR)).coerceIn(0f, 1f)
        upper?.zoomSeekBar?.progress = (frac * 100).roundToInt()
    }

    private fun syncZoomUi() {
        val instance = controller ?: run {
            upper?.zoomSeekBar?.isEnabled = false
            upper?.zoomText?.visibility = View.INVISIBLE
            return
        }
        val (minR, maxR) = instance.sliderRange()
        val available = maxR > minR
        upper?.zoomSeekBar?.isEnabled = available && !capturing
        if (available) {
            upper?.zoomText?.visibility = View.VISIBLE
            syncZoomSliderPosition(instance)
            updateZoomLabel()
        } else {
            upper?.zoomText?.visibility = View.INVISIBLE
        }
    }

    // ======================== Panel size (left slider) ========================

    private fun setupSizeSlider() {
        upper?.sizeSeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyPanelSize(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        // Apply the initial default size once the layout is measured.
        upper?.floatingPanel?.post { applyPanelSize(upper?.sizeSeekBar?.progress ?: 22) }
    }

    private fun applyPanelSize(progress: Int) {
        val dm = resources.displayMetrics
        val maxW = (dm.widthPixels * 0.62f).roundToInt()
        val minW = (dm.widthPixels * 0.24f).roundToInt()
        val w = (minW + (maxW - minW) * (progress / 100f)).roundToInt()
        val h = (w * 4f / 3f).roundToInt()
        val panel = upper?.floatingPanel ?: return
        val lp = panel.layoutParams
        lp.width = w
        lp.height = h.coerceAtMost((dm.heightPixels * 0.7f).roundToInt())
        panel.layoutParams = lp
    }

    // ======================== Drag panel ========================

    private fun setupDragHandle() {
        upper?.dragHandle?.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = e.rawX
                    dragStartY = e.rawY
                    dragStartLeft = upper?.floatingPanel?.left ?: 0
                    dragStartTop = upper?.floatingPanel?.top ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val panel = upper?.floatingPanel ?: return@setOnTouchListener false
                    val parent = upper?.root ?: return@setOnTouchListener false
                    val lp = panel.layoutParams as android.widget.FrameLayout.LayoutParams
                    val maxLeft = (parent.width - panel.width).coerceAtLeast(0)
                    val maxTop = (parent.height - panel.height).coerceAtLeast(0)
                    lp.leftMargin = (dragStartLeft + (e.rawX - dragStartX)).roundToInt().coerceIn(0, maxLeft)
                    lp.topMargin = (dragStartTop + (e.rawY - dragStartY)).roundToInt().coerceIn(0, maxTop)
                    panel.layoutParams = lp
                    true
                }
                else -> false
            }
        }
    }

    // ======================== Focus / touch ========================

    private fun onPreviewTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val preview = upper?.previewView ?: return true
            val point = preview.meteringPointFactory.createPoint(event.x, event.y)
            controller?.focusAtPoint(point)
            if (!blackActive) {
                upper?.focusRing?.startFocusAnimation(event.x, event.y)
            }
        }
        return true
    }

    // ======================== Settings / help / diagnostics ========================

    private fun onSettingPressed() {
        secondaryUiActive = true
        overlayManager?.hide()
        unregisterVolumeControl()
        settingsActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun onHelpPressed() {
        runWithOverlayHidden { showHelpDialog() }
    }

    private fun showHelpDialog() {
        launchingDiagnostics = false
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(getString(R.string.help_body))
            .setPositiveButton(R.string.help_diagnostics) { _, _ ->
                launchDiagnostics()
            }
            .setNegativeButton(android.R.string.ok, null)
            .setOnDismissListener {
                if (isDestroyed || isFinishing) return@setOnDismissListener
                if (!launchingDiagnostics) restoreOverlayUi()
            }
            .show()
    }

    private fun launchDiagnostics() {
        launchingDiagnostics = true
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }

    // ======================== Misc helpers ========================

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun setStatus(text: String) {
        if (isDestroyed || isFinishing) return
        upper?.statusText?.text = text
    }

    private fun onVolumeDownPressed() {
        Log.d("SCOS3-VOL", "volume DOWN -> captureSingle")
        runOnUiThread {
            if (!capturing && !bursting) captureSingle()
        }
    }

    private fun onVolumeUpPressed() {
        Log.d("SCOS3-VOL", "volume UP -> toggleAutoCapture")
        runOnUiThread {
            toggleAutoCapture()
        }
    }

    /** Registers SC OS3 as ACTIVE volume control target (overlay on screen). */
    private fun registerVolumeControl() {
        if (isDestroyed || isFinishing) return
        Log.d("SCOS3-VOL", "registerVolumeControl")
        VolumeKeyDispatcher.register(volumeListener)
    }

    /** Marks SC OS3 INACTIVE; volume keys pass through to the system again. */
    private fun unregisterVolumeControl() {
        Log.d("SCOS3-VOL", "unregisterVolumeControl")
        VolumeKeyDispatcher.unregister(volumeListener)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0 && !capturing && !bursting) {
                    captureSingle()
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) {
                    toggleAutoCapture()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private companion object {
        const val BURST_COUNT = 5
        const val BURST_DELAY_MS = 350L
    }

    /** AUTO capture parameters captured before the notification permission prompt. */
    private data class AutoStartRequest(
        val intervalMs: Long,
        val lens: CameraController.Lens,
        val ultrawide: Boolean,
        val zoom: Float,
    )
}