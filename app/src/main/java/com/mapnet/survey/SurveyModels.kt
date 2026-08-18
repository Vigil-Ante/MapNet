package com.mapnet.survey

import com.mapnet.data.AccessPointEntity
import com.mapnet.security.WifiSecurityType

enum class SecurityFilter(val label: String) {
    ALL("All"),
    OPEN("Open"),
    SECURED("Secured"),
    WEP("WEP"),
    WPA("WPA"),
    WPA2("WPA2"),
    WPA3("WPA3"),
    ENTERPRISE("Enterprise"),
    OWE("OWE")
}

fun SecurityFilter.includes(ap: AccessPointEntity): Boolean = when (this) {
    SecurityFilter.ALL -> true
    SecurityFilter.OPEN -> ap.securityType == WifiSecurityType.OPEN
    SecurityFilter.SECURED -> ap.securityType != WifiSecurityType.OPEN
    SecurityFilter.WEP -> ap.securityType == WifiSecurityType.WEP
    SecurityFilter.WPA -> ap.securityType == WifiSecurityType.WPA
    SecurityFilter.WPA2 -> ap.securityType == WifiSecurityType.WPA2 ||
        ap.securityType == WifiSecurityType.WPA2_WPA3_TRANSITION
    SecurityFilter.WPA3 -> ap.securityType == WifiSecurityType.WPA3 ||
        ap.securityType == WifiSecurityType.WPA2_WPA3_TRANSITION
    SecurityFilter.ENTERPRISE -> ap.securityType == WifiSecurityType.ENTERPRISE
    SecurityFilter.OWE -> ap.securityType == WifiSecurityType.OWE
}

data class SecuritySummary(val total: Int, val open: Int) {
    val secured: Int get() = total - open
}

/**
 * A user-facing survey is organized by Wi-Fi name, not radio BSSID. The raw
 * BSSID rows remain in Room so a later scan can still show observation history.
 */
fun List<AccessPointEntity>.collapseByNetworkName(): List<AccessPointEntity> =
    groupBy { accessPoint ->
        val normalizedName = accessPoint.ssid.trim()
        if (normalizedName.equals("<Hidden SSID>", ignoreCase = true) || normalizedName.isBlank()) {
            // Hidden SSIDs have no stable name to merge on; retain each radio separately.
            "hidden:${accessPoint.bssid}"
        } else {
            "ssid:$normalizedName"
        }
    }
        .values
        .map { matchingAccessPoints ->
            matchingAccessPoints.maxWithOrNull(
                compareBy<AccessPointEntity> { it.lastSeenEpochMs }
                    .thenBy { it.signalDbm }
            )!!
        }
        .sortedByDescending { it.lastSeenEpochMs }

fun List<AccessPointEntity>.securitySummary() = SecuritySummary(
    total = size,
    open = count { it.securityType == WifiSecurityType.OPEN }
)
