package com.example.healthmonitor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


object WebSocketManager {

    private const val TAG = "WebSocketManager"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var shouldReconnect = true
    private val scope = CoroutineScope(Dispatchers.IO)

    // ⚠ NEW — tracks the last time we heard ANYTHING back from the server
    // (a pong reply, or a heartbeat). Without this, killing the server
    // abruptly leaves isConnected/AppState stuck on "LIVE" indefinitely,
    // since OkHttp has no way to know the socket died until it tries to
    // use it again.
    private var lastPongAt: Long = 0L

    // How long we tolerate silence before declaring the connection dead.
    // Server pings every 20s, so 45s covers ~2 missed cycles before acting.
    private const val PONG_TIMEOUT_MS = 45_000L


    // ── Connect ───────────────────────────────────────────────────
    fun connect(context: Context) {
        shouldReconnect = true
        AppState.connectionStatus.value = "CONNECTING"
        lastPongAt = System.currentTimeMillis()

        val token = ApiService.getToken() ?: TokenStorage.getToken(context)
        val wsUrl = if (!token.isNullOrBlank()) {
            if (NetworkConfig.WS_URL.contains("?")) "${NetworkConfig.WS_URL}&token=$token"
            else "${NetworkConfig.WS_URL}?token=$token"
        } else {
            NetworkConfig.WS_URL
        }

        val requestBuilder = Request.Builder().url(wsUrl)
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        val request = requestBuilder.build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    lastPongAt = System.currentTimeMillis()
                    AppState.connectionStatus.value = "LIVE"
                    Log.d(TAG, "Connected to backend")

                    // Ping every 20 seconds to keep connection alive, and
                    // verify we're actually still hearing back.
                    scope.launch {
                        while (isConnected) {
                            delay(20_000)
                            if (!isConnected) return@launch

                            // ⚠ NEW — if too long has passed since the last
                            // pong/heartbeat, the connection is dead even
                            // though OkHttp hasn't noticed. Force-close it
                            // so reconnect logic actually kicks in, instead
                            // of the badge silently staying on "LIVE" forever.
                            val silence = System.currentTimeMillis() - lastPongAt
                            if (silence > PONG_TIMEOUT_MS) {
                                Log.w(TAG, "No pong in ${silence}ms — treating connection as dead")
                                isConnected = false
                                AppState.connectionStatus.value = "OFFLINE"
                                webSocket.cancel()
                                if (shouldReconnect) {
                                    delay(2_000)
                                    connect(context)
                                }
                                return@launch
                            }

                            webSocket.send("""{"type":"ping"}""")
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JsonParser.parseString(text).asJsonObject

                        // ⚠ NEW — any message from the server (not just a
                        // pong reply, but also periodic heartbeats) proves
                        // the connection is genuinely still alive.
                        val type = json.get("type")?.asString
                        if (type == "pong" || type == "heartbeat") {
                            lastPongAt = System.currentTimeMillis()
                        }

                        handleMessage(json)
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse error: ${e.message}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    Log.d(TAG, "Closing: $reason")
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    isConnected = false
                    AppState.connectionStatus.value = "OFFLINE"
                    Log.e(TAG, "Connection failed: ${t.message}")

                    // Auto reconnect after 5 seconds
                    if (shouldReconnect) {
                        scope.launch {
                            delay(5_000)
                            Log.d(TAG, "Reconnecting...")
                            connect(context)
                        }
                    }
                }
            }
        )
    }


    // ── Disconnect ────────────────────────────────────────────────
    fun disconnect() {
        shouldReconnect = false
        isConnected = false
        webSocket?.close(1000, "App closed")
        webSocket = null
        AppState.connectionStatus.value = "OFFLINE"
    }


    // ── Route incoming messages ───────────────────────────────────
    private fun handleMessage(json: JsonObject) {
        when (json.get("type")?.asString) {
            "snapshot"        -> handleSnapshot(json)
            "vitals_update"   -> handleVitalsUpdate(json)
            "location_update" -> handleLocationUpdate(json)
            "new_alert"       -> handleNewAlert(json)
            "heartbeat"       -> Log.d(TAG, "Heartbeat")
            "pong"            -> Log.d(TAG, "Pong")
            else              -> Log.d(TAG, "Unknown message type")
        }
    }


    // ── Snapshot — full state sent on first connect ───────────────
    private fun handleSnapshot(json: JsonObject) {
        try {
            val soldiersArray = json.getAsJsonArray("soldiers")

            SoldierState.soldiers.clear()

            soldiersArray.forEach { element ->
                val s = element.asJsonObject

                val soldier = Soldier(
                    id        = s.get("soldier_id").asString,
                    name      = s.get("name").asString,
                    rankTitle = s.get("rank_title")?.let { if (it.isJsonNull) "" else it.asString } ?: "",
                    rankOrder = s.get("rank_order")?.let { if (it.isJsonNull) 1 else it.asInt } ?: 1,
                    serial    = s.get("serial").asString,
                    squad     = s.get("squad")?.let {
                        if (it.isJsonNull) "Unknown" else it.asString
                    } ?: "Unknown",
                    role      = s.get("role")?.let { if (it.isJsonNull) "" else it.asString } ?: "",
                    bloodGroup = s.get("blood_group")?.let { if (it.isJsonNull) "O+" else it.asString } ?: "O+",
                    hr        = s.get("hr")?.let {
                        if (it.isJsonNull) null else it.asInt
                    },
                    spo2      = s.get("spo2")?.let {
                        if (it.isJsonNull) null else it.asInt
                    },
                    temp      = s.get("temp")?.let {
                        if (it.isJsonNull) null else it.asFloat
                    },
                    battery   = s.get("battery")?.let {
                        if (it.isJsonNull) 0 else it.asInt
                    } ?: 0,
                    status    = s.get("status").asString
                )

                SoldierState.soldiers.add(soldier)
            }

            // Update dashboard badge counts
            val counts = json.getAsJsonObject("alert_counts")
            AppState.criticalCount.value = counts.get("critical").asInt
            AppState.alertCount.value    = counts.get("total").asInt

            Log.d(TAG, "Snapshot: ${SoldierState.soldiers.size} soldiers loaded")

        } catch (e: Exception) {
            Log.e(TAG, "Snapshot error: ${e.message}")
        }
    }


    // ── Vitals update — one soldier's readings changed ────────────
    private fun handleVitalsUpdate(json: JsonObject) {
        try {
            val soldierId = json.get("soldier_id").asString
            val index = SoldierState.soldiers.indexOfFirst { it.id == soldierId }

            if (index != -1) {
                val existing = SoldierState.soldiers[index]
                SoldierState.soldiers[index] = existing.copy(
                    hr      = json.get("hr")?.let {
                        if (it.isJsonNull) null else it.asInt
                    },
                    spo2    = json.get("spo2")?.let {
                        if (it.isJsonNull) null else it.asInt
                    },
                    temp    = json.get("temp")?.let {
                        if (it.isJsonNull) null else it.asFloat
                    },
                    battery = json.get("battery")?.let {
                        if (it.isJsonNull) existing.battery else it.asInt
                    } ?: existing.battery,
                    status  = json.get("status").asString
                )
            }

            recalculateSummary()

        } catch (e: Exception) {
            Log.e(TAG, "Vitals update error: ${e.message}")
        }
    }


    // ── Location update — move dot on Leaflet map ─────────────────
    private fun handleLocationUpdate(json: JsonObject) {
        try {
            val soldierId = json.get("soldier_id").asString
            val lat       = json.get("latitude").asDouble
            val lng       = json.get("longitude").asDouble
            val status    = json.get("status").asString

            // Update soldier status in list
            val index = SoldierState.soldiers.indexOfFirst { it.id == soldierId }
            if (index != -1) {
                SoldierState.soldiers[index] =
                    SoldierState.soldiers[index].copy(status = status)
            }

            // Signal the map to move the dot
            LiveMapState.pendingMapUpdate.value = MapUpdate(
                soldierId = soldierId,
                lat       = lat,
                lng       = lng,
                status    = status
            )

        } catch (e: Exception) {
            Log.e(TAG, "Location update error: ${e.message}")
        }
    }


    // ── New alert — add to list + update badge ────────────────────
    private fun handleNewAlert(json: JsonObject) {
        try {
            val alert = AppAlert(
                id             = json.get("alert_id").asString,
                title          = json.get("title").asString,
                severity       = json.get("severity").asString,
                soldierName    = json.get("soldier_name").asString,
                soldierSerial  = json.get("serial").asString,
                message        = json.get("message").asString,
                timestamp      = System.currentTimeMillis(),
                actionRequired = json.get("action_required").asBoolean
            )

            AlertState.alerts.add(0, alert)

            AppState.alertCount.value += 1
            if (alert.severity == "critical") {
                AppState.criticalCount.value += 1
            }

            Log.d(TAG, "New alert: ${alert.title} [${alert.severity}]")

        } catch (e: Exception) {
            Log.e(TAG, "Alert parse error: ${e.message}")
        }
    }


    // ── Recalculate summary bar counts ────────────────────────────
    private fun recalculateSummary() {
        val soldiers = SoldierState.soldiers
        AppState.criticalCount.value = soldiers.count { it.status == "critical" }
        // Keep alert badge in sync with actual stored alerts
        AppState.alertCount.value = AlertState.alerts.size
    }
}


// ── Supporting models ─────────────────────────────────────────────
data class MapUpdate(
    val soldierId: String,
    val lat: Double,
    val lng: Double,
    val status: String
)

object LiveMapState {
    val pendingMapUpdate = mutableStateOf<MapUpdate?>(null)

    // ⚠ NEW — whichever map WebView (BattlefieldMap or LiveMapScreen) is
    // currently on screen registers itself here. This is what lets a
    // single LaunchedEffect push real WebSocket location updates into
    // whichever map the user is actually looking at.
    val activeMapWebView = mutableStateOf<android.webkit.WebView?>(null)

    // ⚠ NEW — the phone's own real GPS position (from MainActivity's
    // FusedLocationProviderClient), used to center the map on load
    // instead of a hardcoded city.
    val deviceLocation = mutableStateOf<Pair<Double, Double>?>(null)
}