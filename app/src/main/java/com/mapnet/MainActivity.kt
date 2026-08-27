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
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.mapnet.connection.WifiConnectionAction
import com.mapnet.connection.WifiConnectionRequester
import com.mapnet.connection.canConnectWithMapNet
import com.mapnet.connection.needsPassphrase
import com.mapnet.data.AccessPointEntity
import com.mapnet.data.MapNetDatabase
import com.mapnet.data.ObservationEntity
import com.mapnet.data.WifiSurveyRepository
import com.mapnet.maps.GoogleMapsSetup
import com.mapnet.maps.googleMapsSetup
import com.mapnet.security.WifiSecurityType
import com.mapnet.survey.SecurityFilter
import com.mapnet.survey.SecuritySummary
import com.mapnet.survey.SurveyLocationCluster
import com.mapnet.survey.SurveyViewModel
import com.mapnet.survey.toSurveyLocationClusters
import com.mapnet.tools.NetworkToolsUiState
import com.mapnet.tools.NetworkToolsViewModel
import com.mapnet.update.UpdateUiState
import com.mapnet.update.UpdateViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val database by lazy {
        Room.databaseBuilder(applicationContext, MapNetDatabase::class.java, "mapnet.db")
            .addMigrations(MapNetDatabase.MIGRATION_1_2)
            .build()
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

private enum class MapNetScreen(val title: String) {
    SURVEY("Survey"),
    MAP("Map"),
    TOOLS("Tools"),
    SETTINGS("Settings")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MapNetApp(
    viewModel: SurveyViewModel,
    updateViewModel: UpdateViewModel,
    networkToolsViewModel: NetworkToolsViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filteredAccessPoints by viewModel.filteredAccessPoints.collectAsStateWithLifecycle()
    val mapObservations by viewModel.mapObservations.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val selectedAp by viewModel.selectedAccessPoint.collectAsStateWithLifecycle()
    val history by viewModel.selectedHistory.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val isContinuousScanning by viewModel.isContinuousScanning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val networkToolsState by networkToolsViewModel.state.collectAsStateWithLifecycle()
    val mapsSetup = remember(context) { context.googleMapsSetup(BuildConfig.MAPS_API_KEY) }
    val snackbarHostState = remember { SnackbarHostState() }
    val connectionRequester = remember(context) { WifiConnectionRequester(context) }
    var connectionStatus by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var screen = remember { androidx.compose.runtime.mutableStateOf(MapNetScreen.SURVEY) }
    var startContinuousAfterPermission by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    DisposableEffect(connectionRequester) {
        onDispose(connectionRequester::cancelRequestedConnection)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (startContinuousAfterPermission) {
                viewModel.startContinuousScan { context.currentSurveyLocation() }
            } else {
                viewModel.performScan { context.currentSurveyLocation() }
            }
        } else {
            viewModel.clearStatus()
        }
        startContinuousAfterPermission = false
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
                                        MapNetScreen.SETTINGS -> "⚙"
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
                    searchQuery = searchQuery,
                    summary = summary,
                    accessPoints = filteredAccessPoints,
                    isScanning = isScanning,
                    isContinuousScanning = isContinuousScanning,
                    onFilter = viewModel::selectFilter,
                    onSearchQuery = viewModel::setSearchQuery,
                    onOpenOnly = { viewModel.selectFilter(SecurityFilter.OPEN) },
                    onScan = {
                        if (context.hasSurveyPermission()) viewModel.performScan { context.currentSurveyLocation() }
                        else permissionLauncher.launch(context.surveyPermissions())
                    },
                    onToggleContinuousScan = {
                        if (isContinuousScanning) {
                            viewModel.stopContinuousScan()
                        } else if (context.hasSurveyPermission()) {
                            viewModel.startContinuousScan { context.currentSurveyLocation() }
                        } else {
                            startContinuousAfterPermission = true
                            permissionLauncher.launch(context.surveyPermissions())
                        }
                    },
                    onDetails = viewModel::showDetails
                )
                MapNetScreen.MAP -> MapScreen(
                    modifier = Modifier.padding(padding),
                    filter = filter,
                    accessPoints = filteredAccessPoints,
                    observations = mapObservations,
                    hasLocationPermission = context.hasSurveyPermission(),
                    mapsSetup = mapsSetup,
                    onFilter = viewModel::selectFilter,
                    onDetails = viewModel::showDetails,
                    onOpenSettings = { screen.value = MapNetScreen.SETTINGS }
                )
                MapNetScreen.TOOLS -> NetworkToolsScreen(
                    modifier = Modifier.padding(padding),
                    state = networkToolsState,
                    onRefresh = networkToolsViewModel::refresh,
                    onPing = networkToolsViewModel::ping,
                    onTraceroute = networkToolsViewModel::traceroute,
                    onMapLocalNetwork = networkToolsViewModel::mapLocalNetwork
                )
                MapNetScreen.SETTINGS -> SettingsScreen(
                    modifier = Modifier.padding(padding),
                    mapsSetup = mapsSetup
                )
            }
        }
        selectedAp?.let { ap ->
            AccessPointDetailDialog(
                accessPoint = ap,
                history = history,
                hasSecurityChange = history.any { it.securityType != ap.securityType },
                onConnect = { passphrase ->
                    when (val action = connectionRequester.requestConnection(ap, passphrase)) {
                        is WifiConnectionAction.RequestNetworkConnection -> connectionStatus =
                            connectionRequester.requestNetworkConnection(
                                accessPoint = action.accessPoint,
                                passphrase = action.passphrase,
                                onStatus = { connectionStatus = it }
                            )
                        is WifiConnectionAction.ShowMessage -> connectionStatus = action.message
                    }
                },
                onDelete = { viewModel.deleteAccessPoint(ap) },
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
    onTraceroute: (String) -> Unit,
    onMapLocalNetwork: () -> Unit
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LOCAL NETWORK MAP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Sends one ping to each private IPv4 address on this Wi-Fi subnet (up to 510 addresses) and combines replies with local ARP entries. It never probes the public internet.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Guest-network isolation and devices that block ping may keep devices out of the results.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onMapLocalNetwork,
                    enabled = !state.isRunning && state.connection != null
                ) { Text("Map local devices") }
                state.networkMapProgress?.let { progress ->
                    Text(
                        "Checking ${progress.completedAddressCount} of ${progress.totalAddressCount} addresses…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        state.networkMap?.let { networkMap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DEVICES ON ${networkMap.subnet}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${networkMap.devices.size} device entries found after checking ${networkMap.scannedAddressCount} addresses.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    networkMap.devices.forEach { device ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            DetailRow("IPv4", device.ipv4Address)
                            device.macAddress?.let { macAddress -> DetailRow("MAC", macAddress) }
                            Text(device.discoveryDetail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
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
private fun SettingsScreen(
    modifier: Modifier,
    mapsSetup: GoogleMapsSetup
) {
    val clipboard = LocalClipboardManager.current
    var apiKey by remember { androidx.compose.runtime.mutableStateOf("") }
    var keyCopied by remember { androidx.compose.runtime.mutableStateOf(false) }
    val configurationLine = apiKey.trim().takeIf { it.isNotEmpty() }?.let { "MAPS_API_KEY=$it" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Google Maps setup is checked here before you collect survey points.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Google Maps status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MapSetupStatus("API key included in this build", mapsSetup.hasApiKey)
                MapSetupStatus("API key present in the Android manifest", mapsSetup.manifestHasApiKey)
                MapSetupStatus("Google Play services available", mapsSetup.playServicesAvailable)
                Text(
                    if (mapsSetup.canStartMap) {
                        "The app can start Google Maps. Google Cloud must still accept the key's project, billing, SDK enablement, and Android restriction."
                    } else {
                        "Google Maps cannot start until every item above is ready."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add an API key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Paste a Google Maps Android key to copy the exact build setting. The key is not saved by MapNet.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it.replace("\n", "").trim()
                        keyCopied = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Google Maps API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Button(
                    enabled = configurationLine != null,
                    onClick = {
                        clipboard.setText(AnnotatedString(configurationLine.orEmpty()))
                        keyCopied = true
                    }
                ) { Text("Copy build setting") }
                if (keyCopied) {
                    Text("Copied. Paste it into secrets.properties, rebuild, then install the new APK.", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "The Maps SDK reads the API key from the app manifest at build time. Changing a field in an already-installed app cannot change that manifest, so a rebuild is required.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Google Cloud checklist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("1. Enable billing and Maps SDK for Android in the Google Cloud project.", style = MaterialTheme.typography.bodySmall)
                Text("2. Restrict the key to Android apps using this package and signing certificate SHA-1.", style = MaterialTheme.typography.bodySmall)
                Text("3. Build and install the APK signed with that certificate.", style = MaterialTheme.typography.bodySmall)
                Text("Android package", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(mapsSetup.applicationId, style = MaterialTheme.typography.bodySmall)
                Text("Signing certificate SHA-1", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (mapsSetup.signingCertificateSha1s.isEmpty()) {
                    Text("Unavailable on this device.", style = MaterialTheme.typography.bodySmall)
                } else {
                    mapsSetup.signingCertificateSha1s.forEach { sha1 ->
                        Text(sha1, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapSetupStatus(label: String, ready: Boolean) {
    Text(
        text = "${if (ready) "✓" else "•"} $label: ${if (ready) "Ready" else "Needs attention"}",
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium
    )
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
    searchQuery: String,
    summary: SecuritySummary,
    accessPoints: List<AccessPointEntity>,
    isScanning: Boolean,
    isContinuousScanning: Boolean,
    onFilter: (SecurityFilter) -> Unit,
    onSearchQuery: (String) -> Unit,
    onOpenOnly: () -> Unit,
    onScan: () -> Unit,
    onToggleContinuousScan: () -> Unit,
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
            Button(onClick = onScan, enabled = !isScanning && !isContinuousScanning) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isScanning) "Scanning" else "Scan")
            }
        }
        Button(
            onClick = onToggleContinuousScan,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Text(if (isContinuousScanning) "Stop continuous scan" else "Continuous scan")
        }
        if (isContinuousScanning) {
            Text(
                "Scanning about every 30 seconds while MapNet is open. If Android throttles a request, MapNet retries every 5 seconds until scanning resumes.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQuery,
            label = { Text("Search Wi-Fi name or BSSID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        SecuritySummaryCard(summary, onOpenOnly)
        SecurityFilterBar(filter, onFilter)
        if (accessPoints.isEmpty()) {
            EmptySurveyState(searchQuery)
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
private fun EmptySurveyState(searchQuery: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (searchQuery.isBlank()) {
            Text("No access points recorded", style = MaterialTheme.typography.titleMedium)
            Text("Start a scan to build the local survey database.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("No networks match your search", style = MaterialTheme.typography.titleMedium)
            Text("Try a different Wi-Fi name or BSSID.", style = MaterialTheme.typography.bodyMedium)
        }
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
    observations: List<ObservationEntity>,
    hasLocationPermission: Boolean,
    mapsSetup: GoogleMapsSetup,
    onFilter: (SecurityFilter) -> Unit,
    onDetails: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val surveyClusters = remember(observations) { observations.toSurveyLocationClusters() }
    Column(modifier.fillMaxSize()) {
        Text("Survey map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
        Text(
            "Pins mark where this phone heard networks — not their transmitter locations.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SecurityFilterBar(filter, onFilter)
        when {
            !mapsSetup.canStartMap -> GoogleMapsSetupNotice(mapsSetup, onOpenSettings)
            surveyClusters.isNotEmpty() -> GoogleSurveyMap(
                clusters = surveyClusters,
                hasLocationPermission = hasLocationPermission
            )
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
private fun GoogleMapsSetupNotice(
    mapsSetup: GoogleMapsSetup,
    onOpenSettings: () -> Unit
) = Box(
    Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            when {
                !mapsSetup.hasApiKey || !mapsSetup.manifestHasApiKey -> "Google Maps needs an API key in this app build."
                !mapsSetup.playServicesAvailable -> "Google Play services are unavailable, so Google Maps cannot start."
                else -> "Google Maps needs attention."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = onOpenSettings) { Text("Open Maps settings") }
    }
}

@Composable
private fun MapLocationNotice() = Box(
    Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center
) {
    Text(
        "No location-tagged surveys match this filter yet. Turn on precise location and run a new scan.",
        modifier = Modifier.padding(16.dp)
    )
}

@Composable
private fun GoogleSurveyMap(
    clusters: List<SurveyLocationCluster>,
    hasLocationPermission: Boolean
) {
    val newestCluster = clusters.first()
    val newestPosition = LatLng(newestCluster.latitude, newestCluster.longitude)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(newestPosition, 17f)
    }
    Column {
        GoogleMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp)),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
            uiSettings = MapUiSettings(
                compassEnabled = true,
                myLocationButtonEnabled = hasLocationPermission,
                zoomControlsEnabled = false
            )
        ) {
            clusters.forEach { cluster ->
                key(cluster.key) {
                    val position = LatLng(cluster.latitude, cluster.longitude)
                    cluster.locationAccuracyMeters?.takeIf { it > 0f }?.let { accuracy ->
                        Circle(
                            center = position,
                            radius = accuracy.toDouble(),
                            fillColor = Color(0x332196F3),
                            strokeColor = Color(0xFF1976D2),
                            strokeWidth = 1f
                        )
                    }
                    Marker(
                        state = rememberUpdatedMarkerState(position = position),
                        title = "Survey: ${cluster.observationCount} AP observations",
                        snippet = cluster.markerSummary()
                    )
                }
            }
        }
        Text(
            "${clusters.size} survey point${if (clusters.size == 1) "" else "s"}. " +
                "Blue circles are the phone location's reported accuracy, not AP range.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

private fun SurveyLocationCluster.markerSummary(): String {
    val accuracy = locationAccuracyMeters?.let { "location ±${it.toInt()} m" } ?: "location accuracy unavailable"
    val open = if (openNetworkCount > 0) " • $openNetworkCount open" else ""
    val provider = locationProvider?.let { " • $it" }.orEmpty()
    return "$bssidCount radios • $ssidCount named networks • avg $averageSignalDbm dBm$open • $accuracy$provider"
}

@Composable
private fun AccessPointDetailDialog(
    accessPoint: AccessPointEntity,
    history: List<ObservationEntity>,
    hasSecurityChange: Boolean,
    onConnect: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val supportsDirectConnection = accessPoint.securityType.canConnectWithMapNet() && accessPoint.ssid != "<Hidden SSID>"
    val needsPassphrase = supportsDirectConnection && accessPoint.securityType.needsPassphrase()
    var passphrase by rememberSaveable(accessPoint.bssid) { androidx.compose.runtime.mutableStateOf("") }
    var showDeleteConfirmation by rememberSaveable(accessPoint.bssid) { androidx.compose.runtime.mutableStateOf(false) }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete ${accessPoint.ssid}?") },
            text = {
                Text(
                    "This removes the visible network and its local observation history. A later scan can add it again."
                )
            },
            confirmButton = {
                Button(onClick = onDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConnect(passphrase) }) {
                Text(if (supportsDirectConnection) "Connect" else "Wi-Fi settings")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showDeleteConfirmation = true }) { Text("Delete") }
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
                    Text("Connect opens Android's approval prompt and connects MapNet without saving this network on the device.", style = MaterialTheme.typography.bodySmall)
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
            .mapNotNull { manager.getLastKnownLocation(it) }
            .filter { it.hasValidSurveyCoordinates() }
            .filter { System.currentTimeMillis() - it.time <= MAX_LAST_KNOWN_LOCATION_AGE_MS }
            .minWithOrNull(
                compareBy<Location> { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
                    .thenByDescending { it.time }
            )
    }.getOrNull()
}

@SuppressLint("MissingPermission")
private suspend fun Context.currentSurveyLocation(): Location? {
    if (!hasSurveyPermission()) return null
    val manager = getSystemService(LocationManager::class.java)
    // Prefer a fresh GPS fix, then use the network provider as a short fallback.
    // The former is normally more useful for a physical survey map.
    val providers = buildList {
        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    for (provider in providers) {
        val timeoutMs = if (provider == LocationManager.GPS_PROVIDER) 6_000L else 2_000L
        val freshLocation = withTimeoutOrNull(timeoutMs) { requestSingleSurveyLocation(provider) }
        if (freshLocation?.hasValidSurveyCoordinates() == true) return freshLocation
    }
    return lastSurveyLocation()
}

@SuppressLint("MissingPermission")
private suspend fun Context.requestSingleSurveyLocation(provider: String): Location? = suspendCancellableCoroutine { continuation ->
    val manager = getSystemService(LocationManager::class.java)

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

private fun Location.hasValidSurveyCoordinates(): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0

private const val MAX_LAST_KNOWN_LOCATION_AGE_MS = 5 * 60 * 1_000L
