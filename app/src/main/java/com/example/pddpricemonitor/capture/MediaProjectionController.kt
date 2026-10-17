package com.example.pddpricemonitor.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 负责 MediaProjection 的生命周期管理：
 * - 启动/停止屏幕录制
 * - 管理 VirtualDisplay 和 ImageReader
 * - 提供截图能力
 * - 向外部暴露运行状态
 *
 * 注意：此类不持有 Service 引用，通过回调通知停止事件，
 * 由 Service 负责自己的生命周期管理。
 */
class MediaProjectionController(
    private val context: Context,
    private val onProjectionStopped: () -> Unit
) {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bitmapSource: ImageReaderBitmapSource? = null

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    val isStarted: Boolean
        get() = projection != null

    fun start(resultCode: Int, data: Intent): ImageReaderBitmapSource? {
        if (projection != null) return bitmapSource

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val manager = context.getSystemService(MediaProjectionManager::class.java)

        // token 失效（系统重启后重发旧 Intent、授权过期、API 34 前台服务类型时序）时
        // getMediaProjection 抛 SecurityException/IllegalStateException——不兜住的话
        // 异常沿 onStartCommand 上抛直接崩溃，且已创建的 ImageReader 泄漏
        return try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            projection = manager.getMediaProjection(resultCode, data).also { mediaProjection ->
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        _isActive.value = false
                        onProjectionStopped()
                    }
                }, null)
            }
            virtualDisplay = projection?.createVirtualDisplay(
                "pdd-price-monitor",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            bitmapSource = ImageReaderBitmapSource(requireNotNull(imageReader))
            _isActive.value = true
            bitmapSource
        } catch (e: Exception) {
            release()
            null
        }
    }

    fun acquireBitmap() = bitmapSource?.acquireLatestBitmap()

    /**
     * 等一帧「隐藏控件之后」产生的新画面，供截图使用。
     * ImageReader 里可能还压着隐藏前的旧帧，只认时间戳晚于 [sinceNanos] 的帧；
     * 个别设备不提供时间戳（0）时退化为「先丢弃一帧再看下一帧」。
     * @return null = 超时仍未取到新帧（不能拿旧帧冒充成功）
     */
    suspend fun acquireFreshFrame(sinceNanos: Long, timeoutMs: Long = 1_200L): Bitmap? {
        val source = bitmapSource ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var discarded = 0
        while (true) {
            val frame = source.acquireFrame()
            if (frame != null) {
                val fresh = if (frame.timestampNanos > 0L) {
                    frame.timestampNanos >= sinceNanos
                } else {
                    discarded >= 1
                }
                if (fresh) return frame.bitmap
                frame.bitmap.recycle()
                discarded++
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(12L)
        }
    }

    fun release() {
        _isActive.value = false
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        imageReader?.close()
        imageReader = null
        bitmapSource = null
    }
}
