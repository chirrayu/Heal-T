package com.example.healthmonitor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

// ── Colors ────────────────────────────────────────────────────────
val BgDark = Color(0xFF07111F)
val CardDark = Color(0xFF081B33)
val BorderDark = Color(0xFF1A3A5C)

val TextMuted = Color(0xFF6B7F99)

val AccentBlue = Color(0xFF00C2FF)

val StatusGreen = Color(0xFF00E676)
val StatusYellow = Color(0xFFFFD600)
val StatusRed = Color(0xFFFF1744)
val StatusGray = Color(0xFF757575)

fun statusColor(status: String): Color = when (status) {
    "stable"   -> StatusGreen
    "serious"  -> StatusYellow
    "critical" -> StatusRed
    else       -> StatusGray
}

fun hrColor(hr: Int?): Color = when (hrZone(hr)) {
    "green"  -> StatusGreen
    "yellow" -> StatusYellow
    "red"    -> StatusRed
    else     -> TextMuted
}


// ── Main Soldiers Screen ──────────────────────────────────────────
@Composable
fun SoldiersScreen() {

    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSoldier by remember { mutableStateOf<Soldier?>(null) }
    var deletingSoldier by remember { mutableStateOf<Soldier?>(null) }

    // ⚠ NEW — simple error banner so a failed save/delete is visible
    // instead of silently doing nothing (or, as before, silently
    // "succeeding" locally while the backend never actually changed).
    var errorMessage by remember { mutableStateOf<String?>(null) }
    if (errorMessage != null) {
        LaunchedEffect(errorMessage) {
            delay(3000)
            errorMessage = null
        }
    }

    val selectedSquad by SquadState.selectedSquad

    // ⚠ NEW — load the real squad list from the backend once when this
    // screen first appears. Previously SquadState.squads started empty
    // and was only ever appended to locally.
    LaunchedEffect(Unit) {
        SquadState.loadSquads()
    }

    val visibleSoldiers = SoldierState.soldiers
        .filter { selectedSquad == "All Squads" || it.squad == selectedSquad }
        .filter { selectedFilter == "All" || it.status == selectedFilter.lowercase() }
        .filter {
            searchQuery.isBlank() ||
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    it.serial.contains(searchQuery, ignoreCase = true)
        }
        .sortedBy { it.rankOrder }

    if (showAddDialog) {
        SoldierEditDialog(
            existing = null,
            onDismiss = { showAddDialog = false },
            onSave = { soldier ->
                // ⚠ CHANGED — previously called SoldierState.addSoldier()
                // synchronously, which only touched the local list. Now
                // resolves the real squad_id and makes a real network
                // call, closing the dialog only on confirmed success.
                val squadId = SquadState.squadIds[soldier.squad]
                if (squadId == null) {
                    errorMessage = "Unknown squad — try again after squads finish loading"
                    return@SoldierEditDialog
                }
                scope.launch {
                    val ok = SoldierState.addSoldier(soldier, squadId)
                    if (ok) {
                        showAddDialog = false
                    } else {
                        errorMessage = "Failed to add patient — check connection"
                    }
                }
            }
        )
    }

    editingSoldier?.let { soldier ->
        SoldierEditDialog(
            existing = soldier,
            onDismiss = { editingSoldier = null },
            onSave = { updated ->
                // ⚠ CHANGED — previously called SoldierState.updateSoldier()
                // synchronously (local-only). Now a real PUT request;
                // dialog only closes once the backend confirms.
                scope.launch {
                    val ok = SoldierState.updateSoldier(updated)
                    if (ok) {
                        editingSoldier = null
                    } else {
                        errorMessage = "Failed to save changes — check connection"
                    }
                }
            }
        )
    }

    deletingSoldier?.let { soldier ->
        AlertDialog(
            onDismissRequest = { deletingSoldier = null },
            containerColor = CardDark,
            title = { Text("Remove Patient", color = Color.White) },
            text = {
                Text(
                    "Remove ${soldier.name} (${soldier.serial}) from patient records?",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = SoldierState.removeSoldier(soldier.id)
                        if (!ok) {
                            errorMessage = "Failed to remove patient — check connection"
                        }
                        deletingSoldier = null
                    }
                }) {
                    Text("Remove", color = StatusRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSoldier = null }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        val isTablet = maxWidth >= 720.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isTablet) {
                SquadSidebar()
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(if (isTablet) 16.dp else 12.dp)
            ) {
                if (!isTablet) {
                    // Mobile Care Groups selector
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GROUPS:",
                            color = TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            item {
                                SquadRow("All Groups", selectedSquad == "All Squads" || selectedSquad == "All Groups") {
                                    SquadState.selectedSquad.value = "All Squads"
                                }
                            }
                            items(SquadState.squads) { squad ->
                                SquadRow(squad, selectedSquad == squad) {
                                    SquadState.selectedSquad.value = squad
                                }
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(StatusRed.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .border(1.dp, StatusRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(errorMessage ?: "", color = StatusRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .border(1.dp, AccentBlue.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { showAddDialog = true }
                            .padding(horizontal = if (isTablet) 16.dp else 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = AccentBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("ADD PATIENT", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                FilterChips(
                    selected = selectedFilter,
                    onSelect = { selectedFilter = it }
                )

                Spacer(Modifier.height(12.dp))

                SoldierTable(
                    soldiers = visibleSoldiers,
                    onEdit = { editingSoldier = it },
                    onDelete = { deletingSoldier = it }
                )
            }
        }
    }
}


// ── Squad Sidebar ─────────────────────────────────────────────────
@Composable
fun SquadSidebar() {

    val scope = rememberCoroutineScope()
    var showAddSquad by remember { mutableStateOf(false) }
    var newSquadName by remember { mutableStateOf("") }
    var isAddingSquad by remember { mutableStateOf(false) }
    val selectedSquad by SquadState.selectedSquad

    Column(
        modifier = Modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(CardDark)
            .border(width = 1.dp, color = BorderDark)
            .padding(12.dp)
    ) {
        Text(
            text = "CARE GROUPS",
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(12.dp))

        SquadRow("All Groups", selectedSquad == "All Squads" || selectedSquad == "All Groups") {
            SquadState.selectedSquad.value = "All Squads"
        }

        Spacer(Modifier.height(4.dp))

        SquadState.squads.forEach { squad ->
            SquadRow(squad, selectedSquad == squad) {
                SquadState.selectedSquad.value = squad
            }
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(8.dp))

        if (showAddSquad) {
            OutlinedTextField(
                value = newSquadName,
                onValueChange = { newSquadName = it },
                placeholder = { Text("Group / Ward name", color = TextMuted, fontSize = 12.sp) },
                singleLine = true,
                enabled = !isAddingSquad,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = AccentBlue,
                    unfocusedBorderColor = BorderDark
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .clickable(enabled = !isAddingSquad) {
                            // ⚠ CHANGED — previously did
                            // SquadState.squads.add(name) locally only,
                            // never telling the backend a squad existed.
                            if (newSquadName.isNotBlank()) {
                                val name = newSquadName.trim()
                                scope.launch {
                                    isAddingSquad = true
                                    val ok = SquadState.addSquad(name)
                                    isAddingSquad = false
                                    if (ok) {
                                        newSquadName = ""
                                        showAddSquad = false
                                    }
                                }
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isAddingSquad) "Adding..." else "Add",
                        color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(BorderDark, RoundedCornerShape(6.dp))
                        .clickable { showAddSquad = false; newSquadName = "" }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Cancel", color = TextMuted, fontSize = 11.sp)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                    .clickable { showAddSquad = true }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = "Add group", tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Group", color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SquadRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) AccentBlue.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) AccentBlue.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            text = name,
            color = if (selected) AccentBlue else TextMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}


// ── Search Bar ─────────────────────────────────────────────────────
@Composable
fun SearchBar(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(44.dp)
            .background(CardDark, RoundedCornerShape(8.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = "Search", tint = TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search patients...", color = TextMuted, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(AccentBlue),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}


// ── Filter Chips ───────────────────────────────────────────────────
@Composable
fun FilterChips(selected: String, onSelect: (String) -> Unit) {
    val filters = listOf("All", "Stable", "Serious", "Critical", "Offline")

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { filter ->
            val isSelected = selected == filter
            val color = when (filter) {
                "Stable"   -> StatusGreen
                "Serious"  -> StatusYellow
                "Critical" -> StatusRed
                "Offline"  -> StatusGray
                else       -> AccentBlue
            }
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) color.copy(alpha = 0.15f) else CardDark,
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        1.dp,
                        if (isSelected) color else BorderDark,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = filter,
                    color = if (isSelected) color else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}


// ── Soldier Table ──────────────────────────────────────────────────
// IMPORTANT: weight() modifiers on Row children require a *bounded* parent
// width. Inside a horizontalScroll box the parent width is unconstrained, so
// we must give the Column an explicit pixel width — not just widthIn(min=…) —
// otherwise Compose collapses every weighted child to 0 px and text renders
// one character per row (the "vertical text" bug).
@Composable
fun SoldierTable(
    soldiers: List<Soldier>,
    onEdit: (Soldier) -> Unit,
    onDelete: (Soldier) -> Unit
) {
    // Use BoxWithConstraints so we can pick a column width that fills the
    // screen on tablets and stays scrollable on phones.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val tableWidth = maxOf(maxWidth, 900.dp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(tableWidth)           // ← explicit width, not widthIn
                    .background(CardDark, RoundedCornerShape(10.dp))
                    .border(1.dp, BorderDark, RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableHeaderText("#", Modifier.width(28.dp))
                    TableHeaderText("PATIENT", Modifier.weight(2.2f))
                    TableHeaderText("GROUP", Modifier.weight(1f))
                    TableHeaderText("ROLE / CARE", Modifier.weight(1.3f))
                    TableHeaderText("HR", Modifier.weight(0.9f))
                    TableHeaderText("SPO2", Modifier.weight(0.9f))
                    TableHeaderText("TEMP", Modifier.weight(0.9f))
                    TableHeaderText("BATTERY/RISK", Modifier.weight(1.6f))
                    TableHeaderText("STATUS", Modifier.weight(1.1f))
                    TableHeaderText("BLOOD", Modifier.weight(0.7f))
                    Spacer(Modifier.width(60.dp))
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark))

                if (soldiers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No patients match your filters", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(soldiers.size) { index ->
                            val soldier = soldiers[index]
                            SoldierRow(
                                index = index + 1,
                                soldier = soldier,
                                onEdit = { onEdit(soldier) },
                                onDelete = { onDelete(soldier) }
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderDark.copy(alpha = 0.5f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TableHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = modifier
    )
}


@Composable
fun SoldierRow(index: Int, soldier: Soldier, onEdit: () -> Unit, onDelete: () -> Unit) {

    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showActions = !showActions }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Text(text = "$index", color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(28.dp))

        Row(
            modifier = Modifier.weight(2.2f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoldierAvatar(soldier.photoUri, soldier.name)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "${soldier.rankTitle} ${soldier.name}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = soldier.serial, color = TextMuted, fontSize = 11.sp)
            }
        }

        Text(text = soldier.squad, color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(text = soldier.role, color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1.3f))

        Row(
            modifier = Modifier.weight(0.9f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (soldier.hr != null) {
                PulseIcon(color = hrColor(soldier.hr))
                Spacer(Modifier.width(4.dp))
                Text(text = "${soldier.hr}", color = hrColor(soldier.hr), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(text = "—", color = TextMuted, fontSize = 13.sp)
            }
        }

        Text(
            text = soldier.spo2?.let { "$it%" } ?: "—%",
            color = if (soldier.spo2 != null) AccentBlue else TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.9f)
        )

        Text(
            text = soldier.temp?.let { "$it" } ?: "—",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(0.9f)
        )

        Box(modifier = Modifier.weight(1.6f)) {
            BatteryRiskBar(battery = soldier.battery, riskColor = statusColor(soldier.status))
        }

        Box(modifier = Modifier.weight(1.1f)) {
            StatusPill(soldier.status)
        }
        Box(
            modifier = Modifier.weight(0.7f),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFFF1744).copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFFF1744).copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = soldier.bloodGroup,
                    color = Color(0xFFFF1744),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showActions) {
            Row {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = AccentBlue,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onEdit() }
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = StatusRed,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDelete() }
                )
            }
        } else {
            Spacer(Modifier.width(60.dp))
        }
    }
}


// ── Avatar (photo or initials) ────────────────────────────────────
@Composable
fun SoldierAvatar(photoUri: String?, name: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(CardDark)
            .border(1.dp, BorderDark, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (photoUri != null) {
            AsyncImage(
                model = Uri.parse(photoUri),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Text(
                text = name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""),
                color = TextMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


// ── Animated pulse icon ───────────────────────────────────────────
@Composable
fun PulseIcon(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scaleValue by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Icon(
        imageVector = Icons.Default.Favorite,
        contentDescription = "Heart rate",
        tint = color,
        modifier = Modifier
            .size(14.dp)
            .scale(scaleValue)
    )
}


// ── Battery / risk bar ─────────────────────────────────────────────
@Composable
fun BatteryRiskBar(battery: Int, riskColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(50.dp)
                .height(6.dp)
                .background(BorderDark, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(battery / 100f)
                    .background(
                        if (battery < 20) StatusRed else if (battery < 50) StatusYellow else StatusGreen,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text = "$battery%", color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${100 - battery}%",
            color = riskColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}


// ── Status pill ────────────────────────────────────────────────────
@Composable
fun StatusPill(status: String) {
    val color = statusColor(status)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}


// ── Add / Edit Soldier Dialog ──────────────────────────────────────
@Composable
fun SoldierEditDialog(
    existing: Soldier?,
    onDismiss: () -> Unit,
    onSave: (Soldier) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var rankTitle by remember { mutableStateOf(existing?.rankTitle ?: "Mr.") }
    var rankOrder by remember { mutableStateOf(existing?.rankOrder?.toString() ?: "4") }
    var serial by remember { mutableStateOf(existing?.serial ?: "P-00${SoldierState.soldiers.size + 1}") }
    var squad by remember { mutableStateOf(existing?.squad ?: SquadState.squads.firstOrNull() ?: "General") }
    var role by remember { mutableStateOf(existing?.role ?: "General Observation") }
    var photoUri by remember { mutableStateOf(existing?.photoUri) }
    var bloodGroup by remember { mutableStateOf(existing?.bloodGroup ?: "O+") }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { photoUri = it.toString() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (existing == null) "ADD PATIENT" else "EDIT PATIENT",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp).clickable { onDismiss() }
                )
            }

            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(BgDark)
                        .border(1.dp, BorderDark, CircleShape)
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (photoUri != null) {
                        AsyncImage(
                            model = Uri.parse(photoUri),
                            contentDescription = "Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Icon(Icons.Default.Person, contentDescription = "Add photo", tint = TextMuted, modifier = Modifier.size(28.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tap to choose photo",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            DialogField("Name", name) { name = it }
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    DialogField("Title / Prefix (e.g. Mr., Ms., Dr.)", rankTitle) { rankTitle = it }
                }
                Box(modifier = Modifier.weight(1f)) {
                    DialogField("Priority Level (1=High, 4=Normal)", rankOrder) { rankOrder = it.filter { c -> c.isDigit() } }
                }
            }

            Spacer(Modifier.height(10.dp))
            DialogField("Patient ID (e.g. P-007)", serial) { serial = it }

            Spacer(Modifier.height(10.dp))
            DialogField("Care Category (e.g. Outpatient, Cardiac)", role) { role = it }

            Spacer(Modifier.height(10.dp))
            Text("Blood Group", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf("O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-").forEach { bg ->
                    Box(
                        modifier = Modifier
                            .background(
                                if (bloodGroup == bg) Color(0xFFFF1744).copy(alpha = 0.15f) else BgDark,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (bloodGroup == bg) Color(0xFFFF1744) else BorderDark,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { bloodGroup = bg }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(bg, color = if (bloodGroup == bg) Color(0xFFFF1744) else TextMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Care Group / Ward", color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SquadState.squads.forEach { s ->
                    Box(
                        modifier = Modifier
                            .background(
                                if (squad == s) AccentBlue.copy(alpha = 0.15f) else BgDark,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                1.dp,
                                if (squad == s) AccentBlue else BorderDark,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { squad = s }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(s, color = if (squad == s) AccentBlue else TextMuted, fontSize = 12.sp)
                    }
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
                    Text("CANCEL", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(AccentBlue.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .clickable {
                            if (name.isNotBlank()) {
                                val soldier = Soldier(
                                    id = existing?.id ?: "",
                                    name = name.trim(),
                                    rankTitle = rankTitle.trim(),
                                    rankOrder = rankOrder.toIntOrNull() ?: 4,
                                    serial = serial.trim(),
                                    squad = squad,
                                    role = role.trim(),
                                    hr = existing?.hr ?: 75,
                                    spo2 = existing?.spo2 ?: 98,
                                    temp = existing?.temp ?: 98.2f,
                                    battery = existing?.battery ?: 100,
                                    status = existing?.status ?: "stable",
                                    photoUri = photoUri,
                                    bloodGroup = bloodGroup
                                )
                                onSave(soldier)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("SAVE", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DialogField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = BorderDark
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}