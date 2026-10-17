package com.example.pddpricemonitor.capture

import android.graphics.Bitmap
import android.media.ImageReader

class ImageReaderBitmapSource(
    private val imageReader: ImageReader
) {
    /** 一帧画面 + 采集时间戳（System.nanoTime 时基，用于判断帧新旧） */
    class Frame(val bitmap: Bitmap, val timestampNanos: Long)

    fun acquireLatestBitmap(): Bitmap? = acquireFrame()?.bitmap

    fun acquireFrame(): Frame? {
        val image = imageReader.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes.first()
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * it.width

            val paddedBitmap = Bitmap.createBitmap(
                it.width + rowPadding / pixelStride,
                it.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, it.width, it.height)
            paddedBitmap.recycle()
            return Frame(cropped, it.timestamp)
        }
    }
}
