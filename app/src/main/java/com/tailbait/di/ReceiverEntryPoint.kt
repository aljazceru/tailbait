package com.tailbait.di

import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.service.NotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun alertRepository(): AlertRepository
    fun deviceRepository(): DeviceRepository
    fun whitelistRepository(): WhitelistRepository
    fun notificationHelper(): NotificationHelper
}
