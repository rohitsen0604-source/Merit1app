package com.scos3.camera.email

import android.content.Context

/**
 * Process-wide singleton provider for [EmailManager].
 *
 * Every previously direct construction site (MainActivity,
 * CaptureForegroundService, SettingsActivity) now asks this provider for the
 * single shared instance, so there is exactly ONE EmailManager (and therefore
 * one coroutine scope plus one ATM-draining flag) per application process.
 *
 * The manager is always constructed with [Context.getApplicationContext], so it
 * never retains an Activity or Service context. The instance is created lazily
 * on first use and guarded with double-checked locking for thread safety.
 */
object EmailManagerProvider {

    @Volatile
    private var instance: EmailManager? = null

    /** Returns the single application-process EmailManager, creating it on first use. */
    fun get(context: Context): EmailManager {
        val current = instance
        if (current != null) return current
        return synchronized(this) {
            val again = instance
            if (again != null) {
                again
            } else {
                EmailManager(context.applicationContext).also { instance = it }
            }
        }
    }
}