package com.example.pddpricemonitor.ocr

data class DetectedProduct(
    val title: String,
    val normalizedTitle: String,
    val priceCents: Long,
    val rawText: String
)

data class ProductParseResult(
    val products: List<DetectedProduct>,
    val skippedReason: String? = null
)
