package com.mapnet.tools

enum class LanDeviceStatus {
    ONLINE,
    OFFLINE
}

enum class LanDeviceType(val label: String) {
    ROUTER("Router / network"),
    COMPUTER("Computer"),
    PHONE_TABLET("Phone / tablet"),
    TV_STREAMER("TV / streamer"),
    PRINTER("Printer"),
    CAMERA("Camera"),
    SMART_HOME("Smart home"),
    STORAGE("Storage"),
    GAME_CONSOLE("Game console"),
    OTHER("Other"),
    UNKNOWN("Unknown")
}

enum class DiscoverySource(val label: String) {
    THIS_DEVICE("This Android device"),
    GATEWAY("Default gateway"),
    PING("Ping response"),
    ARP("ARP table"),
    REVERSE_DNS("Reverse DNS"),
    MDNS("mDNS / DNS-SD"),
    SSDP("SSDP / UPnP"),
    NETBIOS("NetBIOS"),
    MAC_VENDOR("MAC vendor database"),
    PORT_SCAN("TCP port scan"),
    WEB_FINGERPRINT("Local web identification")
}

enum class LanDeviceEventType {
    DISCOVERED,
    ONLINE,
    OFFLINE,
    IP_CHANGED,
    USER_EDITED,
    PORTS_UPDATED
}

data class DiscoveredService(
    val protocol: String,
    val port: Int,
    val serviceName: String,
    val url: String? = null,
    val source: DiscoverySource
)

data class DiscoveredNetworkDevice(
    val ipv4Address: String,
    val macAddress: String?,
    val advertisedName: String? = null,
    val hostname: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val inferredType: LanDeviceType = LanDeviceType.UNKNOWN,
    val isGateway: Boolean = false,
    val isThisDevice: Boolean = false,
    val sources: Set<DiscoverySource> = emptySet(),
    val services: List<DiscoveredService> = emptyList()
) {
    val discoveryDetail: String
        get() = sources.joinToString(" • ") { it.label }
}

data class LocalNetworkMap(
    val subnet: String,
    val scannedAddressCount: Int,
    val devices: List<DiscoveredNetworkDevice>
)

data class NetworkMapProgress(
    val completedAddressCount: Int,
    val totalAddressCount: Int,
    val phase: String = "Checking addresses"
)

data class TcpPortScanProgress(
    val completedPortCount: Int,
    val totalPortCount: Int
)

data class TcpPortScanResult(
    val target: String,
    val scannedPorts: List<Int>,
    val openPorts: List<Int>
)

data class DeviceIdentification(
    val friendlyName: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val source: DiscoverySource,
    val detail: String
) {
    fun merge(other: DeviceIdentification) = DeviceIdentification(
        friendlyName = friendlyName ?: other.friendlyName,
        vendor = vendor ?: other.vendor,
        model = model ?: other.model,
        source = source,
        detail = listOf(detail, other.detail).filter(String::isNotBlank).distinct().joinToString(" · ").take(500)
    )
}

enum class ProblemCheckStatus {
    PASSED,
    FAILED,
    UNKNOWN,
    SKIPPED
}

enum class ProblemCheckId {
    WIFI,
    GATEWAY,
    DNS,
    INTERNET,
    DEVICE
}

data class ProblemSolverCheck(
    val id: ProblemCheckId,
    val label: String,
    val status: ProblemCheckStatus,
    val detail: String
)

enum class ProblemDiagnosis(
    val title: String,
    val nextStep: String
) {
    NOT_ON_WIFI(
        "Not connected to Wi-Fi",
        "Connect this phone to the Wi-Fi network you want to diagnose, then run the solver again."
    ),
    ROUTER_UNREACHABLE(
        "Your router is not responding",
        "Move closer to the router, reconnect to Wi-Fi, then restart the router if it still does not respond."
    ),
    DNS_FAILURE(
        "DNS is not working",
        "Restart the router or check its DNS settings. The Wi-Fi link works, but website names could not be resolved."
    ),
    INTERNET_LIMITED(
        "Internet connection is limited",
        "Check your ISP connection or complete any sign-in page required by this Wi-Fi network."
    ),
    DEVICE_UNREACHABLE(
        "The selected device is not responding",
        "Check that the device is powered on and connected to this Wi-Fi network, then scan devices again."
    ),
    INCOMPLETE(
        "Diagnosis incomplete",
        "Review the technical results, reconnect to Wi-Fi if needed, and try again."
    ),
    ALL_CLEAR(
        "No connection problem detected",
        "The phone, router, DNS, internet, and selected device responded to these checks."
    )
}

data class ProblemSolverResult(
    val selectedDeviceName: String? = null,
    val checks: List<ProblemSolverCheck>,
    val diagnosis: ProblemDiagnosis
)

fun classifyProblem(checks: List<ProblemSolverCheck>): ProblemDiagnosis {
    fun status(id: ProblemCheckId) = checks.firstOrNull { it.id == id }?.status
    return when {
        status(ProblemCheckId.WIFI) == ProblemCheckStatus.FAILED -> ProblemDiagnosis.NOT_ON_WIFI
        status(ProblemCheckId.GATEWAY) == ProblemCheckStatus.FAILED -> ProblemDiagnosis.ROUTER_UNREACHABLE
        status(ProblemCheckId.DNS) == ProblemCheckStatus.FAILED -> ProblemDiagnosis.DNS_FAILURE
        status(ProblemCheckId.INTERNET) == ProblemCheckStatus.FAILED -> ProblemDiagnosis.INTERNET_LIMITED
        status(ProblemCheckId.DEVICE) == ProblemCheckStatus.FAILED -> ProblemDiagnosis.DEVICE_UNREACHABLE
        checks.any { it.status == ProblemCheckStatus.UNKNOWN } -> ProblemDiagnosis.INCOMPLETE
        else -> ProblemDiagnosis.ALL_CLEAR
    }
}

data class KnownLanNetwork(
    val id: String,
    val ssid: String,
    val bssid: String?,
    val subnet: String,
    val gatewayIp: String?,
    val gatewayMac: String?,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val lastSuccessfulScanEpochMs: Long?
)

data class KnownLanDevice(
    val id: String,
    val networkId: String,
    val ipAddress: String,
    val macAddress: String?,
    val advertisedName: String?,
    val hostname: String?,
    val vendor: String?,
    val model: String?,
    val inferredType: LanDeviceType,
    val customName: String?,
    val customType: LanDeviceType?,
    val note: String,
    val status: LanDeviceStatus,
    val isGateway: Boolean,
    val isThisDevice: Boolean,
    val sources: Set<DiscoverySource>,
    val identificationSource: DiscoverySource? = null,
    val identificationDetail: String? = null,
    val identifiedAtEpochMs: Long? = null,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long
) {
    val displayName: String
        get() = customName.cleanValue()
            ?: advertisedName.cleanValue()
            ?: hostname.cleanValue()
            ?: vendor.cleanValue()?.let { "$it device" }
            ?: "Unknown device"

    val effectiveType: LanDeviceType
        get() = customType ?: inferredType
}

/**
 * A small offline reference for legacy factory admin credentials. These are
 * only hints for equipment the user owns; they are never tried by MapNet.
 */
data class RouterDefaultCredential(
    val brand: String,
    val username: String,
    val password: String,
    val note: String
)

fun KnownLanDevice.defaultRouterCredentials(): List<RouterDefaultCredential> {
    if (!isGateway && effectiveType != LanDeviceType.ROUTER) return emptyList()
    val identification = listOfNotNull(vendor, model, hostname, advertisedName, customName)
        .joinToString(" ")
        .lowercase()
    return ROUTER_DEFAULT_CREDENTIALS.filter { reference ->
        reference.keywords.any(identification::contains)
    }.map { reference ->
        RouterDefaultCredential(reference.brand, reference.username, reference.password, reference.note)
    }
}

private data class RouterDefaultCredentialReference(
    val brand: String,
    val keywords: Set<String>,
    val username: String,
    val password: String,
    val note: String
)

private val ROUTER_DEFAULT_CREDENTIALS = listOf(
    RouterDefaultCredentialReference(
        brand = "NETGEAR",
        keywords = setOf("netgear"),
        username = "admin",
        password = "password",
        note = "Common factory web-admin credential; verify the label for the exact model."
    ),
    RouterDefaultCredentialReference(
        brand = "TP-Link (legacy)",
        keywords = setOf("tp-link", "tplink"),
        username = "admin",
        password = "admin",
        note = "Legacy models only. Many newer models require a password created during setup."
    ),
    RouterDefaultCredentialReference(
        brand = "D-Link DIR series",
        keywords = setOf("d-link", "dlink"),
        username = "admin",
        password = "(blank)",
        note = "DIR-series factory setting; other D-Link products vary by model and region."
    ),
    RouterDefaultCredentialReference(
        brand = "Linksys Smart Wi-Fi",
        keywords = setOf("linksys"),
        username = "(model-dependent)",
        password = "admin",
        note = "For an unconfigured router; verify the exact model and setup state."
    ),
    RouterDefaultCredentialReference(
        brand = "ASUS (some models)",
        keywords = setOf("asus"),
        username = "admin",
        password = "admin",
        note = "Some models use label-specific credentials or require first-time setup."
    )
)

data class LanDeviceEvent(
    val id: Long,
    val occurredAtEpochMs: Long,
    val type: LanDeviceEventType,
    val detail: String
)

data class LanService(
    val protocol: String,
    val port: Int,
    val serviceName: String,
    val url: String?,
    val source: DiscoverySource,
    val lastSeenEpochMs: Long
)

enum class DeviceFilter(val label: String) {
    ALL("All"),
    ONLINE("Online"),
    OFFLINE("Offline")
}

enum class DeviceSort(val label: String) {
    NAME("Name"),
    IP_ADDRESS("IP address"),
    LAST_SEEN("Last seen")
}

fun List<KnownLanDevice>.filterAndSortDevices(
    query: String,
    filter: DeviceFilter,
    sort: DeviceSort
): List<KnownLanDevice> {
    val needle = query.trim().lowercase()
    val filtered = filter { device ->
        val matchesStatus = when (filter) {
            DeviceFilter.ALL -> true
            DeviceFilter.ONLINE -> device.status == LanDeviceStatus.ONLINE
            DeviceFilter.OFFLINE -> device.status == LanDeviceStatus.OFFLINE
        }
        val searchable = listOfNotNull(
            device.displayName,
            device.ipAddress,
            device.macAddress,
            device.vendor,
            device.model,
            device.hostname,
            device.effectiveType.label
        )
        matchesStatus && (needle.isEmpty() || searchable.any { it.lowercase().contains(needle) })
    }
    val withinStatus = when (sort) {
        DeviceSort.NAME -> compareBy<KnownLanDevice> { it.displayName.lowercase() }
            .thenBy { it.ipAddress.toIpv4AddressValueOrNull() ?: Long.MAX_VALUE }
        DeviceSort.IP_ADDRESS -> compareBy { it.ipAddress.toIpv4AddressValueOrNull() ?: Long.MAX_VALUE }
        DeviceSort.LAST_SEEN -> compareByDescending<KnownLanDevice> { it.lastSeenEpochMs }
            .thenBy { it.displayName.lowercase() }
    }
    return filtered.sortedWith(
        compareByDescending<KnownLanDevice> { it.status == LanDeviceStatus.ONLINE }
            .then(withinStatus)
    )
}

val COMMON_TCP_PORTS: List<Int> = listOf(
    21, 22, 23, 53, 80, 139, 443, 445, 515, 548, 554, 631, 1883, 2049, 2869,
    3389, 5000, 5357, 5900, 7000, 8008, 8009, 8060, 8080, 8443, 8883, 9000,
    9100, 32400, 62078
)

fun customTcpPorts(start: Int, endInclusive: Int): List<Int> {
    require(start in 1..65535 && endInclusive in 1..65535) { "Ports must be between 1 and 65535." }
    require(endInclusive >= start) { "The ending port must be at least the starting port." }
    require(endInclusive - start + 1 <= 1024) { "Custom scans are limited to 1,024 ports at a time." }
    return (start..endInclusive).toList()
}

fun serviceNameForTcpPort(port: Int): String = when (port) {
    21 -> "FTP"
    22 -> "SSH"
    23 -> "Telnet"
    53 -> "DNS"
    80 -> "HTTP"
    139 -> "NetBIOS"
    443 -> "HTTPS"
    445 -> "SMB"
    515 -> "LPD printing"
    548 -> "AFP"
    554 -> "RTSP"
    631 -> "IPP printing"
    1883 -> "MQTT"
    2049 -> "NFS"
    2869 -> "UPnP eventing"
    3389 -> "Remote Desktop"
    5000 -> "Web service"
    5357 -> "Web Services for Devices"
    5900 -> "VNC"
    7000 -> "AirPlay"
    8008, 8009 -> "Google Cast"
    8060 -> "Roku ECP"
    8080 -> "HTTP alternate"
    8443 -> "HTTPS alternate"
    8883 -> "MQTT TLS"
    9000 -> "Web service"
    9100 -> "JetDirect printing"
    32400 -> "Plex"
    62078 -> "iOS sync"
    else -> "TCP service"
}

fun webUrlFor(ipAddress: String, port: Int): String? = when (port) {
    80 -> "http://$ipAddress"
    443 -> "https://$ipAddress"
    5000, 8008, 8060, 8080, 9000 -> "http://$ipAddress:$port"
    8443 -> "https://$ipAddress:$port"
    else -> null
}

private fun String?.cleanValue(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** An IPv4 subnet represented as unsigned 32-bit values stored in a Long. */
class Ipv4Subnet private constructor(
    val address: Long,
    val prefixLength: Int,
    val networkAddress: Long,
    val broadcastAddress: Long
) {
    val usableHostCount: Int get() = (broadcastAddress - networkAddress - 1L).toInt()
    val cidr: String get() = "${networkAddress.toIpv4Address()}/$prefixLength"

    fun contains(address: Long): Boolean = address in networkAddress..broadcastAddress

    fun hostAddresses(): Sequence<Long> = (networkAddress + 1 until broadcastAddress).asSequence()

    fun hasPrivateAddress(): Boolean {
        val first = (address shr 24).toInt() and 0xff
        val second = (address shr 16).toInt() and 0xff
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    companion object {
        fun from(address: Long, prefixLength: Int): Ipv4Subnet {
            require(address in 0..0xffff_ffffL) { "Invalid IPv4 address." }
            require(prefixLength in 1..30) { "The Wi-Fi subnet must have usable IPv4 host addresses." }
            val hostBits = 32 - prefixLength
            val hostMask = (1L shl hostBits) - 1L
            val network = address and (0xffff_ffffL xor hostMask)
            return Ipv4Subnet(
                address = address,
                prefixLength = prefixLength,
                networkAddress = network,
                broadcastAddress = network or hostMask
            )
        }
    }
}

fun ByteArray.toIpv4AddressValue(): Long {
    require(size == 4) { "Expected an IPv4 address." }
    return fold(0L) { value, byte -> (value shl 8) or (byte.toInt().toLong() and 0xff) }
}

fun String.toIpv4AddressValueOrNull(): Long? = runCatching {
    val octets = split('.')
    require(octets.size == 4)
    octets.fold(0L) { value, octet ->
        val parsed = octet.toInt()
        require(parsed in 0..255)
        (value shl 8) or parsed.toLong()
    }
}.getOrNull()

fun Long.toIpv4Address(): String = listOf(24, 16, 8, 0)
    .joinToString(".") { shift -> ((this shr shift) and 0xff).toString() }
