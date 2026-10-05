package com.scos3.camera.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FACE auto-capture coordinator wired into the CameraX [ImageAnalysis] use
 * case. Pure presence detection ("is any human face in view?") — no identity,
 * no recognition.
 *
 * Simple stable state machine:
 *
 *   NO_FACE
 *      ↓  any face detected
 *   FACE_DETECTED
 *      ↓  (cooldown open + no capture in flight)
 *   CAPTURE             → fires the existing single-photo capture pipeline
 *      ↓
 *   COOLDOWN
 *      ↓  face gone  OR  cooldown elapsed
 *   FACE_DETECTED ...
 *
 * Guards:
 *  - [captureInFlight] (AtomicBoolean) – never overlap two capture requests.
 *  - [detectionInFlight] (AtomicBoolean) – one ML Kit task at a time, so the
 *    analyzer never queues proxies and never blocks the CameraX thread.
 *  - [FRAME_THROTTLE_MS] – coarse per-frame analysis ceiling (~4 fps).
 *  - Every delivered [ImageProxy] is closed exactly once (in the ML Kit task
 *    completion listener, so the wrapped frame is valid while the detector
 *    runs).
 *  - The ML Kit detector is created lazily and only while FACE is enabled.
 *
 * The capture trigger runs on a background thread (the analysis executor / ML
 * Kit callback); callers must hop to the main thread before touching the UI.
 */
class FaceDetectionController : ImageAnalysis.Analyzer {

    private val detectorOptions: FaceDetectorOptions =
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_RATIO)
            .build()

    @Volatile
    private var detector: FaceDetector? = null

    private val enabled = AtomicBoolean(false)
    private val detectionInFlight = AtomicBoolean(false)
    private val captureInFlight = AtomicBoolean(false)

    @Volatile
    private var onFaceTrigger: (() -> Unit)? = null

    @Volatile
    private var lastAnalyzedAt = 0L

    @Volatile
    private var lastTriggerAt = 0L

    /**
     * Turns FACE auto-capture on/off and (re)wires the capture trigger.
     * Idempotent. Call from the main thread.
     */
    fun setEnabled(value: Boolean, trigger: (() -> Unit)?) {
        enabled.set(value)
        onFaceTrigger = if (value) trigger else null
        if (value) ensureDetector() else captureInFlight.set(false)
    }

    private fun ensureDetector() {
        if (detector != null) return
        synchronized(this) {
            if (detector == null) {
                detector = FaceDetection.getClient(detectorOptions)
            }
        }
    }

    /** Runs on the ImageAnalysis executor (never the CameraX thread). */
    override fun analyze(imageProxy: ImageProxy) {
        val det = detector
        if (det == null || !enabled.get()) {
            imageProxy.close()
            return
        }
        if (captureInFlight.get() || detectionInFlight.get()) {
            imageProxy.close()
            return
        }
        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzedAt < FRAME_THROTTLE_MS) {
            imageProxy.close()
            return
        }
        lastAnalyzedAt = now
        detectionInFlight.set(true)
        val inputImage = InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees)
        det.process(inputImage)
            .addOnSuccessListener { faces -> onFacesDetected(faces) }
            .addOnFailureListener { }
            .addOnCompleteListener {
                detectionInFlight.set(false)
                imageProxy.close()
            }
    }

    private fun onFacesDetected(faces: List<Face>) {
        if (!enabled.get()) return
        if (faces.isEmpty()) {
            // NO_FACE: stay quiet. A face that left and re-enters may re-trigger.
            return
        }
        if (captureInFlight.get()) {
            // A previous photo is still being saved: never overlap. If it was
            // somehow lost (no callback), force the gate open after a while.
            if (SystemClock.elapsedRealtime() - lastTriggerAt > CAPTURE_STALE_TIMEOUT_MS) {
                captureInFlight.set(false)
            } else {
                return
            }
        }
        if (SystemClock.elapsedRealtime() - lastTriggerAt < CAPTURE_COOLDOWN_MS) {
            // Still inside the post-capture cooldown window.
            return
        }
        // FACE_DETECTED → CAPTURE.
        captureInFlight.set(true)
        lastTriggerAt = SystemClock.elapsedRealtime()
        onFaceTrigger?.invoke()
    }

    /** Must be called (main thread) once the single-photo capture finished, success or failure. */
    fun onCaptureFinished() {
        captureInFlight.set(false)
    }

    fun close() {
        enabled.set(false)
        onFaceTrigger = null
        captureInFlight.set(false)
        synchronized(this) {
            detector?.close()
            detector = null
        }
    }

    private companion object {
        /** Coarse analysis ceiling between ML Kit submissions. */
        const val FRAME_THROTTLE_MS = 250L
        /** Cadence between face-triggered captures. Configurable here. */
        const val CAPTURE_COOLDOWN_MS = 3000L
        /** Safety: force the in-flight gate open if a capture callback is ever lost. */
        const val CAPTURE_STALE_TIMEOUT_MS = 8000L
        /** Face must be at least 10% of the frame width to count as present. */
        const val MIN_FACE_RATIO = 0.1f
    }
}