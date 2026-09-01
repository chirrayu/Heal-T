package com.example.healthmonitor

/*
 * BLE receiver for the ESP32 suit beacon (BLE mode / local fallback path).
 *
 * This is intentionally a SEPARATE file from WebSocketManager.kt — nothing
 * in that file is touched. It updates the same shared state objects
 * (SoldierState, AppState, LiveMapState, AlertState) directly, so both
 * the WebSocket path and this BLE path feed the same UI.
 *
 * Important schema note: the firmware's BLE payloads match the backend's
 * VitalsIn / LocationIn REST schemas (soldier_id, hr, spo2, temp, battery,
 * activity_index, respiratory_rate, peak_accel_g, duration_ms,
 * blast_timestamp / latitude, longitude) — NOT the WS push schema
 * (vitals_update / location_update) that WebSocketManager parses. Field
 * sets differ (e.g. no "status" — the backend computes that from
 * thresholds server-side), so this file parses them separately rather
 * than reusing WebSocketManager's private handlers.
 *
 * Requires in AndroidManifest.xml (not added here — add these yourself):
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 * (ACCESS_FINE_LOCATION is only required pre-Android 12 for BLE scanning.)
 *
 * Requires runtime permission requests from an Activity/Fragment before
 * calling BleManager.startScan() — standard Android permission flow,
 * not included here since it depends on your existing permission-handling
 * code.
 *
 * Permission-lint note: every BLE call below (startScan/stopScan, connectGatt,
 * discoverServices, writeCharacteristic, writeDescriptor, disconnect/close)
 * is flagged by Android Studio's lint because it could theoretically throw
 * SecurityException if BLUETOOTH_SCAN/BLUETOOTH_CONNECT aren't granted yet.
 * As noted above, permission requesting is deliberately handled by the
 * caller (Activity/Fragment) before any of these functions run, so each
 * BLE-touching function is annotated with @SuppressLint("MissingPermission")
 * to acknowledge that rather than duplicating permission checks here.
 */

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import android.os.ParcelUuid
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

object BleManager {

    private const val TAG = "BleManager"

    // Must match the UUIDs in the ESP32 firmware exactly.
    private val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val VITALS_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val LOCATION_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val WIFI_CONFIG_CHAR_UUID: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var wifiConfigCharacteristic: BluetoothGattCharacteristic? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Derived from NetworkConfig.WS_URL (e.g. "ws://host:8000/ws/connect"
    // -> "http://host:8000") so no new config constants are introduced.
    private val restBaseUrl: String by lazy {
        NetworkConfig.WS_URL
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
            .substringBefore("/ws")
    }

    // ── Public API ──────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun startScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available/enabled")
            AppState.connectionStatus.value = "BLE_UNAVAILABLE"
            return
        }

        val scanner = adapter.bluetoothLeScanner
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        AppState.connectionStatus.value = "BLE_SCANNING"
        Log.d(TAG, "Starting BLE scan for suit beacon")
        scanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context) {
        if (!scanning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        AppState.connectionStatus.value = "OFFLINE"
    }

    // ── Scanning ────────────────────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found suit beacon: ${result.device.address}")
            scanning = false
            result.device.let { device ->
                bluetoothGatt = device.connectGatt(null, false, gattCallback)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            scanning = false
            AppState.connectionStatus.value = "OFFLINE"
        }
    }

    // ── GATT connection ─────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to suit beacon, discovering services")
                    AppState.connectionStatus.value = "BLE_CONNECTED"
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from suit beacon")
                    AppState.connectionStatus.value = "OFFLINE"
                    gatt.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Suit service not found on device")
                return
            }

            service.getCharacteristic(VITALS_CHAR_UUID)?.let { enableNotify(gatt, it) }
            service.getCharacteristic(LOCATION_CHAR_UUID)?.let { enableNotify(gatt, it) }
            wifiConfigCharacteristic = service.getCharacteristic(WIFI_CONFIG_CHAR_UUID)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = String(characteristic.value)
            Log.d(TAG, "Notify from ${characteristic.uuid}: $payload")

            try {
                val json = JsonParser.parseString(payload).asJsonObject
                when (characteristic.uuid) {
                    VITALS_CHAR_UUID -> handleVitalsPayload(json)
                    LOCATION_CHAR_UUID -> handleLocationPayload(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse BLE payload: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    // ── WiFi provisioning ───────────────────────────────────────
    // True once the suit's BLE service has been discovered and the
    // wifi-config characteristic is ready to accept a write. Gate your
    // "Switch to WiFi" UI on this so the button only appears once BLE
    // is actually connected and ready.
    fun canProvisionWifi(): Boolean = wifiConfigCharacteristic != null

    // Writes Wi-Fi credentials and the deployed backend address to the suit
    // over BLE. On
    // success, the firmware saves the credentials and reboots straight
    // into WiFi mode — which means this BLE connection will drop right
    // after, as expected.
    @SuppressLint("MissingPermission")
    fun sendWifiCredentials(ssid: String, password: String): Boolean {
        val characteristic = wifiConfigCharacteristic
        val gatt = bluetoothGatt
        if (characteristic == null || gatt == null) {
            Log.e(TAG, "Cannot send WiFi credentials, not connected to suit")
            return false
        }

        val payload = JsonObject().apply {
            addProperty("ssid", ssid)
            addProperty("password", password)
            val backend = Uri.parse(NetworkConfig.BASE_URL)
            addProperty("backend_host", backend.host)
            addProperty("backend_scheme", backend.scheme ?: "https")
            addProperty(
                "backend_port",
                if (backend.port != -1) backend.port
                else if (backend.scheme == "https") 443 else 80
            )
        }

        characteristic.value = payload.toString().toByteArray(Charsets.UTF_8)
        val success = gatt.writeCharacteristic(characteristic)
        Log.d(TAG, "WiFi credentials write ${if (success) "sent" else "failed to send"}")
        return success
    }

    // ── Payload handling ────────────────────────────────────────
    // Firmware sends the raw VitalsIn/LocationIn shape (see vitals.py /
    // map_tracking.py). We update local state directly, and also try to
    // forward the same payload to the backend over the phone's own
    // internet connection (e.g. cellular) in case the suit itself has
    // no WiFi path to the backend right now.

    private fun handleVitalsPayload(json: JsonObject) {
        val soldierId = json.get("soldier_id")?.asString ?: return
        val index = SoldierState.soldiers.indexOfFirst { it.id == soldierId }

        if (index != -1) {
            val existing = SoldierState.soldiers[index]
            SoldierState.soldiers[index] = existing.copy(
                hr = json.get("hr")?.takeIf { !it.isJsonNull }?.asInt,
                spo2 = json.get("spo2")?.takeIf { !it.isJsonNull }?.asInt,
                temp = json.get("temp")?.takeIf { !it.isJsonNull }?.asFloat,
                battery = json.get("battery")?.takeIf { !it.isJsonNull }?.asInt ?: existing.battery
            )
        } else {
            Log.w(TAG, "Vitals for unknown soldier_id=$soldierId (no snapshot loaded yet over BLE-only path)")
        }

        forwardToBackend("/vitals/", json)
    }

    private fun handleLocationPayload(json: JsonObject) {
        val soldierId = json.get("soldier_id")?.asString ?: return
        val lat = json.get("latitude")?.takeIf { !it.isJsonNull }?.asDouble ?: return
        val lng = json.get("longitude")?.takeIf { !it.isJsonNull }?.asDouble ?: return

        LiveMapState.pendingMapUpdate.value = MapUpdate(
            soldierId = soldierId,
            lat = lat,
            lng = lng,
            status = SoldierState.soldiers.firstOrNull { it.id == soldierId }?.status ?: "unknown"
        )

        forwardToBackend("/map/location", json)
    }

    // ── Forward to backend over phone's own connection, best-effort ──
    private fun forwardToBackend(path: String, json: JsonObject) {
        scope.launch {
            try {
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(restBaseUrl + path)
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Forward $path failed: HTTP ${response.code}")
                    } else {
                        Log.d(TAG, "Forwarded $path to backend via phone connection")
                    }
                }
            } catch (e: Exception) {
                // No connectivity right now — that's expected in the field.
                // Data still made it into local UI state above; it's just
                // not persisted server-side until connectivity returns.
                // Add a retry queue here if you need guaranteed delivery.
                Log.d(TAG, "Backend unreachable, forwarding skipped: ${e.message}")
            }
        }
    }
}
