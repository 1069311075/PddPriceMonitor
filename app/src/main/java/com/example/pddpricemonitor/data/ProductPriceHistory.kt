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
    val deviceName: String = "本机"
)
