package com.mapnet.tools

import androidx.room.withTransaction
import com.mapnet.data.LanDeviceEntity
import com.mapnet.data.LanDeviceEventEntity
import com.mapnet.data.LanNetworkEntity
import com.mapnet.data.LanServiceEntity
import com.mapnet.data.MapNetDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LanDeviceRepository(private val database: MapNetDatabase) {
    private val dao = database.lanDeviceDao()

    suspend fun findKnownNetwork(connection: ConnectedWifiDetails): KnownLanNetwork? =
        findNetworkEntity(connection)?.toModel()

    fun observeNetwork(networkId: String): Flow<KnownLanNetwork?> =
        dao.observeNetwork(networkId).map { it?.toModel() }

    fun observeDevices(networkId: String): Flow<List<KnownLanDevice>> =
        dao.observeDevices(networkId).map { devices -> devices.map(LanDeviceEntity::toModel) }

    fun observeDevice(deviceId: String): Flow<KnownLanDevice?> =
        dao.observeDevice(deviceId).map { it?.toModel() }

    fun observeEvents(deviceId: String): Flow<List<LanDeviceEvent>> =
        dao.observeEvents(deviceId).map { events -> events.map(LanDeviceEventEntity::toModel) }

    fun observeServices(deviceId: String): Flow<List<LanService>> =
        dao.observeServices(deviceId).map { services -> services.map(LanServiceEntity::toModel) }

    suspend fun persistSuccessfulScan(
        connection: ConnectedWifiDetails,
        map: LocalNetworkMap,
        nowEpochMs: Long = System.currentTimeMillis()
    ): String = database.withTransaction {
        val gatewayDevice = map.devices.firstOrNull { it.isGateway }
        val gatewayMac = gatewayDevice?.macAddress.normalizedMacOrNull()
        val existingNetwork = if (gatewayMac != null) {
            dao.findNetworkByGatewayMacAndSubnet(gatewayMac, map.subnet)
        } else null
            ?: findNetworkEntity(connection)
        val networkId = existingNetwork?.id ?: UUID.randomUUID().toString()
        dao.upsertNetwork(
            LanNetworkEntity(
                id = networkId,
                ssid = connection.ssid,
                bssid = connection.bssid.usableBssidOrNull(),
                subnet = map.subnet,
                gatewayIp = connection.gateway,
                gatewayMac = gatewayMac ?: existingNetwork?.gatewayMac,
                firstSeenEpochMs = existingNetwork?.firstSeenEpochMs ?: nowEpochMs,
                lastSeenEpochMs = nowEpochMs,
                lastSuccessfulScanEpochMs = nowEpochMs
            )
        )

        val observedDeviceIds = mutableSetOf<String>()
        map.devices.forEach { discovered ->
            val normalizedMac = discovered.macAddress.normalizedMacOrNull()
            val byMac = normalizedMac?.let { dao.findDeviceByMac(networkId, it) }
            val byIp = dao.findDeviceByIp(networkId, discovered.ipv4Address)
            val byRole = if (discovered.isThisDevice) dao.findThisDevice(networkId) else null
            val existing = byMac ?: byIp ?: byRole
            val duplicate = listOfNotNull(byMac, byIp, byRole)
                .firstOrNull { existing != null && it.id != existing.id }
            if (existing != null && duplicate != null) {
                dao.upsertServices(dao.getServices(duplicate.id).map { it.copy(deviceId = existing.id) })
                dao.reassignEvents(duplicate.id, existing.id)
                dao.deleteDevice(duplicate.id)
            }
            val deviceId = existing?.id ?: UUID.randomUUID().toString()
            val newType = discovered.inferredType.takeUnless { it == LanDeviceType.UNKNOWN }
                ?: existing?.inferredType.toLanDeviceType()
            val combinedSources = existing?.discoverySources.toDiscoverySources() +
                duplicate?.discoverySources.toDiscoverySources() + discovered.sources
            val updated = LanDeviceEntity(
                id = deviceId,
                networkId = networkId,
                ipAddress = discovered.ipv4Address,
                macAddress = normalizedMac ?: existing?.macAddress,
                advertisedName = discovered.advertisedName.cleanOrNull() ?: existing?.advertisedName,
                hostname = discovered.hostname.cleanOrNull() ?: existing?.hostname,
                vendor = discovered.vendor.cleanOrNull() ?: existing?.vendor,
                model = discovered.model.cleanOrNull() ?: existing?.model,
                inferredType = newType.name,
                customName = existing?.customName ?: duplicate?.customName,
                customType = existing?.customType ?: duplicate?.customType,
                note = existing?.note?.takeIf { it.isNotBlank() } ?: duplicate?.note.orEmpty(),
                status = LanDeviceStatus.ONLINE.name,
                isGateway = discovered.isGateway,
                isThisDevice = discovered.isThisDevice,
                discoverySources = combinedSources.joinToString(",") { it.name },
                firstSeenEpochMs = listOfNotNull(existing?.firstSeenEpochMs, duplicate?.firstSeenEpochMs)
                    .minOrNull() ?: nowEpochMs,
                lastSeenEpochMs = nowEpochMs
            )
            dao.upsertDevice(updated)
            observedDeviceIds += deviceId

            when {
                existing == null -> dao.insertEvent(
                    LanDeviceEventEntity(
                        deviceId = deviceId,
                        occurredAtEpochMs = nowEpochMs,
                        type = LanDeviceEventType.DISCOVERED.name,
                        detail = "First discovered at ${discovered.ipv4Address}"
                    )
                )
                existing.status.toLanDeviceStatus() == LanDeviceStatus.OFFLINE -> dao.insertEvent(
                    LanDeviceEventEntity(
                        deviceId = deviceId,
                        occurredAtEpochMs = nowEpochMs,
                        type = LanDeviceEventType.ONLINE.name,
                        detail = "Device is online at ${discovered.ipv4Address}"
                    )
                )
            }
            if (existing != null && existing.ipAddress != discovered.ipv4Address) {
                dao.insertEvent(
                    LanDeviceEventEntity(
                        deviceId = deviceId,
                        occurredAtEpochMs = nowEpochMs,
                        type = LanDeviceEventType.IP_CHANGED.name,
                        detail = "IP changed from ${existing.ipAddress} to ${discovered.ipv4Address}"
                    )
                )
            }
            if (discovered.services.isNotEmpty()) {
                dao.upsertServices(discovered.services.map { service ->
                    service.toEntity(deviceId, nowEpochMs)
                })
            }
        }

        dao.getDevices(networkId)
            .filter { it.id !in observedDeviceIds && it.status.toLanDeviceStatus() == LanDeviceStatus.ONLINE }
            .forEach { absent ->
                dao.upsertDevice(absent.copy(status = LanDeviceStatus.OFFLINE.name))
                dao.insertEvent(
                    LanDeviceEventEntity(
                        deviceId = absent.id,
                        occurredAtEpochMs = nowEpochMs,
                        type = LanDeviceEventType.OFFLINE.name,
                        detail = "Not found in the latest completed scan"
                    )
                )
            }
        networkId
    }

    suspend fun editDevice(
        deviceId: String,
        customName: String?,
        customType: LanDeviceType?,
        note: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        val current = dao.getDevice(deviceId) ?: return@withTransaction
        val name = customName.cleanOrNull()?.take(80)
        val cleanNote = note.trim().take(500)
        val typeName = customType?.name
        if (current.customName == name && current.customType == typeName && current.note == cleanNote) {
            return@withTransaction
        }
        dao.upsertDevice(
            current.copy(
                customName = name,
                customType = typeName,
                note = cleanNote
            )
        )
        dao.insertEvent(
            LanDeviceEventEntity(
                deviceId = deviceId,
                occurredAtEpochMs = nowEpochMs,
                type = LanDeviceEventType.USER_EDITED.name,
                detail = "Device details updated"
            )
        )
    }

    suspend fun forgetDevice(deviceId: String): Boolean = dao.deleteDevice(deviceId) > 0

    suspend fun savePortScan(
        deviceId: String,
        openPorts: List<Int>,
        nowEpochMs: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        if (dao.getDevice(deviceId) == null) return@withTransaction
        dao.deleteServicesBySource(deviceId, DiscoverySource.PORT_SCAN.name)
        if (openPorts.isNotEmpty()) {
            val ip = dao.getDevice(deviceId)?.ipAddress ?: return@withTransaction
            dao.upsertServices(openPorts.map { port ->
                LanServiceEntity(
                    deviceId = deviceId,
                    protocol = "TCP",
                    port = port,
                    serviceName = serviceNameForTcpPort(port),
                    url = webUrlFor(ip, port),
                    source = DiscoverySource.PORT_SCAN.name,
                    lastSeenEpochMs = nowEpochMs
                )
            })
        }
        dao.insertEvent(
            LanDeviceEventEntity(
                deviceId = deviceId,
                occurredAtEpochMs = nowEpochMs,
                type = LanDeviceEventType.PORTS_UPDATED.name,
                detail = if (openPorts.isEmpty()) "No open TCP ports found" else
                    "Open TCP ports: ${openPorts.joinToString()}"
            )
        )
    }

    private suspend fun findNetworkEntity(connection: ConnectedWifiDetails): LanNetworkEntity? {
        val subnet = connection.subnet ?: return null
        return connection.bssid.usableBssidOrNull()?.let { dao.findNetworkByBssidAndSubnet(it, subnet) }
            ?: dao.findNetworkBySsidAndSubnet(connection.ssid, subnet)
    }
}

private fun LanNetworkEntity.toModel() = KnownLanNetwork(
    id = id,
    ssid = ssid,
    bssid = bssid,
    subnet = subnet,
    gatewayIp = gatewayIp,
    gatewayMac = gatewayMac,
    firstSeenEpochMs = firstSeenEpochMs,
    lastSeenEpochMs = lastSeenEpochMs,
    lastSuccessfulScanEpochMs = lastSuccessfulScanEpochMs
)

private fun LanDeviceEntity.toModel() = KnownLanDevice(
    id = id,
    networkId = networkId,
    ipAddress = ipAddress,
    macAddress = macAddress,
    advertisedName = advertisedName,
    hostname = hostname,
    vendor = vendor,
    model = model,
    inferredType = inferredType.toLanDeviceType(),
    customName = customName,
    customType = customType?.toLanDeviceType(),
    note = note,
    status = status.toLanDeviceStatus(),
    isGateway = isGateway,
    isThisDevice = isThisDevice,
    sources = discoverySources.toDiscoverySources(),
    firstSeenEpochMs = firstSeenEpochMs,
    lastSeenEpochMs = lastSeenEpochMs
)

private fun LanDeviceEventEntity.toModel() = LanDeviceEvent(
    id = id,
    occurredAtEpochMs = occurredAtEpochMs,
    type = enumValueOrDefault(type, LanDeviceEventType.DISCOVERED),
    detail = detail
)

private fun LanServiceEntity.toModel() = LanService(
    protocol = protocol,
    port = port,
    serviceName = serviceName,
    url = url,
    source = enumValueOrDefault(source, DiscoverySource.PORT_SCAN),
    lastSeenEpochMs = lastSeenEpochMs
)

private fun DiscoveredService.toEntity(deviceId: String, seenAt: Long) = LanServiceEntity(
    deviceId = deviceId,
    protocol = protocol,
    port = port,
    serviceName = serviceName,
    url = url,
    source = source.name,
    lastSeenEpochMs = seenAt
)

private fun String?.toLanDeviceType(): LanDeviceType =
    enumValueOrDefault(this, LanDeviceType.UNKNOWN)

private fun String?.toLanDeviceStatus(): LanDeviceStatus =
    enumValueOrDefault(this, LanDeviceStatus.OFFLINE)

private fun String?.toDiscoverySources(): Set<DiscoverySource> = this
    ?.split(',')
    .orEmpty()
    .mapNotNull { value -> DiscoverySource.entries.firstOrNull { it.name == value } }
    .toSet()

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.normalizedMacOrNull(): String? = cleanOrNull()
    ?.uppercase()
    ?.takeIf { it.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) && it != "00:00:00:00:00:00" }

private fun String?.usableBssidOrNull(): String? = normalizedMacOrNull()
    ?.takeUnless { it == "02:00:00:00:00:00" }
