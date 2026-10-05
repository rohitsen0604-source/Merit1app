package com.scos3.camera.email

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import com.scos3.camera.R
import com.scos3.camera.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates the local email queue with the [EmailRepository].
 *
 * The camera capture pipeline (CaptureEngine/foreground service) only calls
 * [enqueueAndAttempt]; it never blocks on the network. This class is the
 * single owner of "when to send", keeping capture and email decoupled.
 *
 * Delivery reliability:
 *  - A connectivity callback drains the queue automatically when the network
 *    becomes available (no capture / settings / manual action required).
 *  - A failed pass schedules a bounded backoff retry so transient SMTP/network
 *    failures are retried without aggressive loops.
 *  - Startup drains any backlog that survived a process restart.
 *  - A queued item is removed only after SMTP success; a permanently missing
 *    source image is dropped so a single stale entry cannot block the queue.
 */
class EmailManager(context: Context) {

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository(appContext)
    private val queue = EmailQueue(appContext)
    private val repository = GmailEmailRepository(appContext)
    private val imageCompressor = ImageCompressor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)

    private var retryJob: Job? = null
    private var retryDelayMs = INITIAL_RETRY_MS

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            attemptDrain()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                attemptDrain()
            }
        }
    }

    init {
        // Auto-recovery: drain when connectivity returns, and drain any backlog
        // that survived a process restart. Registering the default network
        // callback also reports the currently-available network immediately.
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(connectivityCallback)
        }
        attemptDrain()
    }

    /** Called after every successful local save. Enqueues and tries to drain. */
    fun enqueueAndAttempt(uri: Uri, displayName: String) {
        queue.enqueue(uri.toString(), displayName)
        retryDelayMs = INITIAL_RETRY_MS
        attemptDrain()
    }

    /** Attempts to send everything currently queued. Safe to call often. */
    fun attemptDrain() {
        if (!settingsRepository.gmailConfigured()) return
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            val sent = try {
                drainOnce()
            } finally {
                draining.set(false)
            }
            if (sent > 0) retryDelayMs = INITIAL_RETRY_MS
            scheduleRetryIfPending()
        }
    }

    /**
     * Sends every currently queued image one at a time over SMTP.
     *
     * @return the number of items successfully sent and removed. Permanent
     *   failures (source image no longer present in MediaStore) are dropped so
     *   a single stale entry cannot block the whole queue; transient failures
     *   (network/auth/timeout) stop the pass and are retried later.
     */
    suspend fun drainOnce(): Int {
        val gmail = settingsRepository.load().gmail
        if (gmail.senderEmail.isBlank() || gmail.senderAppPassword.isBlank() || gmail.receiverEmail.isBlank()) return 0
        val items = queue.snapshot()
        var sent = 0
        for (item in items) {
            val imageUri = Uri.parse(item.uri)
            // Definitively missing source: nothing can ever be delivered, so
            // remove it instead of blocking the rest of the queue forever.
            if (imageExists(imageUri) == false) {
                queue.remove(item.uri)
                cleanupCompressedFile(imageUri)
                continue
            }
            val result = repository.sendImage(
                imageUri = imageUri,
                senderEmail = gmail.senderEmail,
                appPassword = gmail.senderAppPassword,
                receiverEmail = gmail.receiverEmail,
            )
            if (result.isSuccess) {
                queue.remove(item.uri)
                cleanupCompressedFile(imageUri)
                sent++
            } else {
                // Transient SMTP/network/auth failure: stop, keep the rest
                // queued, and retry later with backoff.
                break
            }
        }
        return sent
    }

    /** @return true when present, false when definitively gone, null when unknown. */
    private fun imageExists(uri: Uri): Boolean? {
        // App-private captures (Gallery Save OFF) are referenced with file://
        // URIs; those are checked directly on disk. MediaStore URIs are probed
        // through the ContentResolver.
        return if ("file" == uri.scheme) {
            runCatching { uri.path?.let { File(it).exists() } ?: false }.getOrNull()
        } else {
            runCatching {
                appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    cursor.count > 0
                } ?: false
            }.getOrNull()
        }
    }

    /**
     * Deletes the compressed email copy after successful delivery or permanent
     * queue drop. Only files inside the dedicated `email_captures/` directory
     * are removed — original MediaStore and private images are never touched.
     */
    private fun cleanupCompressedFile(uri: Uri) {
        runCatching { imageCompressor.cleanup(uri) }
    }

    /** Re-schedules a single backoff retry when items remain pending. */
    private fun scheduleRetryIfPending() {
        retryJob?.cancel()
        if (!settingsRepository.gmailConfigured()) return
        if (queue.count() == 0) return
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        retryJob = scope.launch {
            delay(delayMs)
            attemptDrain()
        }
    }

    /** Sends a test message using the current settings. Returns a user-safe message. */
    fun sendTest(onResult: (Boolean, String) -> Unit) {
        val gmail = settingsRepository.load().gmail
        if (gmail.senderEmail.isBlank() || gmail.senderAppPassword.isBlank() || gmail.receiverEmail.isBlank()) {
            onResult(false, appContext.getString(R.string.email_error_not_configured))
            return
        }
        scope.launch {
            val result = repository.sendTest(gmail.senderEmail, gmail.senderAppPassword, gmail.receiverEmail)
            onResult(
                result.isSuccess,
                if (result.isSuccess) appContext.getString(R.string.email_test_sent)
                else result.exceptionOrNull()?.message ?: appContext.getString(R.string.email_error_generic),
            )
        }
    }

    fun pendingCount(): Int = queue.count()

    private companion object {
        /** First backoff window after a failed drain pass. */
        const val INITIAL_RETRY_MS = 15_000L

        /** Backoff ceiling; retries never get more aggressive than this. */
        const val MAX_RETRY_MS = 5 * 60_000L
    }
}
