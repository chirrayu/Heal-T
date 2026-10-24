/*Configure Suit screen — lets an admin pick a soldier and edit the sensor,
  sampling-rate and communication settings for their wearable suit.
  Field-for-field this maps onto backend/suit_config.py's SuitConfigUpdate
  schema, and the "commands" preview at the bottom mirrors the payload the
  suit firmware would poll from GET /suit/{soldier_id}/commands.

  ⚠ CHANGED — this screen now actually loads/saves through
  SuitConfigState's suspend functions (which call the real backend),
  instead of a local-only map. See SuitConfigState.kt for details.*/
package com.example.healthmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Colors ────────────────────────────────────────────────────────
private val bgDark       = Color(0xFF07111F)
private val cardDark     = Color(0xFF081B33)
private val borderDark   = Color(0xFF1A3A5C)
private val textMuted    = Color(0xFF6B7F99)
private val accentBlue   = Color(0xFF00C2FF)
private val statusGreen  = Color(0xFF00E676)
private val statusYellow = Color(0xFFFFD600)
private val statusRed    = Color(0xFFFF1744)
private val statusGray   = Color(0xFF757575)

@Composable
fun ConfigureSuitScreen() {

    val soldiers = SoldierState.soldiers
    var selectedId by remember { mutableStateOf(soldiers.firstOrNull()?.id) }
    val selectedSoldier = soldiers.find { it.id == selectedId }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
    ) {
        val isTablet = maxWidth >= 700.dp

        if (isTablet) {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── Tablet: Patient picker sidebar ──────────────────────
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(cardDark)
                        .border(width = 1.dp, color = borderDark)
                        .padding(14.dp)
                ) {
                    Text(
                        text = "SELECT PATIENT",
                        color = textMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(soldiers, key = { it.id }) { soldier ->
                            val hasLoadedConfig = SuitConfigState.configs.containsKey(soldier.id)
                            SuitPickerRow(
                                soldier = soldier,
                                selected = soldier.id == selectedId,
                                configured = hasLoadedConfig,
                                onClick = { selectedId = soldier.id }
                            )
                        }
                    }
                }

                // ── Tablet: Config panel ──────────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedSoldier == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No patient selected", color = textMuted)
                        }
                    } else {
                        SuitConfigPanel(soldier = selectedSoldier)
                    }
                }
            }
        } else {
            // ── Phone: Horizontal patient carousel at top + full-width panel below ─
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardDark)
                        .border(width = 1.dp, color = borderDark)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "SELECT PATIENT",
                        color = textMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(soldiers, key = { it.id }) { soldier ->
                            val isSelected = soldier.id == selectedId
                            val hasLoadedConfig = SuitConfigState.configs.containsKey(soldier.id)
                            Row(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) accentBlue.copy(alpha = 0.15f) else bgDark,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) accentBlue.copy(alpha = 0.6f) else borderDark,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedId = soldier.id }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusColor(soldier.status), CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "${soldier.rankTitle} ${soldier.name}",
                                        color = if (isSelected) Color.White else textMuted,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        soldier.serial,
                                        color = textMuted.copy(alpha = 0.7f),
                                        fontSize = 10.sp
                                    )
                                }
                                if (hasLoadedConfig) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = statusGreen,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Phone: Full width config panel
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedSoldier == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No patient selected", color = textMuted)
                        }
                    } else {
                        SuitConfigPanel(soldier = selectedSoldier)
                    }
                }
            }
        }
    }
}

@Composable
fun SuitPickerRow(
    soldier: Soldier,
    selected: Boolean,
    configured: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) accentBlue.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) accentBlue.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
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
                text = "${soldier.rankTitle} ${soldier.name}",
                color = if (selected) Color.White else Color(0xFFB8C6D9),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(text = soldier.serial, color = textMuted, fontSize = 11.sp)
        }
        if (configured) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Configured",
                tint = statusGreen,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun SuitConfigPanel(soldier: Soldier) {

    val scope = rememberCoroutineScope()

    // ⚠ NEW — actual loading state, since getting the real config is now
    // a network call instead of an instant local read.
    var isLoading by remember(soldier.id) { mutableStateOf(true) }
    var draft by remember(soldier.id) { mutableStateOf(SuitConfigState.getConfig(soldier.id).copy()) }
    var isDirty by remember(soldier.id) { mutableStateOf(false) }
    var showEmergencyConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showSavedBanner by remember { mutableStateOf(false) }
    var showErrorBanner by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // ⚠ NEW — fetch the soldier's real config from the backend whenever
    // the selected soldier changes, instead of just reading whatever was
    // already sitting in the local map (which used to always be a
    // hardcoded default the very first time).
    LaunchedEffect(soldier.id) {
        isLoading = true
        val loaded = SuitConfigState.loadConfig(soldier.id)
        draft = (loaded ?: SuitConfigState.getConfig(soldier.id)).copy()
        isDirty = false
        isLoading = false
    }

    fun update(block: (SuitConfig) -> SuitConfig) {
        draft = block(draft)
        isDirty = true
    }

    if (showSavedBanner) {
        LaunchedEffect(showSavedBanner) {
            delay(1800)
            showSavedBanner = false
        }
    }
    if (showErrorBanner) {
        LaunchedEffect(showErrorBanner) {
            delay(2500)
            showErrorBanner = false
        }
    }

    if (showEmergencyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmergencyConfirm = false },
            containerColor = cardDark,
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = statusRed) },
            title = { Text("Enable Emergency Mode?", color = Color.White) },
            text = {
                Text(
                    "This immediately marks ${soldier.rankTitle} ${soldier.name} as CRITICAL " +
                            "and fires an emergency alert to care staff, triggering immediate medical attention.",
                    color = textMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEmergencyConfirm = false
                    // ⚠ CHANGED — previously updated SoldierState locally
                    // by hand. Now calls the real backend endpoint, whose
                    // side effects (marking soldier critical + firing a
                    // real alert) flow back through the normal WebSocket
                    // snapshot/vitals_update path instead of being faked.
                    scope.launch {
                        isSaving = true
                        val ok = SuitConfigState.setEmergencyMode(soldier.id, true)
                        isSaving = false
                        if (ok) {
                            draft = SuitConfigState.getConfig(soldier.id).copy()
                            isDirty = false
                            showSavedBanner = true
                        } else {
                            showErrorBanner = true
                        }
                    }
                }) {
                    Text("Enable", color = statusRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyConfirm = false }) {
                    Text("Cancel", color = textMuted)
                }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = cardDark,
            title = { Text("Reset to Factory Defaults", color = Color.White) },
            text = {
                Text(
                    "All sensor, sampling and communication settings for this device will revert to defaults.",
                    color = textMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    scope.launch {
                        isSaving = true
                        val fresh = SuitConfigState.resetConfig(soldier.id)
                        isSaving = false
                        if (fresh != null) {
                            draft = fresh.copy()
                            isDirty = false
                            showSavedBanner = true
                        } else {
                            showErrorBanner = true
                        }
                    }
                }) {
                    Text("Reset", color = statusRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = textMuted)
                }
            }
        )
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = accentBlue)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {

        // ── Header ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "CONFIGURE WEARABLE DEVICE",
                    color = textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${soldier.rankTitle} ${soldier.name}",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${soldier.serial} · ${soldier.squad} · ${soldier.role}",
                    color = textMuted,
                    fontSize = 13.sp
                )
            }

            Row(
                modifier = Modifier
                    .background(borderDark.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable(enabled = !isSaving) { showResetConfirm = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Restore, contentDescription = "Reset", tint = textMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("RESET DEFAULTS", color = textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Last updated ${draft.formattedUpdatedAt()}",
            color = textMuted,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(20.dp))

        // ── Sensors ─────────────────────────────────────────────
        ConfigSection(title = "SENSORS", icon = Icons.Default.Sensors) {
            ToggleRow("Heart Rate Sensor", "Continuous BPM monitoring", draft.hrSensor) { update { c -> c.copy(hrSensor = it) } }
            ToggleRow("SpO₂ Sensor", "Blood oxygen saturation", draft.spo2Sensor) { update { c -> c.copy(spo2Sensor = it) } }
            ToggleRow("Temperature Sensor", "Core body temperature", draft.tempSensor) { update { c -> c.copy(tempSensor = it) } }
            ToggleRow("Accelerometer", "Motion & fall detection", draft.accelerometer) { update { c -> c.copy(accelerometer = it) } }
            ToggleRow("GPS", "Live location tracking", draft.gpsEnabled) { update { c -> c.copy(gpsEnabled = it) } }
        }

        Spacer(Modifier.height(16.dp))

        // ── Sampling rate ───────────────────────────────────────
        ConfigSection(title = "SAMPLING RATE", icon = Icons.Default.Sensors) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Reading Interval", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("How often the device reports vitals", color = textMuted, fontSize = 12.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepperButton("−") {
                        val next = (draft.samplingRateSecs - 1).coerceAtLeast(1)
                        update { c -> c.copy(samplingRateSecs = next) }
                    }
                    Box(
                        modifier = Modifier.width(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${draft.samplingRateSecs}s",
                            color = accentBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    StepperButton("+") {
                        update { c -> c.copy(samplingRateSecs = c.samplingRateSecs + 1) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Communication ───────────────────────────────────────
        ConfigSection(title = "COMMUNICATION", icon = Icons.Default.WifiTethering) {
            ToggleRow("Wi-Fi Uplink", "Clinic / Base station connectivity", draft.wifiEnabled) { update { c -> c.copy(wifiEnabled = it) } }
            ToggleRow("Mesh Network", "Device-to-device mesh relay", draft.meshEnabled) { update { c -> c.copy(meshEnabled = it) } }
            ToggleRow("Radio Gateway", "Long-range telemetry fallback", draft.radioGateway) { update { c -> c.copy(radioGateway = it) } }
        }

        Spacer(Modifier.height(16.dp))

        // ── Emergency mode ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusRed.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .border(1.dp, statusRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = statusRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Emergency Mode", color = statusRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Marks patient CRITICAL and pushes an alert instantly",
                            color = textMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                Switch(
                    checked = draft.emergencyMode,
                    enabled = !isSaving,
                    onCheckedChange = { checked ->
                        if (checked) {
                            showEmergencyConfirm = true
                        } else {
                            scope.launch {
                                isSaving = true
                                val ok = SuitConfigState.setEmergencyMode(soldier.id, false)
                                isSaving = false
                                if (ok) {
                                    draft = SuitConfigState.getConfig(soldier.id).copy()
                                    isDirty = false
                                    showSavedBanner = true
                                } else {
                                    showErrorBanner = true
                                }
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = statusRed,
                        checkedTrackColor = statusRed.copy(alpha = 0.4f),
                        uncheckedThumbColor = textMuted,
                        uncheckedTrackColor = borderDark
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Commands preview (what the firmware would poll) ─────
        ConfigSection(title = "DEVICE COMMAND PREVIEW", icon = Icons.Default.Bluetooth) {
            Text(
                text = buildString {
                    append("sensors: hr=${draft.hrSensor}, spo2=${draft.spo2Sensor}, ")
                    append("temp=${draft.tempSensor}, accel=${draft.accelerometer}, gps=${draft.gpsEnabled}\n")
                    append("sampling_rate_secs: ${draft.samplingRateSecs}\n")
                    append("comms: wifi=${draft.wifiEnabled}, mesh=${draft.meshEnabled}, radio=${draft.radioGateway}\n")
                    append("emergency_mode: ${draft.emergencyMode}")
                },
                color = Color(0xFF7FE0A6),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Save bar ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showErrorBanner) {
                Text("Save failed — check connection", color = statusRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
            } else if (showSavedBanner) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = statusGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Saved", color = statusGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
            } else if (isDirty) {
                Text("Unsaved changes", color = statusYellow, fontSize = 12.sp)
                Spacer(Modifier.width(16.dp))
            }

            Box(
                modifier = Modifier
                    .background(
                        if (isDirty && !isSaving) accentBlue.copy(alpha = 0.15f) else borderDark.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isDirty && !isSaving) accentBlue.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = isDirty && !isSaving) {
                        // ⚠ CHANGED — previously this just wrote into a
                        // local map and immediately showed "Saved". Now
                        // it's a real PUT request; the banner only shows
                        // success once the backend actually confirms it.
                        scope.launch {
                            isSaving = true
                            val ok = SuitConfigState.saveConfig(soldier.id, draft)
                            isSaving = false
                            if (ok) {
                                draft = SuitConfigState.getConfig(soldier.id).copy()
                                isDirty = false
                                showSavedBanner = true
                            } else {
                                showErrorBanner = true
                            }
                        }
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    if (isSaving) "SAVING..." else "SAVE CONFIG",
                    color = if (isDirty && !isSaving) accentBlue else textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun ConfigSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardDark, RoundedCornerShape(12.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = accentBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = textMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentBlue,
                checkedTrackColor = accentBlue.copy(alpha = 0.4f),
                uncheckedThumbColor = textMuted,
                uncheckedTrackColor = borderDark
            )
        )
    }
}

@Composable
fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(bgDark, RoundedCornerShape(6.dp))
            .border(1.dp, borderDark, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accentBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}