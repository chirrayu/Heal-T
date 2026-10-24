package com.example.healthmonitor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// ── Color palette (matches the rest of the app) ──────────────────
private val bgDark       = Color(0xFF07111F)
private val cardDark     = Color(0xFF081B33)
private val borderDark   = Color(0xFF1A3A5C)
private val textMuted    = Color(0xFF6B7F99)
private val accentBlue   = Color(0xFF00C2FF)
private val statusGreen  = Color(0xFF00E676)
private val statusYellow = Color(0xFFFFD600)
private val statusRed    = Color(0xFFFF1744)
private val statusGray   = Color(0xFF757575)

private val cardDarker   = Color(0xFF06152A)
private val accentCyan   = Color(0xFF00C2FF)
private val accentGreen  = Color(0xFF00FF88)
private val critRed      = Color(0xFFFF445A)
private val warnYellow   = Color(0xFFFFC533)
private val seriousAmber = Color(0xFFFF9800)
private val purpleAccent = Color(0xFF9C6AFF)
private val tealAccent   = Color(0xFF00E5CC)
private val highRiskOrange = Color(0xFFFF6B35)
private val cardGradientEnd = Color(0xFF0A1E3D)
private val bgDarker       = Color(0xFF040E1C)


// ── Data class for per-soldier AI assessment ─────────────────────
private data class SoldierAssessment(
    val soldier: Soldier,
    val severityScore: Float,
    val classification: String,
    val riskFactors: List<String>,
    val recommendation: String,
    val trendDirection: String   // "improving", "stable", "declining"
)


// ── Main Screen ──────────────────────────────────────────────────
@Composable
fun AIAnalyticsScreen() {
    val soldiers = SoldierState.soldiers
    val alerts   = AlertState.alerts

    // Compute assessments from live state
    val assessments = remember(soldiers.toList()) {
        soldiers.map { buildAssessment(it) }
    }

    // Summary counts
    val criticalCount = assessments.count { it.classification == "Critical" }
    val seriousCount  = assessments.count { it.classification == "Serious" }
    val stableCount   = assessments.count { it.classification == "Stable" }
    val offlineCount  = soldiers.count { it.status == "offline" }
    val totalActive   = soldiers.size - offlineCount

    // Threat level
    val threatLevel = when {
        criticalCount >= 3  -> "SEVERE"
        criticalCount >= 1  -> "HIGH"
        seriousCount  >= 3  -> "ELEVATED"
        seriousCount  >= 1  -> "MODERATE"
        else                -> "LOW"
    }
    val threatColor = when (threatLevel) {
        "SEVERE"   -> critRed
        "HIGH"     -> highRiskOrange
        "ELEVATED" -> warnYellow
        "MODERATE" -> accentCyan
        else       -> accentGreen
    }

    // Selected soldier for detail
    var selectedSoldier by remember { mutableStateOf<SoldierAssessment?>(null) }

    // Loading pulse animation
    var pulse by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            pulse = !pulse
            delay(1200)
        }
    }
    val pulseAlpha by animateFloatAsState(
        targetValue = if (pulse) 1f else 0.4f,
        animationSpec = tween(1200),
        label = "pulse"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── Header ───────────────────────────────────────────────
        item {
            AIHeader(threatLevel, threatColor, pulseAlpha, totalActive, soldiers.size)
        }

        // ── Severity Distribution ────────────────────────────────
        item {
            SeverityDistributionPanel(
                critical = criticalCount,
                serious  = seriousCount,
                stable   = stableCount,
                offline  = offlineCount,
                total    = soldiers.size
            )
        }

        // ── Squad Readiness Matrix ───────────────────────────────
        item {
            SquadReadinessMatrix(soldiers, assessments)
        }

        // ── Threat Assessment Panel ──────────────────────────────
        item {
            ThreatAssessmentPanel(assessments, alerts)
        }

        // ── Soldier AI Cards header ──────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "INDIVIDUAL TRIAGE ASSESSMENTS",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    "${assessments.size} PATIENTS",
                    color = textMuted,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        // ── Per-soldier AI triage cards ──────────────────────────
        items(assessments.sortedByDescending { it.severityScore }) { assessment ->
            SoldierAICard(
                assessment = assessment,
                isExpanded = selectedSoldier?.soldier?.id == assessment.soldier.id,
                onToggle = {
                    selectedSoldier = if (selectedSoldier?.soldier?.id == assessment.soldier.id) null
                    else assessment
                }
            )
        }
    }
}


// ── AI Header ────────────────────────────────────────────────────
@Composable
private fun AIHeader(
    threatLevel: String,
    threatColor: Color,
    pulseAlpha: Float,
    activeCount: Int,
    totalCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            cardDark,
                            cardGradientEnd,
                            cardDark
                        )
                    ),
                    RoundedCornerShape(16.dp)
                )
                .border(1.dp, borderDark.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    purpleAccent.copy(alpha = 0.15f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = purpleAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "CLINICAL TRIAGE AI",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                            Text(
                                "Triage Assessment & Health Risk Scoring",
                                color = textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Threat level badge
                    Box(
                        modifier = Modifier
                            .background(
                                threatColor.copy(alpha = 0.15f * pulseAlpha),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                threatColor.copy(alpha = 0.4f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        threatColor.copy(alpha = pulseAlpha),
                                        CircleShape
                                    )
                            )
                            Text(
                                "RISK LEVEL: $threatLevel",
                                color = threatColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Status bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            bgDarker,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MiniStat("ACTIVE", "$activeCount/$totalCount", accentGreen)
                    MiniDivider()
                    MiniStat("SCORING", "REAL-TIME", accentCyan)
                    MiniDivider()
                    MiniStat(
                        "LAST SCAN",
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date()),
                        textMuted
                    )
                    MiniDivider()
                    MiniStat("MODEL", "TRIAGE-AI v1.2", purpleAccent)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = textMuted, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(borderDark)
    )
}


// ── Severity Distribution Panel ──────────────────────────────────
@Composable
private fun SeverityDistributionPanel(
    critical: Int,
    serious: Int,
    stable: Int,
    offline: Int,
    total: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SEVERITY DISTRIBUTION",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Box(
                    modifier = Modifier
                        .background(purpleAccent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("TA-CSS", color = purpleAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Distribution bars
            val safeTotal = total.coerceAtLeast(1)

            SeverityBar("CRITICAL", critical, safeTotal, critRed, Icons.Default.Warning)
            Spacer(Modifier.height(10.dp))
            SeverityBar("SERIOUS", serious, safeTotal, seriousAmber, Icons.AutoMirrored.Filled.TrendingDown)
            Spacer(Modifier.height(10.dp))
            SeverityBar("STABLE", stable, safeTotal, accentGreen, Icons.Default.CheckCircle)
            Spacer(Modifier.height(10.dp))
            SeverityBar("OFFLINE", offline, safeTotal, textMuted, Icons.Default.WifiOff)
        }
    }
}

@Composable
private fun SeverityBar(
    label: String,
    count: Int,
    total: Int,
    color: Color,
    icon: ImageVector
) {
    val fraction by animateFloatAsState(
        targetValue = count.toFloat() / total.toFloat(),
        animationSpec = tween(800),
        label = "bar"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.width(70.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        Brush.horizontalGradient(
                            listOf(color.copy(alpha = 0.6f), color)
                        ),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$count",
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.End
        )
        Text(
            text = " (${if (total > 0) (count * 100 / total) else 0}%)",
            color = textMuted,
            fontSize = 11.sp,
            modifier = Modifier.width(46.dp)
        )
    }
}


// ── Squad Readiness Matrix ───────────────────────────────────────
@Composable
private fun SquadReadinessMatrix(
    soldiers: List<Soldier>,
    assessments: List<SoldierAssessment>
) {
    val squads = soldiers.map { it.squad }.distinct().sorted()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CARE GROUP WELLNESS MATRIX",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Icon(
                    Icons.Default.GridView,
                    contentDescription = null,
                    tint = tealAccent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            // Column headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgDarker, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("GROUP", color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1.4f))
                Text("SIZE", color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(0.6f), textAlign = TextAlign.Center)
                Text("CRIT", color = critRed.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(0.6f), textAlign = TextAlign.Center)
                Text("AVG SCORE", color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("HEALTH", color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }

            squads.forEach { squad ->
                val squadSoldiers = soldiers.filter { it.squad == squad }
                val squadAssessments = assessments.filter { it.soldier.squad == squad }
                val critCount = squadAssessments.count { it.classification == "Critical" }
                val avgScore = if (squadAssessments.isNotEmpty())
                    squadAssessments.map { it.severityScore }.average().toFloat() else 0f
                val readiness = when {
                    critCount > 0   -> "DEGRADED"
                    avgScore > 6.5f -> "CAUTION"
                    else            -> "READY"
                }
                val readinessColor = when (readiness) {
                    "DEGRADED" -> critRed
                    "CAUTION"  -> warnYellow
                    else       -> accentGreen
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardDarker, RoundedCornerShape(6.dp))
                        .border(1.dp, borderDark.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        squad.uppercase(),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${squadSoldiers.size}",
                        color = accentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "$critCount",
                        color = if (critCount > 0) critRed else textMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.6f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        String.format(Locale.getDefault(), "%.1f", avgScore),
                        color = when {
                            avgScore > 13.5f -> critRed
                            avgScore > 6.5f  -> warnYellow
                            else             -> accentGreen
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(readinessColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            readiness,
                            color = readinessColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            if (squads.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("No care group data available", color = textMuted, fontSize = 12.sp)
            }
        }
    }
}


// ── Threat Assessment Panel ──────────────────────────────────────
@Composable
private fun ThreatAssessmentPanel(
    assessments: List<SoldierAssessment>,
    alerts: List<AppAlert>
) {
    val critAssessments = assessments.filter { it.classification == "Critical" }
    val recentCritAlerts = alerts.filter { it.severity == "critical" }.take(3)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardDark),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI HEALTH RISK ASSESSMENT",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = warnYellow, modifier = Modifier.size(14.dp))
                    Text("AI-POWERED", color = warnYellow, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            if (critAssessments.isEmpty() && recentCritAlerts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(accentGreen.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .border(1.dp, accentGreen.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = accentGreen,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "ALL CLEAR",
                            color = accentGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "No critical risks detected. All patients within normal vital parameters.",
                            color = textMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Show critical threats
                critAssessments.forEach { assessment ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(critRed.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .border(1.dp, critRed.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(critRed.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = critRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${assessment.soldier.rankTitle} ${assessment.soldier.name}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    assessment.recommendation,
                                    color = critRed.copy(alpha = 0.9f),
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    assessment.riskFactors.take(3).forEach { factor ->
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    critRed.copy(alpha = 0.12f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                factor,
                                                color = critRed,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                String.format(Locale.getDefault(), "%.1f", assessment.severityScore),
                                color = critRed,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}


// ── Soldier AI Card ──────────────────────────────────────────────
@Composable
private fun SoldierAICard(
    assessment: SoldierAssessment,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val soldier = assessment.soldier
    val classColor = when (assessment.classification) {
        "Critical" -> critRed
        "Serious"  -> seriousAmber
        else       -> accentGreen
    }
    val trendIcon = when (assessment.trendDirection) {
        "improving" -> Icons.AutoMirrored.Filled.TrendingUp
        "declining" -> Icons.AutoMirrored.Filled.TrendingDown
        else        -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    val trendColor = when (assessment.trendDirection) {
        "improving" -> accentGreen
        "declining" -> critRed
        else        -> textMuted
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = cardDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (assessment.classification == "Critical")
                        critRed.copy(alpha = 0.3f)
                    else
                        borderDark.copy(alpha = 0.3f),
                    RoundedCornerShape(12.dp)
                )
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Severity dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(classColor, CircleShape)
                )
                Spacer(Modifier.width(10.dp))

                // Name & info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${soldier.rankTitle} ${soldier.name}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${soldier.serial} · ${soldier.squad} · ${soldier.role}",
                        color = textMuted,
                        fontSize = 11.sp
                    )
                }

                // Trend indicator
                Icon(
                    trendIcon,
                    contentDescription = null,
                    tint = trendColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))

                // Score badge
                Box(
                    modifier = Modifier
                        .background(classColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, classColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            String.format(Locale.getDefault(), "%.1f", assessment.severityScore),
                            color = classColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 18.sp
                        )
                        Text(
                            assessment.classification.uppercase(),
                            color = classColor.copy(alpha = 0.8f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Expanded detail
            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(borderDark)
                )
                Spacer(Modifier.height(12.dp))

                // Vitals grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    VitalChip("HR", soldier.hr?.let { "$it bpm" } ?: "N/A",
                        when {
                            soldier.hr == null        -> textMuted
                            soldier.hr!! > 130        -> critRed
                            soldier.hr!! > 100        -> warnYellow
                            else                      -> accentGreen
                        }
                    )
                    VitalChip("SpO2", soldier.spo2?.let { "$it%" } ?: "N/A",
                        when {
                            soldier.spo2 == null       -> textMuted
                            soldier.spo2!! < 90        -> critRed
                            soldier.spo2!! < 95        -> warnYellow
                            else                       -> accentGreen
                        }
                    )
                    VitalChip("TEMP", soldier.temp?.let { String.format(Locale.getDefault(), "%.1f°F", it) } ?: "N/A",
                        when {
                            soldier.temp == null       -> textMuted
                            soldier.temp!! > 101.3f    -> critRed
                            soldier.temp!! > 99.5f     -> warnYellow
                            else                       -> accentGreen
                        }
                    )
                    VitalChip("BATT", "${soldier.battery}%",
                        when {
                            soldier.battery < 20       -> critRed
                            soldier.battery < 50       -> warnYellow
                            else                       -> accentGreen
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Risk factors
                if (assessment.riskFactors.isNotEmpty()) {
                    Text(
                        "RISK FACTORS",
                        color = textMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        assessment.riskFactors.forEach { factor ->
                            Box(
                                modifier = Modifier
                                    .background(
                                        classColor.copy(alpha = 0.1f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .border(
                                        1.dp,
                                        classColor.copy(alpha = 0.2f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    factor,
                                    color = classColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // AI recommendation
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            purpleAccent.copy(alpha = 0.06f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            purpleAccent.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = purpleAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "AI RECOMMENDATION",
                                color = purpleAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                assessment.recommendation,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VitalChip(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(bgDarker, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}


// ── Assessment Builder ───────────────────────────────────────────
// Mirrors the backend TA-CSS logic (triage.py) on the client side
// using available vitals data from the soldier state.

private fun buildAssessment(soldier: Soldier): SoldierAssessment {
    val riskFactors = mutableListOf<String>()

    // HR sub-score (W1=0.8)
    val hrSub = when {
        soldier.hr == null                                  -> 0
        soldier.hr!! in 60..100                             -> 0
        soldier.hr!! in 101..120 || soldier.hr!! in 50..59  -> 1
        soldier.hr!! in 121..139 || soldier.hr!! in 40..49  -> 2
        else                                                -> 3
    }
    if (hrSub >= 2) riskFactors.add("HR:${soldier.hr}bpm")

    // SpO2 sub-score (W2=1.3)
    val spo2Sub = when {
        soldier.spo2 == null            -> 0
        soldier.spo2!! in 95..100       -> 0
        soldier.spo2!! in 91..94        -> 1
        soldier.spo2!! in 86..90        -> 2
        else                            -> 3
    }
    if (spo2Sub >= 2) riskFactors.add("SpO2:${soldier.spo2}%")

    // Temp check (not in TA-CSS but still a risk indicator)
    if (soldier.temp != null && soldier.temp!! > 101.3f) {
        riskFactors.add("TEMP:${String.format(Locale.getDefault(), "%.1f", soldier.temp)}°F")
    }

    // Battery (operational risk)
    if (soldier.battery < 20) {
        riskFactors.add("LOW_BATT")
    }

    // Offline status
    if (soldier.status == "offline") {
        riskFactors.add("OFFLINE")
    }

    // Weighted sum — using only HR and SpO2 since we don't have
    // activity_index / respiratory_rate on the client.
    // Scale to approximate the full TA-CSS range (0-17.6).
    val partialWeighted = 0.8f * hrSub + 1.3f * spo2Sub
    // Scale factor: full max = 0.8*3 + 1.3*3 + 0.9*3 + 1.2*3 = 12.6
    //               partial max = 0.8*3 + 1.3*3 = 6.3
    // We scale partial → full range proportionally.
    val severityScore = partialWeighted * (12.6f / 6.3f)

    val classification = when (soldier.status) {
        "offline" -> "Stable"   // can't score offline
        else -> when {
            severityScore > 13.5f -> "Critical"
            severityScore > 6.5f  -> "Serious"
            else                  -> "Stable"
        }
    }

    // Override from actual soldier status if they're already marked critical
    val finalClassification = when (soldier.status) {
        "critical" -> if (classification != "Critical") "Critical" else classification
        "serious"  -> if (classification == "Stable") "Serious" else classification
        else       -> classification
    }

    val finalScore = when (soldier.status) {
        "critical" -> if (severityScore < 13.6f) 14f + (soldier.hr?.let { it - 130f } ?: 0f).coerceIn(0f, 6f) else severityScore
        "serious"  -> if (severityScore < 6.6f) 7f + (spo2Sub * 1.5f) else severityScore
        else       -> severityScore
    }

    // Trend direction (heuristic based on current state)
    val trend = when (soldier.status) {
        "offline" -> "stable"
        "stable"  -> if (riskFactors.isEmpty()) "improving" else "stable"
        else -> when {
            soldier.hr != null && soldier.hr!! > 140    -> "declining"
            soldier.spo2 != null && soldier.spo2!! < 88 -> "declining"
            else                                        -> "stable"
        }
    }

    // AI recommendation
    val recommendation = when (finalClassification) {
        "Critical" -> buildCriticalRecommendation(riskFactors)
        "Serious"  -> buildSeriousRecommendation(riskFactors)
        else       -> "Vitals within normal parameters. Continue standard monitoring protocol."
    }

    return SoldierAssessment(
        soldier        = soldier,
        severityScore  = finalScore,
        classification = finalClassification,
        riskFactors    = riskFactors,
        recommendation = recommendation,
        trendDirection = trend
    )
}

private fun buildCriticalRecommendation(factors: List<String>): String {
    val parts = mutableListOf<String>()
    parts.add("IMMEDIATE INTERVENTION REQUIRED.")

    if (factors.any { it.startsWith("HR:") }) {
        parts.add("Heart rate is dangerously elevated — administer beta-blocker and prepare IV access.")
    }
    if (factors.any { it.startsWith("SpO2:") }) {
        parts.add("Blood oxygen critically low — initiate supplemental O₂ and assess airway.")
    }
    if (factors.any { it.startsWith("TEMP:") }) {
        parts.add("Hyperthermia detected — initiate active cooling protocol.")
    }
    if (parts.size == 1) {
        parts.add("Multiple vitals are in critical ranges. Prepare for CASEVAC.")
    }
    return parts.joinToString(" ")
}

private fun buildSeriousRecommendation(factors: List<String>): String {
    val parts = mutableListOf("Monitor closely — condition may deteriorate.")
    if (factors.any { it.startsWith("HR:") }) {
        parts.add("Elevated heart rate requires attention.")
    }
    if (factors.any { it.startsWith("SpO2:") }) {
        parts.add("SpO₂ trending downward — prepare O₂ kit on standby.")
    }
    return parts.joinToString(" ")
}
