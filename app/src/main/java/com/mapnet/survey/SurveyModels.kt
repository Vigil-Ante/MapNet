package com.mapnet.survey

import com.mapnet.data.AccessPointEntity
import com.mapnet.data.ObservationEntity
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

fun SecurityFilter.includes(ap: AccessPointEntity): Boolean = includes(ap.securityType)

fun SecurityFilter.includes(securityType: WifiSecurityType): Boolean = when (this) {
    SecurityFilter.ALL -> true
    SecurityFilter.OPEN -> securityType == WifiSecurityType.OPEN
    SecurityFilter.SECURED -> securityType != WifiSecurityType.OPEN
    SecurityFilter.WEP -> securityType == WifiSecurityType.WEP
    SecurityFilter.WPA -> securityType == WifiSecurityType.WPA
    SecurityFilter.WPA2 -> securityType == WifiSecurityType.WPA2 ||
        securityType == WifiSecurityType.WPA2_WPA3_TRANSITION
    SecurityFilter.WPA3 -> securityType == WifiSecurityType.WPA3 ||
        securityType == WifiSecurityType.WPA2_WPA3_TRANSITION
    SecurityFilter.ENTERPRISE -> securityType == WifiSecurityType.ENTERPRISE
    SecurityFilter.OWE -> securityType == WifiSecurityType.OWE
}

fun AccessPointEntity.matchesSearch(query: String): Boolean {
    val normalizedQuery = query.trim()
    return normalizedQuery.isBlank() ||
        ssid.contains(normalizedQuery, ignoreCase = true) ||
        bssid.contains(normalizedQuery, ignoreCase = true)
}

fun ObservationEntity.matchesSearch(query: String): Boolean {
    val normalizedQuery = query.trim()
    return normalizedQuery.isBlank() ||
        ssid.contains(normalizedQuery, ignoreCase = true) ||
        bssid.contains(normalizedQuery, ignoreCase = true)
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
