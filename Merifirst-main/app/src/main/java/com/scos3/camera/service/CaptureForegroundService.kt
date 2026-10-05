package com.scos3.camera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.scos3.camera.R
import com.scos3.camera.camera.CameraController
import com.scos3.camera.capture.CaptureResult
import com.scos3.camera.ui.camera.MainActivity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch

/**
 * Foreground camera service. Owns the camera while AUTO/INTERVAL capture is
 * active, so capture continues when the UI is minimized, the Activity is
 * destroyed, or the screen is off.
 *
 * Ownership model:
 *  - Activity: UI + preview interaction (idle only).
 *  - This service: long-running, user-started capture task. It is the ONLY
 *    camera owner while AUTO runs. The Activity must not bind the camera then.
 *  - CaptureEngine inside CameraController: capture scheduling (single loop).
 *  - MediaStoreRepository: local storage.
 *  - EmailManager: queued upload (never blocks capture).
 *
 * Android contract (targetSdk 36):
 *  - Must be started via [startCapture] while a visible Activity is on screen
 *    (CAMERA is a while-in-use permission; a camera FGS cannot start from the
 *    background). Starting from a visible activity is the documented model.
 *  - [ServiceCompat.startForeground] with FOREGROUND_SERVICE_TYPE_CAMERA must
 *    be called promptly after startForegroundService (else ANR).
 *  - START_NOT_STICKY: the OS must never relaunch a camera service on its own.
 *
 * Reopening the app (notification OPEN) reconnects the Activity to this same
 * running service without stopping it.
 */
class CaptureForegroundService : LifecycleService() {

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<CaptureStatusListener>()

    private var cameraController: CameraController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var captureCount = 0
    private var activeIntervalMs = 0L
    private var minimized = false
    private var started = false
    private var mediaSession: android.media.session.MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setupMediaSession()
    }

    private fun setupMediaSession() {
        if (mediaSession != null) return
        runCatching {
            val session = android.media.session.MediaSession(this, "Merit1MediaSession")
            val state = android.media.session.PlaybackState.Builder()
                .setActions(
                    android.media.session.PlaybackState.ACTION_PLAY or
                    android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .setState(android.media.session.PlaybackState.STATE_PLAYING, android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            session.setPlaybackState(state)

            val volumeProvider = object : android.media.VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction < 0) {
                        VolumeKeyDispatcher.dispatchVolumeDown()
                    } else if (direction > 0) {
                        VolumeKeyDispatcher.dispatchVolumeUp()
                    }
                }
            }
            session.setPlaybackToRemote(volumeProvider)
            session.isActive = true
            mediaSession = session
        }
    }

    private fun releaseMediaSession() {
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        started = true
        setupMediaSession()
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureTask()
                releaseMediaSession()
                stopSelf()
            }
            ACTION_SHOW_OVERLAY -> {
                // Overlay sessions keep the foreground camera service running so
                // the CAMERA while-in-use grant stays active while the two SC OS3
                // sections float over the underlying app. No capture task starts;
                // a running capture session is never disturbed.
                minimized = false
                if (!startForegroundNow(
                        getString(R.string.notif_minimized),
                        getString(R.string.notif_minimized_sub),
                    )
                ) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_MINIMIZE -> {
                // MINIMIZE parks the app behind a persistent foreground
                // notification while the floating UI is hidden. No capture task
                // is started and a running capture session is never disturbed.
                if (!isCapturing()) {
                    minimized = true
                    if (!startForegroundNow(
                            getString(R.string.notif_minimized),
                            getString(R.string.notif_minimized_sub),
                        )
                    ) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }
            ACTION_START -> {
                // Restart any previous session cleanly.
                stopIntervalIfRunning()
                // Promote to foreground BEFORE any other work and BEFORE any
                // possible stopSelf(). The framework requires that a service
                // started via startForegroundService() calls startForeground()
                // within seconds; stopping first is what raises the fatal
                // RemoteServiceException$ForegroundServiceDidNotStartInTime
                // Exception ("did not then call Service.startForeground()").
                if (!startForegroundNow()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS)
                val lens = intent.getStringExtra(EXTRA_LENS) ?: CameraController.Lens.BACK.name
                val ultrawide = intent.getBooleanExtra(EXTRA_ULTRWIDE, false)
                val zoomRatio = intent.getFloatExtra(EXTRA_ZOOM_RATIO, 1f)
                startCaptureTask(intervalMs, lens, ultrawide, zoomRatio)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Promotes the service to foreground immediately (within the ~5s window).
     *
     * A camera-type [ServiceCompat.startForeground] requires the CAMERA
     * while-in-use grant to be active. On Android 14+ the system throws
     * `SecurityException` when it is not (for example if the app is still
     * transitioning back to the foreground after a permission dialog). In that
     * case we fall back to a plain foreground (type 0, a legal subset of the
     * declared camera type) so the framework contract is always satisfied and
     * the process can never die with a ForegroundServiceDidNotStartInTime
     * exception.
     *
     * @return true when the service entered the foreground; false when the
     *   caller must stop immediately.
     */
    private fun startForegroundNow(
        text: String = getString(R.string.notif_starting),
        subtext: String = getString(R.string.notif_auto_active),
    ): Boolean {
        ensureChannel(this)
        val notification = buildNotification(
            getString(R.string.notif_title),
            text,
            subtext,
        )
        if (hasCameraPermission()) {
            try {
                val fgsType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    fgsType,
                )
                foregroundStarted = true
                return true
            } catch (e: SecurityException) {
                // CAMERA while-in-use grant not active at this instant; fall
                // through to the non-camera foreground type instead of crashing.
            }
        }
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }.onSuccess {
            foregroundStarted = true
        }.isSuccess
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCaptureTask(
        intervalMs: Long,
        lensName: String,
        ultrawide: Boolean,
        zoomRatio: Float,
    ) {
        val controller = cameraController ?: CameraController(applicationContext).also {
            cameraController = it
        }
        acquireWakeLock()

        lifecycleScope.launch {
            runCatching {
                controller.initialize()
                val lens = if (lensName == CameraController.Lens.FRONT.name) CameraController.Lens.FRONT else CameraController.Lens.BACK
                controller.switchLensTo(lens)
                controller.bindCaptureOnly(this@CaptureForegroundService, displayRotation())
                // Resume zoom: wide sessions start at the widest ratio, others
                // restore the ratio the user had in the UI.
                val target = if (ultrawide) controller.sliderRange().first else zoomRatio
                controller.setZoomRatio(target)
            }.onFailure {
                notifyUi(intervalMs, 0, false)
                stopSelf()
                return@launch
            }

            activeIntervalMs = intervalMs
            captureCount = 0
            controller.startIntervalCapture(intervalMs) { result ->
                when (result) {
                    is CaptureResult.Success -> {
                        captureCount++
                        // Persisted storage + email submission are handled
                        // centrally by the capture engine (gated by the Email
                        // ON/OFF and Gallery Save ON/OFF settings).
                        updateNotification(activeIntervalMs, captureCount)
                    }
                    is CaptureResult.Failure -> Unit
                }
                notifyUi(activeIntervalMs, captureCount, controller.isIntervalRunning())
            }
            updateNotification(activeIntervalMs, captureCount)
            notifyUi(activeIntervalMs, captureCount, true)
        }
    }

    /** Stops interval scheduling on a still-foreground service (interval change). */
    private fun stopIntervalIfRunning() {
        cameraController?.stopIntervalCapture()
        cameraController?.release()
        cameraController = null
        captureCount = 0
        activeIntervalMs = 0L
        minimized = false
    }

    private fun stopCaptureTask() {
        cameraController?.stopIntervalCapture()
        cameraController?.release()
        cameraController = null
        releaseWakeLock()
        captureCount = 0
        activeIntervalMs = 0L
        minimized = false
        notifyUi(0L, 0, false)
        if (foregroundStarted) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            foregroundStarted = false
        }
    }

    private fun notifyUi(intervalMs: Long, count: Int, isCapturing: Boolean) {
        listeners.forEach { it.onCaptureStatus(intervalMs, count, isCapturing) }
    }

    private fun updateNotification(intervalMs: Long, count: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.notif_title),
                getString(R.string.notif_captured, intervalSecondsLabel(intervalMs), count),
                getString(R.string.notif_auto_active),
            ),
        )
    }

    private fun intervalSecondsLabel(intervalMs: Long): String =
        (intervalMs / 1000L).toString()

    private fun buildNotification(title: String, text: String, subtext: String): Notification {
        val openIntent = openUiPendingIntent()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_capture)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subtext)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_open), openIntent)
            .addAction(0, getString(R.string.notif_stop), stopPendingIntent())
            .build()
    }

    private fun openUiPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scos3:capture").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return windowManager.defaultDisplay.rotation
    }

    override fun onDestroy() {
        stopCaptureTask()
        releaseMediaSession()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        val service: CaptureForegroundService get() = this@CaptureForegroundService
    }

    fun isCapturing(): Boolean = activeIntervalMs > 0L

    /** True once the service has been started (its lifecycle reached ON_START). */
    fun isStarted(): Boolean = started

    /** True while the service is parked in the foreground for a MINIMIZE (no capture task). */
    fun isMinimized(): Boolean = minimized

    fun currentIntervalMs(): Long = activeIntervalMs

    fun captureCount(): Int = captureCount

    fun addListener(listener: CaptureStatusListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        // Always deliver the current state immediately so late binders are in sync.
        notifyUi(activeIntervalMs, captureCount, isCapturing())
    }

    fun removeListener(listener: CaptureStatusListener) {
        listeners.remove(listener)
    }

    interface CaptureStatusListener {
        fun onCaptureStatus(intervalMs: Long, captureCount: Int, isCapturing: Boolean)
    }

    companion object {
        const val ACTION_START = "com.scos3.camera.action.START_CAPTURE"
        const val ACTION_STOP = "com.scos3.camera.action.STOP_CAPTURE"
        const val ACTION_MINIMIZE = "com.scos3.camera.action.MINIMIZE"
        const val ACTION_SHOW_OVERLAY = "com.scos3.camera.action.SHOW_OVERLAY"
        const val EXTRA_INTERVAL_MS = "com.scos3.camera.extra.INTERVAL_MS"
        const val EXTRA_LENS = "com.scos3.camera.extra.LENS"
        const val EXTRA_ULTRWIDE = "com.scos3.camera.extra.ULTRWIDE"
        const val EXTRA_ZOOM_RATIO = "com.scos3.camera.extra.ZOOM_RATIO"
        const val DEFAULT_INTERVAL_MS = 2000L

        private const val CHANNEL_ID = "scos3_capture"
        private const val NOTIFICATION_ID = 1001

        /** Creates the notification channel. Safe to call more than once. */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        /**
         * Parks the app behind a persistent foreground notification (MINIMIZE).
         * The service enters the foreground WITHOUT starting a capture task, so
         * the app stays represented by the notification and the user can restore
         * the floating camera UI by tapping it. Must be called from a visible
         * Activity with CAMERA granted (same Android contract as [startCapture]).
         *
         * @return true when the foreground service start was accepted.
         */
        fun startMinimized(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            ensureChannel(context)
            val intent = Intent(context, CaptureForegroundService::class.java).setAction(ACTION_MINIMIZE)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the system refused
                // the start because the app was not considered foreground.
                false
            } catch (e: SecurityException) {
                // Camera-type while-in-use check failed at start time.
                false
            }
        }

        /**
         * Keeps the foreground camera service running while the two overlay
         * sections are on screen, so the CAMERA while-in-use grant stays active
         * over the underlying app and the preview keeps producing frames. No
         * capture task is started; a running capture session is never touched.
         * Same Android foreground / while-in-use contract as [startCapture].
         *
         * @return true when the foreground service start was accepted.
         */
        fun showOverlay(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            ensureChannel(context)
            val intent = Intent(context, CaptureForegroundService::class.java)
                .setAction(ACTION_SHOW_OVERLAY)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }

        /**
         * Starts capture. MUST be called while a visible Activity is on screen
         * with the CAMERA permission granted — both are the callers'
         * responsibility; this method is the hard safety net.
         *
         * A camera foreground service cannot be started from the background or
         * without CAMERA granted: on Android 12+ startForegroundService()
         * throws ForegroundServiceStartNotAllowedException and on Android 14+
         * a camera startForeground() throws SecurityException. Both would crash
         * the process, so this method refuses instead and reports the outcome.
         *
         * @return true when the foreground service start was accepted.
         */
        fun startCapture(
            context: Context,
            intervalMs: Long,
            lens: CameraController.Lens,
            ultrawide: Boolean,
            zoomRatio: Float,
        ): Boolean {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            ensureChannel(context)
            val intent = Intent(context, CaptureForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
                .putExtra(EXTRA_LENS, lens.name)
                .putExtra(EXTRA_ULTRWIDE, ultrawide)
                .putExtra(EXTRA_ZOOM_RATIO, zoomRatio)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the system refused
                // the start because the app was not considered foreground.
                false
            } catch (e: SecurityException) {
                // Camera-type while-in-use check failed at start time.
                false
            }
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, CaptureForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
