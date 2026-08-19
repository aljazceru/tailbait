package com.tailbait.data.repository

import com.tailbait.data.database.dao.AlertHistoryDao
import com.tailbait.data.database.dao.CompanionDeviceDao
import com.tailbait.data.database.entities.AlertHistory
import com.tailbait.data.database.entities.CompanionDevice
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registry for paired companion devices (tailbait-companion ESP32s),
 * plus alert persistence for companion-side (sentinel) detections.
 */
interface CompanionDeviceRepository {
    fun getAll(): Flow<List<CompanionDevice>>

    fun getFirst(): Flow<CompanionDevice?>

    suspend fun getByAddress(address: String): CompanionDevice?

    suspend fun pair(
        address: String,
        name: String?,
    ): CompanionDevice

    suspend fun forget(address: String)

    suspend fun setEnabled(
        address: String,
        enabled: Boolean,
    )

    suspend fun updateLinkStats(
        address: String,
        records: Long,
        firmware: String?,
        mode: String?,
        stats: String?,
    )

    suspend fun markConnected(address: String)

    suspend fun insertAlert(alert: AlertHistory)
}

@Singleton
class CompanionDeviceRepositoryImpl
    @Inject
    constructor(
        private val dao: CompanionDeviceDao,
        private val alertDao: AlertHistoryDao,
    ) : CompanionDeviceRepository {
        override fun getAll(): Flow<List<CompanionDevice>> = dao.getAll()

        override fun getFirst(): Flow<CompanionDevice?> = dao.getFirst()

        override suspend fun getByAddress(address: String): CompanionDevice? = dao.getByAddress(address)

        override suspend fun pair(
            address: String,
            name: String?,
        ): CompanionDevice {
            val existing = dao.getByAddress(address)
            val device =
                existing ?: CompanionDevice(address = address, name = name)
                    .let { it.copy(id = dao.insert(it)) }
            if (name != null && existing?.name != name) {
                dao.update(device.copy(name = name))
            }
            return device
        }

        override suspend fun forget(address: String) = dao.deleteByAddress(address)

        override suspend fun setEnabled(
            address: String,
            enabled: Boolean,
        ) = dao.updateEnabled(address, enabled)

        override suspend fun updateLinkStats(
            address: String,
            records: Long,
            firmware: String?,
            mode: String?,
            stats: String?,
        ) = dao.updateStats(address, records, firmware, mode, stats)

        override suspend fun markConnected(address: String) = dao.updateLastConnected(address, System.currentTimeMillis())

        override suspend fun insertAlert(alert: AlertHistory) {
            alertDao.insert(alert)
        }
    }
