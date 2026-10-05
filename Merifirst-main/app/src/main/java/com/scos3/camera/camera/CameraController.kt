package com.scos3.camera.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.scos3.camera.capture.BurstShot
import com.scos3.camera.capture.CaptureEngine
import com.scos3.camera.capture.CaptureResult
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level camera facade used by the UI and (in capture-only mode) by the
 * foreground service.
 *
 * Responsibilities:
 *  - Bind Preview + ImageCapture to a lifecycle owner.
 *  - Expose capture, lens switching, tap-to-focus and zoom.
 *  - Expose "0.5x" the way the device genuinely supports it (zoom is RATIO
 *    based, so the slider maps onto a real zoom ratio 0.5x..max):
 *      (a) via the back logical camera's zoom-ratio range when it dips below
 *          1.0 (CameraX setZoomRatio covers it), or
 *      (b) by binding the physical ultra-wide camera through Camera2Interop
 *          when the logical camera is clamped at 1.0.
 *  - Delegate capture to the [CaptureEngine].
 *
 * The default zoom ratio is 1.0x. [setZoomRatio] switches the bound sensor
 * automatically when the target ratio cannot be produced by the current one.
 */
class CameraController(context: Context) {

    enum class Lens(val facing: Int) {
        BACK(CameraSelector.LENS_FACING_BACK),
        FRONT(CameraSelector.LENS_FACING_FRONT),
    }

    /** Which sensor the current binding uses. */
    private enum class ZoomSource { LOGICAL, ULTRWIDE }

    private val appContext = context.applicationContext
    private val cameraManager = CameraManager(appContext)
    private val capabilities = CameraCapabilities(appContext)
    private val captureEngine = CaptureEngine(appContext)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var lens: Lens = Lens.BACK
    private var zoomSource: ZoomSource = ZoomSource.LOGICAL
    private var lastTargetRatio = 1f
    private var configuredSize: Size? = null
    private var configuredQuality: Int = 95

    // Manual-capture autofocus: serializes the focus-before-capture flow so
    // rapid CAPTURE taps never produce overlapping captures.
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val focusTimeoutHandler = Handler(Looper.getMainLooper())
    private val focusCaptureInFlight = AtomicBoolean(false)

    // FACE auto-capture. The ImageAnalysis use case is always bound in preview
    // mode; the ML Kit detector inside the controller only runs while FACE is on.
    private val faceDetection = FaceDetectionController()
    private val faceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceEnabled = false

    // Binding context so we can re-bind when the zoom source changes.
    private var boundOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null
    private var boundRotation: Int = android.view.Surface.ROTATION_0
    private var previewBound = false

    /** Resolution requested in Settings. Applied when a use case is next bound. */
    fun setRequestedResolution(size: Size?) {
        configuredSize = size
    }

    fun requestedResolution(): Size? = configuredSize

    /** JPEG quality (1-100) requested in Settings. */
    fun setRequestedQuality(quality: Int) {
        configuredQuality = quality.coerceIn(1, 100)
    }

    fun capabilities(): CameraCapabilities = capabilities

    /** Idempotent: safe to call repeatedly (camera provider is a process singleton). */
    suspend fun initialize() {
        cameraProvider = cameraManager.getProvider()
    }

    /** Must run on the main thread. Binds Preview + ImageCapture to the given lifecycle owner. */
    fun bindToLifecycle(owner: LifecycleOwner, previewView: PreviewView, targetRotation: Int) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = buildSelector()
        if (!provider.hasCamera(selector)) return

        boundOwner = owner
        boundPreviewView = previewView
        boundRotation = targetRotation
        previewBound = true

        val preview = buildPreview(previewView)
        val capture = buildImageCapture(targetRotation)
        val analysis = buildImageAnalysis()

        camera = provider.bindToLifecycle(owner, selector, preview, capture, analysis)
        this.preview = preview
        imageCapture = capture
        imageAnalysis = analysis
        applyRatio(lastTargetRatio)
    }

    /**
     * Binds ONLY the ImageCapture use case to the given lifecycle owner.
     * No Preview, so capture keeps working when the screen is off.
     * Used by the foreground service. The service is the single camera owner
     * while interval capture runs; the UI must not bind at the same time.
     */
    fun bindCaptureOnly(owner: LifecycleOwner, targetRotation: Int) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = buildSelector()
        if (!provider.hasCamera(selector)) return

        boundOwner = owner
        boundPreviewView = null
        boundRotation = targetRotation
        previewBound = false

        val capture = buildImageCapture(targetRotation)

        camera = provider.bindToLifecycle(owner, selector, capture)
        preview = null
        imageCapture = capture
        applyRatio(lastTargetRatio)
    }

    /** Re-binds the current use cases after the zoom source changed. */
    fun rebindActiveCamera() {
        val owner = boundOwner ?: return
        val provider = cameraProvider ?: return
        val selector = buildSelector()
        if (!provider.hasCamera(selector)) return

        // A new selector means a new LifecycleCamera: the previous camera must
        // be unbound first or CameraX throws
        // "Multiple LifecycleCameras ... registered to the same LifecycleOwner".
        provider.unbindAll()

        if (previewBound && boundPreviewView != null) {
            val p = buildPreview(boundPreviewView!!)
            val c = buildImageCapture(boundRotation)
            val a = buildImageAnalysis()
            camera = provider.bindToLifecycle(owner, selector, p, c, a)
            preview = p
            imageCapture = c
            imageAnalysis = a
        } else {
            val c = buildImageCapture(boundRotation)
            camera = provider.bindToLifecycle(owner, selector, c)
            preview = null
            imageCapture = c
        }
        applyRatio(lastTargetRatio)
    }

    private fun buildPreview(previewView: PreviewView): Preview {
        val builder = Preview.Builder()
        applyPhysicalCamera(builder)
        return builder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    /**
     * Preview-pipeline ImageAnalysis bound alongside Preview + ImageCapture.
     * Frames are consumed by [FaceDetectionController]; with FACE off it simply
     * closes every proxy immediately, so the camera stream is unaffected.
     */
    private fun buildImageAnalysis(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(boundRotation)
            .build()
            .also { it.setAnalyzer(faceExecutor, faceDetection) }
    }

    /**
     * Binds the target physical sensor for 0.5x.
     *
     * Preference order:
     *  1. If CameraX exposes the ultra-wide as its OWN camera id (as on the
     *     test device, where id=2 is a standalone back camera in
     *     provider.availableCameraInfos), the sensor is selected through the
     *     [buildSelector] Camera filter — no Camera2Interop is needed.
     *  2. Otherwise (ultra-wide only reachable as a physical sub-camera of a
     *     logical multi-camera), pin it via Camera2Interop.setPhysicalCameraId.
     */
    private fun applyPhysicalCamera(builder: Preview.Builder) {
        if (zoomSource != ZoomSource.ULTRWIDE || lens != Lens.BACK) return
        val uwId = capabilities.ultrawidePhysicalCameraId() ?: return
        if (isUltrawideExposedDirectly(uwId)) return
        Camera2Interop.Extender(builder).setPhysicalCameraId(uwId)
    }

    private fun buildImageCapture(targetRotation: Int): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
        configuredSize?.let { size ->
            val strategy = ResolutionStrategy(
                size,
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            )
            builder.setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(strategy).build()
            )
        }
        builder.setJpegQuality(configuredQuality)
        applyPhysicalCamera(builder)
        return builder.build()
    }

    private fun applyPhysicalCamera(builder: ImageCapture.Builder) {
        if (zoomSource != ZoomSource.ULTRWIDE || lens != Lens.BACK) return
        val uwId = capabilities.ultrawidePhysicalCameraId() ?: return
        if (isUltrawideExposedDirectly(uwId)) return
        Camera2Interop.Extender(builder).setPhysicalCameraId(uwId)
    }

    fun switchLens() {
        lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK
        // Front has no ultra-wide; back re-derives the source from the ratio.
        zoomSource = desiredZoomSource(lastTargetRatio)
    }

    /** Sets an explicit lens (used to honor the persisted default camera). */
    fun switchLensTo(target: Lens) {
        if (lens != target) {
            lens = target
            zoomSource = desiredZoomSource(lastTargetRatio)
        }
    }

    fun currentLens(): Lens = lens

    fun hasUltrawideBack(): Boolean =
        capabilities.supportsSubUnitZoomRatio() || capabilities.ultrawidePhysicalCameraId() != null

    fun zoomSourceIsWide(): Boolean = zoomSource == ZoomSource.ULTRWIDE

    fun zoomState(): ZoomState? = camera?.cameraInfo?.zoomState?.value

    /**
     * Effective zoom ratio range exposed by the zoom slider, in "0.5x..max"
     * terms. For the back camera this includes the ultra-wide segment when the
     * device genuinely has one.
     */
    fun sliderRange(): Pair<Float, Float> {
        if (lens == Lens.FRONT) {
            val zs = zoomState() ?: return 1f to 1f
            return zs.minZoomRatio to zs.maxZoomRatio
        }
        val logical = capabilities.backLogicalZoomRange()
        val maxR = logical?.second ?: zoomState()?.maxZoomRatio ?: 1f
        val minR = when {
            logical == null -> zoomState()?.minZoomRatio ?: 1f
            logical.first < 1f -> logical.first
            else -> capabilities.uwToMainRatio()
        }
        return minR to maxR
    }

    /** Effective zoom ratio currently applied (0.5x = ultra-wide sensor). */
    fun currentZoomRatio(): Float {
        val cam = camera ?: return 1f
        val r = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        return if (zoomSource == ZoomSource.ULTRWIDE) r * capabilities.uwToMainRatio() else r
    }

    /**
     * Sets the effective zoom ratio (e.g. 0.5f, 1.0f, 3f). Switches between the
     * logical camera and the physical ultra-wide camera when needed.
     */
    fun setZoomRatio(target: Float) {
        val (minR, maxR) = sliderRange()
        val clamped = target.coerceIn(minR, maxR)
        lastTargetRatio = clamped
        val desired = desiredZoomSource(clamped)
        if (desired != zoomSource) {
            zoomSource = desired
            rebindActiveCamera()
        }
        applyRatio(clamped)
    }

    /** Toggles between the widest effective ratio and 1.0x; returns the new ratio. */
    fun toggleWide(): Float {
        val current = currentZoomRatio()
        val (minR, _) = sliderRange()
        // At <1.0x the physical ultra-wide is active: hop back to 1.0x. At
        // 1.0x or above, drop to the widest (0.5x) ratio.
        val target = if (current < 1.0f) 1.0f else minR
        setZoomRatio(target)
        return currentZoomRatio()
    }

    private fun desiredZoomSource(ratio: Float): ZoomSource {
        if (lens != Lens.BACK) return ZoomSource.LOGICAL
        if (capabilities.supportsSubUnitZoomRatio()) return ZoomSource.LOGICAL
        val logicalMin = capabilities.backLogicalZoomRange()?.first ?: 1f
        if (capabilities.ultrawidePhysicalCameraId() == null) return ZoomSource.LOGICAL
        return if (ratio < logicalMin) ZoomSource.ULTRWIDE else ZoomSource.LOGICAL
    }

    private fun applyRatio(ratio: Float) {
        val cam = camera ?: return
        when (zoomSource) {
            ZoomSource.LOGICAL -> cam.cameraControl.setZoomRatio(ratio.coerceAtLeast(0.01f))
            ZoomSource.ULTRWIDE -> {
                val uwRatio = (ratio / capabilities.uwToMainRatio()).coerceIn(1f, 100f)
                cam.cameraControl.setZoomRatio(uwRatio)
            }
        }
    }

    fun captureSingle(onResult: (CaptureResult) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(CaptureResult.Failure(IllegalStateException("Camera not ready")))
            return
        }
        captureEngine.captureSingle(capture, onResult)
    }

    /**
     * Manual-capture path: requests a real autofocus pass at the center of the
     * preview, waits for the AF result (or a safe timeout), then takes the
     * photo. If autofocus is unavailable, fails, or times out, the photo is
     * still captured safely. Rapid CAPTURE taps while an autofocus+capture is
     * already in flight are ignored (the guard is held until the result is
     * delivered, so CAPTURE can never become permanently stuck or duplicate).
     */
    fun focusAndCapture(onResult: (CaptureResult) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(CaptureResult.Failure(IllegalStateException("Camera not ready")))
            return
        }
        if (!focusCaptureInFlight.compareAndSet(false, true)) return

        val finish: (CaptureResult) -> Unit = { result ->
            focusCaptureInFlight.set(false)
            onResult(result)
        }

        val cam = camera
        val preview = boundPreviewView?.takeIf { it.width > 0 && it.height > 0 }
        val point = preview?.let { pv ->
            pv.meteringPointFactory.createPoint(pv.width / 2f, pv.height / 2f)
        }
        if (cam == null || point == null) {
            captureEngine.captureSingle(capture, finish)
            return
        }

        val initialRatio = currentZoomRatio()
        val (minZoom, maxZoom) = sliderRange()
        val targetMax = if (maxZoom > minZoom) maxZoom else initialRatio
        val zoomInRatio = (initialRatio * 1.25f).coerceAtMost(targetMax)

        // Step 1: Auto Zoom In
        setZoomRatio(zoomInRatio)

        // Step 2: Auto Zoom Out back to initial ratio after 120ms
        focusTimeoutHandler.postDelayed({
            setZoomRatio(initialRatio)

            // Step 3: Trigger Auto Focus lock after returning to initial zoom
            focusTimeoutHandler.postDelayed({
                val focusFuture = runCatching {
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                    )
                }.getOrNull()
                if (focusFuture == null) {
                    captureEngine.captureSingle(capture, finish)
                    return@postDelayed
                }

                val done = object : Runnable {
                    private var fired = false
                    override fun run() {
                        if (fired) return
                        fired = true
                        focusTimeoutHandler.removeCallbacks(this)
                        runCatching { captureEngine.captureSingle(capture, finish) }
                            .onFailure { finish(CaptureResult.Failure(it)) }
                    }
                }
                focusFuture.addListener(done, mainExecutor)
                focusTimeoutHandler.postDelayed(done, FOCUS_TIMEOUT_MS)
            }, 120L)
        }, 120L)
    }

    /** Enables/disables FACE auto-capture and wires the capture trigger. */
    fun setFaceMode(enabled: Boolean, onFace: (() -> Unit)?) {
        faceEnabled = enabled
        faceDetection.setEnabled(enabled, onFace)
    }

    fun isFaceModeEnabled(): Boolean = faceEnabled

    /** Signals that the previous FACE-triggered capture finished (success or failure). */
    fun onFaceCaptureFinished() {
        faceDetection.onCaptureFinished()
    }

    /** Starts AUTO/INTERVAL capture on the currently bound ImageCapture use case. */
    fun startIntervalCapture(intervalMs: Long, onResult: (CaptureResult) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(CaptureResult.Failure(IllegalStateException("Camera not ready")))
            return
        }
        captureEngine.startInterval(intervalMs, capture, onResult)
    }

    fun stopIntervalCapture() = captureEngine.stopInterval()

    fun isIntervalRunning(): Boolean = captureEngine.isIntervalRunning()

    /** Runs a burst of [count] consecutive stills; each shot is reported on the main thread. */
    fun startBurst(count: Int, delayMs: Long, onShot: (BurstShot) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onShot(BurstShot(0, count, CaptureResult.Failure(IllegalStateException("Camera not ready"))))
            return
        }
        captureEngine.startBurst(capture, count, delayMs, onShot)
    }

    /** Cancels an in-flight burst; the current shot still completes and is reported. */
    fun stopBurst() = captureEngine.stopBurst()

    fun focusAtPoint(point: MeteringPoint) {
        camera?.cameraControl?.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
        )
    }

    fun release() {
        val provider = cameraProvider
        if (provider != null) {
            val toUnbind = mutableListOf<UseCase>()
            preview?.let { toUnbind += it }
            imageCapture?.let { toUnbind += it }
            imageAnalysis?.let { toUnbind += it }
            if (toUnbind.isNotEmpty()) provider.unbind(*toUnbind.toTypedArray())
        }
        camera = null
        preview = null
        imageCapture = null
        imageAnalysis = null
        boundOwner = null
        boundPreviewView = null
        previewBound = false
        faceEnabled = false
        faceDetection.close()
        faceExecutor.shutdown()
        captureEngine.close()
    }

    private fun buildSelector(): CameraSelector {
        if (zoomSource == ZoomSource.ULTRWIDE && lens == Lens.BACK) {
            val uwId = capabilities.ultrawidePhysicalCameraId()
            val provider = cameraProvider
            if (uwId != null && provider != null && isUltrawideExposedDirectly(uwId, provider)) {
                // CameraX lists the ultra-wide as its own camera id: select it
                // directly instead of asking Camera2Interop to switch a sensor.
                return CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        infos.filter { info ->
                            runCatching { Camera2CameraInfo.from(info).cameraId == uwId }
                                .getOrDefault(false)
                        }
                    }
                    .build()
            }
        }
        return CameraSelector.Builder().requireLensFacing(lens.facing).build()
    }

    /** True when CameraX exposes the given Camera2 id as its own camera. */
    private fun isUltrawideExposedDirectly(uwId: String): Boolean {
        val provider = cameraProvider ?: return false
        return isUltrawideExposedDirectly(uwId, provider)
    }

    private fun isUltrawideExposedDirectly(uwId: String, provider: ProcessCameraProvider): Boolean =
        provider.availableCameraInfos.any { info ->
            runCatching { Camera2CameraInfo.from(info).cameraId == uwId }.getOrDefault(false)
        }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 2000L
    }
}
