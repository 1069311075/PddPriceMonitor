package com.example.pddpricemonitor.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// 删除墓碑：本地删除的商品/历史记录。随全量数据一起同步，
// 对端应用墓碑（删除对应数据）；本地合并时用墓碑过滤远端数据（防止删掉的数据"复活"）
@Entity(
    tableName = "sync_tombstones",
    indices = [Index(value = ["targetType", "keyTitle", "recordedAt", "deviceId"], unique = true)]
)
data class SyncTombstone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // "product"：整个商品删除；"history"：单条历史记录删除
    val targetType: String,
    // product 墓碑存商品 normalizedTitle；history 墓碑存记录标题（仅备查）
    val keyTitle: String,
    // history 墓碑的定位键：recordedAt + deviceId 在全库唯一（毫秒时间戳 + 设备）
    val recordedAt: Long = 0,
    val deviceId: String = "",
    val deletedAt: Long
)
