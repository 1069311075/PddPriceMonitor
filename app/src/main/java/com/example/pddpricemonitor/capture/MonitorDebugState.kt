package com.example.pddpricemonitor.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MonitorDebugInfo(
    val message: String = "Not started",
    val lastOcrTextLength: Int = 0,
    val lastParsedProducts: Int = 0,
    val lastSavedProducts: Int = 0,
    val updatedAt: Long = 0L
)

object MonitorDebugState {
    private val _info = MutableStateFlow(MonitorDebugInfo())
    val info: StateFlow<MonitorDebugInfo> = _info

    fun update(
        message: String,
        textLength: Int = _info.value.lastOcrTextLength,
        parsedProducts: Int = _info.value.lastParsedProducts,
        savedProducts: Int = _info.value.lastSavedProducts
    ) {
        _info.value = MonitorDebugInfo(
            message = message,
            lastOcrTextLength = textLength,
            lastParsedProducts = parsedProducts,
            lastSavedProducts = savedProducts,
            updatedAt = System.currentTimeMillis()
        )
    }
}
