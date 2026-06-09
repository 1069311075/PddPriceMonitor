package com.example.pddpricemonitor.data

import com.example.pddpricemonitor.matcher.TitleMatcher
import com.example.pddpricemonitor.ocr.DetectedProduct
import kotlinx.coroutines.flow.Flow

class ProductRepository(
    private val dao: ProductPriceDao,
    private val matcher: TitleMatcher = TitleMatcher()
) {
    fun observeAll(): Flow<List<ProductPrice>> = dao.observeAll()

    suspend fun clearAll() {
        dao.deleteAll()
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
                dao.insert(inserted)
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
            dao.insert(
                ProductPrice(
                    title = product.title,
                    normalizedTitle = product.normalizedTitle,
                    priceCents = product.priceCents,
                    firstSeenAt = now,
                    updatedAt = now
                )
            )
        } else {
            dao.update(
                matched.copy(
                    title = product.title,
                    normalizedTitle = product.normalizedTitle,
                    priceCents = product.priceCents,
                    updatedAt = now
                )
            )
        }
        return 1
    }
}
