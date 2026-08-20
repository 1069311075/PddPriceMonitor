package com.example.pddpricemonitor.capture

import android.graphics.Bitmap
import com.example.pddpricemonitor.data.ProductPriceComparison
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.ocr.DetectedProduct
import com.example.pddpricemonitor.ocr.ProductTextParser
import com.example.pddpricemonitor.ocr.TextRecognizerClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OCR 识别业务协调器：
 * - 封装"截图 → OCR → 解析 → 对比 → 保存"的完整业务流程
 * - 不持有任何 UI 引用，纯业务逻辑
 * - 通过回调通知外部结果
 *
 * 这样 Service 只需要关心生命周期和 UI 调度，业务逻辑在这里可以独立测试。
 */
@Singleton
class OcrCaptureInteractor @Inject constructor(
    private val recognizer: TextRecognizerClient,
    private val parser: ProductTextParser,
    private val repository: ProductRepository
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

    /**
     * 执行一次完整的 OCR 识别流程
     */
    suspend fun captureOnce(bitmapProvider: () -> Bitmap?): CaptureResult {
        val bitmap = bitmapProvider() ?: return CaptureResult.NoFrame

        return try {
            val text = recognizer.recognize(bitmap)
            val result = parser.parseWithReason(text, bitmap)
            val product = result.products.singleOrNull()

            if (product == null) {
                CaptureResult.NoProduct(
                    reason = result.skippedReason ?: "没有识别到商品",
                    textLength = text.text.length,
                    parsedCount = result.products.size
                )
            } else {
                val comparison = repository.findPriceComparison(product)
                CaptureResult.Success(product, comparison)
            }
        } catch (error: Throwable) {
            CaptureResult.Error(error)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 保存手动编辑的商品
     */
    suspend fun saveManualProduct(product: DetectedProduct): Int {
        return repository.saveManualProduct(product)
    }

    /**
     * 根据标题和价格构造 DetectedProduct
     */
    fun createDetectedProduct(title: String, priceCents: Long): DetectedProduct =
        DetectedProduct(
            title = title,
            normalizedTitle = parser.normalizeTitle(title),
            priceCents = priceCents,
            rawText = "manual overlay"
        )
}
