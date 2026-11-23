package com.offline.musicplayer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Lends its window token so the capsule can be drawn as an accessibility overlay, the only
 * app-available layer above the status bar. Window bounds are read only to detect an open
 * notification shade; no window content is read.
 */
class CapsuleAccessibilityService : AccessibilityService() {

    private val bounds = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        CapsuleWindowHost.attach(this)
        updateShadeState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> updateShadeState()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        CapsuleWindowHost.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        CapsuleWindowHost.detach(this)
        super.onDestroy()
    }

    /** An expanded shade or quick settings is a tall system window; the status bar is short. */
    private fun updateShadeState() {
        val screenHeight = resources.displayMetrics.heightPixels
        val shadeOpen = windows.orEmpty().any { window ->
            window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                bounds.also(window::getBoundsInScreen).height() > screenHeight / 3
        }
        CapsuleWindowHost.setShadeOpen(shadeOpen)
    }
}
