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

    @Query("SELECT MIN(priceCents) FROM product_price_history WHERE productId = :productId")
    suspend fun getLowestHistoryPrice(productId: Long): Long?

    // 今日新低商品：最新一条记录是今天产生、价格等于该商品历史最低、且严格低于上一条记录。
    // 全标准 SQL（相关子查询 + 聚合 JOIN），不依赖窗口函数；同毫秒多设备重复记录用
    // h2.recordedAt < h.recordedAt 严格取次新，DISTINCT 防 JOIN 放大
    @Query(
        """
        SELECT DISTINCT h.productId FROM product_price_history h
        INNER JOIN (
            SELECT productId, MAX(recordedAt) AS maxAt, MIN(priceCents) AS minPrice, COUNT(*) AS cnt
            FROM product_price_history GROUP BY productId
        ) agg ON agg.productId = h.productId
        WHERE h.recordedAt = agg.maxAt
          AND h.recordedAt >= :todayStart
          AND h.priceCents = agg.minPrice
          AND agg.cnt >= 2
          AND h.priceCents < (
              SELECT h2.priceCents FROM product_price_history h2
              WHERE h2.productId = h.productId AND h2.recordedAt < h.recordedAt
              ORDER BY h2.recordedAt DESC, h2.id DESC LIMIT 1
          )
        """
    )
    fun observeNewLowTodayIds(todayStart: Long): Flow<List<Long>>

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

    // 配合 insertTombstone(IGNORE) 实现墓碑 UPSERT：同键已存在时把删除时间推到较新值。
    // 同一商品「删除→重记→再删」时第二次删除必须盖掉旧 deletedAt，否则对端按旧时间
    // 判定「删除后又重新记价」放行，已删商品在对端复活
    @Query(
        "UPDATE sync_tombstones SET deletedAt = MAX(deletedAt, :deletedAt) " +
            "WHERE targetType = :targetType AND keyTitle = :keyTitle AND recordedAt = :recordedAt AND deviceId = :deviceId"
    )
    suspend fun bumpTombstoneDeletedAt(
        targetType: String,
        keyTitle: String,
        recordedAt: Long,
        deviceId: String,
        deletedAt: Long
    )

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
