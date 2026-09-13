package com.example.healthmonitor

import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.WifiOff

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

import androidx.core.content.ContextCompat

import com.google.android.gms.location.*

// ── App State ─────────────────────────────────────────────────────
object AppState {
    var operatorName     = mutableStateOf("GHOST-6")
    var criticalCount    = mutableStateOf(1)
    var alertCount       = mutableStateOf(0)
    var connectionStatus = mutableStateOf("OFFLINE")   // "CONNECTING" | "LIVE" | "OFFLINE" — set by WebSocketManager
}

val currentScreen =

    mutableStateOf(
        "Dashboard"
    )
class MainActivity : ComponentActivity() {

    // ── WebView reference ────────────────────────────────────────
    private var webViewRef: WebView? = null

    // ── GPS ──────────────────────────────────────────────────────
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // ── Permission launcher ──────────────────────────────────────
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (granted) startLocationUpdates()
            // BLE permission results are read on-demand by BleSuitScanner
            // when PairNewSuitScreen starts a scan — nothing further to
            // do here, just make sure they were included in the request
            // below so the OS prompt actually shows them.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        `NotificationHelper`.createChannel(this)

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())

        setContent {
            AppRoot(
                onWebViewReady = { wv -> webViewRef = wv }
            )
        }
    }

    // ── Start GPS updates ────────────────────────────────────────
    private fun startLocationUpdates() {

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L
        ).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location: Location =
                    result.lastLocation ?: return

                val lat = location.latitude
                val lng = location.longitude
                // Records the real device location; the map composables
                // use this to center the view on load instead of a
                // hardcoded city — see BattlefieldMap/LiveMapScreen.
                runOnUiThread {
                    LiveMapState.deviceLocation.value = Pair(lat, lng)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    // ── Stop GPS + WebSocket when app closes ─────────────────────
    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        WebSocketManager.disconnect()
        BleSuitScanner.stopScan(this)
    }
}


// ── App Root — login gate ─────────────────────────────────────────
@Composable
fun AppRoot(onWebViewReady: (WebView) -> Unit) {
    val context = LocalContext.current
    var isLoggedIn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ApiService.init(context)
        if (TokenStorage.hasToken(context)) {
            val token = TokenStorage.getToken(context)
            if (!token.isNullOrBlank()) {
                ApiService.setToken(token)
                TokenStorage.getOperatorName(context)?.let {
                    AppState.operatorName.value = it
                }
                isLoggedIn = true
                WebSocketManager.connect(context)
            }
        }
    }

    if (isLoggedIn) {
        Dashboard(onWebViewReady = onWebViewReady)
    } else {
        LoginScreen(onLoginSuccess = {
            isLoggedIn = true
            WebSocketManager.connect(context)
        })
    }
}


// ── Dashboard ─────────────────────────────────────────────────────

@Composable
fun Dashboard(
    onWebViewReady:(WebView)->Unit
){
    val context = LocalContext.current

    LaunchedEffect(SoldierState.soldiers.toList()) {
        SoldierState.soldiers.forEach { soldier ->
            AlertState.evaluateRules(soldier) { alert ->
                `NotificationHelper`.sendAlertNotification(context, alert)
            }
        }
    }
    val drawerState =
        rememberDrawerState(
            DrawerValue.Closed
        )

    val scope =
        rememberCoroutineScope()



    ModalNavigationDrawer(

        drawerState =
            drawerState,

        drawerContent = {

            ModalDrawerSheet(

                modifier =
                    Modifier.width(
                        280.dp
                    ),

                drawerContainerColor =
                    Color(
                        0xFF041124
                    )

            ){

                SidePanel()

            }

        }

    ){

        Column(

            modifier =
                Modifier
                    .fillMaxSize()

                    .background(
                        Color(
                            0xFF07111F
                        )
                    )

        ){

            TopBar(

                onEditOperator = {}

            )



            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()



            ){

                Column(

                    modifier =
                        Modifier.padding(
                            12.dp
                        )

                ){

                    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        when(currentScreen.value){
                            "Alerts" -> {
                                AlertsScreen()
                            }
                            "Soldiers" -> {
                                SoldiersScreen()
                            }
                            "Dashboard" -> {
                                Column(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .verticalScroll(rememberScrollState())
                                ){
                                    StatusSummaryBar()
                                    Spacer(Modifier.height(12.dp))
                                    BattlefieldMap(onWebViewReady)
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ){
                                        Box(Modifier.weight(1.6f)) { PriorityCasualtiesPanel() }
                                        Box(Modifier.weight(1f)) { RecentAlertsPanel() }
                                    }
                                }
                            }
                            "Live Map" -> {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    LiveMapScreen()
                                }
                            }
                            "Configure Suit" -> {
                                ConfigureSuitScreen()
                            }
                            "Casualty Queue" -> {
                                CasualtyQueueScreen()
                            }
                            "Pair New Suit" -> {
                                PairNewSuitScreen()
                            }
                            "Medical Records" -> {
                                MedicalRecordsScreen()
                            }
                            else -> {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Coming soon", color = Color(0xFF6B7F99))
                                }
                            }
                        }
                    }
                }

            }

        }

    }

}



// ── Top Bar ───────────────────────────────────────────────────────
@Composable
fun TopBar(onEditOperator: () -> Unit) {

    var time by remember { mutableStateOf("") }

    val criticalCount    by AppState.criticalCount
    val alertCount       by AppState.alertCount
    val operatorName     by AppState.operatorName
    val connectionStatus by AppState.connectionStatus

    // Live clock
    LaunchedEffect(Unit) {
        while (true) {
            val cal = java.util.Calendar.getInstance()
            time = String.format(
                "%02d:%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND)
            )
            kotlinx.coroutines.delay(1000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF081B33))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {

        // ── Left: Title ───────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TRIAGE AI",
                color = Color(0xFF6B7F99),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = "  /  ",
                color = Color(0xFF6B7F99),
                fontSize = 13.sp
            )
            Text(
                text = currentScreen.value,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // ── Right: Status indicators ──────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // Connection badge — reflects real WebSocket state
            val connColor = when (connectionStatus) {
                "LIVE"       -> Color(0xFF00E676)
                "CONNECTING" -> Color(0xFFFFD600)
                else         -> Color(0xFFFF1744)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(connColor, CircleShape)
                )
                Text(
                    text = connectionStatus,
                    color = connColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF1A3A5C)))

            // CRITICAL badge — real time
            Row(
                modifier = Modifier
                    .background(
                        color = if (criticalCount > 0)
                            Color(0xFFFF1744).copy(alpha = 0.15f)
                        else
                            Color(0xFF1A3A5C),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (criticalCount > 0) Color(0xFFFF1744)
                            else Color(0xFF6B7F99),
                            CircleShape
                        )
                )
                Text(
                    text = "$criticalCount CRITICAL",
                    color = if (criticalCount > 0) Color(0xFFFF1744)
                    else Color(0xFF6B7F99),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF1A3A5C)))

            // Bell with real time badge
            Box(
                contentAlignment =
                    Alignment.TopEnd
            ){

                Icon(

                    imageVector =
                        Icons.Default.Notifications,

                    contentDescription =
                        "Alerts",

                    tint =
                        Color(0xFF6B7F99),

                    modifier =
                        Modifier.size(20.dp)

                )

                if(
                    alertCount > 0
                ){

                    Box(

                        modifier =
                            Modifier
                                .size(14.dp)
                                .background(
                                    Color(0xFFFF1744),
                                    CircleShape
                                )
                                .offset(
                                    x = 4.dp,
                                    y = (-4).dp
                                ),

                        contentAlignment =
                            Alignment.Center

                    ){

                        Text(

                            text =
                                "$alertCount",

                            color =
                                Color.White,

                            fontSize =
                                18.sp,

                            fontWeight =
                                FontWeight.ExtraBold

                        )

                    }

                }

            }

            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF1A3A5C)))

            // Clock
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "Time",
                    tint = Color(0xFF6B7F99),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = time,
                    color = Color(0xFF6B7F99),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(modifier = Modifier.width(1.dp).height(20.dp).background(Color(0xFF1A3A5C)))

            // Operator name — tappable to edit
            Row(
                modifier = Modifier
                    .background(
                        Color(0xFF0D2137),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { onEditOperator() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "OPR · $operatorName",
                    color = Color(0xFF6B7F99),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = Color(0xFF6B7F99),
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

// ⚠ REMOVED — TopHeader() used to live here. It was a completely unused
// composable with hardcoded fake values (🔔 2, 17:59:42, "OPR · GHOST-6",
// "• 1 CRITICAL"). Nothing in the codebase ever called it — Dashboard()
// uses TopBar() above instead, which reflects real state. Deleted as
// dead code; no behavior changes since it was never rendered.

// ── Battlefield Map ───────────────────────────────────────────────
@Composable
fun BattlefieldMap(onWebViewReady: (WebView) -> Unit) {
    var localWebView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            if (LiveMapState.activeMapWebView.value === localWebView) {
                LiveMapState.activeMapWebView.value = null
            }
        }
    }

    var hasCentered by remember { mutableStateOf(false) }
    LaunchedEffect(LiveMapState.deviceLocation.value, localWebView) {
        val (lat, lng) = LiveMapState.deviceLocation.value ?: return@LaunchedEffect
        val webView = localWebView ?: return@LaunchedEffect
        if (!hasCentered) {
            webView.evaluateJavascript("centerMap($lat, $lng, 15);", null)
            hasCentered = true
        }
    }

    LaunchedEffect(LiveMapState.pendingMapUpdate.value, localWebView) {
        val update = LiveMapState.pendingMapUpdate.value ?: return@LaunchedEffect
        val webView = localWebView ?: return@LaunchedEffect
        val soldierName = SoldierState.soldiers.find { it.id == update.soldierId }
            ?.let { "${it.rankTitle} ${it.name}" } ?: update.soldierId
        val escapedName = soldierName.replace("'", "\\'")
        webView.evaluateJavascript(
            "upsertMarker('${update.soldierId}', '$escapedName', '${update.status}', ${update.lat}, ${update.lng});",
            null
        )
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode =
                    android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                webChromeClient = android.webkit.WebChromeClient()
                webViewClient = WebViewClient()

                loadUrl("file:///android_asset/map.html")

                localWebView = this
                LiveMapState.activeMapWebView.value = this
                onWebViewReady(this)
            }
        }
    )
}

// ── Status Summary Bar ────────────────────────────────────────────
@Composable
fun StatusSummaryBar() {
    val soldiers = SoldierState.soldiers

    val stableCount   = soldiers.count { it.status == "stable" }
    val seriousCount  = soldiers.count { it.status == "serious" }
    val criticalCount = soldiers.count { it.status == "critical" }
    val offlineCount  = soldiers.count { it.status == "offline" }
    val activeCount   = soldiers.size - offlineCount
    val total         = soldiers.size.coerceAtLeast(1)

    val items = listOf(
        StatusSummaryItem("ACTIVE",   activeCount,   Color(0xFF00C2FF), Icons.Default.Group,               total),
        StatusSummaryItem("STABLE",   stableCount,   Color(0xFF00FF88), Icons.AutoMirrored.Filled.TrendingUp, total),
        StatusSummaryItem("SERIOUS",  seriousCount,  Color(0xFFFFC533), Icons.AutoMirrored.Filled.ShowChart,  total),
        StatusSummaryItem("CRITICAL", criticalCount, Color(0xFFFF445A), Icons.Default.Favorite,             total),
        StatusSummaryItem("OFFLINE",  offlineCount,  Color(0xFF6B7F99), Icons.Default.WifiOff,               total),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            Box(modifier = Modifier.weight(1f)) {
                StatusSummaryCard(item)
            }
        }
    }
}

// ── Single Status Card ────────────────────────────────────────────
@Composable
fun StatusSummaryCard(item: StatusSummaryItem) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF081B33)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.label,
                    color = Color(0xFF6B7F99),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = item.color.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = item.color,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = "${item.count}",
                color = item.color,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 36.sp
            )

            val fraction = (item.count.toFloat() / item.total.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(
                        color = item.color.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(1.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(
                            color = item.color,
                            shape = RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}


// ── Priority Casualties Panel ─────────────────────────────────────
@Composable
fun PriorityCasualtiesPanel(){

    val priorityOrder = mapOf("critical" to 0, "serious" to 1, "offline" to 2, "stable" to 3)
    val casualties = SoldierState.soldiers
        .filter { it.status != "stable" }
        .sortedBy { priorityOrder[it.status] ?: 4 }
        .take(6)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF081B33)),
        shape = RoundedCornerShape(12.dp)
    ){
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)){

            Text(
                text = "PRIORITY CASUALTIES",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(16.dp))

            if (casualties.isEmpty()) {
                Text(
                    text = "No active casualties",
                    color = Color(0xFF6B7F99),
                    fontSize = 13.sp
                )
            } else {
                casualties.forEach { soldier ->
                    SoldierPriorityRow(soldier)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}


// ── Single Soldier Priority Row ────────────────────────────────────
@Composable
fun SoldierPriorityRow(soldier: Soldier) {

    val dotColor = statusColor(soldier.status)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color = dotColor, shape = CircleShape)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${soldier.rankTitle} ${soldier.name}",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${soldier.squad} · ${soldier.role}",
                color = Color(0xFF6B7F99),
                fontSize = 12.sp
            )
        }

        StatusPill(soldier.status)
    }
}


// ── Recent Alerts Panel ───────────────────────────────────────────
@Composable
fun RecentAlertsPanel() {

    val alerts = AlertState.alerts.take(6)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF081B33)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "RECENT ALERTS",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(16.dp))

            if (alerts.isEmpty()) {
                Text(
                    text = "No recent alerts",
                    color = Color(0xFF6B7F99),
                    fontSize = 13.sp
                )
            } else {
                alerts.forEach { alert ->
                    AppAlertRow(alert)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}


// ── Single Alert Row (renders a real AppAlert from AlertState) ────
@Composable
fun AppAlertRow(item: AppAlert) {

    val dotColor = when (item.severity) {
        "critical" -> Color(0xFFFF1744)
        "serious", "warning" -> Color(0xFFFFD600)
        else       -> Color(0xFF00E676)
    }

    val titleColor = when (item.severity) {
        "critical" -> Color(0xFFFF5252)
        "serious", "warning" -> Color(0xFFFFD600)
        else       -> Color(0xFF00E676)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = dotColor,
                    shape = CircleShape
                )
        )

        Spacer(Modifier.width(10.dp))

        Column {
            Text(
                text = item.title,
                color = titleColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${item.soldierName} (${item.soldierSerial})",
                color = Color(0xFF6B7F99),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun SidePanel(){

    Column(

        modifier =
            Modifier
                .fillMaxHeight()
                .background(
                    Color(
                        0xFF041124
                    )
                )
                .padding(
                    16.dp
                )

    ){

        Text(

            "TRIAGE AI",

            color =
                Color(
                    0xFF00FF88
                ),

            fontSize =
                26.sp

        )

        Text(

            "COMMAND CENTER",

            color =
                Color(
                    0xFF6B7F99
                )

        )

        Spacer(
            Modifier.height(
                30.dp
            )
        )



        listOf(

            "Dashboard",

            "Live Map",

            "Soldiers",

            "Alerts",

            "Casualty Queue",

            "Configure Suit",

            "Pair New Suit",

            "Medical Records",

            "AI Analytics",

            "Reports",

            "Settings"

        )

            .forEach{

                    item ->

                Text(

                    text =
                        item,

                    color =
                        Color.White,

                    fontSize =
                        18.sp,

                    modifier =
                        Modifier
                            .fillMaxWidth()

                            .clickable {

                                currentScreen.value =
                                    item

                            }

                            .padding(
                                16.dp
                            )

                )

            }

    }

}



// ── Data Classes ──────────────────────────────────────────────────
data class StatusSummaryItem(
    val label: String,
    val count: Int,
    val color: Color,
    val icon: ImageVector,
    val total: Int = 1
)