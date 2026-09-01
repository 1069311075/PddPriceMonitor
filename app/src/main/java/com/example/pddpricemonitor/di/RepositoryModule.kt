package com.example.pddpricemonitor.di

import android.content.Context
import com.example.pddpricemonitor.data.ProductPriceDao
import com.example.pddpricemonitor.data.ProductRepository
import com.example.pddpricemonitor.data.ScreenshotStore
import com.example.pddpricemonitor.matcher.TitleMatcher
import com.example.pddpricemonitor.ocr.ProductTextParser
import com.example.pddpricemonitor.ocr.TextRecognizerClient
import com.example.pddpricemonitor.sync.DeviceIdentity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
        @ApplicationContext context: Context,
        dao: ProductPriceDao,
        matcher: TitleMatcher,
        screenshotStore: ScreenshotStore
    ): ProductRepository = ProductRepository(
        dao = dao,
        screenshotStore = screenshotStore,
        matcher = matcher,
        deviceId = DeviceIdentity.deviceId(context),
        deviceNameProvider = { DeviceIdentity.deviceName(context) }
    )

    @Provides
    @Singleton
    fun provideTextRecognizerClient(): TextRecognizerClient = TextRecognizerClient()

    @Provides
    @Singleton
    fun provideProductTextParser(): ProductTextParser = ProductTextParser()
}
