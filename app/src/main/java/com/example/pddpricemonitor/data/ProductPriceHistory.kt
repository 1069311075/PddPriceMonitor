package com.example.pddpricemonitor.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "product_price_history",
    foreignKeys = [
        ForeignKey(
            entity = ProductPrice::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["productId"])]
)
data class ProductPriceHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val title: String,
    val priceCents: Long,
    val recordedAt: Long,
    // 记录来源设备：本机记的与同步来的区分开，折线图按设备着色
    val deviceId: String = "local",
    val deviceName: String = "本机",
    // 识别后自动保存（未经用户在面板上点确认）的记录。历史明细中以「自动记录」灰字
    // 区分人工确认过的记录；错价回溯时优先核对带此标的行
    val autoSaved: Boolean = false
)
