package com.tailbait.util

/** Only complete protocol identity keys can establish a physical-device link. */
object DeviceFingerprinter {
    fun isHighConfidenceFingerprint(fingerprint: String?): Boolean {
        if (fingerprint == null) return false
        val keyStart =
            when {
                fingerprint.startsWith("FM:") && fingerprint.length == 47 -> 3
                fingerprint.startsWith("FHN:") && (fingerprint.length == 44 || fingerprint.length == 68) -> 4
                else -> return false
            }
        for (index in keyStart until fingerprint.length) {
            val digit = fingerprint[index]
            if (digit !in '0'..'9' && digit !in 'A'..'F') return false
        }
        return true
    }
}
