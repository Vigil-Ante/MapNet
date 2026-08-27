package com.mapnet.tools

data class NetworkDevice(
    val ipv4Address: String,
    val macAddress: String?,
    val discoveryDetail: String
)

data class LocalNetworkMap(
    val subnet: String,
    val scannedAddressCount: Int,
    val devices: List<NetworkDevice>
)

data class NetworkMapProgress(
    val completedAddressCount: Int,
    val totalAddressCount: Int
)

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
