package com.example.pddpricemonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductPriceDao {
    @Query("SELECT * FROM product_prices ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProductPrice>>

    @Query("SELECT * FROM product_price_history WHERE productId = :productId ORDER BY recordedAt ASC")
    fun observeHistory(productId: Long): Flow<List<ProductPriceHistory>>

    @Query("SELECT * FROM product_price_history")
    suspend fun getAllHistoryOnce(): List<ProductPriceHistory>

    // 同步去重键：同一商品 + 同一时刻 + 同一设备的记录视为同一条
    @Query(
        "SELECT COUNT(*) FROM product_price_history " +
            "WHERE productId = :productId AND recordedAt = :recordedAt AND deviceId = :deviceId"
    )
    suspend fun countHistoryByKey(productId: Long, recordedAt: Long, deviceId: String): Int

    @Query("SELECT MIN(priceCents) FROM product_price_history WHERE productId = :productId")
    suspend fun getLowestHistoryPrice(productId: Long): Long?

    @Query("SELECT * FROM product_prices")
    suspend fun getAllOnce(): List<ProductPrice>

    @Query("SELECT * FROM product_prices WHERE id = :productId")
    suspend fun getProductById(productId: Long): ProductPrice?

    @Query("SELECT * FROM product_price_history WHERE id = :historyId")
    suspend fun getHistoryById(historyId: Long): ProductPriceHistory?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ProductPrice): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(item: ProductPriceHistory): Long

    @Update
    suspend fun update(item: ProductPrice)

    @Query("DELETE FROM product_price_history WHERE productId = :productId")
    suspend fun deleteHistoryForProduct(productId: Long)

    @Query("DELETE FROM product_price_history WHERE id = :historyId")
    suspend fun deleteHistoryById(historyId: Long)

    @Query("DELETE FROM product_prices WHERE id = :productId")
    suspend fun deleteProductById(productId: Long)

    @Query("DELETE FROM product_price_history")
    suspend fun deleteAllHistory()

    @Query("DELETE FROM product_prices")
    suspend fun deleteAll()

    // ---------- 删除墓碑 ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(t: SyncTombstone)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun getAllTombstones(): List<SyncTombstone>

    @Query("DELETE FROM sync_tombstones")
    suspend fun deleteAllTombstones()

    // history 墓碑定位：recordedAt + deviceId 全库唯一，跨设备稳定（本地自增 id 两边不同）
    @Query("SELECT productId FROM product_price_history WHERE recordedAt = :recordedAt AND deviceId = :deviceId")
    suspend fun findHistoryProductIdsBySyncKey(recordedAt: Long, deviceId: String): List<Long>

    @Query("SELECT id FROM product_price_history WHERE recordedAt = :recordedAt AND deviceId = :deviceId")
    suspend fun findHistoryIdsBySyncKey(recordedAt: Long, deviceId: String): List<Long>

    @Query("DELETE FROM product_price_history WHERE recordedAt = :recordedAt AND deviceId = :deviceId")
    suspend fun deleteHistoryBySyncKey(recordedAt: Long, deviceId: String)

    @Query("SELECT * FROM product_price_history WHERE productId = :productId")
    suspend fun getHistoryForProductOnce(productId: Long): List<ProductPriceHistory>

    @Query("SELECT id FROM product_price_history WHERE productId = :productId")
    suspend fun getHistoryIdsForProduct(productId: Long): List<Long>
}
