package com.example.pddpricemonitor.capture

import com.example.pddpricemonitor.data.HdCaptureStore
import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 截图业务：取一帧「隐藏本软件控件之后」的新画面，缩放编码（长边上限、JPEG q85）落成独立记录。
 * 不依赖 OCR 成功，不读不写商品价格，也不碰旧 OCR 附图与待确认记录。
 */
@Singleton
class HdCaptureInteractor @Inject constructor(
    private val store: HdCaptureStore
) {
    sealed class Result {
        data class Success(val entry: HdCaptureStore.Entry) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * @param sinceNanos 画面新鲜度的基准时间（调用方在隐藏控件前取 System.nanoTime()）
     * @param acquireFrame 取一帧时间戳晚于 sinceNanos 的新画面；null = 超时没有新帧
     */
    suspend fun capture(
        sinceNanos: Long,
        acquireFrame: suspend (Long) -> Bitmap?
    ): Result {
        val bitmap = acquireFrame(sinceNanos)
            ?: return Result.Failure("没能取到新画面，请稍后再试")
        return try {
            when (val saved = store.save(bitmap)) {
                is HdCaptureStore.SaveResult.Ok -> Result.Success(saved.entry)
                is HdCaptureStore.SaveResult.Failed -> Result.Failure(saved.reason)
            }
        } catch (error: Throwable) {
            Result.Failure("截图保存失败：${error.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
        }
    }
}
