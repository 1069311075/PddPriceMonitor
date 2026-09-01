package com.example.pddpricemonitor.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_prices",
    indices = [Index(value = ["normalizedTitle"], unique = false)]
)
data class ProductPrice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val normalizedTitle: String,
    val priceCents: Long,
    val firstSeenAt: Long,
    val updatedAt: Long,
    // OCR 签名：该商品最近一次识别的原始标题（未经用户编辑）。
    // 用户编辑 title 后，下次识别仍用它做双路匹配，避免编辑幅度大时匹配断裂产生重复商品；
    // title != ocrTitle 即代表用户编辑过，此时识别保存不覆盖用户标题
    val ocrTitle: String = ""
)
