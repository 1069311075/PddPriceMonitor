package com.example.pddpricemonitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 识别截图存档（默认关闭，主界面可开关）：
 * - 识别成功后把画面压缩暂存（长边 ≤720 的 JPEG，单张约 30~50KB）
 * - 用户点保存时暂存图归档为 h<历史条目id>.jpg，与该条记录一一对应；重新识别自动覆盖上一张暂存
 * - 不设张数上限：删记录 / 清空数据联动删图，存档量天然跟随记录数
 */
@Singleton
class ScreenshotStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dir: File get() = File(context.filesDir, DIR)

    fun isEnabled(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    suspend fun savePending(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            val folder = dir.apply { mkdirs() }
            val scale = (MAX_LONG_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
                .coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else null
            val source = scaled ?: bitmap
            val tmp = File(folder, PENDING_TMP)
            tmp.outputStream().use { out ->
                source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            scaled?.recycle()
            // 先写临时名再改名，压缩中断不留半张坏图
            if (!tmp.renameTo(File(folder, PENDING))) tmp.delete()
        }
    }

    suspend fun commitFor(historyId: Long) = withContext(Dispatchers.IO) {
        val pending = File(dir, PENDING)
        if (pending.exists()) {
            val target = File(dir, shotName(historyId))
            target.delete()
            pending.renameTo(target)
        }
    }

    /** 把已归档的截图退回暂存位：自动保存行被撤回时，让同一瞬间的截图跟随纠错后的新行重新归档 */
    suspend fun revertToPending(historyId: Long) = withContext(Dispatchers.IO) {
        val shot = File(dir, shotName(historyId))
        if (shot.exists()) {
            val pending = File(dir, PENDING)
            pending.delete()
            shot.renameTo(pending)
        }
    }

    suspend fun deleteFor(historyIds: List<Long>) = withContext(Dispatchers.IO) {
        historyIds.forEach { File(dir, shotName(it)).delete() }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun hasShot(historyId: Long): Boolean = File(dir, shotName(historyId)).exists()

    /** 一次目录列举拿到所有已存档截图的 id——替代明细区逐行 File.exists() 的主线程 I/O */
    suspend fun existingShotIds(): Set<Long> = withContext(Dispatchers.IO) {
        dir.listFiles()
            ?.mapNotNull { f ->
                f.name.removePrefix(SHOT_PREFIX).removeSuffix(".jpg").toLongOrNull()
            }
            ?.toSet()
            ?: emptySet()
    }

    /** 采样解码：targetLongSide 控制解码尺寸（列表缩略图传小值、全屏查看传大值） */
    fun decodeSampled(historyId: Long, targetLongSide: Int): Bitmap? {
        val file = File(dir, shotName(historyId))
        if (!file.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (longSide / (sample * 2) >= targetLongSide) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }

    private fun shotName(historyId: Long) = "$SHOT_PREFIX$historyId.jpg"

    companion object {
        private const val DIR = "screenshots"
        private const val PREFS = "capture_prefs"
        private const val KEY_ENABLED = "save_screenshot"
        private const val PENDING = "pending.jpg"
        private const val PENDING_TMP = "pending.tmp"
        private const val SHOT_PREFIX = "h"
        private const val MAX_LONG_SIDE = 720
        private const val JPEG_QUALITY = 55
    }
}
