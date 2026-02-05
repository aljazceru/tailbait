package com.tailbait.di

import android.content.Context
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.SettingsRepository
import com.tailbait.service.NotificationHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {

    @Provides
    @Singleton
    fun provideNotificationHelper(
        @ApplicationContext context: Context,
        alertRepository: AlertRepository,
        settingsRepository: SettingsRepository
    ): NotificationHelper {
        return NotificationHelper(context, alertRepository, settingsRepository)
    }
}
