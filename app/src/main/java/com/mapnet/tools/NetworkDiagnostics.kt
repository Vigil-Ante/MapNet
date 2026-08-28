package com.mapnet.tools

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ConnectedWifiDetails(
    val ssid: String,
    val bssid: String,
    val ipv4Addresses: List<String>,
    val subnet: String?,
    val gateway: String?,
    val dnsServers: List<String>
)

/** Device-local network diagnostics for the Wi-Fi connection that is active now. */
class NetworkDiagnostics(private val context: Context) {
    private val vendorLookup = MacVendorLookup(context)

    @SuppressLint("MissingPermission", "DEPRECATION")
    fun currentWifiDetails(): ConnectedWifiDetails? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.currentWifiNetwork() ?: return null
        val wifiInfo = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        val rawSsid = wifiInfo.ssid.orEmpty().removeSurrounding("\"")
        val ssid = rawSsid.takeUnless { it.equals("<unknown ssid>", ignoreCase = true) }
            ?: "Unknown Wi-Fi"
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        val ipv4Links = linkProperties?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
        val subnet = ipv4Links.firstOrNull()?.let { link ->
            runCatching {
                Ipv4Subnet.from(link.address.address.toIpv4AddressValue(), link.prefixLength).cidr
            }.getOrNull()
        }
        return ConnectedWifiDetails(
            ssid = ssid,
            bssid = wifiInfo.bssid ?: "Unavailable",
            ipv4Addresses = ipv4Links.mapNotNull { it.address.hostAddress },
            subnet = subnet,
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
                ensureActive()
                val output = runCommand(
                    listOf(pingBinary(), "-c", "1", "-W", "1", "-t", ttl.toString(), target),
                    4
                )
                appendLine("$ttl  ${output.traceSummary()}")
                if (output.contains("bytes from", ignoreCase = true)) break
            }
        }.trimEnd()
    }

    /**
     * Runs a small, on-demand chain of probes. It only uses the active Wi-Fi
     * network and does not save or upload the result.
     */
    suspend fun solveProblem(
        selectedDevice: KnownLanDevice? = null,
        onProgress: (String) -> Unit = {}
    ): ProblemSolverResult = withContext(Dispatchers.IO) {
        val checks = mutableListOf<ProblemSolverCheck>()
        onProgress("Checking Wi-Fi connection…")
        val connection = currentWifiDetails()
        if (connection == null) {
            checks += ProblemSolverCheck(
                ProblemCheckId.WIFI,
                "Wi-Fi connection",
                ProblemCheckStatus.FAILED,
                "This phone is not connected to an active Wi-Fi network."
            )
            return@withContext ProblemSolverResult(
                selectedDeviceName = selectedDevice?.displayName,
                checks = checks,
                diagnosis = classifyProblem(checks)
            )
        }
        checks += ProblemSolverCheck(
            ProblemCheckId.WIFI,
            "Wi-Fi connection",
            ProblemCheckStatus.PASSED,
            "Connected to ${connection.ssid}${connection.subnet?.let { " ($it)" }.orEmpty()}."
        )

        val gateway = connection.gateway
        onProgress("Checking router…")
        checks += if (gateway == null) {
            ProblemSolverCheck(
                ProblemCheckId.GATEWAY,
                "Router reachability",
                ProblemCheckStatus.UNKNOWN,
                "Android did not provide a default gateway for this Wi-Fi connection."
            )
        } else if (respondsToPing(gateway)) {
            ProblemSolverCheck(ProblemCheckId.GATEWAY, "Router reachability", ProblemCheckStatus.PASSED, "$gateway responded to a local ping.")
        } else {
            ProblemSolverCheck(ProblemCheckId.GATEWAY, "Router reachability", ProblemCheckStatus.FAILED, "$gateway did not respond to a local ping.")
        }

        onProgress("Checking DNS…")
        val resolvedAddresses = runCatching {
            InetAddress.getAllByName(PROBLEM_SOLVER_HOST).mapNotNull(InetAddress::getHostAddress)
        }.getOrElse { emptyList() }
        checks += if (resolvedAddresses.isEmpty()) {
            ProblemSolverCheck(ProblemCheckId.DNS, "DNS lookup", ProblemCheckStatus.FAILED, "Could not resolve $PROBLEM_SOLVER_HOST using this network's DNS.")
        } else {
            ProblemSolverCheck(ProblemCheckId.DNS, "DNS lookup", ProblemCheckStatus.PASSED, "$PROBLEM_SOLVER_HOST resolved to ${resolvedAddresses.first()}.")
        }

        onProgress("Checking internet access…")
        checks += if (resolvedAddresses.isEmpty()) {
            ProblemSolverCheck(ProblemCheckId.INTERNET, "Internet access", ProblemCheckStatus.SKIPPED, "Skipped because DNS lookup did not succeed.")
        } else {
            val responseCode = runCatching { connectivityProbeResponseCode() }.getOrNull()
            when {
                responseCode == 204 -> ProblemSolverCheck(ProblemCheckId.INTERNET, "Internet access", ProblemCheckStatus.PASSED, "The minimal HTTPS connectivity probe returned HTTP 204.")
                responseCode != null -> ProblemSolverCheck(ProblemCheckId.INTERNET, "Internet access", ProblemCheckStatus.FAILED, "The HTTPS connectivity probe returned HTTP $responseCode instead of 204.")
                else -> ProblemSolverCheck(ProblemCheckId.INTERNET, "Internet access", ProblemCheckStatus.FAILED, "The HTTPS connectivity probe could not be reached.")
            }
        }

        if (selectedDevice != null) {
            onProgress("Checking ${selectedDevice.displayName}…")
            checks += if (respondsToPing(selectedDevice.ipAddress)) {
                ProblemSolverCheck(ProblemCheckId.DEVICE, "Selected device", ProblemCheckStatus.PASSED, "${selectedDevice.displayName} (${selectedDevice.ipAddress}) responded to a local ping.")
            } else {
                ProblemSolverCheck(ProblemCheckId.DEVICE, "Selected device", ProblemCheckStatus.FAILED, "${selectedDevice.displayName} (${selectedDevice.ipAddress}) did not respond to a local ping.")
            }
        } else {
            checks += ProblemSolverCheck(ProblemCheckId.DEVICE, "Selected device", ProblemCheckStatus.SKIPPED, "No device was selected for this diagnosis.")
        }
        ProblemSolverResult(
            selectedDeviceName = selectedDevice?.displayName,
            checks = checks,
            diagnosis = classifyProblem(checks)
        )
    }

    /** Discovers and identifies devices only on the active private Wi-Fi IPv4 subnet. */
    suspend fun discoverLocalDevices(
        onProgress: (NetworkMapProgress) -> Unit
    ): LocalNetworkMap = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.currentWifiNetwork()
            ?: error("Connect to Wi-Fi before mapping local devices.")
        val linkProperties = connectivityManager.getLinkProperties(network)
            ?: error("Wi-Fi network details are unavailable.")
        val linkAddress = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address }
            ?: error("This Wi-Fi connection does not have an IPv4 address to map.")
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
            .filterNot { it == ownAddress }
            .toList()
        onProgress(NetworkMapProgress(0, candidates.size))

        val pingReplies = mutableSetOf<String>()
        var completed = 0
        coroutineScope {
            candidates.chunked(PING_BATCH_SIZE).forEach { batch ->
                ensureActive()
                val replies = batch.map { address -> async { address.takeIf(::respondsToPing) } }.awaitAll()
                pingReplies += replies.filterNotNull()
                completed += batch.size
                onProgress(NetworkMapProgress(completed, candidates.size))
            }
        }

        val neighbors = arpBefore + arpNeighbors(subnet, linkProperties.interfaceName)
        val baseAddresses = buildSet {
            add(ownAddress)
            gateway?.let(::add)
            addAll(pingReplies)
            addAll(neighbors.keys)
        }
        onProgress(NetworkMapProgress(candidates.size, candidates.size, "Identifying local services"))
        val advertised = coroutineScope {
            val ssdp = async { discoverSsdpDevices(network, subnet) }
            val mdns = async { discoverMdnsDevices(subnet) }
            mergeAdvertisedMaps(ssdp.await(), mdns.await())
        }
        val addresses = (baseAddresses + advertised.keys)
            .filter { address -> address.toIpv4AddressValueOrNull()?.let(subnet::contains) == true }
            .sortedBy { it.toIpv4AddressValueOrNull() ?: Long.MAX_VALUE }

        val hostIdentities = coroutineScope {
            addresses.map { address ->
                async {
                    val reverseName = reverseDnsName(address)
                    val netbios = if (reverseName == null && address != ownAddress) {
                        netbiosName(network, address)
                    } else null
                    address to HostIdentity(reverseName ?: netbios, netbios != null)
                }
            }.awaitAll().toMap()
        }
        onProgress(NetworkMapProgress(candidates.size, candidates.size, "Saving device details"))

        LocalNetworkMap(
            subnet = subnet.cidr,
            scannedAddressCount = candidates.size,
            devices = addresses.map { address ->
                val serviceIdentity = advertised[address]
                val hostIdentity = hostIdentities[address]
                val mac = neighbors[address]
                val ouiVendor = vendorLookup.lookup(mac)
                val vendor = serviceIdentity?.vendor ?: ouiVendor
                val services = serviceIdentity?.services.orEmpty()
                val sources = buildSet {
                    if (address == ownAddress) add(DiscoverySource.THIS_DEVICE)
                    if (address == gateway) add(DiscoverySource.GATEWAY)
                    if (address in pingReplies) add(DiscoverySource.PING)
                    if (address in neighbors) add(DiscoverySource.ARP)
                    if (hostIdentity?.name != null) {
                        add(if (hostIdentity.fromNetbios) DiscoverySource.NETBIOS else DiscoverySource.REVERSE_DNS)
                    }
                    addAll(serviceIdentity?.sources.orEmpty())
                    if (ouiVendor != null) add(DiscoverySource.MAC_VENDOR)
                }
                DiscoveredNetworkDevice(
                    ipv4Address = address,
                    macAddress = mac,
                    advertisedName = serviceIdentity?.friendlyName,
                    hostname = hostIdentity?.name ?: serviceIdentity?.hostname,
                    vendor = vendor,
                    model = serviceIdentity?.model,
                    inferredType = inferDeviceType(
                        isGateway = address == gateway,
                        name = serviceIdentity?.friendlyName ?: hostIdentity?.name,
                        vendor = vendor,
                        model = serviceIdentity?.model,
                        services = services
                    ),
                    isGateway = address == gateway,
                    isThisDevice = address == ownAddress,
                    sources = sources,
                    services = services
                )
            }
        )
    }

    suspend fun scanTcpPorts(
        targetAddress: String,
        ports: List<Int>,
        onProgress: (TcpPortScanProgress) -> Unit
    ): TcpPortScanResult = withContext(Dispatchers.IO) {
        require(ports.isNotEmpty()) { "Choose at least one TCP port." }
        require(ports.size <= 1024) { "Port scans are limited to 1,024 ports at a time." }
        require(ports.all { it in 1..65535 }) { "Ports must be between 1 and 65535." }
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.currentWifiNetwork()
            ?: error("Connect to Wi-Fi before scanning ports.")
        val linkProperties = connectivityManager.getLinkProperties(network)
            ?: error("Wi-Fi network details are unavailable.")
        val linkAddress = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address }
            ?: error("This Wi-Fi connection does not have an IPv4 address.")
        val subnet = Ipv4Subnet.from(linkAddress.address.address.toIpv4AddressValue(), linkAddress.prefixLength)
        val targetValue = targetAddress.toIpv4AddressValueOrNull()
            ?: error("The selected device does not have a valid IPv4 address.")
        require(subnet.hasPrivateAddress() && subnet.contains(targetValue)) {
            "MapNet scans ports only on the connected private Wi-Fi subnet."
        }

        val distinctPorts = ports.distinct().sorted()
        val open = mutableListOf<Int>()
        var completed = 0
        onProgress(TcpPortScanProgress(0, distinctPorts.size))
        coroutineScope {
            distinctPorts.chunked(PORT_SCAN_BATCH_SIZE).forEach { batch ->
                ensureActive()
                val results = batch.map { port ->
                    async { port.takeIf { canConnect(network, targetAddress, port) } }
                }.awaitAll()
                open += results.filterNotNull()
                completed += batch.size
                onProgress(TcpPortScanProgress(completed, distinctPorts.size))
            }
        }
        TcpPortScanResult(targetAddress, distinctPorts, open.sorted())
    }

    private fun respondsToPing(address: String): Boolean =
        runCommand(listOf(pingBinary(), "-c", "1", "-W", "1", address), 2)
            .contains("bytes from", ignoreCase = true)

    private fun canConnect(network: Network, address: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(address, port), PORT_CONNECT_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    private suspend fun reverseDnsName(address: String): String? = withTimeoutOrNull(NAME_LOOKUP_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            InetAddress.getByName(address).canonicalHostName
                ?.removeSuffix(".")
                ?.takeUnless { it == address || it.isBlank() }
        }
    }

    private fun discoverSsdpDevices(network: Network, subnet: Ipv4Subnet): Map<String, AdvertisedIdentity> =
        runCatching {
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 1\r\n")
                append("ST: ssdp:all\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            val found = mutableMapOf<String, AdvertisedIdentity>()
            DatagramSocket().use { socket ->
                network.bindSocket(socket)
                socket.soTimeout = SSDP_RECEIVE_SLICE_MS
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))
                val deadline = System.currentTimeMillis() + SSDP_WINDOW_MS
                while (System.currentTimeMillis() < deadline) {
                    val buffer = ByteArray(8_192)
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.getOrNull() ?: continue
                    val address = packet.address.hostAddress ?: continue
                    val value = address.toIpv4AddressValueOrNull() ?: continue
                    if (!subnet.contains(value)) continue
                    val response = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val headers = response.lineSequence().mapNotNull { line ->
                        val index = line.indexOf(':')
                        if (index <= 0) null else line.substring(0, index).trim().lowercase() to
                            line.substring(index + 1).trim()
                    }.toMap()
                    val location = headers["location"]
                    val descriptor = location?.let { fetchLocalDescriptor(network, subnet, address, it) }
                    val identity = AdvertisedIdentity(
                        friendlyName = descriptor?.friendlyName,
                        hostname = null,
                        vendor = descriptor?.vendor,
                        model = descriptor?.model,
                        sources = setOf(DiscoverySource.SSDP),
                        services = location?.toLocalWebService(address, DiscoverySource.SSDP)?.let(::listOf).orEmpty()
                    )
                    found[address] = found[address]?.merge(identity) ?: identity
                }
            }
            found
        }.getOrDefault(emptyMap())

    private fun fetchLocalDescriptor(
        network: Network,
        subnet: Ipv4Subnet,
        expectedAddress: String,
        location: String
    ): DeviceDescriptor? =
        runCatching {
            val uri = URI(location)
            if (!uri.scheme.equals("http", ignoreCase = true)) return@runCatching null
            val host = uri.host ?: return@runCatching null
            val value = host.toIpv4AddressValueOrNull() ?: return@runCatching null
            if (host != expectedAddress || !subnet.contains(value)) return@runCatching null
            val port = if (uri.port > 0) uri.port else 80
            val path = buildString {
                append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            }
            val bytes = Socket().use { socket ->
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), LOCAL_HTTP_TIMEOUT_MS)
                socket.soTimeout = LOCAL_HTTP_TIMEOUT_MS
                socket.getOutputStream().write(
                    "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII)
                )
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                while (output.size() < MAX_DESCRIPTOR_BYTES) {
                    val count = socket.getInputStream().read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, minOf(count, MAX_DESCRIPTOR_BYTES - output.size()))
                }
                output.toByteArray()
            }
            val body = String(bytes, StandardCharsets.UTF_8).substringAfter("\r\n\r\n", "")
            DeviceDescriptor(
                friendlyName = body.xmlValue("friendlyName"),
                vendor = body.xmlValue("manufacturer"),
                model = body.xmlValue("modelName") ?: body.xmlValue("modelNumber")
            ).takeIf { it.friendlyName != null || it.vendor != null || it.model != null }
        }.getOrNull()

    private suspend fun discoverMdnsDevices(subnet: Ipv4Subnet): Map<String, AdvertisedIdentity> {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val multicastLock = wifiManager.createMulticastLock("MapNet-device-discovery").apply {
            setReferenceCounted(false)
        }
        return try {
            multicastLock.acquire()
            runCatching {
            val services = coroutineScope {
                MDNS_SERVICE_TYPES.map { type -> async { discoverNsdType(type) } }.awaitAll().flatten()
            }.distinctBy { "${it.serviceName}|${it.serviceType}" }.take(MAX_MDNS_SERVICES)
            services.mapNotNull { resolveNsdService(it) }.mapNotNull { service ->
                @Suppress("DEPRECATION")
                val address = service.host?.hostAddress ?: return@mapNotNull null
                val value = address.toIpv4AddressValueOrNull() ?: return@mapNotNull null
                if (!subnet.contains(value)) return@mapNotNull null
                val attributes = service.attributes.mapValues { (_, bytes) ->
                    bytes.toString(StandardCharsets.UTF_8).cleanValue()
                }
                val type = service.serviceType.lowercase()
                val friendlyName = attributes["fn"] ?: attributes["name"] ?: service.serviceName.cleanValue()
                val model = attributes["md"] ?: attributes["model"] ?: attributes["am"]
                val vendor = attributes["manufacturer"] ?: attributes["mf"]
                val url = when {
                    type.contains("_https") -> "https://$address:${service.port}"
                    type.contains("_http") || type.contains("_googlecast") || type.contains("_roku") ->
                        "http://$address:${service.port}"
                    else -> null
                }
                address to AdvertisedIdentity(
                    friendlyName = friendlyName,
                    hostname = service.host?.hostName?.removeSuffix("."),
                    vendor = vendor,
                    model = model,
                    sources = setOf(DiscoverySource.MDNS),
                    services = listOf(
                        DiscoveredService("TCP", service.port, mdnsServiceLabel(type), url, DiscoverySource.MDNS)
                    )
                )
            }.groupBy({ it.first }, { it.second })
                .mapValues { (_, identities) -> identities.reduce(AdvertisedIdentity::merge) }
            }.getOrDefault(emptyMap())
        } finally {
            if (multicastLock.isHeld) multicastLock.release()
        }
    }

    private suspend fun discoverNsdType(serviceType: String): List<NsdServiceInfo> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val nsdManager = context.getSystemService(NsdManager::class.java)
                val handler = Handler(Looper.getMainLooper())
                val services = mutableListOf<NsdServiceInfo>()
                lateinit var listener: NsdManager.DiscoveryListener
                val finish = Runnable {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    if (continuation.isActive) continuation.resume(services.toList())
                }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) = Unit
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) { services += serviceInfo }
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onDiscoveryStopped(serviceType: String) {
                        if (continuation.isActive) continuation.resume(services.toList())
                    }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        runCatching { nsdManager.stopServiceDiscovery(this) }
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(services.toList())
                    }
                }
                continuation.invokeOnCancellation {
                    handler.removeCallbacks(finish)
                    handler.post { runCatching { nsdManager.stopServiceDiscovery(listener) } }
                }
                runCatching {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    handler.postDelayed(finish, MDNS_DISCOVERY_WINDOW_MS)
                }.onFailure {
                    handler.removeCallbacks(finish)
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun resolveNsdService(service: NsdServiceInfo): NsdServiceInfo? =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val nsdManager = context.getSystemService(NsdManager::class.java)
                val handler = Handler(Looper.getMainLooper())
                val timeout = Runnable { if (continuation.isActive) continuation.resume(null) }
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        handler.removeCallbacks(timeout)
                        if (continuation.isActive) continuation.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        handler.removeCallbacks(timeout)
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                }
                continuation.invokeOnCancellation { handler.removeCallbacks(timeout) }
                runCatching {
                    nsdManager.resolveService(service, listener)
                    handler.postDelayed(timeout, MDNS_RESOLVE_TIMEOUT_MS)
                }.onFailure {
                    handler.removeCallbacks(timeout)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    private fun netbiosName(network: Network, address: String): String? = runCatching {
        val transactionId = Random.nextInt(0, 65536)
        val query = netbiosNodeStatusQuery(transactionId)
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = NETBIOS_TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(address), NETBIOS_PORT))
            val buffer = ByteArray(1_024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            parseNetbiosNodeStatus(packet.data.copyOf(packet.length), transactionId)
        }
    }.getOrNull()

    private fun arpNeighbors(subnet: Ipv4Subnet, interfaceName: String?): Map<String, String> = runCatching {
        File("/proc/net/arp").useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                val address = columns.getOrNull(0) ?: return@mapNotNull null
                val macAddress = columns.getOrNull(3) ?: return@mapNotNull null
                val device = columns.getOrNull(5) ?: return@mapNotNull null
                val addressValue = address.toIpv4AddressValueOrNull() ?: return@mapNotNull null
                if (!subnet.contains(addressValue) || macAddress == "00:00:00:00:00:00" ||
                    (interfaceName != null && device != interfaceName)
                ) return@mapNotNull null
                address to macAddress.uppercase()
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun ConnectivityManager.currentWifiNetwork(): Network? {
        fun Network.isWifi(): Boolean = getNetworkCapabilities(this)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return boundNetworkForProcess?.takeIf { it.isWifi() }
            ?: activeNetwork?.takeIf { it.isWifi() }
    }

    private fun connectivityProbeResponseCode(): Int {
        val connection = (URL(PROBLEM_SOLVER_HTTPS_URL).openConnection() as HttpsURLConnection).apply {
            connectTimeout = PROBLEM_SOLVER_TIMEOUT_MS
            readTimeout = PROBLEM_SOLVER_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
        }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun runCommand(command: List<String>, timeoutSeconds: Long): String = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching "Timed out after ${timeoutSeconds}s."
        }
        process.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().ifBlank {
                "No diagnostic output (exit ${process.exitValue()})."
            }.take(8_000)
        }
    }.getOrElse { error ->
        "Diagnostic command unavailable: ${error.message ?: "unknown error"}"
    }

    private fun pingBinary(): String = if (File("/system/bin/ping").canExecute()) "/system/bin/ping" else "ping"

    private companion object {
        const val MAX_DISCOVERY_HOSTS = 510
        const val PING_BATCH_SIZE = 16
        const val PORT_SCAN_BATCH_SIZE = 24
        const val PORT_CONNECT_TIMEOUT_MS = 450
        const val NAME_LOOKUP_TIMEOUT_MS = 800L
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val SSDP_RECEIVE_SLICE_MS = 250
        const val SSDP_WINDOW_MS = 1_400L
        const val LOCAL_HTTP_TIMEOUT_MS = 700
        const val MAX_DESCRIPTOR_BYTES = 65_536
        const val MDNS_DISCOVERY_WINDOW_MS = 900L
        const val MDNS_RESOLVE_TIMEOUT_MS = 800L
        const val MAX_MDNS_SERVICES = 24
        const val NETBIOS_PORT = 137
        const val NETBIOS_TIMEOUT_MS = 450
        const val PROBLEM_SOLVER_HOST = "connectivitycheck.gstatic.com"
        const val PROBLEM_SOLVER_HTTPS_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val PROBLEM_SOLVER_TIMEOUT_MS = 4_000

        val MDNS_SERVICE_TYPES = listOf(
            "_http._tcp.", "_https._tcp.", "_googlecast._tcp.", "_airplay._tcp.",
            "_ipp._tcp.", "_workstation._tcp.", "_smb._tcp.", "_hap._tcp.", "_roku-ecp._tcp."
        )
    }
}

private data class AdvertisedIdentity(
    val friendlyName: String?,
    val hostname: String?,
    val vendor: String?,
    val model: String?,
    val sources: Set<DiscoverySource>,
    val services: List<DiscoveredService>
) {
    fun merge(other: AdvertisedIdentity) = AdvertisedIdentity(
        friendlyName = friendlyName ?: other.friendlyName,
        hostname = hostname ?: other.hostname,
        vendor = vendor ?: other.vendor,
        model = model ?: other.model,
        sources = sources + other.sources,
        services = (services + other.services).distinctBy { "${it.protocol}:${it.port}:${it.source}" }
    )
}

private data class HostIdentity(val name: String?, val fromNetbios: Boolean)

private data class DeviceDescriptor(
    val friendlyName: String?,
    val vendor: String?,
    val model: String?
)

private fun mergeAdvertisedMaps(
    first: Map<String, AdvertisedIdentity>,
    second: Map<String, AdvertisedIdentity>
): Map<String, AdvertisedIdentity> = (first.keys + second.keys).associateWith { address ->
    val left = first[address]
    val right = second[address]
    when {
        left != null && right != null -> left.merge(right)
        left != null -> left
        else -> checkNotNull(right)
    }
}

private fun inferDeviceType(
    isGateway: Boolean,
    name: String?,
    vendor: String?,
    model: String?,
    services: List<DiscoveredService>
): LanDeviceType {
    if (isGateway) return LanDeviceType.ROUTER
    val text = listOfNotNull(name, vendor, model, services.joinToString { it.serviceName }).joinToString(" ").lowercase()
    return when {
        listOf("printer", "jetdirect", "ipp", "brother", "epson", "xerox", "canon").any(text::contains) -> LanDeviceType.PRINTER
        listOf("rtsp", "camera", "doorbell").any(text::contains) -> LanDeviceType.CAMERA
        listOf("roku", "cast", "airplay", "smart tv", "apple tv", "fire tv").any(text::contains) -> LanDeviceType.TV_STREAMER
        listOf("synology", "qnap", "nas", "nfs").any(text::contains) -> LanDeviceType.STORAGE
        listOf("xbox", "playstation", "nintendo", "game console").any(text::contains) -> LanDeviceType.GAME_CONSOLE
        listOf("iphone", "ipad", "android", "moto g", "pixel phone").any(text::contains) -> LanDeviceType.PHONE_TABLET
        listOf("tuya", "espressif", "chamberlain", "homekit", "smart home", "mqtt").any(text::contains) -> LanDeviceType.SMART_HOME
        listOf("dell", "hewlett", "hp ", "lenovo", "windows", "macbook", "workstation").any(text::contains) -> LanDeviceType.COMPUTER
        listOf("router", "gateway", "ubiquiti", "netgear", "tp-link", "calix", "cisco").any(text::contains) -> LanDeviceType.ROUTER
        else -> LanDeviceType.UNKNOWN
    }
}

private fun String.toLocalWebService(sourceAddress: String, source: DiscoverySource): DiscoveredService? =
    runCatching {
        val uri = URI(this)
        val host = uri.host ?: return@runCatching null
        if (host != sourceAddress || (uri.scheme != "http" && uri.scheme != "https")) return@runCatching null
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        DiscoveredService("TCP", port, "Web interface", this, source)
    }.getOrNull()

private fun mdnsServiceLabel(type: String): String = when {
    type.contains("googlecast") -> "Google Cast"
    type.contains("airplay") -> "AirPlay"
    type.contains("ipp") -> "IPP printing"
    type.contains("workstation") -> "Workstation"
    type.contains("smb") -> "SMB"
    type.contains("hap") -> "HomeKit"
    type.contains("roku") -> "Roku ECP"
    type.contains("https") -> "HTTPS"
    else -> "HTTP"
}

private fun String.xmlValue(tag: String): String? = Regex(
    "<$tag(?:\\s[^>]*)?>(.*?)</$tag>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
).find(this)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.decodeXml()?.cleanValue()

private fun String.decodeXml(): String = replace("&amp;", "&")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

private fun String?.cleanValue(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun netbiosNodeStatusQuery(transactionId: Int): ByteArray {
    val output = ByteArrayOutputStream()
    fun writeU16(value: Int) {
        output.write((value shr 8) and 0xff)
        output.write(value and 0xff)
    }
    writeU16(transactionId)
    writeU16(0)
    writeU16(1)
    writeU16(0)
    writeU16(0)
    writeU16(0)
    val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
    output.write(32)
    name.forEach { byte ->
        val value = byte.toInt() and 0xff
        output.write('A'.code + ((value shr 4) and 0x0f))
        output.write('A'.code + (value and 0x0f))
    }
    output.write(0)
    writeU16(0x21)
    writeU16(1)
    return output.toByteArray()
}

private fun parseNetbiosNodeStatus(bytes: ByteArray, transactionId: Int): String? {
    if (bytes.size < 12 || readU16(bytes, 0) != transactionId || readU16(bytes, 6) < 1) return null
    var offset = skipDnsName(bytes, 12) + 4
    if (offset >= bytes.size) return null
    offset = skipDnsName(bytes, offset)
    if (offset + 10 > bytes.size || readU16(bytes, offset) != 0x21) return null
    val dataLength = readU16(bytes, offset + 8)
    val dataStart = offset + 10
    if (dataStart + dataLength > bytes.size || dataLength < 1) return null
    val nameCount = bytes[dataStart].toInt() and 0xff
    repeat(nameCount) { index ->
        val entry = dataStart + 1 + index * 18
        if (entry + 18 > bytes.size) return null
        val suffix = bytes[entry + 15].toInt() and 0xff
        val flags = readU16(bytes, entry + 16)
        if (suffix == 0x00 && flags and 0x8000 == 0) {
            return String(bytes, entry, 15, StandardCharsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
        }
    }
    return null
}

private fun skipDnsName(bytes: ByteArray, start: Int): Int {
    var offset = start
    while (offset < bytes.size) {
        val length = bytes[offset].toInt() and 0xff
        if (length == 0) return offset + 1
        if (length and 0xc0 == 0xc0) return offset + 2
        offset += length + 1
    }
    return bytes.size
}

private fun readU16(bytes: ByteArray, offset: Int): Int {
    if (offset + 1 >= bytes.size) return -1
    return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
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
        line.contains("bytes from", ignoreCase = true) || line.startsWith("From ", ignoreCase = true) ||
            line.contains("unreachable", ignoreCase = true) || line.contains("timed out", ignoreCase = true)
    }
    return usefulLine?.trim() ?: lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "No response"
}
