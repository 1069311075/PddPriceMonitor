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

    @Query("SELECT * FROM product_prices")
    suspend fun getAllOnce(): List<ProductPrice>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ProductPrice): Long

    @Update
    suspend fun update(item: ProductPrice)

    @Query("DELETE FROM product_prices")
    suspend fun deleteAll()
}
