package com.tailbait.util

import java.security.MessageDigest
import kotlin.random.Random

/**
 * Utility for generating short, identifiable device names for map display
 *
 * Creates names like:
 * - "Tracker•A3F9" (device type + 4-char identifier)
 * - "Headphones•B2C7"
 * - "Unknown•C8D5" (when device type unknown)
 */
object DeviceNameGenerator {

    /**
     * Generate a short, identifiable name for a device
     *
     * @param deviceType Type of device (e.g., "Tracker", "Headphones", "Watch")
     * @param deviceAddress Bluetooth MAC address of the device
     * @param manufacturerData Optional manufacturer data for additional uniqueness
     * @return Short name in format "DeviceType•XXXX" where XXXX is 4-char identifier
     */
    fun generateShortName(
        deviceType: String?,
        deviceAddress: String,
        manufacturerData: String? = null
    ): String {
        val baseName = deviceType ?: "Unknown"
        val identifier = generateIdentifier(deviceAddress, manufacturerData)
        return "$baseName•$identifier"
    }

    /**
     * Generate a 4-character identifier from device data
     * Uses a hash of the device address and manufacturer data
     */
    private fun generateIdentifier(deviceAddress: String, manufacturerData: String?): String {
        val input = deviceAddress + (manufacturerData ?: "")
        val hash = md5(input)

        // Take first 4 characters of hash and convert to readable format
        return hash.substring(0, 4).uppercase()
    }

    /**
     * MD5 hash function
     */
    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate a more random but still deterministic identifier
     * Alternative to hash-based approach
     */
    private fun generateRandomIdentifier(deviceAddress: String, manufacturerData: String?): String {
        val input = deviceAddress + (manufacturerData ?: "")
        val seed = input.fold(0L) { acc, char -> acc * 31 + char.code }
        val localRandom = Random(seed)

        // Generate 4-character readable identifier
        val chars = ('A'..'Z') + ('0'..'9')
        return (1..4).map { chars[localRandom.nextInt(chars.size)] }.joinToString("")
    }

    /**
     * Generate different types of identifiers for variety
     */
    enum class IdentifierStyle {
        HASH_BASED,
        RANDOM_BASED,
        SEQUENTIAL
    }

    /**
     * Generate short name with configurable identifier style
     */
    fun generateShortName(
        deviceType: String?,
        deviceAddress: String,
        manufacturerData: String? = null,
        style: IdentifierStyle = IdentifierStyle.HASH_BASED
    ): String {
        val baseName = deviceType ?: "Unknown"
        val identifier = when (style) {
            IdentifierStyle.HASH_BASED -> generateIdentifier(deviceAddress, manufacturerData)
            IdentifierStyle.RANDOM_BASED -> generateRandomIdentifier(deviceAddress, manufacturerData)
            IdentifierStyle.SEQUENTIAL -> generateSequentialIdentifier(deviceAddress, manufacturerData)
        }
        return "$baseName•$identifier"
    }

    /**
     * Generate sequential identifier based on hash
     */
    private fun generateSequentialIdentifier(deviceAddress: String, manufacturerData: String?): String {
        val input = deviceAddress + (manufacturerData ?: "")
        val hash = md5(input)
        val num = hash.substring(0, 8).toInt(16)
        return String.format("%04d", num % 10000).substring(0, 4)
    }
}
