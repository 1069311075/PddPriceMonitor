package com.example.pddpricemonitor.data

import com.example.pddpricemonitor.matcher.TitleMatcher
import com.example.pddpricemonitor.ocr.DetectedProduct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class ProductPriceComparison(
    val matchedTitle: String,
    val previousPriceCents: Long,
    val previousLowestCents: Long
)

// 同步交换格式：商品 + 历史记录（recordedAt/deviceId 原样保留，是合并去重键的一部分）
data class PeerHistoryRecord(
    val priceCents: Long,
    val recordedAt: Long,
    val deviceId: String,
    val deviceName: String,
    // 该条是否「识别后自动保存」（未经面板确认）：对端明细里据此显示 auto 标记；
    // 旧版本对端不传此字段时按 false 处理
    val autoSaved: Boolean = false
)

data class PeerProductData(
    val title: String,
    val normalizedTitle: String,
    val firstSeenAt: Long,
    val history: List<PeerHistoryRecord>,
    // 旧版本对端发来的数据无此字段 → 空串，合并侧回填 title
    val ocrTitle: String = ""
)

// 同步交换的删除墓碑
data class PeerTombstone(
    val targetType: String, // "product" | "history"
    val keyTitle: String,
    val recordedAt: Long,
    val deviceId: String,
    val deletedAt: Long
)

class ProductRepository(
    private val dao: ProductPriceDao,
    private val screenshotStore: ScreenshotStore,
    private val matcher: TitleMatcher = TitleMatcher(),
    private val deviceId: String = "local",
    // 动态读取：同步弹窗里改名后，新保存的记录立即用新名字
    private val deviceNameProvider: () -> String = { "本机" }
) {
    private val deviceName: String get() = deviceNameProvider()
    // 本地用户操作（识别保存/手动保存）触发；mergeFromPeer 吸收远端数据时静默，避免同步回环
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val localChanges: SharedFlow<Unit> = _localChanges

    fun observeAll(): Flow<List<ProductPrice>> = dao.observeAll()

    fun observeHistory(productId: Long): Flow<List<ProductPriceHistory>> = dao.observeHistory(productId)

    suspend fun clearAll() {
        dao.deleteAllHistory()
        dao.deleteAll()
        dao.deleteAllTombstones()
        screenshotStore.deleteAll()
    }

    suspend fun deleteProduct(productId: Long) {
        val product = dao.getProductById(productId)
        val historyIds = dao.getHistoryIdsForProduct(productId)
        dao.deleteHistoryForProduct(productId)
        dao.deleteProductById(productId)
        if (historyIds.isNotEmpty()) screenshotStore.deleteFor(historyIds)
        if (product != null) {
            dao.insertTombstone(
                SyncTombstone(
                    targetType = "product",
                    keyTitle = product.normalizedTitle,
                    deletedAt = System.currentTimeMillis()
                )
            )
            _localChanges.tryEmit(Unit)
        }
    }

    suspend fun deleteHistoryEntry(historyId: Long) {
        val entry = dao.getHistoryById(historyId) ?: return
        val product = dao.getProductById(entry.productId)
        dao.deleteHistoryById(historyId)
        screenshotStore.deleteFor(listOf(historyId))
        val lowest = dao.getLowestHistoryPrice(entry.productId)
        if (lowest == null) {
            // 历史清空后商品已无意义，连同删除，避免留下无记录的空壳商品
            dao.deleteProductById(entry.productId)
            // 商品删空时补一张商品级墓碑，整个商品（含其余历史）在对端一并消失
            product?.let {
                dao.insertTombstone(
                    SyncTombstone(
                        targetType = "product",
                        keyTitle = it.normalizedTitle,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
        } else {
            dao.getProductById(entry.productId)?.let { p ->
                dao.update(p.copy(priceCents = lowest, updatedAt = System.currentTimeMillis()))
            }
        }
        // 墓碑的 deviceId 要与导出格式一致：旧版本本机数据（"local"）导出时会改写成
        // 真实设备 ID，对端收到的记录用的是真实 ID，墓碑若写 "local" 将匹配不上
        val tombDeviceId = if (entry.deviceId == "local") deviceId else entry.deviceId
        dao.insertTombstone(
            SyncTombstone(
                targetType = "history",
                keyTitle = entry.title,
                recordedAt = entry.recordedAt,
                deviceId = tombDeviceId,
                deletedAt = System.currentTimeMillis()
            )
        )
        _localChanges.tryEmit(Unit)
    }

    suspend fun findPriceComparison(product: DetectedProduct): ProductPriceComparison? {
        val matched = matcher.findBestMatch(
            product.normalizedTitle,
            product.ocrTitle.ifBlank { product.normalizedTitle },
            dao.getAllOnce()
        ) ?: return null
        return ProductPriceComparison(
            matchedTitle = matched.title,
            previousPriceCents = matched.priceCents,
            previousLowestCents = dao.getLowestHistoryPrice(matched.id) ?: matched.priceCents
        )
    }

    suspend fun upsertLowerPrices(products: List<DetectedProduct>): Int {
        if (products.isEmpty()) return 0

        var existing = dao.getAllOnce()
        val now = System.currentTimeMillis()
        var changedCount = 0

        products.forEach { detected ->
            val ocrTitle = detected.ocrTitle.ifBlank { detected.title }
            val matched = matcher.findBestMatch(detected.normalizedTitle, ocrTitle, existing)
            if (matched == null) {
                val inserted = ProductPrice(
                    title = detected.title,
                    normalizedTitle = detected.normalizedTitle,
                    priceCents = detected.priceCents,
                    firstSeenAt = now,
                    updatedAt = now,
                    ocrTitle = ocrTitle
                )
                val productId = dao.insert(inserted)
                dao.insertHistory(detected.toHistory(productId, now))
                existing = dao.getAllOnce()
                changedCount++
            } else if (detected.priceCents < matched.priceCents) {
                // 低价更新不覆盖标题——商品若被用户编辑过（title != ocrTitle）保持现状
                val userEditedBefore = matched.ocrTitle.isNotBlank() && matched.title != matched.ocrTitle
                val updated = matched.copy(
                    title = if (userEditedBefore) matched.title else detected.title,
                    normalizedTitle = if (userEditedBefore) matched.normalizedTitle else detected.normalizedTitle,
                    priceCents = detected.priceCents,
                    updatedAt = now,
                    ocrTitle = ocrTitle
                )
                dao.update(updated)
                dao.insertHistory(detected.toHistory(matched.id, now))
                existing = existing.map { if (it.id == matched.id) updated else it }
                changedCount++
            }
        }

        if (changedCount > 0) _localChanges.tryEmit(Unit)
        return changedCount
    }

    // 返回新插入的历史条目 id（>0）：识别流程拿它把暂存截图归档为该条记录的存档图。
    // autoSaved=true 时该条历史打上自动保存标（识别后未经用户面板确认）
    suspend fun saveManualProduct(product: DetectedProduct, autoSaved: Boolean = false): Long {
        val existing = dao.getAllOnce()
        val now = System.currentTimeMillis()
        val ocrTitle = product.ocrTitle.ifBlank { product.title }
        val matched = matcher.findBestMatch(product.normalizedTitle, ocrTitle, existing)

        val historyId = if (matched == null) {
            val productId = dao.insert(
                ProductPrice(
                    title = product.title,
                    normalizedTitle = product.normalizedTitle,
                    priceCents = product.priceCents,
                    firstSeenAt = now,
                    updatedAt = now,
                    ocrTitle = ocrTitle
                )
            )
            dao.insertHistory(product.toHistory(productId, now, autoSaved))
        } else {
            // 标题覆盖规则——保护用户编辑：
            // - 本次面板值 != 本次 OCR 原文 → 用户本次编辑了，尊重本次值
            // - 本次面板值 == OCR 原文（没编辑）但商品此前被编辑过（title != ocrTitle）
            //   → 保留商品现有标题，仅刷新 OCR 签名与价格，不让 OCR 原文盖回用户改的名字
            val userEditedNow = product.title != ocrTitle
            val userEditedBefore = matched.ocrTitle.isNotBlank() && matched.title != matched.ocrTitle
            val keepExistingTitle = !userEditedNow && userEditedBefore
            dao.update(
                matched.copy(
                    title = if (keepExistingTitle) matched.title else product.title,
                    normalizedTitle = if (keepExistingTitle) matched.normalizedTitle else product.normalizedTitle,
                    priceCents = product.priceCents,
                    updatedAt = now,
                    ocrTitle = ocrTitle
                )
            )
            dao.insertHistory(product.toHistory(matched.id, now, autoSaved))
        }
        _localChanges.tryEmit(Unit)
        return historyId
    }

    // ---------- P2P 同步：导出 / 合并 ----------

    suspend fun exportForSync(): List<PeerProductData> {
        val products = dao.getAllOnce()
        val history = dao.getAllHistoryOnce().groupBy { it.productId }
        return products.map { product ->
            PeerProductData(
                title = product.title,
                normalizedTitle = product.normalizedTitle,
                ocrTitle = product.ocrTitle,
                firstSeenAt = product.firstSeenAt,
                history = (history[product.id] ?: emptyList()).map {
                    // 归一化：本机旧版本数据（"local"）导出时改写成真实设备 ID，
                    // 否则对方会把 "local" 当成一台陌生的第三设备
                    val isLegacyLocal = it.deviceId == "local"
                    PeerHistoryRecord(
                        priceCents = it.priceCents,
                        recordedAt = it.recordedAt,
                        deviceId = if (isLegacyLocal) deviceId else it.deviceId,
                        deviceName = if (isLegacyLocal) deviceName else it.deviceName,
                        autoSaved = it.autoSaved
                    )
                }
            )
        }
    }

    suspend fun exportTombstones(): List<PeerTombstone> =
        dao.getAllTombstones().map {
            PeerTombstone(
                targetType = it.targetType,
                keyTitle = it.keyTitle,
                recordedAt = it.recordedAt,
                deviceId = it.deviceId,
                deletedAt = it.deletedAt
            )
        }

    // 合并远端数据：先吸收并应用对方墓碑（删掉本地对应数据），再按本地墓碑过滤后合并。
    // 返回新插入的记录数；0 表示对方数据已全部拥有（幂等，重复对账无副作用）
    suspend fun mergeFromPeer(
        peerProducts: List<PeerProductData>,
        peerTombstones: List<PeerTombstone> = emptyList()
    ): Int {
        // 1. 吸收墓碑入库（幂等去重）：后续本机再连第三台设备时墓碑能继续传递
        peerTombstones.forEach { t ->
            dao.insertTombstone(
                SyncTombstone(
                    targetType = t.targetType,
                    keyTitle = t.keyTitle,
                    recordedAt = t.recordedAt,
                    deviceId = t.deviceId,
                    deletedAt = t.deletedAt
                )
            )
        }
        // 2. 应用墓碑到本地现有数据（静默，不触发 localChanges，避免同步回环）
        applyPeerTombstones(peerTombstones)
        if (peerProducts.isEmpty()) return 0

        // 3. 本地墓碑过滤远端数据，防止已删数据借对方全量复活
        val productTombstones = dao.getAllTombstones().filter { it.targetType == "product" }
        val historyTombstones = dao.getAllTombstones().filter { it.targetType == "history" }

        var existing = dao.getAllOnce()
        var insertedCount = 0
        val touchedProductIds = mutableSetOf<Long>()

        peerProducts.forEach { peer ->
            // 商品级墓碑命中：对方这份数据整体早于删除时间 → 视为已删数据，跳过。
            // （若有更新记录晚于删除时间，说明对端后来重新记过价，放行）
            val newestRecordedAt = peer.history.maxOfOrNull { it.recordedAt } ?: 0
            val killedBy = productTombstones.firstOrNull { it.keyTitle == peer.normalizedTitle }
            if (killedBy != null && newestRecordedAt <= killedBy.deletedAt) return@forEach

            var matched = matcher.findBestMatch(
                peer.normalizedTitle,
                peer.ocrTitle.ifBlank { peer.title },
                existing
            )
            if (matched == null) {
                val lowest = peer.history.minOfOrNull { it.priceCents }
                val newest = peer.history.maxByOrNull { it.recordedAt }
                val newId = dao.insert(
                    ProductPrice(
                        title = peer.title,
                        normalizedTitle = peer.normalizedTitle,
                        ocrTitle = peer.ocrTitle.ifBlank { peer.title },
                        priceCents = newest?.priceCents ?: lowest ?: 0L,
                        firstSeenAt = peer.firstSeenAt,
                        updatedAt = newest?.recordedAt ?: peer.firstSeenAt
                    )
                )
                // 刚插入必存在；极端情况下（插入被约束拒绝）跳过该商品继续同步其余数据
                matched = dao.getProductById(newId) ?: return@forEach
                existing = dao.getAllOnce()
            }
            val productId = matched.id

            peer.history.forEach { record ->
                // 历史级墓碑命中：这条记录已被本机删除过，跳过
                val recordKilled = historyTombstones.any {
                    it.recordedAt == record.recordedAt && it.deviceId == record.deviceId
                }
                if (recordKilled) return@forEach

                if (dao.countHistoryByKey(productId, record.recordedAt, record.deviceId) == 0) {
                    dao.insertHistory(
                        ProductPriceHistory(
                            productId = productId,
                            title = peer.title,
                            priceCents = record.priceCents,
                            recordedAt = record.recordedAt,
                            deviceId = record.deviceId,
                            deviceName = record.deviceName,
                            autoSaved = record.autoSaved
                        )
                    )
                    insertedCount++
                    touchedProductIds += productId
                }
            }
        }

        // 合并后刷新受影响商品：priceCents 跟随最新一条记录，updatedAt 取最大
        touchedProductIds.forEach { productId ->
            val history = dao.getAllHistoryOnce().filter { it.productId == productId }
            val newest = history.maxByOrNull { it.recordedAt } ?: return@forEach
            dao.getProductById(productId)?.let { product ->
                dao.update(product.copy(priceCents = newest.priceCents, updatedAt = newest.recordedAt))
            }
        }

        return insertedCount
    }

    // 把远端墓碑作用到本地数据：命中的商品/历史记录直接删除。
    // 墓碑带删除时间——本地数据若在删除之后又记过价（对端重新保存），保留本地数据。
    // 删行前先收集历史条目 id：行没了之后本机截图文件要一并清掉，避免孤儿文件堆积
    private suspend fun applyPeerTombstones(tombstones: List<PeerTombstone>) {
        if (tombstones.isEmpty()) return
        val products = dao.getAllOnce()
        val removedHistoryIds = mutableListOf<Long>()
        tombstones.forEach { t ->
            when (t.targetType) {
                "product" -> {
                    val target = products.firstOrNull { it.normalizedTitle == t.keyTitle } ?: return@forEach
                    val newest = dao.getHistoryForProductOnce(target.id).maxOfOrNull { it.recordedAt } ?: 0
                    if (newest <= t.deletedAt) {
                        removedHistoryIds += dao.getHistoryIdsForProduct(target.id)
                        dao.deleteHistoryForProduct(target.id)
                        dao.deleteProductById(target.id)
                    }
                }
                "history" -> {
                    val productIds = dao.findHistoryProductIdsBySyncKey(t.recordedAt, t.deviceId)
                    if (productIds.isEmpty()) return@forEach
                    removedHistoryIds += dao.findHistoryIdsBySyncKey(t.recordedAt, t.deviceId)
                    dao.deleteHistoryBySyncKey(t.recordedAt, t.deviceId)
                    // 受影响商品重算：跟随最新记录；历史删空则连同商品删除
                    productIds.forEach { productId ->
                        val history = dao.getHistoryForProductOnce(productId)
                        val newest = history.maxByOrNull { it.recordedAt }
                        if (newest == null) {
                            dao.deleteProductById(productId)
                        } else {
                            dao.getProductById(productId)?.let { product ->
                                dao.update(product.copy(priceCents = newest.priceCents, updatedAt = System.currentTimeMillis()))
                            }
                        }
                    }
                }
            }
        }
        if (removedHistoryIds.isNotEmpty()) {
            screenshotStore.deleteFor(removedHistoryIds)
        }
    }

    private fun DetectedProduct.toHistory(
        productId: Long,
        recordedAt: Long,
        autoSaved: Boolean = false
    ): ProductPriceHistory =
        ProductPriceHistory(
            productId = productId,
            title = title,
            priceCents = priceCents,
            recordedAt = recordedAt,
            deviceId = deviceId,
            deviceName = deviceName,
            autoSaved = autoSaved
        )
}
