/*Pair New Suit — real BLE scan/pair flow. Previously this used a static
  mock scan list; it now uses BleSuitScanner, which does a real
  BluetoothLeScanner scan filtered to the ESP32 firmware's BLE service
  UUID (see BleSuitScanner.kt / suit_firmware.py's BLEVitalsServer).

  ⚠ Picking a suit and confirming creates a SuitConfig entry for the
  chosen soldier (via SuitConfigState, the same store ConfigureSuitScreen
  reads/writes) so a freshly-paired suit immediately shows up as
  "configured" there.

  ⚠ KNOWN LIMITATION — which soldier is paired to which physical suit
  (pairedSuits below) is still only tracked locally, for this app
  session. There's no PairedSuit concept in database.py yet, so this
  resets if the app restarts. Worth adding a real backend table/endpoint
  if suit-to-soldier pairing needs to persist and sync across devices.*/
package com.example.healthmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun PairNewSuitScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // soldierId -> suit address it's paired to (local-only pairing
    // registry — see the file-level note above about persistence).
    val pairedSuits = remember { mutableStateMapOf<String, String>() }

    // ⚠ CHANGED — these now come from BleSuitScanner's real, live state
    // instead of a local mock list.
    val isScanning by BleSuitScanner.isScanning
    val scanResults = BleSuitScanner.discovered.filterNot { it.address in pairedSuits.values }
    val permissionError by BleSuitScanner.permissionError

    var selectedSuit by remember { mutableStateOf<DiscoveredSuit?>(null) }
    var selectedSoldierId by remember { mutableStateOf<String?>(null) }
    var justPaired by remember { mutableStateOf<Pair<Soldier, DiscoveredSuit>?>(null) }
    var isPairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }

    fun startScan() {
        selectedSuit = null
        BleSuitScanner.startScan(context)
    }

    // ⚠ NEW — actually start/stop the real scan when this screen
    // appears/disappears, instead of a fake one-shot delay(1400).
    DisposableEffect(Unit) {
        startScan()
        onDispose {
            BleSuitScanner.stopScan(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(20.dp)
    ) {
        Text(
            text = "PAIR WEARABLE DEVICE",
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Connect a wearable sensor device to a patient profile",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        if (permissionError != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(StatusRed.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .border(1.dp, StatusRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(permissionError ?: "", color = StatusRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(20.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTablet = maxWidth >= 700.dp
            if (isTablet) {
                Row(modifier = Modifier.fillMaxSize()) {

            // ── Left: scan results ─────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "NEARBY DEVICES",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Row(
                        modifier = Modifier
                            .background(BorderDark.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable(enabled = !isScanning) { startScan() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = if (isScanning) TextMuted else AccentBlue, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isScanning) "SCANNING..." else "RESCAN",
                            color = if (isScanning) TextMuted else AccentBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (isScanning && scanResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Scanning for nearby wearable devices…", color = TextMuted, fontSize = 13.sp)
                        }
                    }
                } else if (scanResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text("No wearable devices found nearby", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scanResults, key = { it.address }) { suit ->
                            SuitDiscoveryRow(
                                suit = suit,
                                selected = suit.address == selectedSuit?.address,
                                onClick = { selectedSuit = suit }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(20.dp))

            // ── Right: assign to soldier + confirm ─────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardDark, RoundedCornerShape(12.dp))
                    .padding(18.dp)
            ) {
                if (justPaired != null) {
                    val (soldier, suit) = justPaired!!
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Paired successfully", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${suit.suitName} → ${soldier.rankTitle} ${soldier.name}",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(20.dp))
                            Box(
                                modifier = Modifier
                                    .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                    .border(1.dp, AccentBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        justPaired = null
                                        selectedSuit = null
                                        selectedSoldierId = null
                                        startScan()
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            ) {
                                Text("PAIR ANOTHER", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Text(
                        "ASSIGN TO PATIENT",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    if (pairError != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(StatusRed.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(pairError ?: "", color = StatusRed, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    if (selectedSuit == null) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text("Select a device from the left to continue", color = TextMuted, fontSize = 13.sp)
                        }
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(SoldierState.soldiers, key = { it.id }) { soldier ->
                                    val alreadyHasSuit = pairedSuits.containsKey(soldier.id)
                                    SoldierAssignRow(
                                        soldier = soldier,
                                        selected = soldier.id == selectedSoldierId,
                                        alreadyPaired = alreadyHasSuit,
                                        onClick = { if (!alreadyHasSuit) selectedSoldierId = soldier.id }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        val soldier = SoldierState.soldiers.find { it.id == selectedSoldierId }
                        val canConfirm = soldier != null && !isPairing

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (canConfirm) StatusGreen.copy(alpha = 0.15f) else BorderDark.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (canConfirm) StatusGreen.copy(alpha = 0.5f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = canConfirm) {
                                    val s = soldier ?: return@clickable
                                    val suit = selectedSuit ?: return@clickable
                                    // ⚠ FIXED — resetConfig() is now a suspend
                                    // function (it makes a real backend call —
                                    // see SuitConfigState.kt), so this has to
                                    // run inside a coroutine instead of being
                                    // called directly from the click handler.
                                    scope.launch {
                                        isPairing = true
                                        pairError = null
                                        val fresh = SuitConfigState.resetConfig(s.id)
                                        isPairing = false
                                        if (fresh != null) {
                                            pairedSuits[s.id] = suit.address
                                            justPaired = s to suit
                                        } else {
                                            pairError = "Failed to configure device — check connection"
                                        }
                                    }
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.BluetoothConnected,
                                    contentDescription = null,
                                    tint = if (canConfirm) StatusGreen else TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isPairing) "PAIRING..." else "CONFIRM & PAIR",
                                    color = if (canConfirm) StatusGreen else TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } // end Right Column
                } // end tablet Row
            } else {
                // Phone: vertical stack
                Column(modifier = Modifier.fillMaxSize()) {

                    // ── Nearby Devices section ───────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("NEARBY DEVICES", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        Row(
                            modifier = Modifier
                                .background(BorderDark.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .clickable(enabled = !isScanning) { startScan() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = if (isScanning) TextMuted else AccentBlue, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isScanning) "SCANNING..." else "RESCAN", color = if (isScanning) TextMuted else AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    if (isScanning && scanResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AccentBlue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(10.dp))
                                Text("Scanning for nearby wearable devices…", color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    } else if (scanResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                            Text("No wearable devices found nearby", color = TextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(scanResults, key = { it.address }) { suit ->
                                SuitDiscoveryRow(suit = suit, selected = suit.address == selectedSuit?.address, onClick = { selectedSuit = suit })
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── Assign to Patient section ─────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(CardDark, RoundedCornerShape(12.dp))
                            .padding(18.dp)
                    ) {
                        if (justPaired != null) {
                            val (soldier, suit) = justPaired!!
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(40.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("Paired successfully", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${suit.suitName} → ${soldier.rankTitle} ${soldier.name}", color = TextMuted, fontSize = 13.sp)
                                    Spacer(Modifier.height(20.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                            .border(1.dp, AccentBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                            .clickable { justPaired = null; selectedSuit = null; selectedSoldierId = null; startScan() }
                                            .padding(horizontal = 20.dp, vertical = 10.dp)
                                    ) { Text("PAIR ANOTHER", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        } else {
                            Text("ASSIGN TO PATIENT", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(12.dp))
                            if (pairError != null) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().background(StatusRed.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
                                ) { Text(pairError ?: "", color = StatusRed, fontSize = 12.sp) }
                                Spacer(Modifier.height(10.dp))
                            }
                            if (selectedSuit == null) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text("Select a device above to continue", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                Column(modifier = Modifier.weight(1f)) {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(SoldierState.soldiers, key = { it.id }) { soldier ->
                                            val alreadyHasSuit = pairedSuits.containsKey(soldier.id)
                                            SoldierAssignRow(soldier = soldier, selected = soldier.id == selectedSoldierId, alreadyPaired = alreadyHasSuit, onClick = { if (!alreadyHasSuit) selectedSoldierId = soldier.id })
                                        }
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                val soldier = SoldierState.soldiers.find { it.id == selectedSoldierId }
                                val canConfirm = soldier != null && !isPairing
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (canConfirm) StatusGreen.copy(alpha = 0.15f) else BorderDark.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .border(1.dp, if (canConfirm) StatusGreen.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(8.dp))
                                        .clickable(enabled = canConfirm) {
                                            val s = soldier ?: return@clickable
                                            val suit = selectedSuit ?: return@clickable
                                            scope.launch {
                                                isPairing = true; pairError = null
                                                val fresh = SuitConfigState.resetConfig(s.id)
                                                isPairing = false
                                                if (fresh != null) { pairedSuits[s.id] = suit.address; justPaired = s to suit }
                                                else pairError = "Failed to configure device — check connection"
                                            }
                                        }
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.BluetoothConnected, contentDescription = null, tint = if (canConfirm) StatusGreen else TextMuted, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (isPairing) "PAIRING..." else "CONFIRM & PAIR", color = if (canConfirm) StatusGreen else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } // end phone assign column
                } // end phone Column
            } // end else (phone branch)
        } // end BoxWithConstraints
    } // end Column
} // end PairNewSuitScreen

@Composable
fun SuitDiscoveryRow(suit: DiscoveredSuit, selected: Boolean, onClick: () -> Unit) {
    val signalColor = when {
        suit.signalPercent >= 70 -> StatusGreen
        suit.signalPercent >= 40 -> StatusYellow
        else -> StatusRed
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) AccentBlue.copy(alpha = 0.15f) else CardDark,
                RoundedCornerShape(10.dp)
            )
            .border(
                1.dp,
                if (selected) AccentBlue.copy(alpha = 0.5f) else BorderDark,
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(suit.suitName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            // ⚠ CHANGED — firmware/battery aren't in a BLE advertisement
            // packet, only the real address is available pre-connection.
            Text(suit.address, color = TextMuted, fontSize = 11.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = signalColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("${suit.signalPercent}%", color = signalColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SoldierAssignRow(soldier: Soldier, selected: Boolean, alreadyPaired: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (selected) AccentBlue.copy(alpha = 0.5f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = !alreadyPaired) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor(soldier.status), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${soldier.rankTitle} ${soldier.name}",
                color = if (alreadyPaired) TextMuted else Color.White,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(soldier.serial, color = TextMuted, fontSize = 11.sp)
        }
        if (alreadyPaired) {
            Text("PAIRED", color = StatusGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}