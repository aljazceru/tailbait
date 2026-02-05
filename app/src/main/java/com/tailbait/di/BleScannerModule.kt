package com.tailbait.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner
import javax.inject.Singleton

/**
 * Hilt dependency injection module for Nordic BLE components.
 *
 * This module provides application-level BLE dependencies:
 * - BleScanner: The main entry point for BLE scanning operations using Nordic's Kotlin BLE library
 *
 * The BleScanner is provided as a singleton to ensure consistent BLE state
 * management across the entire application lifecycle.
 *
 * Usage in classes:
 * ```kotlin
 * @Inject
 * constructor(private val bleScanner: BleScanner) { ... }
 * ```
 *
 * @see BleScanner
 */
@Module
@InstallIn(SingletonComponent::class)
object BleScannerModule {

    /**
     * Provides a singleton instance of BleScanner for BLE operations.
     *
     * The BleScanner is the main interface for:
     * - Scanning for BLE devices
     * - Managing BLE adapter state
     * - Handling BLE permissions
     *
     * This instance is shared across the entire application to maintain
     * consistent state and avoid resource conflicts.
     *
     * @param context The application context
     * @return A singleton BleScanner instance
     */
    @Provides
    @Singleton
    fun provideBleScanner(
        @ApplicationContext context: Context
    ): BleScanner {
        return BleScanner(context)
    }
}
