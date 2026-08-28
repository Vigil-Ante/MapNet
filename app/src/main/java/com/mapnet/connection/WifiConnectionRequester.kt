package com.mapnet.connection

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.mapnet.data.AccessPointEntity
import com.mapnet.security.WifiSecurityType

sealed interface WifiConnectionAction {
    data class RequestNetworkConnection(
        val accessPoint: AccessPointEntity,
        val passphrase: String
    ) : WifiConnectionAction
    data class ShowMessage(val message: String) : WifiConnectionAction
}

/**
 * Builds Android-approved requests for a surveyed access point.
 *
 * A Wi-Fi Network Request prompts the user and connects MapNet to the selected network without
 * saving it as a device-wide network. Android reserves permanent, device-wide Wi-Fi changes for
 * the system Wi-Fi UI.
 */
class WifiConnectionRequester(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun requestConnection(accessPoint: AccessPointEntity, passphrase: String): WifiConnectionAction {
        if (accessPoint.ssid == "<Hidden SSID>") {
            return WifiConnectionAction.ShowMessage(
                openWifiSettings("Hidden networks need to be selected in Android Wi-Fi settings.")
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return WifiConnectionAction.ShowMessage(
                openWifiSettings("Android 10 or newer is needed for the in-app connection prompt. Choose this network in Wi-Fi settings.")
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

        return WifiConnectionAction.RequestNetworkConnection(accessPoint, passphrase)
    }

    /**
     * Requests Android's connection prompt. The connection remains requested only while MapNet
     * is alive and is not added to Android's saved-network list.
     */
    @SuppressLint("MissingPermission")
    fun requestNetworkConnection(
        accessPoint: AccessPointEntity,
        passphrase: String,
        onStatus: (String) -> Unit
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return openWifiSettings("Choose this network in Android Wi-Fi settings.")
        }
        if (!accessPoint.securityType.canConnectWithMapNet()) {
            return openWifiSettings("This network type needs Android Wi-Fi settings for its full configuration.")
        }

        return runCatching {
            cancelRequestedConnection()
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(buildNetworkSpecifier(accessPoint, passphrase))
                .build()
            val callback = connectionCallback(
                connectivityManager = connectivityManager,
                accessPoint = accessPoint,
                onStatus = onStatus
            )
            activeNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback, CONNECTION_REQUEST_TIMEOUT_MS)
            "Approve Android's connection prompt for ${accessPoint.ssid}."
        }.getOrElse { error ->
            "Android could not request this connection: ${error.message ?: "unknown error"}"
        }
    }

    fun cancelRequestedConnection() {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        activeNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            activeNetworkCallback = null
        }
        if (boundNetwork != null) {
            connectivityManager.bindProcessToNetwork(null)
            boundNetwork = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildNetworkSpecifier(accessPoint: AccessPointEntity, passphrase: String): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder().setSsid(accessPoint.ssid)
        accessPoint.bssidAsMacAddress()?.let(builder::setBssid)

        when (accessPoint.securityType) {
            WifiSecurityType.WPA,
            WifiSecurityType.WPA2,
            WifiSecurityType.WPA2_WPA3_TRANSITION -> builder.setWpa2Passphrase(passphrase)
            WifiSecurityType.WPA3 -> builder.setWpa3Passphrase(passphrase)
            WifiSecurityType.OPEN -> Unit
            else -> error("Unsupported security type")
        }

        return builder.build()
    }

    private fun connectionCallback(
        connectivityManager: ConnectivityManager,
        accessPoint: AccessPointEntity,
        onStatus: (String) -> Unit
    ): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (activeNetworkCallback !== this) return
            boundNetwork = network
            connectivityManager.bindProcessToNetwork(network)
            postStatus(onStatus, "Connected to ${accessPoint.ssid} for MapNet.")
        }

        override fun onUnavailable() {
            if (activeNetworkCallback !== this) return
            activeNetworkCallback = null
            postStatus(onStatus, "Android could not connect to ${accessPoint.ssid}. Check its range and password.")
        }

        override fun onLost(network: Network) {
            if (boundNetwork != network) return
            connectivityManager.bindProcessToNetwork(null)
            boundNetwork = null
            postStatus(onStatus, "Connection to ${accessPoint.ssid} ended.")
        }
    }

    private fun postStatus(onStatus: (String) -> Unit, message: String) {
        mainHandler.post { onStatus(message) }
    }

    private fun openWifiSettings(message: String): String {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return message
    }

    private companion object {
        const val CONNECTION_REQUEST_TIMEOUT_MS = 20_000
    }

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private val mainHandler = Handler(Looper.getMainLooper())
}

@RequiresApi(Build.VERSION_CODES.P)
private fun AccessPointEntity.bssidAsMacAddress(): MacAddress? =
    runCatching { MacAddress.fromString(bssid) }.getOrNull()

fun WifiSecurityType.canConnectWithMapNet(): Boolean = this in setOf(
    WifiSecurityType.OPEN,
    WifiSecurityType.WPA,
    WifiSecurityType.WPA2,
    WifiSecurityType.WPA3,
    WifiSecurityType.WPA2_WPA3_TRANSITION
)

fun WifiSecurityType.needsPassphrase(): Boolean = this != WifiSecurityType.OPEN
