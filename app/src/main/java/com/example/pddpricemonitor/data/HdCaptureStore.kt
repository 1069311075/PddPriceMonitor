package com.example.pddpricemonitor.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 截图独立存档（悬浮球下段按钮产生）：
 *
 * ```
 * filesDir/hd_captures/<uuid>/
 *   original.jpg     # 新记录主图：JPEG q85，长边不超过 1920
 *   original.png     # 旧记录主图：历史版本的全分辨率 PNG，原样保留不迁移
 *   thumb.jpg        # 相册网格用的小图，不覆盖主图
 *   metadata.json    # schemaVersion/id/capturedAt/宽高/相对路径/mime/来源
 * ```
 *
 * 与旧 OCR 附图（filesDir/screenshots/，长边 720 JPEG55）、OCR 调试转储、
 * 价格数据库完全分开：不共文件名、不共开关、删旧记录不牵连新截图。
 * 写入走「暂存目录 → 改名归档」，保存与列举/清理共用一把锁，
 * 避免把正在写入的暂存目录当成中断残留删掉。
 */
@Singleton
class HdCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Entry(
        /** 记录身份 = 归档目录名；metadata 里的 id 只当备注，避免坏 id 把操作引向另一条记录 */
        val id: String,
        val capturedAt: Long,
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val source: String,
        /** 主图在归档目录内的相对文件名（只允许 original.jpg / original.png） */
        val path: String = NAME_PNG,
        val mime: String = MIME_PNG,
        /** 元数据或主图缺失：相册里显示占位，仍可删除 */
        val broken: Boolean = false,
        /** capturedAt 缺失/无效：日期不可信，不参与按时间清理，网格单列一组 */
        val dateUnknown: Boolean = false
    ) {
        val isJpeg: Boolean get() = mime == MIME_JPEG || path.endsWith(".jpg", ignoreCase = true)

        /** 导出默认文件名：pdd-shot-20261017-153012.jpg / .png */
        fun exportFileName(): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(capturedAt))
            return "pdd-shot-$stamp.${if (isJpeg) "jpg" else "png"}"
        }
    }

    sealed class SaveResult {
        data class Ok(val entry: Entry) : SaveResult()
        data class Failed(val reason: String) : SaveResult()
    }

    /** 清理预览：截止时间固定、候选 id 固定，确认时只按这份 id 集合执行 */
    data class CleanupPreview(
        val ids: List<String>,
        val count: Int,
        val bytes: Long,
        /** 因日期未知被跳过的记录数（不含在候选里） */
        val skippedUnknown: Int = 0
    ) {
        val isEmpty: Boolean get() = count == 0
    }

    /**
     * 删除结果：逐项区分成功 / 物理失败 / 已不存在，供界面移除成功项、保留失败项重试。
     * [failed] 是面向反馈的「失败」口径（物理失败 + 已不存在，都没删掉）。
     */
    data class DeleteResult(
        val deletedIds: List<String> = emptyList(),
        val failedIds: List<String> = emptyList(),
        val missingIds: List<String> = emptyList()
    ) {
        val deleted: Int get() = deletedIds.size
        val failed: Int get() = failedIds.size + missingIds.size
    }

    private val root: File get() = File(context.filesDir, DIR)

    /** 保存 / 列举 / 删除互斥，防止把进行中的暂存目录当残留清理掉 */
    private val ioLock = Mutex()

    /** 一张新截图：按长边上限缩放后存 JPEG，返回是否落盘成功，失败给出可读原因 */
    suspend fun save(
        bitmap: Bitmap,
        capturedAt: Long = System.currentTimeMillis(),
        source: String = SOURCE_MANUAL
    ): SaveResult = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val base = root.apply { mkdirs() }
            val free = base.usableSpace
            if (free in 1 until MIN_FREE_BYTES) {
                return@withLock SaveResult.Failed("存储空间不足，无法保存截图")
            }

            // 长边压到上限内；小于上限的截图不放大。缩放只作用在本记录的主图上，
            // 不影响 OCR 识别时使用的原始画面
            val longSide = maxOf(bitmap.width, bitmap.height)
            val shrink = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toFloat() / longSide else 1f
            val scaled = if (shrink < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * shrink).roundToInt().coerceAtLeast(1),
                    (bitmap.height * shrink).roundToInt().coerceAtLeast(1),
                    true
                )
            } else {
                null
            }
            val main = scaled ?: bitmap

            val id = UUID.randomUUID().toString()
            val staging = File(base, STAGING_PREFIX + id)
            if (!staging.mkdirs()) {
                scaled?.recycle()
                return@withLock SaveResult.Failed("无法创建截图目录")
            }

            val target = File(staging, NAME_JPEG)
            val encoded = runCatching {
                target.outputStream().use { out ->
                    main.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }.getOrDefault(false)
            if (!encoded || target.length() <= 0L) {
                staging.deleteRecursively()
                scaled?.recycle()
                return@withLock SaveResult.Failed("图片编码失败，请重试")
            }

            // 列表缩略图另存小图；主图不被缩略图覆盖
            writeThumb(main, File(staging, THUMB))

            val entry = Entry(
                id = id,
                capturedAt = capturedAt,
                width = main.width,
                height = main.height,
                sizeBytes = target.length(),
                source = source,
                path = NAME_JPEG,
                mime = MIME_JPEG
            )
            val metaWritten = runCatching {
                File(staging, META).writeText(metadataJson(entry))
                true
            }.getOrDefault(false)
            if (!metaWritten) {
                staging.deleteRecursively()
                scaled?.recycle()
                return@withLock SaveResult.Failed("截图信息写入失败")
            }

            // 改名完成归档：列表只认识最终目录名，半成品不会出现在列表里
            if (!staging.renameTo(File(base, id))) {
                staging.deleteRecursively()
                scaled?.recycle()
                return@withLock SaveResult.Failed("截图归档失败")
            }
            scaled?.recycle()
            SaveResult.Ok(entry)
        }
    }

    /** 按时间倒序列举全部截图；顺带清掉中断保存残留的暂存目录 */
    suspend fun listEntries(): List<Entry> = withContext(Dispatchers.IO) {
        ioLock.withLock {
            cleanupStaging()
            root.listFiles()?.mapNotNull { dir ->
                if (!dir.isDirectory || dir.name.startsWith(STAGING_PREFIX)) null else readEntry(dir)
            }.orEmpty()
                .sortedWith(compareByDescending<Entry> { it.capturedAt }.thenByDescending { it.id })
        }
    }

    suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        root.listFiles()?.count { it.isDirectory && !it.name.startsWith(STAGING_PREFIX) } ?: 0
    }

    /**
     * 清理预览：cutoffMillis 为截止时间（只删 capturedAt < cutoff 的记录），
     * null 表示全部。候选 id 与字节数在这里固定，确认阶段不再重新遍历筛选。
     */
    suspend fun previewCleanup(cutoffMillis: Long?): CleanupPreview = withContext(Dispatchers.IO) {
        ioLock.withLock {
            cleanupStaging()
            val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(STAGING_PREFIX) }
                ?: emptyList()
            val ids = mutableListOf<String>()
            var bytes = 0L
            var skipped = 0
            dirs.forEach { dir ->
                val entry = readEntry(dir) ?: return@forEach
                // 日期未知的记录不参与按时间清理（目录修改时间是推测值，不能拿来判年龄）；
                // 「全部」不按时间筛，仍然包含它们
                if (cutoffMillis != null && entry.dateUnknown) {
                    skipped++
                    return@forEach
                }
                if (cutoffMillis != null && entry.capturedAt >= cutoffMillis) return@forEach
                ids += entry.id
                bytes += directoryBytes(dir)
            }
            CleanupPreview(ids = ids, count = ids.size, bytes = bytes, skippedUnknown = skipped)
        }
    }

    /** 删除单张：主图、缩略图、元数据整目录一起删 */
    suspend fun delete(id: String): Boolean = deleteIds(listOf(id)).deleted == 1

    /**
     * 删除给定 id 集合（清理与单张共用）。只认归档根目录下的、非暂存的一级子目录，
     * 越界 id 一律拒绝；返回真实成功/失败条数。
     */
    suspend fun deleteIds(ids: List<String>): DeleteResult = withContext(Dispatchers.IO) {
        ioLock.withLock {
            val deleted = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val missing = mutableListOf<String>()
            ids.distinct().forEach { id ->
                val dir = entryDir(id)
                if (dir == null || !dir.isDirectory) {
                    missing += id
                    return@forEach
                }
                if (dir.deleteRecursively()) deleted += id else failed += id
            }
            DeleteResult(deletedIds = deleted, failedIds = failed, missingIds = missing)
        }
    }

    /** 归档根目录下的合法一级子目录；id 越界（含分隔符/暂存前缀）返回 null */
    private fun entryDir(id: String): File? {
        if (id.isBlank() || id.startsWith(STAGING_PREFIX)) return null
        if (id.contains('/') || id.contains('\\') || id == "." || id == "..") return null
        val base = root
        return File(base, id).takeIf { it.parentFile?.absolutePath == base.absolutePath }
    }

    /** 主图文件：路径取记录元数据里受控的 allowed path，越界一律拒绝 */
    fun mainFile(entry: Entry): File? {
        if (entry.broken) return null
        val dir = entryDir(entry.id) ?: return null
        val name = if (entry.path == NAME_JPEG || entry.path == NAME_PNG) entry.path else NAME_PNG
        return File(dir, name).takeIf { it.isFile }
    }

    /** 相册网格用缩略图：缺失时按需从主图重建（不改主图） */
    suspend fun loadThumb(entry: Entry): Bitmap? = withContext(Dispatchers.IO) {
        decodeThumbFile(entry)?.let { return@withContext it }
        if (entry.broken) return@withContext null
        rebuildThumb(entry)
    }

    /** 大图查看用主图：按目标长边采样解码，结果长边不会超过目标，小于目标的不放大 */
    fun decodeMain(entry: Entry, targetLongSide: Int): Bitmap? {
        val file = mainFile(entry) ?: return null
        return decodeSampled(file, targetLongSide)
    }

    /** 30/90 天前的本机零点：仅删除 capturedAt 早于该时刻的截图，截止当天零点起的保留 */
    fun cutoffMillis(daysBefore: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -daysBefore)
        return cal.timeInMillis
    }

    private fun readEntry(dir: File): Entry? {
        val json = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull()
        // 元数据缺失/损坏也返回占位记录：单条坏记录不该让整个相册打不开
        val rawPath = json?.optString("path").orEmpty()
        val path = if (rawPath == NAME_JPEG || rawPath == NAME_PNG) rawPath else NAME_PNG
        val main = File(dir, path)
        val exists = main.isFile
        // 记录里的格式与真实字节矛盾时按真实字节修正（否则会导出扩展名/MIME 与内容不符的伪格式文件）
        val sniffed = if (exists) sniffMime(main) else null
        val mime = sniffed
            ?: json?.optString("mime").takeIf { !it.isNullOrBlank() }
            ?: if (path == NAME_JPEG) MIME_JPEG else MIME_PNG
        val recordedAt = json?.optLong("capturedAt", 0L) ?: 0L
        return Entry(
            // 身份用目录名：坏 id 不会把查看/删除引向另一条记录，也不会出现重复 id
            id = dir.name,
            // 日期未知时只给一个用于排序/命名的推测值，删除路径绝不使用它
            capturedAt = recordedAt.takeIf { it > 0L } ?: dir.lastModified(),
            width = json?.optInt("width", 0) ?: 0,
            height = json?.optInt("height", 0) ?: 0,
            sizeBytes = if (exists) main.length() else json?.optLong("sizeBytes", 0L) ?: 0L,
            source = json?.optString("source")?.takeIf { it.isNotBlank() } ?: SOURCE_MANUAL,
            path = path,
            mime = mime,
            broken = !exists,
            dateUnknown = recordedAt <= 0L
        )
    }

    /** 从文件头判断真实格式；认不出来返回 null（此时沿用记录里的格式） */
    private fun sniffMime(file: File): String? = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            val read = input.read(head)
            when {
                read >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                    head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> MIME_PNG
                read >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
                    head[2] == 0xFF.toByte() -> MIME_JPEG
                else -> null
            }
        }
    }.getOrNull()

    private fun metadataJson(entry: Entry): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("id", entry.id)
        put("capturedAt", entry.capturedAt)
        put("width", entry.width)
        put("height", entry.height)
        put("sizeBytes", entry.sizeBytes)
        put("path", entry.path)
        put("thumb", THUMB)
        put("mime", entry.mime)
        put("source", entry.source)
    }.toString(2)

    private fun writeThumb(source: Bitmap, target: File) {
        val longSide = maxOf(source.width, source.height).coerceAtLeast(1)
        val scale = (THUMB_LONG_SIDE.toFloat() / longSide).coerceAtMost(1f)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else null
        runCatching {
            target.outputStream().use { out ->
                (scaled ?: source).compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
        }
        scaled?.recycle()
    }

    /** 缩略图缺失时从主图重建：先按 2× 目标采样解码，再缩到目标长边 */
    private fun rebuildThumb(entry: Entry): Bitmap? {
        val file = mainFile(entry) ?: return null
        val sampled = decodeSampled(file, THUMB_LONG_SIDE * 2) ?: return null
        val longSide = maxOf(sampled.width, sampled.height).coerceAtLeast(1)
        val scale = (THUMB_LONG_SIDE.toFloat() / longSide).coerceAtMost(1f)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                sampled,
                (sampled.width * scale).roundToInt().coerceAtLeast(1),
                (sampled.height * scale).roundToInt().coerceAtLeast(1),
                true
            ).also { sampled.recycle() }
        } else {
            sampled
        }
        runCatching {
            File(file.parentFile, THUMB).outputStream().use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
        }
        return thumb
    }

    private fun decodeThumbFile(entry: Entry): Bitmap? {
        val dir = entryDir(entry.id) ?: return null
        val file = File(dir, THUMB)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * 采样解码：先按二次幂 inSampleSize 降采样，若仍超过目标长边再精确缩到目标。
     * 采样结果的尺寸上限是目标的 2 倍以内，不会为了一个小图先完整解码原图。
     */
    private fun decodeSampled(file: File, targetLongSide: Int): Bitmap? {
        if (targetLongSide <= 0 || !file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            if (longSide <= 0) return@runCatching null
            var sample = 1
            while (longSide / (sample * 2) >= targetLongSide) sample *= 2
            val sampled = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null
            val sampledLong = maxOf(sampled.width, sampled.height)
            if (sampledLong <= targetLongSide) {
                sampled
            } else {
                val scale = targetLongSide.toFloat() / sampledLong
                Bitmap.createScaledBitmap(
                    sampled,
                    (sampled.width * scale).roundToInt().coerceAtLeast(1),
                    (sampled.height * scale).roundToInt().coerceAtLeast(1),
                    true
                ).also { sampled.recycle() }
            }
        }.getOrNull()
    }

    private fun directoryBytes(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    private fun cleanupStaging() {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { file ->
            if (!file.name.startsWith(STAGING_PREFIX)) return@forEach
            // 双保险：既在锁内（不会撞上进行中的保存），又只清明显过期的残留
            if (now - file.lastModified() > STAGING_STALE_MS) file.deleteRecursively()
        }
    }

    companion object {
        const val DIR = "hd_captures"
        const val SOURCE_MANUAL = "manual_hd_capture"
        const val VIEW_LONG_SIDE = 2048

        private const val SCHEMA_VERSION = 2
        private const val NAME_JPEG = "original.jpg"
        private const val NAME_PNG = "original.png"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_PNG = "image/png"
        private const val THUMB = "thumb.jpg"
        private const val META = "metadata.json"
        private const val STAGING_PREFIX = ".staging-"
        /** 新截图主图长边上限 */
        private const val MAX_LONG_SIDE = 1920
        private const val JPEG_QUALITY = 85
        /** 缩略图长边：三列网格单元约 378px 宽，竖图取 720 长边避免放大发虚 */
        private const val THUMB_LONG_SIDE = 720
        private const val THUMB_QUALITY = 80
        /** 落盘前的最低可用空间要求 */
        private const val MIN_FREE_BYTES = 16L * 1024 * 1024
        /** 暂存目录超过这么久才算中断残留 */
        private const val STAGING_STALE_MS = 10L * 60L * 1000L
    }
}
