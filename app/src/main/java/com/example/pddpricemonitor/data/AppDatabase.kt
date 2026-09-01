package com.example.pddpricemonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProductPrice::class, ProductPriceHistory::class, SyncTombstone::class],
    version = 7,
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

        // v3：历史记录加设备来源列，存量数据归为 "local"（本机）
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `product_price_history` ADD COLUMN `deviceId` TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE `product_price_history` ADD COLUMN `deviceName` TEXT NOT NULL DEFAULT '本机'")
            }
        }

        // v4：删除墓碑表——删除操作跨设备传播，防止已删数据在同步时复活
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_tombstones` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `keyTitle` TEXT NOT NULL,
                        `recordedAt` INTEGER NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `deletedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_tombstones_targetType_keyTitle_recordedAt_deviceId` " +
                        "ON `sync_tombstones` (`targetType`, `keyTitle`, `recordedAt`, `deviceId`)"
                )
            }
        }

        // v5：修复同步数据翻倍。v2→v3 迁移把存量数据标为 'local'，但导出同步时会改写成真实设备ID，
        // 对端回传后与本机 'local' 行判重失败 → 同一条记录插入两次（主机侧 4 条变 8 条）。
        // 归一化存量 'local' 行为真实设备ID，并按 (productId, recordedAt, deviceId) 去重清除已产生的重复行
        private fun migration4to5(context: Context) = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val realId = com.example.pddpricemonitor.sync.DeviceIdentity.deviceId(context)
                val realName = com.example.pddpricemonitor.sync.DeviceIdentity.deviceName(context)
                db.execSQL(
                    "UPDATE `product_price_history` SET `deviceId` = ?, `deviceName` = ? WHERE `deviceId` = 'local'",
                    arrayOf(realId, realName)
                )
                db.execSQL(
                    """
                    DELETE FROM `product_price_history`
                    WHERE `id` NOT IN (
                        SELECT MIN(`id`) FROM `product_price_history`
                        GROUP BY `productId`, `recordedAt`, `deviceId`
                    )
                    """.trimIndent()
                )
            }
        }

        // v6：商品表加 OCR 签名列。存量行回填 ocrTitle = title（多数用户从未编辑，
        // 标题即 OCR 原文；编辑过的存量商品该值不准，但双路匹配中路1仍有效，无害）
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `product_prices` ADD COLUMN `ocrTitle` TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE `product_prices` SET `ocrTitle` = `title`")
            }
        }

        // v7：历史表加自动保存标记列。存量行全部为人工确认记录，回填 false
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `product_price_history` ADD COLUMN `autoSaved` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pdd_price_monitor.db"
            )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    migration4to5(context), MIGRATION_5_6, MIGRATION_6_7
                )
                .build()
    }
}
