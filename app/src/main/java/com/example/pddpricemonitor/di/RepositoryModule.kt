package com.example.pddpricemonitor.di

import com.example.pddpricemonitor.data.ProductPriceDao
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.matcher.TitleMatcher
import com.example.pddpricemonitor.ocr.ProductTextParser
import com.example.pddpricemonitor.ocr.TextRecognizerClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideTitleMatcher(): TitleMatcher = TitleMatcher()

    @Provides
    @Singleton
    fun provideProductRepository(
        dao: ProductPriceDao,
        matcher: TitleMatcher
    ): ProductRepository = ProductRepository(dao, matcher)

    @Provides
    @Singleton
    fun provideTextRecognizerClient(): TextRecognizerClient = TextRecognizerClient()

    @Provides
    @Singleton
    fun provideProductTextParser(): ProductTextParser = ProductTextParser()
}
