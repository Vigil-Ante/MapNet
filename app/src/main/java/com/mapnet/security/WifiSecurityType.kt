package com.mapnet.security

/** A stable, human-readable classification derived from Android scan capabilities. */
enum class WifiSecurityType(val label: String) {
    OPEN("Open"),
    WEP("WEP"),
    WPA("WPA"),
    WPA2("WPA2"),
    WPA3("WPA3"),
    WPA2_WPA3_TRANSITION("WPA2/WPA3"),
    OWE("Enhanced Open / OWE"),
    ENTERPRISE("Enterprise"),
    UNKNOWN("Unknown")
}

data class SecurityProfile(
    val type: WifiSecurityType,
    val requiresPassword: Boolean,
    val isEncrypted: Boolean
)

