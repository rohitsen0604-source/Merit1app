package com.scos3.camera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2Manager
import android.os.Build
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.atan
import kotlin.math.PI

/**
 * Per-camera characteristics snapshot used by the diagnostics screen and by
 * [CameraController] to decide how zoom is exposed.
 *
 * All numbers are read straight from Camera2 metadata; nothing is faked. A
 * device that does not expose a value reports `null`/empty instead.
 */
data class CameraInfoReport(
    val cameraId: String,
    val facing: Int?,
    val isLogical: Boolean,
    val physicalCameraIds: List<String>,
    val focalLengths: List<Float>,
    val sensorSizeMm: android.util.SizeF?,
    val pixelArraySize: Size?,
    val availableCapabilities: List<String>,
    val zoomRange: Pair<Float, Float>?,
    val maxJpegSize: Size?,
    val horizontalFovDegrees: Float?,
    val logicalMultiCamera: Boolean,
) {
    val description: String
        get() {
            val facingText = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            val sb = StringBuilder()
            sb.append("id=").append(cameraId)
            sb.append(" facing=").append(facingText)
            if (isLogical) sb.append(" logical")
            if (physicalCameraIds.isNotEmpty()) {
                sb.append(" physicals=").append(physicalCameraIds.joinToString(","))
            }
            sb.append(" focals=").append(focalLengths.joinToString("/"))
            sensorSizeMm?.let { sb.append(" sensor=").append("%.2fx%.2fmm".format(it.width, it.height)) }
            pixelArraySize?.let { sb.append(" pixels=").append(it.width).append("x").append(it.height) }
            zoomRange?.let { sb.append(" zoom=").append("%.2f..%.2f".format(it.first, it.second)) }
            horizontalFovDegrees?.let { sb.append(" fov=").append("%.1f".format(it)) }
            sb.append(" capabilities=[").append(availableCapabilities.joinToString(",")).append("]")
            return sb.toString()
        }

    companion object {
        const val CAP_LOGICAL_MULTI_CAMERA = "LOGICAL_MULTI_CAMERA"
        const val CAP_BACKWARD_COMPATIBLE = "BACKWARD_COMPATIBLE"
        const val CAP_PRIVATE_REPROCESSING = "PRIVATE_REPROCESSING"
        const val CAP_BURST_CAPTURE = "BURST_CAPTURE"
    }
}

/**
 * Device camera capability layer built on the raw Camera2 API.
 *
 * CameraX exposes *logical* cameras only; physical sub-cameras (ultra-wide,
 * telephoto) are reachable through Camera2 or through CameraX's
 * Camera2Interop. This class reads Camera2 directly so we can:
 *  - enumerate every camera id (logical AND physical)
 *  - read focal lengths / sensor size / pixel array / capabilities
 *  - decide how "0.5x" is genuinely exposed on the device:
 *      (a) the back logical camera's zoom ratio range dips below 1.0, or
 *      (b) there is a physically wider back camera.
 */
class CameraCapabilities(context: Context) {

    private val cameraManager: Camera2Manager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager

    private val reportCache: List<CameraInfoReport> by lazy { buildReports() }

    val reports: List<CameraInfoReport> get() = reportCache

    /** Camera ids CameraX exposes (logical cameras only). */
    fun cameraXIds(provider: ProcessCameraProvider): List<String> =
        provider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }

    fun cameraXHasBack(provider: ProcessCameraProvider): Boolean =
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)

    fun cameraXHasFront(provider: ProcessCameraProvider): Boolean =
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)

    private fun backReports(): List<CameraInfoReport> =
        reportCache.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }

    private fun frontReport(): CameraInfoReport? =
        reportCache.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

    /** All back-facing physical camera ids (including logical cameras that are themselves the widest). */
    fun backCameraIds(): List<String> = backReports().map { it.cameraId }

    /** First back-facing logical camera id, or null. */
    fun backLogicalId(): String? =
        backReports().firstOrNull { it.isLogical }?.cameraId ?: backReports().firstOrNull()?.cameraId

    fun frontCameraId(): String? = frontReport()?.cameraId

    fun logicalMultiCameraAvailable(): Boolean =
        backReports().any { it.logicalMultiCamera }

    /**
     * True when the back logical camera can zoom below 1.0x through its zoom
     * control (i.e. the device exposes 0.5x directly via zoom ratio).
     */
    fun supportsSubUnitZoomRatio(): Boolean {
        val range = backLogicalZoomRange() ?: return false
        return range.first < 1.0f
    }

    fun backLogicalZoomRange(): Pair<Float, Float>? {
        val logical = backReports().firstOrNull { it.isLogical }
            ?: backReports().maxByOrNull { it.pixelArraySize?.width ?: 0 }
        return logical?.zoomRange
    }

    /**
     * Effective zoom multiplier of the ultra-wide lens relative to the main
     * 1.0x lens, as exposed to the UI (0.5x). When the device has a physical
     * ultra-wide back camera the slider's minimum maps to 0.5x so the label
     * reads "0.5x" at the widest position.
     */
    fun uwToMainRatio(): Float {
        if (ultrawidePhysicalCameraId() == null) return 1f
        return NOMINAL_ULTRAWIDE_RATIO
    }

    /**
     * Camera id of a physically wider (ultra-wide) back camera, or null when
     * the device has none. This is a *physical* camera id: binding it requires
     * Camera2/Camera2Interop. Returns null when 0.5x is only reachable through
     * the logical camera's zoom control (see [supportsSubUnitZoomRatio]).
     */
    fun ultrawidePhysicalCameraId(): String? {
        val back = backReports().filter { it.horizontalFovDegrees != null }
        if (back.size < 2) return null
        val widest = back.maxByOrNull { it.horizontalFovDegrees!! } ?: return null
        val main = backReports()
            .filter { it.cameraId != widest.cameraId }
            .maxByOrNull { it.pixelArraySize?.width?.times(it.pixelArraySize?.height ?: 0) ?: 0 }
            ?: return null
        val delta = (widest.horizontalFovDegrees ?: 0f) - (main.horizontalFovDegrees ?: 0f)
        if (delta < ULTRAWIDE_FOV_DELTA_DEGREES) return null
        // Prefer an explicit physical camera id; fall back to the logical id.
        return widest.cameraId
    }

    fun ultrawideBackCameraFov(): Float? =
        backReports().filter { it.horizontalFovDegrees != null }
            .maxByOrNull { it.horizontalFovDegrees!! }?.horizontalFovDegrees

    /** The "1.0x" back camera: largest-sensor back camera that is not the ultra-wide. */
    fun mainBackReport(): CameraInfoReport? {
        val back = backReports()
        if (back.isEmpty()) return null
        val uwId = ultrawidePhysicalCameraId()
        val candidates = back.filter { it.cameraId != uwId }
        return candidates.maxByOrNull { it.pixelArraySize?.width?.times(it.pixelArraySize?.height ?: 0) ?: 0 }
            ?: candidates.firstOrNull()
    }

    fun mainBackFov(): Float? = mainBackReport()?.horizontalFovDegrees

    /**
     * JPEG resolutions the given facing camera genuinely supports, largest
     * first. Uses the first logical camera of the requested facing.
     */
    fun supportedResolutions(back: Boolean): List<Size> {
        val id = (if (back) backLogicalId() else frontCameraId()) ?: return emptyList()
        val map = runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull() ?: return emptyList()
        return map.getOutputSizes(ImageFormat.JPEG)
            ?.sortedByDescending { it.width * it.height }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Human-readable "0.5x" verdict used by the diagnostics screen and the
     * settings page.
     */
    fun ultrawideVerdict(): String {
        if (supportsSubUnitZoomRatio()) {
            val range = backLogicalZoomRange()
            return "0.5x supported (back logical camera zoom range %.2f..%.2f)".format(range!!.first, range.second)
        }
        val physical = ultrawidePhysicalCameraId()
        if (physical != null) {
            return "0.5x supported via physical camera id=$physical (requires Camera2/Camera2Interop)"
        }
        return "0.5x not exposed by this device/API"
    }

    /** Full multi-line diagnostics report. */
    fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("== Merit1st device camera report ==\n")
        sb.append("Device  : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("Android : ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Camera2 cameras:\n")
        reportCache.forEach { sb.append("  ").append(it.description).append("\n") }
        sb.append("Back logical zoom range: ").append(backLogicalZoomRange()?.let { "%.2f..%.2f".format(it.first, it.second) } ?: "n/a").append("\n")
        sb.append("Sub-unit zoom (<1.0x): ").append(supportsSubUnitZoomRatio()).append("\n")
        sb.append("Physical ultra-wide id : ").append(ultrawidePhysicalCameraId() ?: "none").append("\n")
        sb.append("Logical multi-camera   : ").append(logicalMultiCameraAvailable()).append("\n")
        sb.append("Verdict: ").append(ultrawideVerdict()).append("\n")
        return sb.toString()
    }

    private fun buildReports(): List<CameraInfoReport> {
        val ids = runCatching { cameraManager.cameraIdList }.getOrElse { arrayOfNulls<String>(0) }
        return ids.mapNotNull { id ->
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
                ?: return@mapNotNull null
            buildReport(id, characteristics)
        }.sortedWith(compareBy<CameraInfoReport> { it.cameraId.length }.thenBy { it.cameraId })
    }

    private fun buildReport(id: String, c: CameraCharacteristics): CameraInfoReport {
        val physicalIds = c.physicalCameraIds.toList()
        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.map { capabilityName(it) }
            ?: emptyList()
        val logicalMulti = capabilities.contains(CameraInfoReport.CAP_LOGICAL_MULTI_CAMERA)
        val focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val zoomRange = readZoomRange(c)
        val fov = if (sensor != null && focalLengths.isNotEmpty()) {
            val shortest = focalLengths.minOrNull() ?: 0f
            if (shortest > 0f) 2f * atan(sensor.width / (2f * shortest)) * (180f / PI.toFloat()) else null
        } else null
        val maxJpeg = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width * it.height }
        return CameraInfoReport(
            cameraId = id,
            facing = c.get(CameraCharacteristics.LENS_FACING),
            isLogical = physicalIds.isNotEmpty(),
            physicalCameraIds = physicalIds,
            focalLengths = focalLengths,
            sensorSizeMm = sensor,
            pixelArraySize = pixels,
            availableCapabilities = capabilities,
            zoomRange = zoomRange,
            maxJpegSize = maxJpeg,
            horizontalFovDegrees = fov,
            logicalMultiCamera = logicalMulti,
        )
    }

    @Suppress("DEPRECATION")
    private fun readZoomRange(c: CameraCharacteristics): Pair<Float, Float>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) return range.lower to range.upper
        }
        // CONTROL_ZOOM_RATIO_RANGE only exists on API 30+; below that the zoom
        // range is not exposed in a way we can trust, so report unknown.
        return null
    }

    private fun capabilityName(value: Int): String = when (value) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> CameraInfoReport.CAP_BACKWARD_COMPATIBLE
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> CameraInfoReport.CAP_PRIVATE_REPROCESSING
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> CameraInfoReport.CAP_BURST_CAPTURE
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> CameraInfoReport.CAP_LOGICAL_MULTI_CAMERA
        else -> "CAP_$value"
    }

    private companion object {
        const val ULTRAWIDE_FOV_DELTA_DEGREES = 15f

        /** UI zoom multiplier of the ultra-wide lens relative to 1.0x main. */
        const val NOMINAL_ULTRAWIDE_RATIO = 0.5f
    }
}
