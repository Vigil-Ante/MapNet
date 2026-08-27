package com.mapnet.tools

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val activeNetwork = connectivityManager.currentWifiNetwork() ?: return null

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

    /**
     * Discovers devices only inside the active Wi-Fi network's private IPv4 subnet. A device is
     * listed when it responds to one ping, appears in the local ARP table, or is the configured
     * gateway. Devices that block these signals or sit behind client isolation cannot be found.
     */
    suspend fun discoverLocalDevices(
        onProgress: (NetworkMapProgress) -> Unit
    ): LocalNetworkMap = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.currentWifiNetwork()
            ?: error("Connect to Wi-Fi before mapping local devices.")
        val linkProperties = connectivityManager.getLinkProperties(network)
            ?: error("Wi-Fi network details are unavailable.")
        val linkAddress = linkProperties.linkAddresses.firstOrNull { link ->
            link.address is Inet4Address
        } ?: error("This Wi-Fi connection does not have an IPv4 address to map.")
        val subnet = Ipv4Subnet.from(
            address = linkAddress.address.address.toIpv4AddressValue(),
            prefixLength = linkAddress.prefixLength
        )
        require(subnet.hasPrivateAddress()) { "For safety, MapNet maps only private Wi-Fi IPv4 networks." }
        require(subnet.usableHostCount <= MAX_DISCOVERY_HOSTS) {
            "This Wi-Fi subnet has ${subnet.usableHostCount} addresses. MapNet maps up to $MAX_DISCOVERY_HOSTS addresses at a time."
        }

        val ownAddress = subnet.address.toIpv4Address()
        val gateway = linkProperties.routes
            .firstOrNull { route -> route.isDefaultRoute && route.gateway is Inet4Address }
            ?.gateway
            ?.address
            ?.toIpv4AddressValue()
            ?.takeIf(subnet::contains)
            ?.toIpv4Address()
        val arpBefore = arpNeighbors(subnet, linkProperties.interfaceName)
        val candidates = subnet.hostAddresses()
            .map(Long::toIpv4Address)
            .filterNot { address -> address == ownAddress }
            .toList()
        onProgress(NetworkMapProgress(0, candidates.size))

        val pingReplies = mutableSetOf<String>()
        var completed = 0
        coroutineScope {
            candidates.chunked(PING_BATCH_SIZE).forEach { batch ->
                val replies = batch.map { address ->
                    async { address.takeIf(::respondsToPing) }
                }.awaitAll()
                pingReplies += replies.filterNotNull()
                completed += batch.size
                onProgress(NetworkMapProgress(completed, candidates.size))
            }
        }

        val arpNeighbors = arpBefore + arpNeighbors(subnet, linkProperties.interfaceName)
        val addresses = buildSet {
            add(ownAddress)
            gateway?.let(::add)
            addAll(pingReplies)
            addAll(arpNeighbors.keys)
        }
        LocalNetworkMap(
            subnet = subnet.cidr,
            scannedAddressCount = candidates.size,
            devices = addresses
                .sortedBy { address -> address.toIpv4AddressValueOrNull() ?: Long.MAX_VALUE }
                .map { address ->
                    val details = buildList {
                        if (address == ownAddress) add("This Android device")
                        if (address == gateway) add("Default gateway")
                        if (address in pingReplies) add("Responded to ping")
                        if (address in arpNeighbors) add("Seen in local ARP table")
                    }
                    NetworkDevice(
                        ipv4Address = address,
                        macAddress = arpNeighbors[address],
                        discoveryDetail = details.joinToString(" • ")
                    )
                }
        )
    }

    private fun respondsToPing(address: String): Boolean =
        runCommand(listOf(pingBinary(), "-c", "1", "-W", "1", address), 2)
            .contains("bytes from", ignoreCase = true)

    private fun arpNeighbors(subnet: Ipv4Subnet, interfaceName: String?): Map<String, String> = runCatching {
        File("/proc/net/arp").useLines { lines ->
            lines.drop(1)
                .mapNotNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    val address = columns.getOrNull(0) ?: return@mapNotNull null
                    val macAddress = columns.getOrNull(3) ?: return@mapNotNull null
                    val device = columns.getOrNull(5) ?: return@mapNotNull null
                    val addressValue = address.toIpv4AddressValueOrNull() ?: return@mapNotNull null
                    if (
                        !subnet.contains(addressValue) ||
                        macAddress == "00:00:00:00:00:00" ||
                        (interfaceName != null && device != interfaceName)
                    ) {
                        return@mapNotNull null
                    }
                    address to macAddress.uppercase()
                }
                .toMap()
        }
    }.getOrDefault(emptyMap())

    private fun ConnectivityManager.currentWifiNetwork(): Network? {
        fun Network.isWifi(): Boolean = getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return boundNetworkForProcess?.takeIf { network -> network.isWifi() }
            ?: activeNetwork?.takeIf { network -> network.isWifi() }
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

    private companion object {
        const val MAX_DISCOVERY_HOSTS = 510
        const val PING_BATCH_SIZE = 16
    }
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
