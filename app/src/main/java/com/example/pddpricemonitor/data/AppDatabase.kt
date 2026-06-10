package com.example.pddpricemonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProductPrice::class, ProductPriceHistory::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productPriceDao(): ProductPriceDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `product_price_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `priceCents` INTEGER NOT NULL,
                        `recordedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`productId`) REFERENCES `product_prices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_product_price_history_productId` ON `product_price_history` (`productId`)")
                db.execSQL(
                    """
                    INSERT INTO `product_price_history` (`productId`, `title`, `priceCents`, `recordedAt`)
                    SELECT `id`, `title`, `priceCents`, `updatedAt`
                    FROM `product_prices`
                    """.trimIndent()
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pdd_price_monitor.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
