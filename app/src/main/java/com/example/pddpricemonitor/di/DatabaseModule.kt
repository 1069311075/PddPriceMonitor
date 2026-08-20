package com.example.pddpricemonitor.di

import android.content.Context
import com.example.pddpricemonitor.data.AppDatabase
import com.example.pddpricemonitor.data.ProductPriceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.create(context)

    @Provides
    fun provideProductPriceDao(database: AppDatabase): ProductPriceDao =
        database.productPriceDao()
}
