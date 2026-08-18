package com.mapnet.tools

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ConnectedWifiDetails(
    val ssid: String,
    val bssid: String,
    val ipv4Addresses: List<String>,
    val gateway: String?,
    val dnsServers: List<String>
)

/** Device-local network diagnostics for the Wi-Fi connection that is active now. */
class NetworkDiagnostics(private val context: Context) {
    @SuppressLint("MissingPermission", "DEPRECATION")
    fun currentWifiDetails(): ConnectedWifiDetails? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val wifiInfo = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        val rawSsid = wifiInfo.ssid.orEmpty().removeSurrounding("\"")
        val ssid = rawSsid.takeUnless { it.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true) } ?: "Unknown Wi-Fi"
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        return ConnectedWifiDetails(
            ssid = ssid,
            bssid = wifiInfo.bssid ?: "Unavailable",
            ipv4Addresses = linkProperties?.linkAddresses
                ?.mapNotNull { it.address.hostAddress?.takeIf { address -> address.contains('.') } }
                .orEmpty(),
            gateway = linkProperties?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress,
            dnsServers = linkProperties?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        )
    }

    suspend fun ping(destination: String): String = withContext(Dispatchers.IO) {
        val target = destination.checkedHost()
        val output = runCommand(listOf(pingBinary(), "-c", "4", "-W", "2", target), 12)
        "Ping $target\n\n$output"
    }

    suspend fun traceroute(destination: String): String = withContext(Dispatchers.IO) {
        val target = destination.checkedHost()
        buildString {
            appendLine("Traceroute to $target (up to 12 hops)")
            for (ttl in 1..12) {
                val output = runCommand(listOf(pingBinary(), "-c", "1", "-W", "1", "-t", ttl.toString(), target), 4)
                appendLine("$ttl  ${output.traceSummary()}")
                if (output.contains("bytes from", ignoreCase = true)) break
            }
        }.trimEnd()
    }

    private fun runCommand(command: List<String>, timeoutSeconds: Long): String = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching "Timed out after ${timeoutSeconds}s."
        }
        process.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().ifBlank { "No diagnostic output (exit ${process.exitValue()})." }.take(8_000)
        }
    }.getOrElse { error ->
        "Diagnostic command unavailable: ${error.message ?: "unknown error"}"
    }

    private fun pingBinary(): String = if (File("/system/bin/ping").canExecute()) "/system/bin/ping" else "ping"
}

private fun String.checkedHost(): String {
    val candidate = trim()
    require(candidate.isNotBlank()) { "Enter a destination first." }
    require(candidate.length <= 253) { "Destination is too long." }
    require(candidate.none { it.isWhitespace() }) { "Destination cannot contain spaces." }
    return candidate
}

private fun String.traceSummary(): String {
    val usefulLine = lineSequence().firstOrNull { line ->
        line.contains("bytes from", ignoreCase = true) ||
            line.startsWith("From ", ignoreCase = true) ||
            line.contains("unreachable", ignoreCase = true) ||
            line.contains("timed out", ignoreCase = true)
    }
    return usefulLine?.trim() ?: lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "No response"
}
