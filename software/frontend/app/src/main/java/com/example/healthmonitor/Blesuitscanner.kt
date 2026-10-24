/*Real BLE scanning for suits in BLUETOOTH/fallback mode.
  Filters for the ESP32 firmware's BLE service UUID or suit device name.
*/
package com.example.healthmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import java.util.UUID

// Matches the ESP32 firmware's BLE service UUID
val SUIT_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

data class DiscoveredSuit(
    val suitName: String,   // from BLE advertisement device name
    val address: String,    // BLE MAC address
    val rssi: Int,           // raw signal strength in dBm
    val signalPercent: Int   // 0-100% approximation
)

fun rssiToPercent(rssi: Int): Int {
    val clamped = rssi.coerceIn(-100, -50)
    return (((clamped + 100) / 50.0) * 100).toInt().coerceIn(0, 100)
}

object BleSuitScanner {

    private const val TAG = "BleSuitScanner"

    val discovered = mutableStateListOf<DiscoveredSuit>()
    val isScanning = mutableStateOf(false)
    val permissionError = mutableStateOf<String?>(null)

    private var scanCallback: ScanCallback? = null

    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled == true
    }

    fun hasPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan(context: Context) {
        if (isScanning.value) return
        permissionError.value = null

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            permissionError.value = "Bluetooth is turned off. Please enable Bluetooth."
            return
        }

        if (!hasPermissions(context)) {
            permissionError.value = "Bluetooth permissions are required to scan for devices."
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            permissionError.value = "BLE scanning unavailable on this device"
            return
        }

        discovered.clear()

        // Provide both explicit service UUID and fallback generic filter to prevent
        // hardware-level chipset dropping of 128-bit UUIDs
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SUIT_SERVICE_UUID)).build(),
            ScanFilter.Builder().build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val record = result.scanRecord
                val advName = record?.deviceName
                val devName = try { device.name } catch (e: SecurityException) { null }
                val rawName = (advName ?: devName ?: "").trim()

                val hasSuitService = record?.serviceUuids?.any { it.uuid == SUIT_SERVICE_UUID } == true
                val isSuitName = rawName.contains("Suit", ignoreCase = true) ||
                                 rawName.contains("Beacon", ignoreCase = true) ||
                                 rawName.contains("ESP32", ignoreCase = true) ||
                                 rawName.contains("Heal", ignoreCase = true) ||
                                 rawName.contains("Wearable", ignoreCase = true)

                // Accept if matches service UUID or recognized suit naming pattern
                if (!hasSuitService && !isSuitName) {
                    return
                }

                val displayName = if (rawName.isNotEmpty()) rawName else "Suit Beacon (${device.address.takeLast(5)})"
                val address = device.address
                val rssi = result.rssi

                val entry = DiscoveredSuit(
                    suitName = displayName,
                    address = address,
                    rssi = rssi,
                    signalPercent = rssiToPercent(rssi)
                )

                val existingIndex = discovered.indexOfFirst { it.address == address }
                if (existingIndex != -1) {
                    discovered[existingIndex] = entry
                } else {
                    discovered.add(entry)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
                permissionError.value = "Scan failed (error code $errorCode)"
                isScanning.value = false
            }
        }

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning.value = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLE permission: ${e.message}")
            permissionError.value = "Bluetooth permissions are required."
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan: ${e.message}")
            permissionError.value = "Could not start scan: ${e.localizedMessage}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context) {
        if (!isScanning.value) return
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}")
        }
        isScanning.value = false
    }
}