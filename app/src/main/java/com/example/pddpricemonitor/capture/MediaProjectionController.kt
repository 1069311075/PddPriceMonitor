package com.example.pddpricemonitor.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
        return bitmapSource
    }

    fun acquireBitmap() = bitmapSource?.acquireLatestBitmap()

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
