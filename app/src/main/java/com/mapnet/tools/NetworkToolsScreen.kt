package com.mapnet.tools

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import java.text.DateFormat
import java.util.Date

@Composable
fun NetworkToolsScreen(
    modifier: Modifier,
    state: NetworkToolsUiState,
    viewModel: NetworkToolsViewModel
) {
    when (state.route) {
        ToolsRoute.INVENTORY -> DeviceInventoryScreen(modifier, state, viewModel)
        ToolsRoute.DEVICE_DETAIL -> DeviceDetailScreen(modifier, state, viewModel)
        ToolsRoute.MANUAL_DIAGNOSTICS -> ManualDiagnosticsScreen(modifier, state, viewModel)
    }
}

@Composable
private fun DeviceInventoryScreen(
    modifier: Modifier,
    state: NetworkToolsUiState,
    viewModel: NetworkToolsViewModel
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Devices", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Device identification runs locally on the Wi-Fi network you are connected to.",
                    style = MaterialTheme.typography.bodySmall
                )
                NetworkSummaryCard(state, viewModel)
                state.networkMapProgress?.let { progress ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                if (progress.phase == "Checking addresses") {
                                    "${progress.phase}: ${progress.completedAddressCount} of ${progress.totalAddressCount}"
                                } else progress.phase,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                state.scanMessage?.let { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::clearTransientMessage) { Text("Dismiss") }
                        }
                    }
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search devices") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DeviceFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort devices")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DeviceSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    onClick = {
                                        viewModel.setSort(sort)
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        if (state.sort == sort) Text("✓") else Spacer(Modifier.width(14.dp))
                                    }
                                )
                            }
                        }
                    }
                }
                Text(
                    "${state.visibleDevices.size} shown · sorted by ${state.sort.label.lowercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.connection == null) {
            item {
                EmptyDevicesCard(
                    title = "Connect to Wi-Fi",
                    detail = "MapNet can list devices only on the active private Wi-Fi network."
                )
            }
        } else if (state.devices.isEmpty() && !state.isScanning) {
            item {
                EmptyDevicesCard(
                    title = "No saved devices yet",
                    detail = "Tap Scan devices to map this network. Guest isolation and devices that reject discovery traffic can limit results."
                )
            }
        } else if (state.visibleDevices.isEmpty()) {
            item {
                EmptyDevicesCard(
                    title = "No matching devices",
                    detail = "Change the search text or status filter."
                )
            }
        } else {
            items(state.visibleDevices, key = KnownLanDevice::id) { device ->
                DeviceRow(device = device, onClick = { viewModel.openDevice(device.id) })
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun NetworkSummaryCard(state: NetworkToolsUiState, viewModel: NetworkToolsViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val connection = state.connection
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        connection?.ssid ?: "No active Wi-Fi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        state.network?.subnet ?: connection?.subnet ?: "Network details unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = viewModel::refreshNow,
                    enabled = connection != null
                ) {
                    Icon(
                        if (state.isScanning) Icons.Default.Cancel else Icons.Default.Refresh,
                        contentDescription = if (state.isScanning) "Cancel scan" else "Scan devices"
                    )
                }
            }
            if (state.network != null) {
                Text(
                    "${state.onlineDeviceCount} online · ${state.devices.size} known",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Last completed scan ${state.network.lastSuccessfulScanEpochMs?.let(::formatRelativeTime) ?: "never"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::refreshNow,
                    enabled = connection != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (state.isScanning) Icons.Default.Cancel else Icons.Default.Refresh,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isScanning) "Cancel scan" else "Scan devices")
                }
                OutlinedButton(onClick = viewModel::openManualDiagnostics, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Manual tools")
                }
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard(title: String, detail: String) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeviceRow(device: KnownLanDevice, onClick: () -> Unit) {
    val online = device.status == LanDeviceStatus.ONLINE
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (online) 1f else 0.56f)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Icon(
                deviceIcon(device.effectiveType),
                contentDescription = device.effectiveType.label,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .background(if (online) ONLINE_GREEN else Color.Gray, CircleShape)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.displayName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (device.isGateway) Text("Gateway", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (online) device.ipAddress else "${device.ipAddress} · ${formatRelativeTime(device.lastSeenEpochMs)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(112.dp)) {
            Text(
                device.vendor ?: device.effectiveType.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            device.model?.let {
                Text(
                    it,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open device details")
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun DeviceDetailScreen(
    modifier: Modifier,
    state: NetworkToolsUiState,
    viewModel: NetworkToolsViewModel
) {
    val device = state.selectedDevice
    if (device == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showEdit by rememberSaveable(device.id) { mutableStateOf(false) }
    var showPortScan by rememberSaveable(device.id) { mutableStateOf(false) }
    var showCopy by rememberSaveable(device.id) { mutableStateOf(false) }
    var showForget by rememberSaveable(device.id) { mutableStateOf(false) }
    val webUrl = remember(device.ipAddress, state.selectedServices) {
        state.selectedServices.firstNotNullOfOrNull { it.url.safeWebUrlFor(device.ipAddress) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { DeviceHeaderCard(device, onEdit = { showEdit = true }) }
        item {
            DeviceActionGrid(
                diagnosticsEnabled = !state.isScanning && !state.diagnostic.isRunning,
                hasWebUrl = webUrl != null,
                onPing = viewModel::pingSelectedDevice,
                onTraceroute = viewModel::tracerouteSelectedDevice,
                onPorts = { showPortScan = true },
                onCopy = { showCopy = true },
                onOpenWeb = {
                    webUrl?.let { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                }
            )
        }
        if (state.diagnostic.title != null || state.diagnostic.output != null) {
            item { DiagnosticResultCard(state.diagnostic, viewModel::cancelDiagnostic) }
        }
        item { IdentityCard(device) }
        item { ServicesCard(state.selectedServices, device.ipAddress) }
        item { NetworkDetailsCard(device) }
        item { TimelineCard(state.selectedEvents) }
        item {
            TextButton(onClick = { showForget = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Forget this saved device")
            }
        }
    }

    if (showEdit) {
        EditDeviceDialog(
            device = device,
            onSave = { name, type, note ->
                viewModel.editSelectedDevice(name, type, note)
                showEdit = false
            },
            onDismiss = { showEdit = false }
        )
    }
    if (showPortScan) {
        PortScanDialog(
            onCommon = {
                showPortScan = false
                viewModel.scanCommonPorts()
            },
            onCustom = { start, end ->
                showPortScan = false
                viewModel.scanCustomPorts(start, end)
            },
            onDismiss = { showPortScan = false }
        )
    }
    if (showCopy) {
        AlertDialog(
            onDismissRequest = { showCopy = false },
            title = { Text("Copy address") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(device.ipAddress))
                            showCopy = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy IP · ${device.ipAddress}") }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(device.macAddress.orEmpty()))
                            showCopy = false
                        },
                        enabled = device.macAddress != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(device.macAddress?.let { "Copy MAC · $it" } ?: "MAC unavailable") }
                }
            },
            confirmButton = { TextButton(onClick = { showCopy = false }) { Text("Close") } }
        )
    }
    if (showForget) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text("Forget ${device.displayName}?") },
            text = { Text("This removes MapNet's saved history and note. It does not disconnect the device, and it can appear again after another scan.") },
            confirmButton = {
                Button(onClick = viewModel::forgetSelectedDevice) { Text("Forget device") }
            },
            dismissButton = { TextButton(onClick = { showForget = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DeviceHeaderCard(device: KnownLanDevice, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    deviceIcon(device.effectiveType),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (device.status == LanDeviceStatus.ONLINE) "Online now" else "Offline · ${formatRelativeTime(device.lastSeenEpochMs)}",
                        color = if (device.status == LanDeviceStatus.ONLINE) ONLINE_GREEN else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            }
            Text(
                listOfNotNull(device.vendor, device.model, device.effectiveType.label).distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium
            )
            if (device.note.isNotBlank()) Text(device.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeviceActionGrid(
    diagnosticsEnabled: Boolean,
    hasWebUrl: Boolean,
    onPing: () -> Unit,
    onTraceroute: () -> Unit,
    onPorts: () -> Unit,
    onCopy: () -> Unit,
    onOpenWeb: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Device actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceActionButton("Ping", Icons.Default.NetworkCheck, diagnosticsEnabled, onPing, Modifier.weight(1f))
            DeviceActionButton("Traceroute", Icons.Default.Timeline, diagnosticsEnabled, onTraceroute, Modifier.weight(1f))
            DeviceActionButton("Open ports", Icons.Default.Dns, diagnosticsEnabled, onPorts, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeviceActionButton("Copy address", Icons.Default.ContentCopy, true, onCopy, Modifier.weight(1f))
            DeviceActionButton("Web interface", Icons.Default.Http, hasWebUrl, onOpenWeb, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        if (!hasWebUrl) {
            Text(
                "Web interface becomes available when a local service advertises one or a port scan finds HTTP/HTTPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DeviceActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.height(82.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DiagnosticResultCard(diagnostic: DiagnosticUiState, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    diagnostic.title ?: "Diagnostic result",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (diagnostic.isRunning) {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Cancel, contentDescription = "Cancel diagnostic") }
                }
            }
            if (diagnostic.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                diagnostic.progressText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            diagnostic.output?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun IdentityCard(device: KnownLanDevice) {
    DetailCard("Device identity") {
        DeviceDetailRow("IP address", device.ipAddress)
        DeviceDetailRow("MAC address", device.macAddress ?: "Unavailable")
        DeviceDetailRow("Hostname", device.hostname ?: "Not advertised")
        DeviceDetailRow("Manufacturer", device.vendor ?: "Unknown")
        DeviceDetailRow("Model", device.model ?: "Not advertised")
        DeviceDetailRow("Type", device.effectiveType.label)
        if (device.isGateway) DeviceDetailRow("Role", "Default gateway")
        if (device.isThisDevice) DeviceDetailRow("Role", "This Android device")
    }
}

@Composable
private fun ServicesCard(services: List<LanService>, ipAddress: String) {
    DetailCard("Services and open ports") {
        if (services.isEmpty()) {
            Text("No services have been advertised or scanned yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            services.forEach { service ->
                DeviceDetailRow(
                    "${service.protocol} ${service.port}",
                    "${service.serviceName} · ${service.source.label}"
                )
            }
        }
        Text("Results apply to $ipAddress and may change.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NetworkDetailsCard(device: KnownLanDevice) {
    DetailCard("Network details") {
        DeviceDetailRow("Status", if (device.status == LanDeviceStatus.ONLINE) "Online" else "Offline")
        DeviceDetailRow("First seen", formatTimestamp(device.firstSeenEpochMs))
        DeviceDetailRow("Last seen", formatTimestamp(device.lastSeenEpochMs))
        Text("Found through", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (device.sources.isEmpty()) {
            Text("Saved scan", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                device.sources.sortedBy { it.label }.forEach { source ->
                    AssistChip(onClick = {}, label = { Text(source.label) })
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(events: List<LanDeviceEvent>) {
    DetailCard("Timeline") {
        if (events.isEmpty()) {
            Text("No saved events yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            events.take(50).forEachIndexed { index, event ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.padding(top = 6.dp).size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(event.type.eventLabel(), style = MaterialTheme.typography.labelLarge)
                        Text(event.detail, style = MaterialTheme.typography.bodySmall)
                        Text(formatTimestamp(event.occurredAtEpochMs), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (index != events.take(50).lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun DeviceDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.8f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1.2f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EditDeviceDialog(
    device: KnownLanDevice,
    onSave: (String?, LanDeviceType?, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.customName.orEmpty()) }
    var note by rememberSaveable(device.id) { mutableStateOf(device.note) }
    var type by remember(device.id) { mutableStateOf(device.customType) }
    var typeMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Friendly name") },
                    placeholder = { Text(device.advertisedName ?: device.hostname ?: device.displayName) },
                    singleLine = true
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(type?.label ?: "Automatic · ${device.inferredType.label}")
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Automatic · ${device.inferredType.label}") },
                            onClick = { type = null; typeMenu = false }
                        )
                        LanDeviceType.entries.filterNot { it == LanDeviceType.UNKNOWN }.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { type = option; typeMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Private note") },
                    minLines = 3,
                    supportingText = { Text("${note.length}/500 · stored only on this device") }
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(name, type, note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PortScanDialog(
    onCommon: () -> Unit,
    onCustom: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var start by rememberSaveable { mutableStateOf("1") }
    var end by rememberSaveable { mutableStateOf("1024") }
    val startPort = start.toIntOrNull()
    val endPort = end.toIntOrNull()
    val valid = startPort != null && endPort != null && startPort in 1..65535 &&
        endPort in startPort..65535 && endPort - startPort + 1 <= 1024
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find open TCP ports") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The common scan checks ${COMMON_TCP_PORTS.size} useful device-service ports. It runs only against this selected device.")
                Button(onClick = onCommon, modifier = Modifier.fillMaxWidth()) { Text("Scan common services") }
                HorizontalDivider()
                Text("Custom range · maximum 1,024 ports", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start,
                        onValueChange = { start = it.filter(Char::isDigit).take(5) },
                        label = { Text("Start") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = end,
                        onValueChange = { end = it.filter(Char::isDigit).take(5) },
                        label = { Text("End") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = { onCustom(checkNotNull(startPort), checkNotNull(endPort)) },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan custom range") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ManualDiagnosticsScreen(
    modifier: Modifier,
    state: NetworkToolsUiState,
    viewModel: NetworkToolsViewModel
) {
    var destination by rememberSaveable { mutableStateOf("1.1.1.1") }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Manual diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Run a diagnostic for any host or IP over the current Wi-Fi connection.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it.take(253) },
            label = { Text("Destination (host or IP)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.runManualPing(destination) },
                enabled = !state.diagnostic.isRunning && !state.isScanning,
                modifier = Modifier.weight(1f)
            ) { Text("Ping") }
            Button(
                onClick = { viewModel.runManualTraceroute(destination) },
                enabled = !state.diagnostic.isRunning && !state.isScanning,
                modifier = Modifier.weight(1f)
            ) { Text("Traceroute") }
        }
        if (state.diagnostic.title != null || state.diagnostic.output != null) {
            DiagnosticResultCard(state.diagnostic, viewModel::cancelDiagnostic)
        }
        state.connection?.let { connection ->
            DetailCard("Current Wi-Fi") {
                DeviceDetailRow("Name", connection.ssid)
                DeviceDetailRow("IPv4", connection.ipv4Addresses.ifEmpty { listOf("Unavailable") }.joinToString())
                DeviceDetailRow("Subnet", connection.subnet ?: "Unavailable")
                DeviceDetailRow("Gateway", connection.gateway ?: "Unavailable")
                DeviceDetailRow("DNS", connection.dnsServers.ifEmpty { listOf("Unavailable") }.joinToString())
            }
        }
    }
}

private fun deviceIcon(type: LanDeviceType): ImageVector = when (type) {
    LanDeviceType.ROUTER -> Icons.Default.Router
    LanDeviceType.COMPUTER -> Icons.Default.Computer
    LanDeviceType.PHONE_TABLET -> Icons.Default.PhoneAndroid
    LanDeviceType.TV_STREAMER -> Icons.Default.Tv
    LanDeviceType.PRINTER -> Icons.Default.Print
    LanDeviceType.CAMERA -> Icons.Default.Videocam
    LanDeviceType.SMART_HOME -> Icons.Default.Lightbulb
    LanDeviceType.STORAGE -> Icons.Default.Storage
    LanDeviceType.GAME_CONSOLE -> Icons.Default.Gamepad
    LanDeviceType.OTHER -> Icons.Default.DevicesOther
    LanDeviceType.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
}

private fun LanDeviceEventType.eventLabel(): String = when (this) {
    LanDeviceEventType.DISCOVERED -> "Discovered"
    LanDeviceEventType.ONLINE -> "Came online"
    LanDeviceEventType.OFFLINE -> "Went offline"
    LanDeviceEventType.IP_CHANGED -> "IP changed"
    LanDeviceEventType.USER_EDITED -> "Details edited"
    LanDeviceEventType.PORTS_UPDATED -> "Port scan"
}

private fun String?.safeWebUrlFor(expectedIp: String): String? = runCatching {
    val value = this ?: return@runCatching null
    val uri = URI(value)
    value.takeIf { uri.host == expectedIp && (uri.scheme == "http" || uri.scheme == "https") }
}.getOrNull()

private fun formatTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun formatRelativeTime(epochMs: Long): String {
    val difference = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
    return when {
        difference < 60_000 -> "just now"
        difference < 3_600_000 -> "${difference / 60_000} min ago"
        difference < 86_400_000 -> "${difference / 3_600_000} hr ago"
        difference < 7 * 86_400_000 -> "${difference / 86_400_000} days ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

private val ONLINE_GREEN = Color(0xFF20A464)
