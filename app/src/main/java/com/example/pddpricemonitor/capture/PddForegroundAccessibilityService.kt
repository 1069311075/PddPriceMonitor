package com.example.pddpricemonitor.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class PddForegroundAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            PddForegroundState.update(event.packageName)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        PddForegroundState.setAccessibilityConnected(true)
        MonitorDebugState.update("Accessibility service connected")
    }

    override fun onInterrupt() {
        MonitorDebugState.update("Accessibility service interrupted")
    }

    override fun onDestroy() {
        PddForegroundState.setAccessibilityConnected(false)
        super.onDestroy()
    }
}
