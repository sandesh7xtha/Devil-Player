package com.offline.musicplayer.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager

/**
 * Connects the floating capsule to [CapsuleAccessibilityService] when the user has enabled it.
 * All calls happen on the main thread.
 */
object CapsuleWindowHost {

    private var service: AccessibilityService? = null
    private val listeners = mutableSetOf<() -> Unit>()

    /** True while the notification shade or quick settings covers the screen. */
    var shadeOpen: Boolean = false
        private set

    /** WindowManager able to add TYPE_ACCESSIBILITY_OVERLAY windows, or null when unavailable. */
    val windowManager: WindowManager?
        get() = service?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    internal fun attach(accessibilityService: AccessibilityService) {
        service = accessibilityService
        notifyListeners()
    }

    internal fun detach(accessibilityService: AccessibilityService) {
        if (service !== accessibilityService) return
        service = null
        shadeOpen = false
        notifyListeners()
    }

    internal fun setShadeOpen(open: Boolean) {
        if (shadeOpen == open) return
        shadeOpen = open
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }
}
