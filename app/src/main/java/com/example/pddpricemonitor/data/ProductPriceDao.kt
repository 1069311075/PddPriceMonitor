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

    @Query("SELECT MIN(priceCents) FROM product_price_history WHERE productId = :productId")
    suspend fun getLowestHistoryPrice(productId: Long): Long?

    @Query("SELECT * FROM product_prices")
    suspend fun getAllOnce(): List<ProductPrice>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ProductPrice): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(item: ProductPriceHistory): Long

    @Update
    suspend fun update(item: ProductPrice)

    @Query("DELETE FROM product_price_history WHERE productId = :productId")
    suspend fun deleteHistoryForProduct(productId: Long)

    @Query("DELETE FROM product_prices WHERE id = :productId")
    suspend fun deleteProductById(productId: Long)

    @Query("DELETE FROM product_price_history")
    suspend fun deleteAllHistory()

    @Query("DELETE FROM product_prices")
    suspend fun deleteAll()
}
