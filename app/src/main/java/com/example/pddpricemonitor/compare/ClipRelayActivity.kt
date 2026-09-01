package com.example.pddpricemonitor.compare

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * 剪贴板中转页：透明无 UI，被唤起到前台拿到焦点后写剪贴板，再唤起目标比价应用。
 * 直接从服务进程（后台）写剪贴板会被 MIUI/HyperOS 判为后台操作，
 * 弹出系统剪贴板悬浮窗并盖住悬浮球；经此页完成则系统视为正常前台复制。
 */
class ClipRelayActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题，视觉上无感
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true

        val title = intent.getStringExtra(EXTRA_TITLE)
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        if (!title.isNullOrBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("title", title))
            // 写入成功即登记账本：此后同标题的双击都是纯跳转，不再重复复制
            CompareApps.markCopied(title)
            Toast.makeText(this, "已复制商品名", Toast.LENGTH_SHORT).show()
        }

        if (targetPackage != null) {
            val launch = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                runCatching { startActivity(launch) }
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TARGET_PACKAGE = "pkg"

        fun start(context: Context, title: String, targetPackage: String) {
            val intent = Intent(context, ClipRelayActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
