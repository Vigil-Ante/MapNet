package com.mapnet.security

/**
 * Normalizes Android's bracketed ScanResult.capabilities value. The raw value is
 * deliberately retained in the database for auditability and future parser changes.
 */
object WifiSecurityClassifier {
    fun classify(capabilities: String?): SecurityProfile {
        val raw = capabilities.orEmpty().uppercase()
        val tokens = Regex("\\[([^]]+)]").findAll(raw).map { it.groupValues[1] }.toSet()

        // OWE is passwordless but encrypted; test it before the OPEN fallback.
        if (raw.contains("OWE")) return SecurityProfile(WifiSecurityType.OWE, false, true)
        if (raw.contains("EAP") || raw.contains("IEEE8021X") || raw.contains("SUITE_B")) {
            return SecurityProfile(WifiSecurityType.ENTERPRISE, true, true)
        }
        if (raw.contains("WEP")) return SecurityProfile(WifiSecurityType.WEP, true, true)

        // WPA's legacy [WPA-PSK-…] format must not be promoted to WPA2.
        // RSN-PSK is Android's common WPA2 representation when WPA2 is omitted.
        val hasWpa2 = raw.contains("WPA2") || raw.contains("RSN-PSK")
        val hasWpa3 = raw.contains("WPA3") || raw.contains("SAE")
        if (hasWpa2 && hasWpa3) {
            return SecurityProfile(WifiSecurityType.WPA2_WPA3_TRANSITION, true, true)
        }
        if (hasWpa3) return SecurityProfile(WifiSecurityType.WPA3, true, true)
        if (hasWpa2) return SecurityProfile(WifiSecurityType.WPA2, true, true)
        if (raw.contains("WPA")) return SecurityProfile(WifiSecurityType.WPA, true, true)

        // Android reports ordinary open infrastructure networks as [ESS]. Empty
        // capabilities is also treated as open because no security suite was advertised.
        if (raw.isBlank() || "ESS" in tokens || "IBSS" in tokens) {
            return SecurityProfile(WifiSecurityType.OPEN, false, false)
        }
        return SecurityProfile(WifiSecurityType.UNKNOWN, false, false)
    }
}
