package com.mapnet.connection

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.mapnet.data.AccessPointEntity
import com.mapnet.security.WifiSecurityType

/**
 * Submits a single Wi-Fi suggestion for a surveyed AP. Android owns the actual
 * connection decision and displays its approval UI; credentials are never persisted by MapNet.
 */
class WifiConnectionRequester(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun requestConnection(accessPoint: AccessPointEntity, passphrase: String): String {
        if (accessPoint.ssid == "<Hidden SSID>") {
            return openWifiSettings("Hidden networks need to be selected in Android Wi-Fi settings.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return openWifiSettings("Android 10 or newer is needed for in-app connection requests.")
        }
        if (!accessPoint.securityType.canConnectWithMapNet()) {
            return openWifiSettings("This network type needs Android Wi-Fi settings for its full configuration.")
        }
        if (accessPoint.securityType.needsPassphrase() && passphrase.isBlank()) {
            return "Enter the Wi-Fi password before connecting."
        }

        return runCatching {
            submitSuggestion(accessPoint, passphrase)
        }.getOrElse { error ->
            "Android could not request this connection: ${error.message ?: "invalid network details"}"
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun submitSuggestion(accessPoint: AccessPointEntity, passphrase: String): String {
        val builder = WifiNetworkSuggestion.Builder()
            .setSsid(accessPoint.ssid)
            .setIsAppInteractionRequired(true)
        runCatching { MacAddress.fromString(accessPoint.bssid) }
            .getOrNull()
            ?.let(builder::setBssid)

        when (accessPoint.securityType) {
            WifiSecurityType.WPA,
            WifiSecurityType.WPA2,
            WifiSecurityType.WPA2_WPA3_TRANSITION -> builder.setWpa2Passphrase(passphrase)
            WifiSecurityType.WPA3 -> builder.setWpa3Passphrase(passphrase)
            WifiSecurityType.OPEN -> Unit
            else -> error("Unsupported security type")
        }

        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return when (wifiManager.addNetworkSuggestions(listOf(builder.build()))) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                "Connection request sent for ${accessPoint.ssid}. Approve Android's Wi-Fi prompt to connect."
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                "MapNet has already suggested ${accessPoint.ssid}. Check Android's Wi-Fi prompt or settings."
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                "Android has disabled MapNet Wi-Fi control. Re-enable it in Special app access."
            else -> "Android did not accept the connection request for ${accessPoint.ssid}."
        }
    }

    private fun openWifiSettings(message: String): String {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return message
    }
}

fun WifiSecurityType.canConnectWithMapNet(): Boolean = this in setOf(
    WifiSecurityType.OPEN,
    WifiSecurityType.WPA,
    WifiSecurityType.WPA2,
    WifiSecurityType.WPA3,
    WifiSecurityType.WPA2_WPA3_TRANSITION
)

fun WifiSecurityType.needsPassphrase(): Boolean = this != WifiSecurityType.OPEN
