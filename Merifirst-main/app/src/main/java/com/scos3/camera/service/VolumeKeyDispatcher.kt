package com.scos3.camera.service

import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-level bridge between the [VolumeKeyAccessibilityService] and the SC OS3
 * UI, and the single source of truth for whether SC OS3 is currently volume
 * active ("SCOS3 ACTIVE").
 *
 * The AccessibilityService is a separate Android component, so it must not call
 * an Activity method directly. The two overlay-owning [MainActivity] registers
 * a [VolumeCommandListener] here every time the overlay sections are shown and
 * unregisters whenever they are hidden (MINIMIZE, EXIT, Settings/Help hand‑off,
 * or teardown). While no listener is registered, [isVolumeControlActive] is
 * false and [dispatchVolumeDown]/[dispatchVolumeUp] return false, so the
 * AccessibilityService leaves every volume key to the system.
 *
 * This holder contains no camera, capture or email logic; it only forwards a
 * volume press to whatever SC OS3 component is currently active.
 */
object VolumeKeyDispatcher {

    /** Receives a single, debounced volume press while SC OS3 is active. */
    interface VolumeCommandListener {
        /** Exactly one KEYCODE_VOLUME_DOWN ACTION_DOWN press. */
        fun onVolumeDown()

        /** Exactly one KEYCODE_VOLUME_UP ACTION_DOWN press. */
        fun onVolumeUp()
    }

    private val listeners = CopyOnWriteArrayList<VolumeCommandListener>()

    /** True only while SC OS3 is active (overlay visible and listening). */
    fun isVolumeControlActive(): Boolean = listeners.isNotEmpty()

    fun register(listener: VolumeCommandListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregister(listener: VolumeCommandListener) {
        listeners.remove(listener)
    }

    fun unregisterAll() {
        listeners.clear()
    }

    /**
     * Dispatches a Volume Down press. Returns true when SC OS3 consumed the key
     * (must then be consumed by the caller); returns false when SC OS3 is
     * inactive and the system must handle the key normally.
     */
    fun dispatchVolumeDown(): Boolean {
        if (listeners.isEmpty()) return false
        listeners.forEach { it.onVolumeDown() }
        return true
    }

    /**
     * Dispatches a Volume Up press. Returns true when SC OS3 consumed the key;
     * returns false when SC OS3 is inactive and the key must pass through.
     */
    fun dispatchVolumeUp(): Boolean {
        if (listeners.isEmpty()) return false
        listeners.forEach { it.onVolumeUp() }
        return true
    }
}