package com.example.pddpricemonitor.sync

import android.content.Context
import android.os.Build
import java.util.UUID

// 本机设备身份：首次生成 UUID 持久化，设备名取机型，重装 App 会产生新身份（demo 可接受）
object DeviceIdentity {
    private const val PREFS = "device_identity"
    private const val KEY_ID = "deviceId"
    private const val KEY_NAME = "deviceName"

    fun deviceId(context: Context): String =
        prefs(context).getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs(context).edit().putString(KEY_ID, it).apply()
        }

    fun deviceName(context: Context): String =
        prefs(context).getString(KEY_NAME, null) ?: (Build.MODEL ?: "安卓设备").also {
            prefs(context).edit().putString(KEY_NAME, it).apply()
        }

    // Build.MODEL 在小米机型上常是 "23116PNAB" 这类暗号，演示前改成可读名字（图例/弹窗展示用）
    fun rename(context: Context, name: String) {
        val trimmed = name.trim().take(12)
        if (trimmed.isNotEmpty()) {
            prefs(context).edit().putString(KEY_NAME, trimmed).apply()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
