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
    val updatedAt: Long
)
