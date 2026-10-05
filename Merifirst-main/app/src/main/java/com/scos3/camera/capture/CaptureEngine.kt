package com.scos3.camera.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.scos3.camera.email.EmailManager
import com.scos3.camera.email.EmailManagerProvider
import com.scos3.camera.email.ImageCompressor
import com.scos3.camera.settings.SettingsRepository
import com.scos3.camera.storage.MediaStoreRepository
import com.scos3.camera.storage.PrivateStorageRepository
import com.scos3.camera.storage.SavedImage
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One completed shot within a burst. Delivered on the main thread. */
data class BurstShot(
    val index: Int,
    val count: Int,
    val result: CaptureResult,
)

/**
 * Capture engine. Single-shot JPEG capture plus AUTO/INTERVAL capture on a
 * configurable schedule, written to MediaStore and delivered as
 * [CaptureResult] callbacks.
 *
 * The engine owns background executors so capture and storage I/O never run on
 * the main thread, while the result callback is always posted to main.
 *
 * Interval mode is self-rescheduling and guarantees a single in-flight
 * capture and exactly ONE scheduled loop:
 *  - the next tick is scheduled only after the previous capture completes,
 *  - if a capture is already in flight, a tick never overlaps it,
 *  - [stopInterval] cancels the pending tick and resets the engine so it can
 *    be restarted on the SAME instance (the executor is NOT shut down here,
 *    only [close] shuts it down).
 */
class CaptureEngine(context: Context) {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val settingsRepository = SettingsRepository(context)
    private val mediaStoreRepository = MediaStoreRepository(context)
    private val privateStorageRepository = PrivateStorageRepository(context)
    private val emailManager: EmailManager = EmailManagerProvider.get(context)
    private val imageCompressor = ImageCompressor(context)
    private val haptics = Haptics(context)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val capturing = AtomicBoolean(false)
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var stopped = true
    private var currentIntervalMs: Long = 0L
    private var currentImageCapture: ImageCapture? = null
    private var currentListener: ((CaptureResult) -> Unit)? = null

    private var burstStopped = true
    private var currentBurstCount = 0
    private var currentBurstDelayMs = 350L
    private var currentBurstListener: ((BurstShot) -> Unit)? = null

    fun captureSingle(imageCapture: ImageCapture, onResult: (CaptureResult) -> Unit) {
        imageCapture.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = image.jpegBytes()
                val width = image.width
                val height = image.height
                val outcome: CaptureResult = runCatching {
                    try {
                        val saved = storeImage(jpeg, width, height)
                        CaptureResult.Success(saved.uri, saved.width, saved.height, saved.displayName)
                    } finally {
                        image.close()
                    }
                }.getOrElse { CaptureResult.Failure(it) }
                if (outcome is CaptureResult.Success) {
                    haptics.shutterTick()
                    deliverByEmail(outcome, jpeg)
                }
                mainExecutor.execute { onResult(outcome) }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute { onResult(CaptureResult.Failure(exception)) }
            }
        })
    }

    fun captureBitmap(bitmap: android.graphics.Bitmap, onResult: (CaptureResult) -> Unit) {
        ioExecutor.execute {
            try {
                val swBitmap = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                    bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                } else {
                    bitmap
                }
                val width = swBitmap.width
                val height = swBitmap.height
                val stream = java.io.ByteArrayOutputStream()
                swBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
                val jpeg = stream.toByteArray()
                if (swBitmap !== bitmap) swBitmap.recycle()
                bitmap.recycle()

                val saved = storeImage(jpeg, width, height)
                val outcome = CaptureResult.Success(saved.uri, saved.width, saved.height, saved.displayName)
                haptics.shutterTick()
                deliverByEmail(outcome, jpeg)
                mainExecutor.execute { onResult(outcome) }
            } catch (e: Exception) {
                Log.e("SCOS3-CAPTURE", "captureBitmap failed", e)
                mainExecutor.execute { onResult(CaptureResult.Failure(e)) }
            }
        }
    }

    /**
     * Starts AUTO/INTERVAL capture. Replaces any previously running interval
     * schedule (idempotent start). Safe to call again after [stopInterval] on
     * the same engine instance.
     *
     * @param intervalMs spacing between capture attempts (2/4/6/10 s).
     * @param imageCapture the bound ImageCapture use case.
     * @param onResult posted on the main thread for every attempt.
     */
    fun startInterval(
        intervalMs: Long,
        imageCapture: ImageCapture,
        onResult: (CaptureResult) -> Unit,
    ) {
        if (intervalMs <= 0L) return
        stopInterval()
        burstStopped = true
        currentIntervalMs = intervalMs
        currentImageCapture = imageCapture
        currentListener = onResult
        stopped = false
        scheduleNext()
    }

    private fun scheduleNext() {
        if (stopped || scheduler.isShutdown) return
        val task = object : Runnable {
            override fun run() {
                val capture = currentImageCapture
                val listener = currentListener
                if (capture == null || listener == null) return
                if (!capturing.compareAndSet(false, true)) return
                captureSingle(capture) { result ->
                    capturing.set(false)
                    listener(result)
                    if (!stopped) {
                        scheduledFuture = scheduler.schedule(this, currentIntervalMs, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        scheduledFuture = scheduler.schedule(task, 0L, TimeUnit.MILLISECONDS)
    }

    fun isIntervalRunning(): Boolean = !stopped && !scheduler.isShutdown

    fun currentIntervalMs(): Long = currentIntervalMs

    /** Cancels interval capture; any in-flight capture finishes and is reported. */
    fun stopInterval() {
        stopped = true
        burstStopped = true
        scheduledFuture?.cancel(true)
        scheduledFuture = null
        currentImageCapture = null
        currentListener = null
        capturing.set(false)
    }

    /**
     * Runs a burst of [count] consecutive still images, one at a time. The next
     * shot is only scheduled after the previous one fully completes, so no two
     * CameraX capture requests are ever in flight. Each completed shot is
     * reported via [onShot] (main thread). [stopBurst] cancels any pending shots.
     */
    fun startBurst(
        imageCapture: ImageCapture,
        count: Int,
        delayMs: Long,
        onShot: (BurstShot) -> Unit,
    ) {
        if (count <= 0) return
        stopInterval()
        burstStopped = false
        currentImageCapture = imageCapture
        currentBurstCount = count
        currentBurstDelayMs = delayMs
        currentBurstListener = onShot
        fireNextBurstShot(0)
    }

    private fun fireNextBurstShot(index: Int) {
        if (burstStopped) return
        val capture = currentImageCapture ?: return
        val listener = currentBurstListener ?: return
        val count = currentBurstCount
        capture.takePicture(ioExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val jpeg = image.jpegBytes()
                val width = image.width
                val height = image.height
                val outcome: CaptureResult = runCatching {
                    try {
                        val saved = storeImage(jpeg, width, height)
                        CaptureResult.Success(saved.uri, saved.width, saved.height, saved.displayName)
                    } finally {
                        image.close()
                    }
                }.getOrElse { CaptureResult.Failure(it) }
                if (outcome is CaptureResult.Success) {
                    haptics.shutterTick()
                    deliverByEmail(outcome, jpeg)
                }
                postBurstShot(BurstShot(index + 1, count, outcome))
            }

            override fun onError(exception: ImageCaptureException) {
                postBurstShot(BurstShot(index + 1, count, CaptureResult.Failure(exception)))
            }
        })
    }

    private fun postBurstShot(shot: BurstShot) {
        mainExecutor.execute {
            val listener = currentBurstListener
            if (listener != null && !burstStopped) listener(shot)
            if (!burstStopped && shot.index < shot.count) {
                scheduler.schedule({
                    mainExecutor.execute { fireNextBurstShot(shot.index) }
                }, currentBurstDelayMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Shared storage decision for every successful still image capture (manual
     * CAPTURE, FACE, volume, AUTO/interval and BURST all funnel through here).
     *
     * Gallery Save ON  -> MediaStore  (visible in the Android Gallery).
     * Gallery Save OFF -> app-private durable storage (not exposed to Gallery).
     */
    private fun storeImage(jpeg: ByteArray, width: Int, height: Int): SavedImage =
        if (settingsRepository.isGallerySaveEnabled()) {
            mediaStoreRepository.saveImage(jpeg, width, height)
        } else {
            privateStorageRepository.saveImage(jpeg, width, height)
        }

    /**
     * Submits a newly stored photo to the existing email queue only when Email
     * sending is ON. The original JPEG bytes are compressed to a separate file
     * before queueing so the email attachment is smaller; the original image
     * is never modified. Compression runs on [ioExecutor] so capture is never
     * blocked.
     *
     * If compression fails, the failure is logged and no email item is queued
     * (the original is never sent as a fallback).
     */
    private fun deliverByEmail(result: CaptureResult.Success, originalJpeg: ByteArray) {
        if (!settingsRepository.isEmailEnabled()) return
        ioExecutor.execute {
            runCatching {
                Log.d("SCOS3-C4", "Starting compression: ${originalJpeg.size} bytes, ${result.width}x${result.height}")
                val compressedUri = imageCompressor.compress(originalJpeg, result.width, result.height)
                Log.d("SCOS3-C4", "Compressed URI: $compressedUri")
                emailManager.enqueueAndAttempt(compressedUri, result.displayName)
                Log.d("SCOS3-C4", "Enqueued for email: ${result.displayName}")
            }.onFailure {
                Log.e("SCOS3-C4", "Compression/email failed", it)
            }
        }
    }

    /** Cancels burst capture; the current in-flight shot still completes and is reported. */
    fun stopBurst() {
        burstStopped = true
        currentBurstListener = null
    }

    fun close() {
        stopInterval()
        burstStopped = true
        scheduler.shutdownNow()
        ioExecutor.shutdown()
    }
}
