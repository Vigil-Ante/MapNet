package com.mapnet.connection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.MacAddress
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.mapnet.data.AccessPointEntity
import com.mapnet.security.WifiSecurityType

sealed interface WifiConnectionAction {
    data class LaunchAddNetworkConfirmation(val intent: Intent) : WifiConnectionAction
    data class ShowMessage(val message: String) : WifiConnectionAction
}

/** Builds an explicit Android network-add request for a surveyed AP. */
class WifiConnectionRequester(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun requestConnection(accessPoint: AccessPointEntity, passphrase: String): WifiConnectionAction {
        if (accessPoint.ssid == "<Hidden SSID>") {
            return WifiConnectionAction.ShowMessage(
                openWifiSettings("Hidden networks need to be selected in Android Wi-Fi settings.")
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return WifiConnectionAction.ShowMessage(
                openWifiSettings("Android 11 or newer is needed for the in-app confirmation screen. Choose this network in Wi-Fi settings.")
            )
        }
        if (!accessPoint.securityType.canConnectWithMapNet()) {
            return WifiConnectionAction.ShowMessage(
                openWifiSettings("This network type needs Android Wi-Fi settings for its full configuration.")
            )
        }
        if (accessPoint.securityType.needsPassphrase() && passphrase.isBlank()) {
            return WifiConnectionAction.ShowMessage("Enter the Wi-Fi password before connecting.")
        }

        return runCatching {
            WifiConnectionAction.LaunchAddNetworkConfirmation(buildAddNetworkIntent(accessPoint, passphrase))
        }.getOrElse { error ->
            WifiConnectionAction.ShowMessage(
                "Android could not prepare this network: ${error.message ?: "invalid network details"}"
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildAddNetworkIntent(accessPoint: AccessPointEntity, passphrase: String): Intent {
        val builder = WifiNetworkSuggestion.Builder().setSsid(accessPoint.ssid)
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

        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(
                Settings.EXTRA_WIFI_NETWORK_LIST,
                arrayListOf(builder.build())
            )
        }
    }

    private fun openWifiSettings(message: String): String {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return message
    }

    companion object {
        fun addNetworkResultMessage(resultCode: Int, data: Intent?): String {
            if (resultCode != Activity.RESULT_OK) {
                return "Android canceled the network addition."
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return "Android accepted the network addition."
            }
            return when (data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)?.firstOrNull()) {
                Settings.ADD_WIFI_RESULT_SUCCESS ->
                    "Network saved by Android. It can now connect normally."
                Settings.ADD_WIFI_RESULT_ALREADY_EXISTS ->
                    "This network is already saved by Android."
                Settings.ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED ->
                    "Android could not save this network. Check the password and try again."
                else -> "Android accepted the network addition."
            }
        }
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
