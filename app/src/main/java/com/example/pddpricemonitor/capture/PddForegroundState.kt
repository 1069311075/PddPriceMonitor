package com.example.pddpricemonitor.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object PddForegroundState {
    const val PDD_PACKAGE_NAME = "com.xunmeng.pinduoduo"

    private val _isPddForeground = MutableStateFlow(false)
    val isPddForeground: StateFlow<Boolean> = _isPddForeground

    private val _lastPackageName = MutableStateFlow("")
    val lastPackageName: StateFlow<String> = _lastPackageName

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected

    private val _usageAccessGranted = MutableStateFlow(false)
    val usageAccessGranted: StateFlow<Boolean> = _usageAccessGranted

    fun update(packageName: CharSequence?) {
        val name = packageName?.toString().orEmpty()
        _lastPackageName.value = name
        _isPddForeground.value = name == PDD_PACKAGE_NAME
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
    }

    fun updateUsageAccess(granted: Boolean) {
        _usageAccessGranted.value = granted
    }
}
