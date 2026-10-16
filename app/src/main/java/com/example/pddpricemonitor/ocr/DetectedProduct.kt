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
    val screenWidth: Int,
    // 行内词块（elements）：底栏价格的词块定位路径（¥ 锚点后取主数字+小数位）依赖
    // 词块矩形，行文本丢 ¥/小数点时词块路径仍能出价。旧 dump 无此字段（空表），
    // 回放时退化为纯文本路径（KNOWN_ELEMENT_LOSS 白名单的来源）
    val elements: List<OcrElementDump> = emptyList()
)

// 词块级数据：文本 + 坐标（颜色比例按行记录，词块不重复采样）
data class OcrElementDump(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
