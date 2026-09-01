package com.example.pddpricemonitor.sync

import com.example.pddpricemonitor.data.PeerHistoryRecord
import com.example.pddpricemonitor.data.PeerProductData
import com.example.pddpricemonitor.data.PeerTombstone
import org.json.JSONArray
import org.json.JSONObject

// 线上协议（每行一条 JSON）：
//   {"type":"hello","deviceId":"..","deviceName":".."}
//   {"type":"full","products":[..],"tombstones":[..]}
//   {"type":"bye"}
sealed class SyncMessage {
    data class Hello(val deviceId: String, val deviceName: String) : SyncMessage()
    data class Full(
        val products: List<PeerProductData>,
        val tombstones: List<PeerTombstone> = emptyList()
    ) : SyncMessage()
    object Bye : SyncMessage()
}

object SyncProtocol {
    fun encode(message: SyncMessage): String = when (message) {
        is SyncMessage.Hello -> JSONObject()
            .put("type", "hello")
            .put("deviceId", message.deviceId)
            .put("deviceName", message.deviceName)
            .toString()
        is SyncMessage.Full -> JSONObject()
            .put("type", "full")
            .put("products", encodeProducts(message.products))
            .put("tombstones", encodeTombstones(message.tombstones))
            .toString()
        SyncMessage.Bye -> JSONObject().put("type", "bye").toString()
    }

    // 返回 null 表示无法解析的行（忽略，协议容错）
    fun decode(line: String): SyncMessage? = runCatching {
        val json = JSONObject(line)
        when (json.optString("type")) {
            "hello" -> SyncMessage.Hello(
                deviceId = json.optString("deviceId"),
                deviceName = json.optString("deviceName", "对方设备")
            )
            "full" -> SyncMessage.Full(
                products = decodeProducts(json.optJSONArray("products")),
                tombstones = decodeTombstones(json.optJSONArray("tombstones"))
            )
            "bye" -> SyncMessage.Bye
            else -> null
        }
    }.getOrNull()

    private fun encodeProducts(products: List<PeerProductData>): JSONArray {
        val array = JSONArray()
        products.forEach { product ->
            val history = JSONArray()
            product.history.forEach { record ->
                history.put(
                    JSONObject()
                        .put("priceCents", record.priceCents)
                        .put("recordedAt", record.recordedAt)
                        .put("deviceId", record.deviceId)
                        .put("deviceName", record.deviceName)
                        .put("autoSaved", record.autoSaved)
                )
            }
            array.put(
                JSONObject()
                    .put("title", product.title)
                    .put("normalizedTitle", product.normalizedTitle)
                    .put("ocrTitle", product.ocrTitle)
                    .put("firstSeenAt", product.firstSeenAt)
                    .put("history", history)
            )
        }
        return array
    }

    private fun encodeTombstones(tombstones: List<PeerTombstone>): JSONArray {
        val array = JSONArray()
        tombstones.forEach { t ->
            array.put(
                JSONObject()
                    .put("targetType", t.targetType)
                    .put("keyTitle", t.keyTitle)
                    .put("recordedAt", t.recordedAt)
                    .put("deviceId", t.deviceId)
                    .put("deletedAt", t.deletedAt)
            )
        }
        return array
    }

    private fun decodeTombstones(array: JSONArray?): List<PeerTombstone> {
        if (array == null) return emptyList()
        val result = mutableListOf<PeerTombstone>()
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            result.add(
                PeerTombstone(
                    targetType = t.optString("targetType", "product"),
                    keyTitle = t.optString("keyTitle"),
                    recordedAt = t.optLong("recordedAt"),
                    deviceId = t.optString("deviceId"),
                    deletedAt = t.optLong("deletedAt")
                )
            )
        }
        return result
    }

    private fun decodeProducts(array: JSONArray?): List<PeerProductData> {
        if (array == null) return emptyList()
        val result = mutableListOf<PeerProductData>()
        for (i in 0 until array.length()) {
            val product = array.optJSONObject(i) ?: continue
            val historyArray = product.optJSONArray("history") ?: JSONArray()
            val history = mutableListOf<PeerHistoryRecord>()
            for (j in 0 until historyArray.length()) {
                val record = historyArray.optJSONObject(j) ?: continue
                history.add(
                    PeerHistoryRecord(
                        priceCents = record.optLong("priceCents"),
                        recordedAt = record.optLong("recordedAt"),
                        deviceId = record.optString("deviceId", "peer"),
                        deviceName = record.optString("deviceName", "对方设备"),
                        autoSaved = record.optBoolean("autoSaved", false)
                    )
                )
            }
            result.add(
                PeerProductData(
                    title = product.optString("title"),
                    normalizedTitle = product.optString("normalizedTitle"),
                    firstSeenAt = product.optLong("firstSeenAt", System.currentTimeMillis()),
                    history = history,
                    ocrTitle = product.optString("ocrTitle")
                )
            )
        }
        return result
    }
}
