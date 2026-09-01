package com.example.pddpricemonitor.capture

import android.content.Context

/** 识别流程的可持久化开关：主界面设置弹窗写入，识别服务每次识别时现读现用 */
object CapturePrefs {
    private const val PREFS = "capture_prefs"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_RECEIPT_DURATION = "receipt_duration_ms"

    /** 识别后自动保存（默认关）：开启后识别成功即入库，面板转为回执 */
    fun isAutoSaveEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SAVE, false)

    fun setAutoSaveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SAVE, enabled)
            .apply()
    }

    /** 识别结果面板自动折叠时长（毫秒）；0 = 常驻不折叠（点 × / 重新识别 / 下次识别收起）。默认 8 秒 */
    fun getReceiptDurationMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_RECEIPT_DURATION, DEFAULT_RECEIPT_DURATION_MS)

    fun setReceiptDurationMs(context: Context, ms: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_RECEIPT_DURATION, ms)
            .apply()
    }

    const val DEFAULT_RECEIPT_DURATION_MS = 8_000L
}
