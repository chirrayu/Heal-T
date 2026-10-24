package com.example.healthmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

// Reuses shared colors from SoldiersScreen.kt
private val critRed     = Color(0xFFFF445A)
private val warnYellow  = Color(0xFFFFC533)
private val infoBlue    = Color(0xFF00C2FF)

private fun severityColor(severity: String): Color = when (severity) {
    "critical" -> critRed
    "warning"  -> warnYellow
    else       -> infoBlue
}

private fun severityLabel(severity: String): String = when (severity) {
    "critical" -> "Critical"
    "warning"  -> "Warning"
    else       -> "Information"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))


@Composable
fun AlertsScreen() {

    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("All") }
    var showSettings by remember { mutableStateOf(false) }

    val allAlerts = AlertState.alerts

    val visibleAlerts = allAlerts.filter {
        selectedFilter == "All" || it.severity == selectedFilter.lowercase()
    }

    LaunchedEffect(Unit) {
        AlertState.markAllRead()
    }

    if (showSettings) {
        NoMovementSettingsDialog(onDismiss = { showSettings = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
            .padding(20.dp)
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FILTER:",
                color = textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.width(12.dp))

            listOf("All", "Critical", "Warning", "Information").forEach { filter ->
                FilterButton(
                    label = filter,
                    selected = selectedFilter == filter,
                    color = when (filter) {
                        "Critical" -> critRed
                        "Warning" -> warnYellow
                        "Information" -> infoBlue
                        else -> accentBlue
                    },
                    onClick = { selectedFilter = filter }
                )
                Spacer(Modifier.width(8.dp))
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .background(cardDark, RoundedCornerShape(6.dp))
                    .border(1.dp, borderDark, RoundedCornerShape(6.dp))
                    .clickable { showSettings = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = textMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("No-Movement: ${NoMovementSettings.thresholdMinutes.value} min", color = textMuted, fontSize = 12.sp)
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = "${allAlerts.size} alerts",
                color = textMuted,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        if (visibleAlerts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No alerts to show", color = textMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(visibleAlerts.size) { index ->
                    AlertTimelineCard(
                        alert = visibleAlerts[index],
                        isLast = index == visibleAlerts.lastIndex
                    )
                }
            }
        }
    }
}


@Composable
fun FilterButton(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) color.copy(alpha = 0.15f) else cardDark,
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (selected) color else borderDark,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) color else textMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}


@Composable
fun AlertTimelineCard(alert: AppAlert, isLast: Boolean) {

    val color = severityColor(alert.severity)

    Row(modifier = Modifier.fillMaxWidth()) {

        Column(
            modifier = Modifier.width(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .weight(1f)
                        .background(borderDark)
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 14.dp)
                .background(cardDark, RoundedCornerShape(10.dp))
                .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .padding(16.dp)
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            alert.title.contains("Fall", true) || alert.title.contains("Impact", true) || alert.title.contains("Blast", true) -> Icons.Default.Bolt
                            alert.title.contains("Sensor", true) || alert.title.contains("Movement", true) -> Icons.Default.WifiOff
                            else -> Icons.Default.Warning
                        },
                        contentDescription = alert.severity,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = alert.title,
                            color = color,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(color.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(severityLabel(alert.severity), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = "${alert.soldierName} (${alert.soldierSerial})",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }

                Text(
                    text = formatTime(alert.timestamp),
                    color = textMuted,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = alert.message,
                color = textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            if (alert.actionRequired) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(critRed.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .border(1.dp, critRed.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).background(critRed, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "IMMEDIATE ACTION REQUIRED",
                            color = critRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun NoMovementSettingsDialog(onDismiss: () -> Unit) {

    var customInput by remember { mutableStateOf("") }
    var showCustomField by remember { mutableStateOf(false) }
    val current = NoMovementSettings.thresholdMinutes.value

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardDark, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "NO-MOVEMENT THRESHOLD",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = textMuted,
                    modifier = Modifier.size(18.dp).clickable { onDismiss() }
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Trigger a no-movement alert after this period of inactivity.",
                color = textMuted,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(16.dp))

            ThresholdOption("10 min", current == 10 && !showCustomField) {
                NoMovementSettings.thresholdMinutes.value = 10
                showCustomField = false
            }
            Spacer(Modifier.height(8.dp))

            ThresholdOption("20 min (advised)", current == 20 && !showCustomField, recommended = true) {
                NoMovementSettings.thresholdMinutes.value = 20
                showCustomField = false
            }
            Spacer(Modifier.height(8.dp))

            ThresholdOption("30 min", current == 30 && !showCustomField) {
                NoMovementSettings.thresholdMinutes.value = 30
                showCustomField = false
            }
            Spacer(Modifier.height(8.dp))

            ThresholdOption("Enter minutes manually", showCustomField) {
                showCustomField = true
            }

            if (showCustomField) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgDark, RoundedCornerShape(6.dp))
                        .border(1.dp, accentBlue, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = customInput,
                        onValueChange = { customInput = it.filter { c -> c.isDigit() } },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(accentBlue),
                        modifier = Modifier.weight(1f)
                    )
                    Text("minutes", color = textMuted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(Color(0xFF0D2137), RoundedCornerShape(6.dp))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("CANCEL", color = textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(accentBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .clickable {
                            if (showCustomField) {
                                customInput.toIntOrNull()?.let {
                                    if (it > 0) NoMovementSettings.thresholdMinutes.value = it
                                }
                            }
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("SAVE", color = accentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ThresholdOption(label: String, selected: Boolean, recommended: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) accentBlue.copy(alpha = 0.15f) else bgDark,
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (selected) accentBlue else borderDark,
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(Color.Transparent, CircleShape)
                .border(1.5.dp, if (selected) accentBlue else borderDark, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(modifier = Modifier.size(8.dp).background(accentBlue, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Color.White else textMuted, fontSize = 13.sp)
        if (recommended) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF00E676).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("BEST", color = Color(0xFF00E676), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}