package com.tailbait.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.tailbait.data.repository.AlertRepository
import com.tailbait.data.repository.DeviceRepository
import com.tailbait.data.repository.WhitelistRepository
import com.tailbait.di.ReceiverEntryPoint
import com.tailbait.util.Constants
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

class AlertNotificationReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverEntryPoint::class.java
        )

        val alertRepository = entryPoint.alertRepository()
        val deviceRepository = entryPoint.deviceRepository()
        val whitelistRepository = entryPoint.whitelistRepository()
        val notificationHelper = entryPoint.notificationHelper()

        Timber.d("Received action: ${intent.action}")

        when (intent.action) {
            Constants.ACTION_DISMISS_ALERT -> {
                handleDismissAlert(context, intent, notificationHelper)
            }
            Constants.ACTION_ADD_TO_WHITELIST -> {
                handleAddToWhitelist(context, intent, deviceRepository, whitelistRepository)
            }
            else -> {
                Timber.w("Unknown action received: ${intent.action}")
            }
        }
    }

    private fun handleDismissAlert(
        context: Context,
        intent: Intent,
        notificationHelper: NotificationHelper
    ) {
        val alertId = intent.getLongExtra(Constants.EXTRA_ALERT_ID, -1L)

        if (alertId == -1L) {
            Timber.e("Invalid alert ID for dismiss action")
            return
        }

        Timber.d("Dismissing alert: $alertId")

        val pendingResult = goAsync()

        scope.launch {
            try {
                notificationHelper.dismissNotification(alertId)

                Timber.i("Alert $alertId dismissed successfully")

                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Alert dismissed",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Timber.e(e, "Error dismissing alert $alertId")

                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Error dismissing alert",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleAddToWhitelist(
        context: Context,
        intent: Intent,
        deviceRepository: DeviceRepository,
        whitelistRepository: WhitelistRepository
    ) {
        val alertId = intent.getLongExtra(Constants.EXTRA_ALERT_ID, -1L)
        val deviceAddressesJson = intent.getStringExtra(Constants.EXTRA_DEVICE_IDS)

        if (alertId == -1L || deviceAddressesJson.isNullOrEmpty()) {
            Timber.e("Invalid data for add to whitelist action")
            return
        }

        Timber.d("Adding devices from alert $alertId to whitelist")

        val pendingResult = goAsync()

        scope.launch {
            try {
                val deviceAddresses = parseDeviceAddresses(deviceAddressesJson)

                if (deviceAddresses.isEmpty()) {
                    Timber.w("No device addresses found in alert $alertId")
                    launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "No devices to whitelist",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                var addedCount = 0
                var alreadyWhitelistedCount = 0

                for (address in deviceAddresses) {
                    try {
                        val device = deviceRepository.getDeviceByAddress(address)

                        if (device != null) {
                            val isWhitelisted = whitelistRepository.isDeviceWhitelisted(device.id)

                            if (!isWhitelisted) {
                                whitelistRepository.addToWhitelist(
                                    deviceId = device.id,
                                    label = device.name ?: "Unknown Device",
                                    category = Constants.WHITELIST_CATEGORY_TRUSTED,
                                    addedViaLearnMode = false,
                                    notes = "Added from alert #$alertId"
                                )
                                addedCount++
                                Timber.d("Added device ${device.address} to whitelist")
                            } else {
                                alreadyWhitelistedCount++
                                Timber.d("Device ${device.address} already whitelisted")
                            }
                        } else {
                            Timber.w("Device with address $address not found in database")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error adding device $address to whitelist")
                    }
                }

                val message = when {
                    addedCount > 0 && alreadyWhitelistedCount > 0 -> {
                        "$addedCount device(s) added to whitelist, $alreadyWhitelistedCount already whitelisted"
                    }
                    addedCount > 0 -> {
                        "$addedCount device(s) added to whitelist"
                    }
                    alreadyWhitelistedCount > 0 -> {
                        "All devices already whitelisted"
                    }
                    else -> {
                        "No devices were added to whitelist"
                    }
                }

                launch(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }

                Timber.i(
                    "Whitelist operation completed: $addedCount added, " +
                        "$alreadyWhitelistedCount already whitelisted"
                )

            } catch (e: Exception) {
                Timber.e(e, "Error adding devices to whitelist from alert $alertId")

                launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Error adding to whitelist",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun parseDeviceAddresses(jsonString: String): List<String> {
        return try {
            val jsonArray = JSONArray(jsonString)
            val addresses = mutableListOf<String>()

            for (i in 0 until jsonArray.length()) {
                val address = jsonArray.getString(i)
                if (address.isNotBlank()) {
                    addresses.add(address)
                }
            }

            addresses
        } catch (e: Exception) {
            Timber.e(e, "Error parsing device addresses JSON: $jsonString")
            emptyList()
        }
    }
}
