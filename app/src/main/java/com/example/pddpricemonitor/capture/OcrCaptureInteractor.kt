package com.example.pddpricemonitor.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.example.pddpricemonitor.data.ProductPriceComparison
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.matcher.TitleMatcher
import com.google.mlkit.vision.text.Text
import com.example.pddpricemonitor.ocr.DetectedProduct
import com.example.pddpricemonitor.ocr.OcrLineDump
import com.example.pddpricemonitor.ocr.ProductTextParser
import com.example.pddpricemonitor.ocr.TextRecognizerClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR 识别业务协调器：
 * - 封装"截图 → OCR → 解析 → 标题/价格二遍精识别 → 对比 → 保存"的完整业务流程
 * - 不持有任何 UI 引用，纯业务逻辑
 * - 通过回调通知外部结果
 *
 * 这样 Service 只需要关心生命周期和 UI 调度，业务逻辑在这里可以独立测试。
 */
@Singleton
class OcrCaptureInteractor @Inject constructor(
    private val recognizer: TextRecognizerClient,
    private val parser: ProductTextParser,
    private val repository: ProductRepository,
    private val dumpWriter: OcrDumpWriter,
    private val titleMatcher: TitleMatcher,
    private val titleFuser: TitleFuser
) {
    /**
     * 单次识别结果
     */
    sealed class CaptureResult {
        data class Success(
            val product: DetectedProduct,
            val comparison: ProductPriceComparison?
        ) : CaptureResult()

        data class NoProduct(
            val reason: String,
            val textLength: Int,
            val parsedCount: Int
        ) : CaptureResult()

        data class Error(val throwable: Throwable) : CaptureResult()
        object NoFrame : CaptureResult()
    }

    // 价格二遍精识别的裁决记录：进转储供离线排查
    data class PriceRefineDump(
        val zoomRawText: String,
        val firstCents: Long,
        val firstHasDecimal: Boolean,
        val candidates: List<ProductTextParser.ZoomPriceCandidate>,
        val chosenCents: Long?,
        val applied: Boolean
    )

    /**
     * 执行一次完整的 OCR 识别流程
     *
     * @param onScreenshot 截图存档回调：识别成功时在 bitmap 回收前触发，
     *        由调用方决定是否压缩保存（开关关闭时传 null）
     */
    suspend fun captureOnce(
        bitmapProvider: () -> Bitmap?,
        onScreenshot: (suspend (Bitmap) -> Unit)? = null
    ): CaptureResult {
        val bitmap = bitmapProvider() ?: return CaptureResult.NoFrame

        return try {
            val text = recognizer.recognize(bitmap)
            var result = parser.parseWithReason(text, bitmap)
            var product = result.products.singleOrNull()

            // 方案A·标题二遍精识别：标题行区域裁剪放大后重识别，消除笔画级误读（李→季）
            var firstPassTitle: String? = null
            var secondPassRawLines: List<String>? = null
            var refineReason: String? = null
            var fusion: List<TitleFuser.Disagreement>? = null
            if (product != null && result.titleRect != null) {
                val rect = result.titleRect!!
                // 证人语料剔除标题区域行：标题行自身的误读会命中自己的探针给自己投票
                // （速/遠案例：一遍把"速"读成"遠"，2 票全来自标题行自己）
                val witnessLines = result.lines
                    .filterNot { line ->
                        line.top < rect.bottom && line.bottom > rect.top &&
                            line.left < rect.right && line.right > rect.left
                    }
                    .map { it.text }
                val outcome = refineTitle(bitmap, rect, product!!, result.lines.map { it.text }, witnessLines)
                outcome.refined?.let { refined ->
                    firstPassTitle = product!!.title
                    product = refined
                    result = result.copy(products = listOf(refined))
                }
                secondPassRawLines = outcome.secondPassRawLines
                refineReason = outcome.refineReason
                fusion = outcome.fusion
            }

            // 价格二遍精识别：价格行裁剪放大重读，把小数点真正识别出来（而非按定价习惯反推）
            var priceRefine: PriceRefineDump? = null
            if (product != null) {
                refinePrice(bitmap, product!!, result.lines).let { outcome ->
                    priceRefine = outcome.dump
                    outcome.product?.let { refined ->
                        product = refined
                        result = result.copy(products = listOf(refined))
                    }
                }
            }

            // 诊断转储：仅供排查，写失败不影响识别流程（含二遍精识别前后对比、失败原因与融合记录）
            runCatching { dumpWriter.write(result, firstPassTitle, secondPassRawLines, refineReason, fusion, priceRefine) }

            if (product == null) {
                CaptureResult.NoProduct(
                    reason = result.skippedReason ?: "没有识别到商品",
                    textLength = text.text.length,
                    parsedCount = result.products.size
                )
            } else {
                val comparison = repository.findPriceComparison(product!!)
                // 截图存档：此时 bitmap 仍存活（finally 才回收），调用方压缩保存
                onScreenshot?.invoke(bitmap)
                CaptureResult.Success(product!!, comparison)
            }
        } catch (error: Throwable) {
            CaptureResult.Error(error)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 标题二遍精识别：裁剪标题区域 → 放大 2.5 倍 → 重新 OCR → 同一清理管线组装 → 两遍逐字融合。
     * 三重护栏防"改坏"：长度比例、相似度下限、失败静默回退第一遍结果。
     * 诊断字段透出二遍原始行、失败原因与融合分歧记录，供转储排查。
     */
    private class RefineOutcome(
        val refined: DetectedProduct?,
        val secondPassRawLines: List<String>?,
        val refineReason: String?,
        val fusion: List<TitleFuser.Disagreement>?
    )

    private suspend fun refineTitle(
        bitmap: Bitmap,
        titleRect: Rect,
        first: DetectedProduct,
        pageLines: List<String>,
        witnessLines: List<String>
    ): RefineOutcome = runCatching {
        // 外扩边距：标题行 bbox 常贴字裁切，紧边距会切掉笔画
        val marginH = (titleRect.width() * 0.04f).toInt().coerceIn(8, 48)
        val marginV = (titleRect.height() * 0.12f).toInt().coerceIn(4, 24)
        val left = (titleRect.left - marginH).coerceAtLeast(0)
        val top = (titleRect.top - marginV).coerceAtLeast(0)
        val right = (titleRect.right + marginH).coerceAtMost(bitmap.width)
        val bottom = (titleRect.bottom + marginV).coerceAtMost(bitmap.height)
        if (right - left < 40 || bottom - top < 20) {
            return@runCatching RefineOutcome(null, null, "crop_too_small ${right - left}x${bottom - top}", null)
        }

        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = try {
            // 宽高必须按同一缩放比换算（w 已被 2400 上限截断，以 w/原宽 为实际倍率），否则图像变形
            val w = ((right - left) * SCALE).toInt().coerceAtMost(2400)
            val h = ((bottom - top) * (w.toFloat() / (right - left))).toInt()
            Bitmap.createScaledBitmap(crop, w, h, true)
        } catch (oom: OutOfMemoryError) {
            crop
        }

        val secondPass = try {
            val text2 = recognizer.recognize(scaled)
            text2.textBlocks
                .flatMap { block -> block.lines }
                .mapNotNull { line ->
                    line.boundingBox?.let { rect -> rect.top to rect.left to line.text.trim() }
                }
                .sortedWith(compareBy({ it.first.first }, { it.first.second }))
                .map { it.second }
                .filter { it.isNotBlank() }
        } finally {
            crop.recycle()
            if (scaled !== crop) scaled.recycle()
        }

        val refinedTitle = parser.buildTitleFromRawLines(secondPass)
        val refinedNorm = parser.normalizeTitle(refinedTitle)
        val firstNorm = parser.normalizeTitle(first.title)

        // 护栏1：太短不算标题
        if (refinedNorm.length < 6) {
            return@runCatching RefineOutcome(null, secondPass, "too_short len=${refinedNorm.length}", null)
        }
        // 护栏2：长度比例异常说明裁剪区域错位（第二遍看到了别的区域）
        val ratio = refinedNorm.length.toDouble() / firstNorm.length.coerceAtLeast(1)
        if (ratio < 0.6 || ratio > 1.7) {
            return@runCatching RefineOutcome(null, secondPass, "ratio $ratio", null)
        }
        // 护栏3：与第一遍相似度过低说明重识别跑偏（正常误读修正相似度 >0.8）
        val similarity = titleMatcher.similarity(refinedNorm, firstNorm)
        if (similarity < 0.4) {
            return@runCatching RefineOutcome(null, secondPass, "similarity $similarity", null)
        }

        // 两遍逐字融合：一致的字符直接信；分歧字符由标题行以外的整页证据投票裁决（详见 TitleFuser）
        val fused = titleFuser.fuse(first.title, refinedTitle, pageLines, witnessLines)
        val finalTitle = fused.title.takeIf { it.isNotEmpty() } ?: refinedTitle
        RefineOutcome(
            first.copy(
                title = finalTitle,
                normalizedTitle = parser.normalizeTitle(finalTitle),
                ocrTitle = finalTitle
            ),
            secondPass,
            null,
            fused.disagreements
        )
    }.getOrDefault(RefineOutcome(null, null, "exception", null))

    private class PriceRefineOutcome(
        val product: DetectedProduct?,
        val dump: PriceRefineDump?
    )

    /**
     * 价格二遍精识别：裁剪价格行 → 放大 → 重读。小数点在整页截图只有 1-2px，低于
     * ML Kit 感知阈值；放大后是真实可见像素，任意尾数（.1/.5/.73/.99）都能读出——
     * 这是识别率的提升，不是按定价习惯（尾数签名）反推猜测。
     * 裁决护栏：候选须与第一遍读数同数量级（比例 [1/100, 100]——OCR 数字通常读对、
     * 丢的只是小数点，10 倍关系在窗口内；窗口同时挡住分期价/库存数等无关数字）；
     * 第一遍已见小数点而放大未见时保留第一遍。
     */
    private suspend fun refinePrice(
        bitmap: Bitmap,
        product: DetectedProduct,
        lines: List<OcrLineDump>
    ): PriceRefineOutcome = runCatching {
        if (product.priceCents <= 0) {
            return@runCatching PriceRefineOutcome(null, null)
        }
        val src = lines.firstOrNull { it.text == product.rawText }
            ?: return@runCatching PriceRefineOutcome(null, null)

        // 外扩边距：行 bbox 贴字裁切会切掉基线上的小数点
        val marginH = ((src.right - src.left) * 0.05f).toInt().coerceIn(8, 48)
        val marginV = ((src.bottom - src.top) * 0.15f).toInt().coerceIn(6, 28)
        val left = (src.left - marginH).coerceAtLeast(0)
        val top = (src.top - marginV).coerceAtLeast(0)
        val right = (src.right + marginH).coerceAtMost(bitmap.width)
        val bottom = (src.bottom + marginV).coerceAtMost(bitmap.height)
        if (right - left < 30 || bottom - top < 15) {
            return@runCatching PriceRefineOutcome(null, null)
        }

        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        // 反色预处理：白字红底/白字黑底的行（dark 或 red 占多数）对 ML Kit 是分布外输入，
        // 最小字形（小数点、¥）最先丢。反色成黑字浅底后回到模型的训练分布
        val prepared = if (src.darkRatio > 0.5 || src.redRatio > 0.5) {
            invertBitmap(crop).also { crop.recycle() }
        } else {
            crop
        }
        // 二级紧聚焦放大：整行放大时「补贴价 限时」等标签占掉约 40% 行宽，稀释了
        // 2400px 像素预算（数字实际只分到 3 倍）；加大整行倍率反而更糊（6 倍时 ML Kit
        // 内部降采样连 ¥ 都丢）。改为用一级放大的元素框定位数字区，只裁数字重放大——
        // 预算全花在数字上，等效 12-18 倍，小数点从 ~5px 放大到 60px+ 进入可靠感知区
        val firstCents = product.priceCents
        val firstHasDecimal = firstCents % 100 != 0L || product.rawText.contains('.')
        val widthPx = right - left
        val heightPx = bottom - top
        val firstZoom = zoomRecognizeAt(prepared, widthPx, heightPx, PRICE_SCALE)
        val firstZoomText = firstZoom.text.text
        val tightZoomText: String?
        try {
            tightZoomText =
                if (!firstHasDecimal && parser.zoomedPriceCandidates(firstZoomText).none { it.hasDecimal }) {
                    tightDigitZoom(prepared, firstZoom)
                } else {
                    null
                }
        } finally {
            runCatching { prepared.recycle() }
        }
        val candidates = (
            parser.zoomedPriceCandidates(firstZoomText) +
                (tightZoomText?.let { parser.zoomedPriceCandidates(it) } ?: emptyList())
            ).distinct()
        val zoomRawText = if (tightZoomText != null) {
            "$firstZoomText || [tight]$tightZoomText"
        } else {
            firstZoomText
        }

        val acceptable = candidates.filter { c ->
            val ratio = c.cents.toDouble() / firstCents.toDouble()
            ratio in 0.01..100.0
        }
        // 带小数点的候选优先（点被放大真实读出）；同为无点时取贴近第一遍数字者（修数字误读）
        val decimalCandidates = acceptable.filter { it.hasDecimal }
        val chosen = when {
            decimalCandidates.isNotEmpty() ->
                decimalCandidates.minByOrNull { Math.abs(Math.log(it.cents.toDouble() / firstCents)) }
            firstHasDecimal -> null
            else -> acceptable.minByOrNull { Math.abs(Math.log(it.cents.toDouble() / firstCents)) }
        }
        val applied = chosen != null && chosen.cents != firstCents
        PriceRefineOutcome(
            if (applied) product.copy(priceCents = chosen!!.cents) else null,
            PriceRefineDump(zoomRawText, firstCents, firstHasDecimal, candidates, chosen?.cents, applied)
        )
    }.getOrDefault(PriceRefineOutcome(null, null))

    private class ZoomOutcome(val text: Text, val scale: Float)

    private suspend fun zoomRecognizeAt(
        prepared: Bitmap,
        widthPx: Int,
        heightPx: Int,
        scale: Float
    ): ZoomOutcome {
        val scaled = try {
            // 宽高同一缩放比（w 被 2400 上限截断时以 w/原宽 为实际倍率），保持比例防变形
            val w = (widthPx * scale).toInt().coerceAtMost(2400)
            val h = (heightPx * (w.toFloat() / widthPx)).toInt()
            Bitmap.createScaledBitmap(prepared, w, h, true)
        } catch (oom: OutOfMemoryError) {
            prepared
        }
        val effectiveScale = if (scaled === prepared) 1f else scaled.width.toFloat() / widthPx
        return try {
            ZoomOutcome(recognizer.recognize(scaled), effectiveScale)
        } finally {
            if (scaled !== prepared) scaled.recycle()
        }
    }

    /**
     * 紧聚焦二级放大：从一级放大的识别结果里找含数字的元素框（换算回裁剪图坐标），
     * 只裁数字区域重放大。行内标签文字（如「补贴价 限时」）不再稀释像素预算。
     * 返回 null 表示定位不到数字元素（放弃二级，走整数候选）。
     */
    private suspend fun tightDigitZoom(prepared: Bitmap, zoom: ZoomOutcome): String? {
        var digitBox: Rect? = null
        for (block in zoom.text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    if (element.text.any { it.isDigit() }) {
                        val box = element.boundingBox ?: continue
                        digitBox = digitBox?.apply { union(box) } ?: Rect(box)
                    }
                }
            }
        }
        val box = digitBox ?: return null
        val s = zoom.scale
        // 余量：小数点骑在基线上且夹在数字之间，横向 20% 防切半个数字，纵向 40% 护住基线下的点
        val marginH = ((box.width() / s) * 0.2f).toInt().coerceAtLeast(6)
        val marginV = ((box.height() / s) * 0.4f).toInt().coerceAtLeast(8)
        val tl = ((box.left / s).toInt() - marginH).coerceAtLeast(0)
        val tt = ((box.top / s).toInt() - marginV).coerceAtLeast(0)
        val tr = ((box.right / s).toInt() + marginH).coerceAtMost(prepared.width)
        val tb = ((box.bottom / s).toInt() + marginV).coerceAtMost(prepared.height)
        if (tr - tl < 20 || tb - tt < 10) return null
        val tight = Bitmap.createBitmap(prepared, tl, tt, tr - tl, tb - tt)
        val scale = (2400f / (tr - tl)).coerceAtMost(TIGHT_ZOOM_MAX_SCALE)
        val outcome = zoomRecognizeAt(tight, tr - tl, tb - tt, scale)
        tight.recycle()
        return outcome.text.text
    }

    private fun invertBitmap(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 保存手动编辑的商品：返回新插入的历史条目 id（>0），供截图归档
     * autoSaved=true 时该条历史打上自动保存标（识别后未经用户面板确认）
     */
    suspend fun saveManualProduct(product: DetectedProduct, autoSaved: Boolean = false): Long {
        return repository.saveManualProduct(product, autoSaved)
    }

    /** 撤回一条历史记录（连带截图、空壳商品清理、同步墓碑）——自动保存行的纠错路径 */
    suspend fun deleteHistoryEntry(historyId: Long) {
        repository.deleteHistoryEntry(historyId)
    }

    /**
     * 根据面板内容构造 DetectedProduct。ocrTitle 是识别瞬间的原始标题——
     * 与 title 的差异即用户本次的编辑幅度，保存侧据此决定是否沿用商品已有标题
     */
    fun createDetectedProduct(title: String, priceCents: Long, ocrTitle: String): DetectedProduct =
        DetectedProduct(
            title = title,
            normalizedTitle = parser.normalizeTitle(title),
            priceCents = priceCents,
            rawText = "manual overlay",
            ocrTitle = ocrTitle
        )

    private companion object {
        const val SCALE = 2.5f
        const val PRICE_SCALE = 3f
        const val TIGHT_ZOOM_MAX_SCALE = 12f
    }
}
