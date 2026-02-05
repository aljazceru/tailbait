package com.tailbait.util

/**
 * Example usage of DeviceNameGenerator
 */
object DeviceNameGeneratorExample {

    fun demonstrate() {
        val examples = listOf(
            Triple("Tracker", "AA:BB:CC:DD:EE:FF", "4C00001A12345678"),
            Triple("Headphones", "11:22:33:44:55:66", "4C00002B87654321"),
            Triple("Watch", "33:44:55:66:77:88", "75001C1234ABCD"),
            Triple("Tracker", "55:66:77:88:99:AA", "0E0C0012345678"),
            Triple("Unknown", "77:88:99:AA:BB:CC", "4C00003DFFFFEE"),
            Triple(null, "99:AA:BB:CC:DD:EE", "75001E1234567890")
        )

        println("=== Device Name Generator Examples ===")
        examples.forEach { (deviceType, address, manufData) ->
            val shortName = DeviceNameGenerator.generateShortName(deviceType, address, manufData)
            println("Device: $deviceType | Address: $address")
            println("  Short Name: $shortName")
            println()
        }
    }
}
