package com.example.pddpricemonitor.ocr

import android.graphics.Rect

data class DetectedProduct(
    val title: String,
    val normalizedTitle: String,
    val priceCents: Long,
    val rawText: String,
    // 识别时的 OCR 原始标题（未经用户编辑）。OCR 产物自身 title 即原文；
    // 用户编辑面板后重建的 DetectedProduct 由调用方把识别原文传入
    val ocrTitle: String = ""
)

data class ProductParseResult(
    val products: List<DetectedProduct>,
    val skippedReason: String? = null,
    val lines: List<OcrLineDump> = emptyList(),
    // 标题行区域（屏幕坐标系），供第二遍高分辨率重识别裁剪用
    val titleRect: Rect? = null
)

// 诊断转储用的单行 OCR 数据：文本 + 坐标 + 颜色比例（黑/亮/红/绿像素占比）
data class OcrLineDump(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val darkRatio: Double,
    val lightRatio: Double,
    val redRatio: Double,
    val greenRatio: Double,
    val screenHeight: Int,
    val screenWidth: Int
)
