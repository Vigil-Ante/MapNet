package com.mapnet

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.mapnet.connection.WifiConnectionRequester
import com.mapnet.connection.canConnectWithMapNet
import com.mapnet.connection.needsPassphrase
import com.mapnet.data.AccessPointEntity
import com.mapnet.data.MapNetDatabase
import com.mapnet.data.ObservationEntity
import com.mapnet.data.WifiSurveyRepository
import com.mapnet.security.WifiSecurityType
import com.mapnet.survey.SecurityFilter
import com.mapnet.survey.SecuritySummary
import com.mapnet.survey.SurveyViewModel
import com.mapnet.tools.NetworkToolsUiState
import com.mapnet.tools.NetworkToolsViewModel
import com.mapnet.update.UpdateUiState
import com.mapnet.update.UpdateViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val database by lazy {
        Room.databaseBuilder(applicationContext, MapNetDatabase::class.java, "mapnet.db").build()
    }
    private val viewModel: SurveyViewModel by viewModels {
        SurveyViewModel.Factory(WifiSurveyRepository(applicationContext, database))
    }
    private val updateViewModel: UpdateViewModel by viewModels()
    private val networkToolsViewModel: NetworkToolsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MapNetApp(viewModel, updateViewModel, networkToolsViewModel) }
    }
}

private enum class MapNetScreen(val title: String) { SURVEY("Survey"), MAP("Map"), TOOLS("Tools") }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MapNetApp(
    viewModel: SurveyViewModel,
    updateViewModel: UpdateViewModel,
    networkToolsViewModel: NetworkToolsViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val filteredAccessPoints by viewModel.filteredAccessPoints.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val selectedAp by viewModel.selectedAccessPoint.collectAsStateWithLifecycle()
    val history by viewModel.selectedHistory.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val networkToolsState by networkToolsViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var connectionStatus by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var screen = remember { androidx.compose.runtime.mutableStateOf(MapNetScreen.SURVEY) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            viewModel.performScan { context.currentSurveyLocation() }
        } else {
            viewModel.clearStatus()
        }
    }

    LaunchedEffect(status) {
        status?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatus()
        }
    }

    LaunchedEffect(updateState) {
        val message = when (val state = updateState) {
            UpdateUiState.NoUpdate -> "MapNet is up to date."
            UpdateUiState.NotConfigured -> "Updates are not configured in this build yet."
            is UpdateUiState.Error -> state.message
            else -> null
        }
        message?.let {
            snackbarHostState.showSnackbar(it)
            updateViewModel.clearTransientStatus()
        }
    }

    LaunchedEffect(connectionStatus) {
        connectionStatus?.let {
            snackbarHostState.showSnackbar(it)
            connectionStatus = null
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("MapNet") },
                    actions = { UpdateAction(updateState, updateViewModel::checkForUpdate) }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    MapNetScreen.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = screen.value == destination,
                            onClick = { screen.value = destination },
                            icon = {
                                Text(
                                    when (destination) {
                                        MapNetScreen.SURVEY -> "⌁"
                                        MapNetScreen.MAP -> "⌖"
                                        MapNetScreen.TOOLS -> "⌘"
                                    }
                                )
                            },
                            label = { Text(destination.title) }
                        )
                    }
                }
            }
        ) { padding ->
            when (screen.value) {
                MapNetScreen.SURVEY -> SurveyScreen(
                    modifier = Modifier.padding(padding),
                    filter = filter,
                    summary = summary,
                    accessPoints = filteredAccessPoints,
                    isScanning = isScanning,
                    onFilter = viewModel::selectFilter,
                    onOpenOnly = { viewModel.selectFilter(SecurityFilter.OPEN) },
                    onScan = {
                        if (context.hasSurveyPermission()) viewModel.performScan { context.currentSurveyLocation() }
                        else permissionLauncher.launch(context.surveyPermissions())
                    },
                    onDetails = viewModel::showDetails
                )
                MapNetScreen.MAP -> MapScreen(
                    modifier = Modifier.padding(padding),
                    filter = filter,
                    accessPoints = filteredAccessPoints,
                    onFilter = viewModel::selectFilter,
                    onDetails = viewModel::showDetails
                )
                MapNetScreen.TOOLS -> NetworkToolsScreen(
                    modifier = Modifier.padding(padding),
                    state = networkToolsState,
                    onRefresh = networkToolsViewModel::refresh,
                    onPing = networkToolsViewModel::ping,
                    onTraceroute = networkToolsViewModel::traceroute
                )
            }
        }
        selectedAp?.let { ap ->
            AccessPointDetailDialog(
                accessPoint = ap,
                history = history,
                hasSecurityChange = history.any { it.securityType != ap.securityType },
                onConnect = { passphrase ->
                    connectionStatus = WifiConnectionRequester(context).requestConnection(ap, passphrase)
                },
                onDismiss = viewModel::dismissDetails
            )
        }
        UpdateDialog(
            state = updateState,
            onDownload = updateViewModel::download,
            onInstall = updateViewModel::requestInstall,
            onDismiss = updateViewModel::dismissDialog
        )
    }
}

@Composable
private fun NetworkToolsScreen(
    modifier: Modifier,
    state: NetworkToolsUiState,
    onRefresh: () -> Unit,
    onPing: (String) -> Unit,
    onTraceroute: (String) -> Unit
) {
    var destination by rememberSaveable { androidx.compose.runtime.mutableStateOf("1.1.1.1") }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Network tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Diagnostics run locally over the device's active Wi-Fi connection.", style = MaterialTheme.typography.bodySmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CURRENT CONNECTED AP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                state.connection?.let { connection ->
                    DetailRow("Wi-Fi name", connection.ssid)
                    DetailRow("BSSID", connection.bssid)
                    DetailRow("IPv4", connection.ipv4Addresses.ifEmpty { listOf("Unavailable") }.joinToString())
                    DetailRow("Gateway", connection.gateway ?: "Unavailable")
                    DetailRow("DNS", connection.dnsServers.ifEmpty { listOf("Unavailable") }.joinToString())
                } ?: Text("No active Wi-Fi connection detected. Connect in Android, then tap Refresh.")
            }
        }
        Button(onClick = onRefresh, modifier = Modifier.align(Alignment.End)) { Text("Refresh IP details") }
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Destination (host or IP)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onPing(destination) }, enabled = !state.isRunning, modifier = Modifier.weight(1f)) {
                Text("Ping")
            }
            Button(onClick = { onTraceroute(destination) }, enabled = !state.isRunning, modifier = Modifier.weight(1f)) {
                Text("Traceroute")
            }
        }
        if (state.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Running ${state.outputTitle?.lowercase().orEmpty()}…")
            }
        }
        state.output?.let { output ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.outputTitle ?: "Result", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(output, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun UpdateAction(state: UpdateUiState, onCheck: () -> Unit) {
    val isBusy = state is UpdateUiState.Checking || state is UpdateUiState.Downloading
    TextButton(onClick = onCheck, enabled = !isBusy) {
        if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        val label = when (state) {
            is UpdateUiState.Downloading -> state.progressPercent?.let { "$it%" } ?: "Update"
            else -> "Update"
        }
        Text(label)
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onDownload: (com.mapnet.update.UpdateManifest) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update available") },
            text = { Text("MapNet ${state.manifest.versionName} is ready to download.") },
            confirmButton = { Button(onClick = { onDownload(state.manifest) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } }
        )
        is UpdateUiState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update ready") },
            text = { Text("Android will confirm the MapNet update before it is installed.") },
            confirmButton = { Button(onClick = { onInstall(state.apk) }) { Text("Install update") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
        )
        is UpdateUiState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Allow MapNet installs") },
            text = { Text("Android needs permission to install updates from MapNet. Allow it in Settings, return here, then tap Install update.") },
            confirmButton = { Button(onClick = { onInstall(state.apk) }) { Text("Install update") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
        )
        else -> Unit
    }
}

@Composable
private fun SurveyScreen(
    modifier: Modifier,
    filter: SecurityFilter,
    summary: SecuritySummary,
    accessPoints: List<AccessPointEntity>,
    isScanning: Boolean,
    onFilter: (SecurityFilter) -> Unit,
    onOpenOnly: () -> Unit,
    onScan: () -> Unit,
    onDetails: (String) -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Wi-Fi survey", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Local observations only", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onScan, enabled = !isScanning) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isScanning) "Scanning" else "Scan")
            }
        }
        SecuritySummaryCard(summary, onOpenOnly)
        SecurityFilterBar(filter, onFilter)
        if (accessPoints.isEmpty()) {
            EmptySurveyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(accessPoints, key = { it.bssid }) { ap ->
                    AccessPointCard(ap, onDetails = { onDetails(ap.bssid) })
                }
            }
        }
    }
}

@Composable
private fun SecuritySummaryCard(summary: SecuritySummary, onOpenOnly: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("NETWORKS DETECTED", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryMetric("Networks", summary.total.toString())
                SummaryMetric("Secured", summary.secured.toString())
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable(onClick = onOpenOnly)) {
                    Text("Open", style = MaterialTheme.typography.labelMedium)
                    Text(summary.open.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Text("Tap to filter", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun SecurityFilterBar(filter: SecurityFilter, onFilter: (SecurityFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SecurityFilter.entries.forEach { option ->
            FilterChip(selected = filter == option, onClick = { onFilter(option) }, label = { Text(option.label) })
        }
    }
}

@Composable
private fun EmptySurveyState() = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No access points recorded", style = MaterialTheme.typography.titleMedium)
        Text("Start a scan to build the local survey database.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccessPointCard(ap: AccessPointEntity, onDetails: () -> Unit) {
    val isOpen = ap.securityType == WifiSecurityType.OPEN
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDetails),
        colors = CardDefaults.cardColors(containerColor = if (isOpen) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(ap.ssid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isOpen) OpenBadge()
            }
            Spacer(Modifier.height(6.dp))
            Text(ap.bssid, style = MaterialTheme.typography.bodySmall)
            Text("${ap.signalDbm} dBm  •  ${ap.frequencyMhz / 1000.0} GHz${ap.channel?.let { " • Channel $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            Text(if (isOpen) "No Wi-Fi password required" else ap.securityType.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isOpen) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

@Composable
private fun OpenBadge() {
    Surface(
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shape = RoundedCornerShape(6.dp)
    ) { Text("⚠ OPEN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) }
}

@Composable
private fun MapScreen(
    modifier: Modifier,
    filter: SecurityFilter,
    accessPoints: List<AccessPointEntity>,
    onFilter: (SecurityFilter) -> Unit,
    onDetails: (String) -> Unit
) {
    val located = accessPoints.filter { it.latitude != null && it.longitude != null }
    Column(modifier.fillMaxSize()) {
        Text("Survey map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
        Text("Markers use the same security filter as the survey list.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        SecurityFilterBar(filter, onFilter)
        when {
            located.isNotEmpty() -> SurveyMapCanvas(located)
            accessPoints.isNotEmpty() -> RelativeMarkerCanvas(accessPoints)
            else -> MapLocationNotice()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(accessPoints, key = { it.bssid }) { ap ->
                AssistChip(onClick = { onDetails(ap.bssid) }, label = { Text("Marker  ${ap.ssid}  •  ${ap.securityType.label}") })
            }
        }
    }
}

@Composable
private fun MapLocationNotice() = Box(
    Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center
) { Text("Location data will appear after a permitted survey scan.", modifier = Modifier.padding(16.dp)) }

@Composable
private fun RelativeMarkerCanvas(points: List<AccessPointEntity>) {
    val columns = ceil(kotlin.math.sqrt(points.size.toDouble())).toInt().coerceAtLeast(1)
    Canvas(
        modifier = Modifier.fillMaxWidth().height(210.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        points.forEachIndexed { index, ap ->
            val column = index % columns
            val row = index / columns
            val rows = ceil(points.size / columns.toDouble()).toInt()
            val x = ((column + 1f) / (columns + 1f)) * size.width
            val y = ((row + 1f) / (rows + 1f)) * size.height
            drawMapMarker(Offset(x, y), ap.securityType == WifiSecurityType.OPEN)
        }
    }
    Text(
        "Relative marker layout — rescan with Location enabled to place these on the survey map.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SurveyMapCanvas(points: List<AccessPointEntity>) {
    val latitudes = points.mapNotNull { it.latitude }
    val longitudes = points.mapNotNull { it.longitude }
    val minLat = latitudes.minOrNull() ?: 0.0
    val maxLat = latitudes.maxOrNull() ?: 1.0
    val minLon = longitudes.minOrNull() ?: 0.0
    val maxLon = longitudes.maxOrNull() ?: 1.0
    val latSpan = max(maxLat - minLat, 0.0001)
    val lonSpan = max(maxLon - minLon, 0.0001)
    Canvas(
        modifier = Modifier.fillMaxWidth().height(210.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        val allAtSameSurveyPoint = latSpan == 0.0001 && lonSpan == 0.0001
        points.forEachIndexed { index, ap ->
            val markerCenter = if (allAtSameSurveyPoint) {
                // A phone records every AP in a scan at its own coordinate. Spread
                // co-located BSSIDs around that coordinate instead of hiding them.
                val angle = (2.0 * PI * index / points.size).toFloat()
                val ring = 24f + (index / 10) * 22f
                Offset(size.width / 2 + cos(angle) * ring, size.height / 2 + sin(angle) * ring)
            } else {
                Offset(
                    20f + (((ap.longitude!! - minLon) / lonSpan).toFloat() * (size.width - 40f)),
                    20f + ((1f - ((ap.latitude!! - minLat) / latSpan).toFloat()) * (size.height - 40f))
                )
            }
            drawMapMarker(markerCenter, ap.securityType == WifiSecurityType.OPEN)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMapMarker(
    center: Offset,
    isOpen: Boolean
) {
    val color = if (isOpen) Color(0xFFC62828) else Color(0xFF1769AA)
    drawCircle(color, 10f, center)
    drawCircle(Color.White, 10f, center, style = Stroke(2f))
}

@Composable
private fun AccessPointDetailDialog(
    accessPoint: AccessPointEntity,
    history: List<ObservationEntity>,
    hasSecurityChange: Boolean,
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val supportsDirectConnection = accessPoint.securityType.canConnectWithMapNet() && accessPoint.ssid != "<Hidden SSID>"
    val needsPassphrase = supportsDirectConnection && accessPoint.securityType.needsPassphrase()
    var passphrase by rememberSaveable(accessPoint.bssid) { androidx.compose.runtime.mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConnect(passphrase) }) {
                    Text(if (supportsDirectConnection) "Connect" else "Wi-Fi settings")
                }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        title = { Text(accessPoint.ssid) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("BSSID", accessPoint.bssid)
                DetailRow("Last observed", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(accessPoint.lastSeenEpochMs)))
                Text("SECURITY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                DetailRow("Type", accessPoint.securityType.label)
                DetailRow("Password Required", if (accessPoint.requiresPassword) "Yes" else "No")
                DetailRow("Encryption", if (accessPoint.isEncrypted) "Enabled" else "None")
                DetailRow("Raw Capabilities", accessPoint.securityCapabilities.ifBlank { "(none advertised)" })
                if (hasSecurityChange) Text("Security configuration changed", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Text("${accessPoint.observationCount} observations stored", style = MaterialTheme.typography.bodySmall)
                if (needsPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Wi-Fi password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("The password is used only for this Android connection request and is not stored by MapNet.", style = MaterialTheme.typography.bodySmall)
                } else if (!supportsDirectConnection) {
                    Text("This security type needs Android Wi-Fi settings for its full connection configuration.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Connect sends this access point to Android for approval.", style = MaterialTheme.typography.bodySmall)
                }
                if (history.isNotEmpty()) {
                    Text("OBSERVATIONS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    history.take(3).forEach { observation ->
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(observation.observedAtEpochMs))}  •  ${observation.signalDbm} dBm  •  ${observation.securityType.label}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(max = 130.dp))
    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
}

private fun Context.surveyPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

private fun Context.hasSurveyPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun Context.lastSurveyLocation(): Location? {
    if (!hasSurveyPermission()) return null
    val manager = getSystemService(LocationManager::class.java)
    return runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { manager.getLastKnownLocation(it) }
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private suspend fun Context.currentSurveyLocation(): Location? {
    if (!hasSurveyPermission()) return null
    val freshLocation = withTimeoutOrNull(8_000) { requestSingleSurveyLocation() }
    return freshLocation ?: lastSurveyLocation()
}

@SuppressLint("MissingPermission")
private suspend fun Context.requestSingleSurveyLocation(): Location? = suspendCancellableCoroutine { continuation ->
    val manager = getSystemService(LocationManager::class.java)
    val provider = when {
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        manager.getCurrentLocation(provider, cancellationSignal, ContextCompat.getMainExecutor(this)) { location ->
            if (continuation.isActive) continuation.resume(location)
        }
    } else {
        lateinit var listener: LocationListener
        listener = LocationListener { location ->
            manager.removeUpdates(listener)
            if (continuation.isActive) continuation.resume(location)
        }
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        @Suppress("DEPRECATION")
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
    }
}
