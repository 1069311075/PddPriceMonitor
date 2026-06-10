package com.example.pddpricemonitor.data

import com.example.pddpricemonitor.matcher.TitleMatcher
import com.example.pddpricemonitor.ocr.DetectedProduct
import kotlinx.coroutines.flow.Flow

data class ProductPriceComparison(
    val matchedTitle: String,
    val previousPriceCents: Long,
    val previousLowestCents: Long
)

class ProductRepository(
    private val dao: ProductPriceDao,
    private val matcher: TitleMatcher = TitleMatcher()
) {
    fun observeAll(): Flow<List<ProductPrice>> = dao.observeAll()

    fun observeHistory(productId: Long): Flow<List<ProductPriceHistory>> = dao.observeHistory(productId)

    suspend fun clearAll() {
        dao.deleteAllHistory()
        dao.deleteAll()
    }

    suspend fun deleteProduct(productId: Long) {
        dao.deleteHistoryForProduct(productId)
        dao.deleteProductById(productId)
    }

    suspend fun findPriceComparison(product: DetectedProduct): ProductPriceComparison? {
        val matched = matcher.findBestMatch(product.normalizedTitle, dao.getAllOnce()) ?: return null
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
            val matched = matcher.findBestMatch(detected.normalizedTitle, existing)
            if (matched == null) {
                val inserted = ProductPrice(
                    title = detected.title,
                    normalizedTitle = detected.normalizedTitle,
                    priceCents = detected.priceCents,
                    firstSeenAt = now,
                    updatedAt = now
                )
                val productId = dao.insert(inserted)
                dao.insertHistory(detected.toHistory(productId, now))
                existing = dao.getAllOnce()
                changedCount++
            } else if (detected.priceCents < matched.priceCents) {
                val updated = matched.copy(
                    title = detected.title,
                    normalizedTitle = detected.normalizedTitle,
                    priceCents = detected.priceCents,
                    updatedAt = now
                )
                dao.update(updated)
                dao.insertHistory(detected.toHistory(matched.id, now))
                existing = existing.map { if (it.id == matched.id) updated else it }
                changedCount++
            }
        }

        return changedCount
    }

    suspend fun saveManualProduct(product: DetectedProduct): Int {
        val existing = dao.getAllOnce()
        val now = System.currentTimeMillis()
        val matched = matcher.findBestMatch(product.normalizedTitle, existing)

        if (matched == null) {
            val productId = dao.insert(
                ProductPrice(
                    title = product.title,
                    normalizedTitle = product.normalizedTitle,
                    priceCents = product.priceCents,
                    firstSeenAt = now,
                    updatedAt = now
                )
            )
            dao.insertHistory(product.toHistory(productId, now))
        } else {
            dao.update(
                matched.copy(
                    title = product.title,
                    normalizedTitle = product.normalizedTitle,
                    priceCents = product.priceCents,
                    updatedAt = now
                )
            )
            dao.insertHistory(product.toHistory(matched.id, now))
        }
        return 1
    }

    private fun DetectedProduct.toHistory(productId: Long, recordedAt: Long): ProductPriceHistory =
        ProductPriceHistory(
            productId = productId,
            title = title,
            priceCents = priceCents,
            recordedAt = recordedAt
        )
}
