package com.example.healthmonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import java.util.concurrent.TimeUnit

object BleManager {

    private const val TAG = "BleManager"

    // Must match the UUIDs in the ESP32 firmware exactly.
    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val VITALS_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val LOCATION_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val WIFI_CONFIG_CHAR_UUID: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val isConnected = mutableStateOf(false)
    val connectedAddress = mutableStateOf<String?>(null)
    val connectedSoldier = mutableStateOf<String?>(null)

    private var wifiConfigCharacteristic: BluetoothGattCharacteristic? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val descriptorWriteQueue: Queue<BluetoothGattDescriptor> = LinkedList()
    private var isWritingDescriptor = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val restBaseUrl: String by lazy {
        NetworkConfig.WS_URL
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
            .substringBefore("/ws")
    }

    // ── Direct connection to a selected device ──────────────────
    @SuppressLint("MissingPermission")
    fun connectDevice(context: Context, address: String, soldierId: String? = null) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available/enabled")
            AppState.connectionStatus.value = "BLE_UNAVAILABLE"
            return
        }

        disconnect()

        try {
            val device = adapter.getRemoteDevice(address)
            connectedSoldier.value = soldierId
            connectedAddress.value = address
            AppState.connectionStatus.value = "CONNECTING"
            Log.d(TAG, "Connecting to GATT device: $address (soldier=$soldierId)")
            bluetoothGatt = device.connectGatt(context.applicationContext, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device $address: ${e.message}")
            AppState.connectionStatus.value = "OFFLINE"
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available/enabled")
            AppState.connectionStatus.value = "BLE_UNAVAILABLE"
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            AppState.connectionStatus.value = "BLE_UNAVAILABLE"
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
            ScanFilter.Builder().build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        AppState.connectionStatus.value = "BLE_SCANNING"
        Log.d(TAG, "Starting BLE scan for suit beacon")
        scanner.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context) {
        if (!scanning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect: ${e.message}")
        } finally {
            bluetoothGatt = null
            wifiConfigCharacteristic = null
            descriptorWriteQueue.clear()
            isWritingDescriptor = false
            isConnected.value = false
            connectedAddress.value = null
            connectedSoldier.value = null
            mainHandler.post {
                AppState.connectionStatus.value = "OFFLINE"
            }
        }
    }

    // ── Scanning callback (fallback auto-connect) ───────────────
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found suit beacon: ${result.device.address}")
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
                    Log.d(TAG, "Connected to suit beacon, requesting MTU 512")
                    mainHandler.post {
                        isConnected.value = true
                        AppState.connectionStatus.value = "BLE_CONNECTED"
                    }
                    // Request high MTU so 150+ byte JSON payloads fit in a single notification
                    val mtuRequested = gatt.requestMtu(512)
                    if (!mtuRequested) {
                        Log.w(TAG, "requestMtu returned false, proceeding to discover services")
                        gatt.discoverServices()
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from suit beacon")
                    mainHandler.post {
                        isConnected.value = false
                        AppState.connectionStatus.value = "OFFLINE"
                    }
                    gatt.close()
                    bluetoothGatt = null
                    wifiConfigCharacteristic = null
                    descriptorWriteQueue.clear()
                    isWritingDescriptor = false
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "BLE MTU changed to $mtu (status: $status)")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Suit service ($SERVICE_UUID) not found on device")
                return
            }

            wifiConfigCharacteristic = service.getCharacteristic(WIFI_CONFIG_CHAR_UUID)

            val vitalsChar = service.getCharacteristic(VITALS_CHAR_UUID)
            val locationChar = service.getCharacteristic(LOCATION_CHAR_UUID)

            descriptorWriteQueue.clear()
            isWritingDescriptor = false

            if (vitalsChar != null) queueEnableNotification(gatt, vitalsChar)
            if (locationChar != null) queueEnableNotification(gatt, locationChar)

            processNextDescriptorWrite(gatt)
        }

        // Modern Android 13+ (API 33+) callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingPayload(characteristic.uuid, value)
        }

        // Legacy pre-Android 13 callback
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleIncomingPayload(characteristic.uuid, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor write completed: ${descriptor.uuid}, status: $status")
            isWritingDescriptor = false
            processNextDescriptorWrite(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun queueEnableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptorWriteQueue.offer(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextDescriptorWrite(gatt: BluetoothGatt) {
        if (isWritingDescriptor) return
        val nextDescriptor = descriptorWriteQueue.poll() ?: return

        isWritingDescriptor = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(nextDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            nextDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(nextDescriptor)
        }
    }

    private fun handleIncomingPayload(charUuid: UUID, data: ByteArray) {
        val payload = String(data, Charsets.UTF_8).trim()
        Log.d(TAG, "BLE Notify from $charUuid (${data.size} bytes): $payload")

        if (payload.isEmpty()) return

        try {
            val json = JsonParser.parseString(payload).asJsonObject
            when (charUuid) {
                VITALS_CHAR_UUID -> handleVitalsPayload(json)
                LOCATION_CHAR_UUID -> handleLocationPayload(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse BLE payload ($payload): ${e.message}")
        }
    }

    fun canProvisionWifi(): Boolean = wifiConfigCharacteristic != null

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
            connectedSoldier.value?.let {
                addProperty("soldier_id", it)
            }
        }

        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val success: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            code == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        Log.d(TAG, "WiFi credentials write ${if (success) "sent" else "failed to send"}")
        return success
    }

    private fun handleVitalsPayload(json: JsonObject) {
        val parsedId = json.get("soldier_id")?.takeIf { !it.isJsonNull }?.asString
        val soldierId = parsedId ?: connectedSoldier.value ?: return

        mainHandler.post {
            val index = SoldierState.soldiers.indexOfFirst { it.id == soldierId }
            if (index != -1) {
                val existing = SoldierState.soldiers[index]
                SoldierState.soldiers[index] = existing.copy(
                    hr = json.get("hr")?.takeIf { !it.isJsonNull }?.asInt ?: existing.hr,
                    spo2 = json.get("spo2")?.takeIf { !it.isJsonNull }?.asInt ?: existing.spo2,
                    temp = json.get("temp")?.takeIf { !it.isJsonNull }?.asFloat ?: existing.temp,
                    battery = json.get("battery")?.takeIf { !it.isJsonNull }?.asInt ?: existing.battery
                )
            } else {
                Log.w(TAG, "Vitals for unknown soldier_id=$soldierId")
            }
        }

        forwardToBackend("/vitals/", json)
    }

    private fun handleLocationPayload(json: JsonObject) {
        val parsedId = json.get("soldier_id")?.takeIf { !it.isJsonNull }?.asString
        val soldierId = parsedId ?: connectedSoldier.value ?: return
        val lat = json.get("latitude")?.takeIf { !it.isJsonNull }?.asDouble ?: return
        val lng = json.get("longitude")?.takeIf { !it.isJsonNull }?.asDouble ?: return

        mainHandler.post {
            LiveMapState.pendingMapUpdate.value = MapUpdate(
                soldierId = soldierId,
                lat = lat,
                lng = lng,
                status = SoldierState.soldiers.firstOrNull { it.id == soldierId }?.status ?: "unknown"
            )
        }

        forwardToBackend("/map/location", json)
    }

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
                Log.d(TAG, "Backend unreachable, forwarding skipped: ${e.message}")
            }
        }
    }
}
