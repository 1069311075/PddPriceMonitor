package com.example.pddpricemonitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
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
 * - 卡片缩略图：归档时同步从截图裁出商品图区域存 t<id>.jpg（卡片左栏方图），
 *   裁剪区域在识别时按标题行坐标算好随暂存传递——撤回重记、迁移都走同一份几何
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

    /** 商品图裁剪区域：屏幕坐标系的比例（0..1），跨分辨率稳定 */
    data class CropRegion(
        val leftRatio: Float,
        val topRatio: Float,
        val rightRatio: Float,
        val bottomRatio: Float
    ) {
        fun toRect(w: Int, h: Int): RectF = RectF(
            leftRatio * w, topRatio * h, rightRatio * w, bottomRatio * h
        )
    }

    suspend fun savePending(bitmap: Bitmap, crop: CropRegion? = null) = withContext(Dispatchers.IO) {
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
            // 暂存裁剪参数：归档时用它生成卡片缩略图（商品图区域）。
            // crop 为 null（loose 识别路径拿不到标题锚点）时写固定比例带——
            // prefs 必须随每次暂存同步更新，否则会用上一次识别的锚点裁这次的截图
            val effectiveCrop = crop ?: DEFAULT_CROP
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    KEY_PENDING_CROP,
                    "${effectiveCrop.leftRatio},${effectiveCrop.topRatio},${effectiveCrop.rightRatio},${effectiveCrop.bottomRatio}"
                )
                .apply()
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
            invalidateShotIds()
            // 归档同时生成卡片缩略图；裁剪参数缺省时退化为固定比例带
            writeThumbFor(historyId, target)
        }
    }

    /** 把已归档的截图退回暂存位：自动保存行被撤回时，让同一瞬间的截图跟随纠错后的新行重新归档 */
    suspend fun revertToPending(historyId: Long) = withContext(Dispatchers.IO) {
        File(dir, thumbName(historyId)).delete()
        val shot = File(dir, shotName(historyId))
        if (shot.exists()) {
            val pending = File(dir, PENDING)
            pending.delete()
            shot.renameTo(pending)
            invalidateShotIds()
        }
    }

    suspend fun deleteFor(historyIds: List<Long>) = withContext(Dispatchers.IO) {
        historyIds.forEach {
            File(dir, shotName(it)).delete()
            File(dir, thumbName(it)).delete()
        }
        invalidateShotIds()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        invalidateShotIds()
    }

    fun hasThumb(historyId: Long): Boolean = File(dir, thumbName(historyId)).exists()

    // 已存档截图 id 缓存：列表每张卡片各查一次，历史 Flow 每次发射都会重新触发，
    // 不缓存的话一次重组就是 N 次全目录扫描；写入路径（归档/删除/退回）统一失效
    @Volatile
    private var cachedShotIds: Set<Long>? = null

    private fun invalidateShotIds() {
        cachedShotIds = null
    }

    /** 一次目录列举拿到所有已存档截图的 id——替代明细区逐行 File.exists() 的主线程 I/O */
    suspend fun existingShotIds(): Set<Long> = withContext(Dispatchers.IO) {
        cachedShotIds?.let { return@withContext it }
        val ids = dir.listFiles()
            ?.mapNotNull { f ->
                f.name.removePrefix(SHOT_PREFIX).removeSuffix(".jpg").toLongOrNull()
            }
            ?.toSet()
            ?: emptySet()
        cachedShotIds = ids
        ids
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

    /** 缩略图解码：卡片方图按需读取，读取失败返回 null（卡片退化为无图布局） */
    fun decodeThumb(historyId: Long): Bitmap? {
        val file = File(dir, thumbName(historyId))
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** 存量归档图补生成缩略图（固定比例带裁剪）。App 启动后后台跑一次，已有 t 图的跳过 */
    suspend fun backfillThumbs() = withContext(Dispatchers.IO) {
        // 一次列举同时拿归档截图与已有缩略图：替代逐文件 exists() 的 N 次磁盘 stat
        val names = dir.listFiles()?.map { it.name } ?: return@withContext
        val thumbIds = mutableSetOf<Long>()
        val archived = mutableListOf<Pair<Long, String>>()
        names.forEach { name ->
            when {
                name.startsWith(SHOT_PREFIX) && name.endsWith(".jpg") ->
                    name.removePrefix(SHOT_PREFIX).removeSuffix(".jpg").toLongOrNull()
                        ?.let { archived += it to name }
                name.startsWith(THUMB_PREFIX) && name.endsWith(".jpg") ->
                    name.removePrefix(THUMB_PREFIX).removeSuffix(".jpg").toLongOrNull()
                        ?.let { thumbIds += it }
            }
        }
        val fallback = DEFAULT_CROP
        archived.forEach { (id, name) ->
            if (id !in thumbIds) {
                writeThumb(id, File(dir, name), fallback)
            }
        }
    }

    private suspend fun writeThumbFor(historyId: Long, archived: File) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_CROP, null)
        val crop = saved?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
            ?.let { (l, t, r, b) -> CropRegion(l, t, r, b) }
            ?: DEFAULT_CROP
        writeThumb(historyId, archived, crop)
    }

    private fun writeThumb(historyId: Long, source: File, crop: CropRegion) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            // 解码尺寸按 crop 宽度对齐：缩略图宽 320，两侧余量后完整解码保证裁剪清楚
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_LONG_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val full = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return
            val rect = crop.toRect(full.width, full.height)
            val cropLeft = rect.left.toInt().coerceIn(0, full.width - 1)
            val cropTop = rect.top.toInt().coerceIn(0, full.height - 1)
            val cropRight = rect.right.toInt().coerceIn(cropLeft + 1, full.width)
            val cropBottom = rect.bottom.toInt().coerceIn(cropTop + 1, full.height)
            val cropped = Bitmap.createBitmap(
                full, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop
            )
            full.recycle()
            // 方图：短边内缩居中裁（商品图通常横向铺满、纵向取中部）
            val side = minOf(cropped.width, cropped.height)
            val sx = (cropped.width - side) / 2
            val sy = (cropped.height - side) / 2
            val square = if (side == cropped.width && side == cropped.height) {
                cropped
            } else {
                Bitmap.createBitmap(cropped, sx, sy, side, side).also {
                    if (it !== cropped) cropped.recycle()
                }
            }
            val scaled = if (side > THUMB_LONG_SIDE) {
                Bitmap.createScaledBitmap(square, THUMB_LONG_SIDE, THUMB_LONG_SIDE, true).also {
                    if (it !== square) square.recycle()
                }
            } else square
            // 先写临时名再改名，压缩中断不留半张坏图
            val tmp = File(dir, THUMB_TMP)
            tmp.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            scaled.recycle()
            tmp.renameTo(File(dir, thumbName(historyId)))
        }
    }

    private fun shotName(historyId: Long) = "$SHOT_PREFIX$historyId.jpg"

    private fun thumbName(historyId: Long) = "$THUMB_PREFIX$historyId.jpg"

    companion object {
        private const val DIR = "screenshots"
        private const val PREFS = "capture_prefs"
        private const val KEY_ENABLED = "save_screenshot"
        private const val KEY_PENDING_CROP = "pending_crop"
        private const val PENDING = "pending.jpg"
        private const val PENDING_TMP = "pending.tmp"
        private const val THUMB_TMP = "thumb.tmp"
        private const val SHOT_PREFIX = "h"
        private const val THUMB_PREFIX = "t"
        private const val MAX_LONG_SIDE = 720
        private const val THUMB_LONG_SIDE = 320
        private const val JPEG_QUALITY = 55
        private const val THUMB_QUALITY = 60

        // 固定比例带：无标题锚点时的商品图裁剪区域（两侧 6%、上 8%、下 44%）
        private val DEFAULT_CROP = CropRegion(0.06f, 0.08f, 0.94f, 0.44f)
    }
}
