package com.example.deriv.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.example.DerivAccessibilityService
import com.example.deriv.ui.ChatMessage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.scale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.deriv.analytics.AnalysisReport
import com.example.deriv.database.TickEntity
import com.example.deriv.websocket.ConnectionStatus

// Core Slate Palette Definitions
val DarkBg = Color(0xFF0F111A)
val CardBg = Color(0xFF1A1F2E)
val BorderColor = Color(0xFF2C354A)

val DarkGreen = Color(0xFF00C853)
val DarkRed = Color(0xFFFF1744)
val DarkOrange = Color(0xFFFF9100)
val LiveBlue = Color(0xFF2979FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DigitAnalysisViewModel,
    isInPip: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val selectedSymbol by viewModel.selectedSymbol.collectAsState()
    val sampleSize by viewModel.sampleSize.collectAsState()
    val barrier by viewModel.barrier.collectAsState()
    val entryTimerEnabled by viewModel.entryTimerEnabled.collectAsState()
    val nextEntrySeconds by viewModel.nextEntrySeconds.collectAsState()
    val selectedTradeType by viewModel.selectedTradeType.collectAsState()
    
    val nowBarText by viewModel.nowBarText.collectAsState()
    val nowBarFlashActive by viewModel.nowBarFlashActive.collectAsState()
    val isPipSupported = viewModel.isPipSupported

    val livePrice by viewModel.livePrice.collectAsState()
    val liveLastDigit by viewModel.liveLastDigit.collectAsState()
    val liveTimestamp by viewModel.liveTimestamp.collectAsState()
    val isRise by viewModel.priceDirectionRise.collectAsState()

    val analysisReport by viewModel.analysisReport.collectAsState()
    val totalTicksList by viewModel.latestTicksFlow.collectAsState()
    val livePriceList = remember(totalTicksList) { totalTicksList.map { it.price } }
    val top40Ticks = remember(totalTicksList) { totalTicksList.take(40) }

    val recentDigits by viewModel.recentDigits.collectAsState()
    val customStrategies by viewModel.customStrategies.collectAsState()
    val customStrategyLogs by viewModel.customStrategyLogs.collectAsState()
    val geminiReportState by viewModel.geminiReportState.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val chatbotLoading by viewModel.chatbotLoading.collectAsState()

    // --- Dynamic User Settings ---
    val selectedTheme by viewModel.selectedColorTheme.collectAsState()
    val alertPreference by viewModel.alertPreference.collectAsState()
    val overUnderBias by viewModel.overUnderBias.collectAsState()
    val isOverUnderTriggered by viewModel.isOverUnderEntryTriggered.collectAsState()
    val overUnderConditionDesc by viewModel.overUnderConditionDesc.collectAsState()
    val matchesDiffersBias by viewModel.matchesDiffersBias.collectAsState()
    val evenOddBias by viewModel.evenOddBias.collectAsState()
    val riseFallBias by viewModel.riseFallBias.collectAsState()
    
    var showSettingsState by remember { mutableStateOf(false) }

    // --- AUTO-CLICKER FLOATING ACCESSIBILITY BUTTON STATES ---
    val autoClickerEnabled by viewModel.autoClickerEnabled.collectAsState()
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    val transactionLogs = remember { mutableStateListOf<String>() }

    LaunchedEffect(autoClickerEnabled) {
        if (autoClickerEnabled) {
            DerivAccessibilityService.showReticleOverlay(context)
        } else {
            DerivAccessibilityService.hideReticleOverlay()
        }
    }

    // Listen to active digit signals to trigger real Accessibility Service tap at reticle position
    LaunchedEffect(autoClickerEnabled) {
        viewModel.signalConfirmedFlow.collect { signalType ->
            if (autoClickerEnabled) {
                val isActiveOnOverlay = com.example.DerivAccessibilityService.overlayClickerActive.value
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                
                if (!isActiveOnOverlay) {
                    transactionLogs.add(0, "[$timeStr] ⏸ [AUTO-CLICK PAUSED] Click suppressed because Bot is toggled OFF on the Floating Overlay.")
                } else {
                    val clickX = com.example.DerivAccessibilityService.reticleX
                    val clickY = com.example.DerivAccessibilityService.reticleY
                    
                    val dispatched = com.example.DerivAccessibilityService.executeClickAt(clickX, clickY)
                    
                    if (dispatched) {
                        transactionLogs.add(0, "[$timeStr] 🤖 [AUTO-CLICK] Touch tap simulated at coordinates (X: ${clickX.roundToInt()}, Y: ${clickY.roundToInt()}) on signal match '$signalType'.")
                        viewModel.triggerVibration(500)
                    } else {
                        transactionLogs.add(0, "[$timeStr] ⚠️ [AUTO-CLICK FAILED] Click dispatch failed. Is Accessibility Service running?")
                    }
                }
            }
        }
    }

    // CENTRALIZED LIVE THEME COLOR MAPS
    val customBg = when (selectedTheme) {
        "CYBERPUNK" -> Color(0xFF120024)
        "FOREST" -> Color(0xFF081C10)
        "GOLD" -> Color(0xFF141414)
        else -> DarkBg // SLATE
    }

    val customCard = when (selectedTheme) {
        "CYBERPUNK" -> Color(0xFF2A004C)
        "FOREST" -> Color(0xFF10281C)
        "GOLD" -> Color(0xFF242424)
        else -> CardBg // SLATE
    }

    val customBorder = when (selectedTheme) {
        "CYBERPUNK" -> Color(0xFF5A1E96)
        "FOREST" -> Color(0xFF224A37)
        "GOLD" -> Color(0xFF383838)
        else -> BorderColor // SLATE
    }

    val customAccent = when (selectedTheme) {
        "CYBERPUNK" -> Color(0xFFFF007F) // fuchsia highlight
        "FOREST" -> Color(0xFF00E676) // neon green highlight
        "GOLD" -> Color(0xFFFFD700) // precious amber gold
        else -> LiveBlue // SLATE
    }

    val latencyColor = when {
        connectionStatus != ConnectionStatus.CONNECTED -> DarkRed
        latency < 100 -> customAccent
        latency < 250 -> DarkGreen
        latency < 500 -> DarkOrange
        else -> DarkRed
    }

    val latencyText = when (connectionStatus) {
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.CONNECTED -> "${latency}ms"
        ConnectionStatus.ERROR -> "Error"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
    }

    // Modal Preferences settings dialog
    if (showSettingsState) {
        val vibrationSetting by viewModel.vibrationSetting.collectAsState()
        val strengthThreshold by viewModel.vibrationStrengthThreshold.collectAsState()
        
        val p1Enabled by viewModel.p1Enabled.collectAsState()
        val p1TargetDigit by viewModel.p1TargetDigit.collectAsState()
        val p1MinConfidence by viewModel.p1MinConfidence.collectAsState()
        
        val p2Enabled by viewModel.p2Enabled.collectAsState()
        val p2TargetDigit by viewModel.p2TargetDigit.collectAsState()
        val p2MinConfidence by viewModel.p2MinConfidence.collectAsState()
        
        val p3Enabled by viewModel.p3Enabled.collectAsState()
        val p3MinFrequency by viewModel.p3MinFrequency.collectAsState()
        val p3MinAbsence by viewModel.p3MinAbsence.collectAsState()
        
        val p4Enabled by viewModel.p4Enabled.collectAsState()
        val p4MinAppearances by viewModel.p4MinAppearances.collectAsState()
        
        val sessionSignalLimit by viewModel.sessionSignalLimit.collectAsState()
        val sessionSignalCount by viewModel.sessionSignalCount.collectAsState()
        
        val timerSession1 by viewModel.timerSession1.collectAsState()
        val timerSession2 by viewModel.timerSession2.collectAsState()
        val timerSession3 by viewModel.timerSession3.collectAsState()
        
        val aiProvider by viewModel.aiProvider.collectAsState()
        val aiApiKey by viewModel.aiApiKey.collectAsState()
        
        AlertDialog(
            onDismissRequest = { showSettingsState = false },
            containerColor = customCard,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = "System Settings",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 1. ACTIVE STRATEGY SELECTION
                    Column {
                        Text("ACTIVE TRADE SYSTEM", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                TradeType.MATCHES_DIFFERS to "Matches",
                                TradeType.OVER_UNDER to "Over/Under"
                            ).forEach { (type, title) ->
                                val isSelected = selectedTradeType == type
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) customAccent else customBg)
                                        .border(1.dp, if (isSelected) customAccent else customBorder, RoundedCornerShape(8.dp))
                                        .clickable { viewModel.selectTradeType(type) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 2. ALERT LOCATION CHANNELS
                    Column {
                        Text("ALERT DELIVERY MODEL", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("BOTH" to "Both", "PIP" to "PiP Only", "SYSTEM_NOTIFICATION" to "Banner alert").forEach { (code, title) ->
                                val isSelected = alertPreference == code
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) customAccent else customBg)
                                        .border(1.dp, if (isSelected) customAccent else customBorder, RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setAlertPreference(code) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    // 3. TACTILE SENSITIVITIES
                    Column {
                        Text("TACTILE HAPTIC VIBRATION", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("STANDARD" to "Default", "TICK_ONLY" to "Tick", "HEAVY" to "Heavy", "OFF" to "Mute").forEach { (code, title) ->
                                val isSelected = vibrationSetting == code
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) customAccent else customBg)
                                        .border(1.dp, if (isSelected) customAccent else customBorder, RoundedCornerShape(8.dp))
                                        .clickable { 
                                            viewModel.setVibrationSetting(code)
                                            viewModel.triggerVibration(150)
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // 4. COLOR THEMES PRESETS
                    Column {
                        Text("COLOR IDENTITY THEME", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("SLATE" to "Slate", "CYBERPUNK" to "Cyber", "FOREST" to "Forest", "GOLD" to "Gold").forEach { (code, title) ->
                                val isSelected = selectedTheme == code
                                val themeDotColor = when (code) {
                                    "CYBERPUNK" -> Color(0xFFFF007F)
                                    "FOREST" -> Color(0xFF00E676)
                                    "GOLD" -> Color(0xFFFFD700)
                                    else -> LiveBlue
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) customAccent else customBg)
                                        .border(1.dp, if (isSelected) customAccent else customBorder, RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setSelectedColorTheme(code) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(themeDotColor)
                                        )
                                        Text(
                                            text = title,
                                            color = if (isSelected) Color.White else Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 5. MINIMUM HAPTIC SIGNAL STRENGTH FILTER
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SIGNAL STRENGTH FILTER",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${strengthThreshold.toInt()}% min",
                                color = customAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Slider(
                            value = strengthThreshold,
                            onValueChange = { viewModel.setVibrationStrengthThreshold(it) },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = customAccent,
                                activeTrackColor = customAccent,
                                inactiveTrackColor = customBorder
                            ),
                            modifier = Modifier.testTag("signal_strength_slider")
                        )
                    }

                    Divider(color = customBorder)

                    // 6. SESSION SIGNAL TARGETS
                    Column {
                        Text("TRADING STATISTICS AND THRESHOLDS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Current Session Signal Count:", color = Color.LightGray, fontSize = 11.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("$sessionSignalCount", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = { viewModel.resetSessionSignalCount() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("Reset", color = Color.White, fontSize = 9.sp)
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Session Signal Limit (Auto-Disengage):", color = Color.LightGray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(3 to "3", 5 to "5", 10 to "10", 25 to "25", -1 to "No Limit").forEach { (limit, title) ->
                                val isSel = sessionSignalLimit == limit
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) customAccent else customBg)
                                        .clickable { viewModel.setSessionSignalLimit(limit) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(title, color = if (isSel) Color.White else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Divider(color = customBorder)

                    // 7. SESSIONS SCHEDULE CLOCKS
                    Column {
                        Text("HOURLY SESSIONS (HH:mm format)", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Triple("1", timerSession1) { t: String -> viewModel.setTimerSession1(t) },
                                Triple("2", timerSession2) { t: String -> viewModel.setTimerSession2(t) },
                                Triple("3", timerSession3) { t: String -> viewModel.setTimerSession3(t) }
                            ).forEach { (num, textVal, setVal) ->
                                var tempText by remember(textVal) { mutableStateOf(textVal) }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Timer $num", color = Color.Gray, fontSize = 9.sp)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    BasicTextField(
                                        value = tempText,
                                        onValueChange = { input ->
                                            if (input.length <= 5) {
                                                tempText = input
                                                if (input.matches(Regex("^[0-2][0-9]:[0-5][0-9]$"))) {
                                                    setVal(input)
                                                }
                                            }
                                        },
                                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(customBg)
                                            .border(1.dp, customBorder, RoundedCornerShape(6.dp))
                                            .padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        Text("Make sure formatting uses 24H HH:mm format (e.g., 09:30, 21:15) to matching alert checks.", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
                    }

                    Divider(color = customBorder)

                    // 8. P1 ENGINE
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("P1: CONFID MATCH ENGINE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = p1Enabled,
                                onCheckedChange = { viewModel.setP1Enabled(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                        if (p1Enabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Target Digit: $p1TargetDigit", color = Color.LightGray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (0..9).forEach { d ->
                                    val isSel = p1TargetDigit == d
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSel) customAccent else customBg)
                                            .clickable { viewModel.setP1TargetDigit(d) }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(d.toString(), color = if (isSel) Color.White else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Min Confidence Threshold:", color = Color.Gray, fontSize = 11.sp)
                                Text("${(p1MinConfidence * 100).toInt()}%", color = customAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = p1MinConfidence.toFloat(),
                                onValueChange = { viewModel.setP1MinConfidence(it.toDouble()) },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = customAccent, activeTrackColor = customAccent)
                            )
                        }
                    }

                    Divider(color = customBorder)

                    // 9. P2 ENGINE
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("P2: CONTRA MATCH ENGINE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = p2Enabled,
                                onCheckedChange = { viewModel.setP2Enabled(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                        if (p2Enabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Target Digit: $p2TargetDigit", color = Color.LightGray, fontSize = 11.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                (0..9).forEach { d ->
                                    val isSel = p2TargetDigit == d
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSel) customAccent else customBg)
                                            .clickable { viewModel.setP2TargetDigit(d) }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(d.toString(), color = if (isSel) Color.White else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Min Confidence Threshold:", color = Color.Gray, fontSize = 11.sp)
                                Text("${(p2MinConfidence * 100).toInt()}%", color = customAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = p2MinConfidence.toFloat(),
                                onValueChange = { viewModel.setP2MinConfidence(it.toDouble()) },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = customAccent, activeTrackColor = customAccent)
                            )
                        }
                    }

                    Divider(color = customBorder)

                    // 10. P3 ENGINE
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("P3: FREQUENCY & ABSENCE ENGINE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = p3Enabled,
                                onCheckedChange = { viewModel.setP3Enabled(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                        if (p3Enabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Min Frequency Threshold:", color = Color.Gray, fontSize = 11.sp)
                                Text("${(p3MinFrequency * 100).toInt()}%", color = customAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = p3MinFrequency.toFloat(),
                                onValueChange = { viewModel.setP3MinFrequency(it.toDouble()) },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = customAccent, activeTrackColor = customAccent)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Min Absence periods:", color = Color.Gray, fontSize = 11.sp)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(5, 7, 10, 15, 20).forEach { a ->
                                        val isSel = p3MinAbsence == a
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSel) customAccent else customBg)
                                                .clickable { viewModel.setP3MinAbsence(a) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(a.toString(), color = if (isSel) Color.White else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = customBorder)

                    // 11. P4 ENGINE
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("P4: TREND SEQUENCE ENGINE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = p4Enabled,
                                onCheckedChange = { viewModel.setP4Enabled(it) },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                        if (p4Enabled) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Min Sequential Appearances:", color = Color.Gray, fontSize = 11.sp)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(2, 3, 4, 5, 6).forEach { ap ->
                                        val isSel = p4MinAppearances == ap
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSel) customAccent else customBg)
                                                .clickable { viewModel.setP4MinAppearances(ap) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(ap.toString(), color = if (isSel) Color.White else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Divider(color = customBorder)

                    // 12. SPECIALIZED AI QUANT PROVIDER & API CREDENTIAL KEYS
                    Column {
                        Text("SPECIALIZED AI PRO QUANT ENGINE", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "GEMINI" to "Gemini",
                                "OPENAI" to "OpenAI",
                                "CLAUDE" to "Claude",
                                "DEEPSEEK" to "DeepSeek"
                            ).forEach { (code, label) ->
                                val isSelected = aiProvider == code
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) customAccent else customBg)
                                        .border(1.dp, if (isSelected) customAccent else customBorder, RoundedCornerShape(8.dp))
                                        .clickable { viewModel.setAiProvider(code) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("CUSTOM PRIVATE API TOKEN KEY", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(customBg)
                                .border(1.dp, customBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (aiApiKey.isEmpty()) {
                                Text(
                                    text = if (aiProvider == "GEMINI") "Using standard Key or Enter custom key..." else "Enter your custom API key here...",
                                    color = Color.DarkGray,
                                    fontSize = 12.sp
                                )
                            }
                            BasicTextField(
                                value = aiApiKey,
                                onValueChange = { viewModel.setAiApiKey(it) },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = Color.White,
                                    fontSize = 12.sp
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (aiProvider == "GEMINI") 
                                "Leave blank to fall back on internal shared Google AI Studio BuildConfig key specifications." 
                            else 
                                "Required secret authorization credential to dispatch queries to your specific cloud provider.",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSettingsState = false },
                    colors = ButtonDefaults.buttonColors(containerColor = customAccent)
                ) {
                    Text("DONE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (isInPip) {
        // Picture in Picture View: Compact, high readability, shows selected predictions and color status
        val flashActive = if (selectedTradeType == TradeType.OVER_UNDER) isOverUnderTriggered else nowBarFlashActive
        val animatedBgColor = if (flashActive) customAccent.copy(alpha = 0.3f) else customBg
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(animatedBgColor)
                .padding(6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header (Symbol & Ping Latency dot)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedSymbol.replace("_", ""),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(latencyColor)
                        )
                        Text(
                            text = latencyText,
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }

                // Active Quote Box
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (livePrice > 0.0) String.format("%.2f", livePrice) else "Analyzing...",
                        color = if (isRise) DarkGreen else DarkRed,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (liveLastDigit != -1) {
                        Text(
                            text = "Last: $liveLastDigit",
                            color = if (flashActive) customAccent else DarkGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Contract-specific Telemetry Details in PiP Mode!
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (selectedTradeType) {
                        TradeType.OVER_UNDER -> {
                            val countNeeded = if (overUnderBias == "OVER") {
                                when (barrier) {
                                    0, 1 -> 1
                                    2 -> 2
                                    3 -> 3
                                    4 -> 4
                                    5 -> 5
                                    6 -> 6
                                    7 -> 7
                                    8 -> 8
                                    else -> 1
                                }
                            } else {
                                when (barrier) {
                                    9, 8 -> 1
                                    7 -> 2
                                    6 -> 3
                                    5 -> 4
                                    4 -> 5
                                    3 -> 6
                                    2 -> 7
                                    1 -> 8
                                    else -> 1
                                }
                            }
                            
                            val condTicks = recentDigits.take(countNeeded)
                            Text(
                                text = "O_U: $overUnderBias $barrier",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (flashActive) "🔥 EXECUTE INTERRUPT!" else "TICKS: $condTicks",
                                color = if (flashActive) Color.White else customAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TradeType.MATCHES_DIFFERS -> {
                            val report = analysisReport
                            if (report != null) {
                                val isMatches = matchesDiffersBias == "MATCHES"
                                val targetDigit = if (isMatches) report.topMatchesDigit else report.topDiffersDigit
                                val pctVal = if (isMatches) report.topMatchesPct else report.topDiffersPct
                                Text(
                                    text = "${matchesDiffersBias}: DIGIT $targetDigit",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Confidence: ${String.format("%.1f", pctVal)}%",
                                    color = customAccent,
                                    fontSize = 10.sp
                                )
                            } else {
                                Text(text = "Analyzing...", color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                        TradeType.EVEN_ODD -> {
                            val report = analysisReport
                            if (report != null) {
                                val biasVal = evenOddBias
                                val pctVal = if (biasVal == "EVEN") report.evenPercentage else report.oddPercentage
                                Text(
                                    text = "PARITY Preferred: $biasVal",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Trend: ${String.format("%.1f", pctVal)}%",
                                    color = customAccent,
                                    fontSize = 10.sp
                                )
                            } else {
                                Text(text = "Analyzing...", color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                        TradeType.RISE_FALL -> {
                            val report = analysisReport
                            if (report != null) {
                                val biasVal = riseFallBias
                                val pctVal = if (biasVal == "RISE") report.risePercentage else report.fallPercentage
                                Text(
                                    text = "TREND Preferred: $biasVal",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Momentum: ${String.format("%.1f", pctVal)}%",
                                    color = customAccent,
                                    fontSize = 10.sp
                                )
                            } else {
                                Text(text = "Analyzing...", color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Default Full App Workspace View
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = modifier.fillMaxSize(),
            containerColor = customBg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = customBg,
                        titleContentColor = Color.White
                    ),
                    title = {
                        Column {
                            Text(
                                text = "ProTrader · Deriv Digit Analysis",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                            Text(
                                text = "Live ticks · Predictions · Streaks · Digit stats",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            IconButton(onClick = { showSettingsState = true }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Preferences settings panel",
                                    tint = Color.White
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(customCard)
                                    .border(1.dp, customBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(latencyColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = latencyText,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LiveNowBar(
                    text = nowBarText,
                    flashActive = nowBarFlashActive,
                    isPipSupported = isPipSupported
                )
                    LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                // 1. Parameters Form Card
                item {
                    ParametersCard(
                        viewModel = viewModel,
                        currentSymbol = selectedSymbol,
                        currentSize = sampleSize,
                        currentBarrier = barrier,
                        timerEnabled = entryTimerEnabled,
                        selectedTradeType = selectedTradeType,
                        cardBgColor = customCard,
                        borderColor = customBorder,
                        darkBgColor = customBg,
                        accentColor = customAccent
                    )
                }

                // Strategy Trigger Cards (Only shown depending on chosen mode context)
                if (selectedTradeType == TradeType.OVER_UNDER) {
                    item {
                        OverUnderTriggerStatusCard(
                            bias = overUnderBias,
                            onBiasChange = { viewModel.setOverUnderBias(it) },
                            isTriggered = isOverUnderTriggered,
                            conditionDesc = overUnderConditionDesc,
                            accentColor = customAccent,
                            cardColor = customCard,
                            borderColor = customBorder
                        )
                    }
                } else if (selectedTradeType == TradeType.MATCHES_DIFFERS) {
                    item {
                        MatchesDiffersTriggerStatusCard(
                            bias = matchesDiffersBias,
                            onBiasChange = { viewModel.setMatchesDiffersBias(it) },
                            accentColor = customAccent,
                            cardColor = customCard,
                            borderColor = customBorder
                        )
                    }
                } else if (selectedTradeType == TradeType.EVEN_ODD) {
                    item {
                        EvenOddTriggerStatusCard(
                            bias = evenOddBias,
                            onBiasChange = { viewModel.setEvenOddBias(it) },
                            accentColor = customAccent,
                            cardColor = customCard,
                            borderColor = customBorder
                        )
                    }
                } else if (selectedTradeType == TradeType.RISE_FALL) {
                    item {
                        RiseFallTriggerStatusCard(
                            bias = riseFallBias,
                            onBiasChange = { viewModel.setRiseFallBias(it) },
                            accentColor = customAccent,
                            cardColor = customCard,
                            borderColor = customBorder
                        )
                    }
                }

                // Custom Strategies Dashboard Automation card
                item {
                    CustomStrategiesDashboardCard(
                        strategies = customStrategies,
                        logs = customStrategyLogs,
                        onAddStrategy = { viewModel.addCustomStrategy(it) },
                        onRemoveStrategy = { viewModel.removeCustomStrategy(it) },
                        onToggleStrategy = { viewModel.toggleCustomStrategy(it) },
                        accentColor = customAccent,
                        cardColor = customCard,
                        borderColor = customBorder
                    )
                }

                // 1.5. Gemini AI Quant Advisor Chatbot Card
                item {
                    GeminiAIAdvisorChatbotCard(
                        chatMessages = chatMessages,
                        chatbotLoading = chatbotLoading,
                        livePriceList = livePriceList,
                        onSendMessage = { viewModel.sendChatbotMessage(it) },
                        onClearChat = { viewModel.clearChatHistory() },
                        accentColor = customAccent,
                        cardBgColor = customCard,
                        borderColor = customBorder
                    )
                }

                // 2. Live Tick Quote Info Card
                item {
                    LiveTickCard(
                        symbol = selectedSymbol,
                        price = livePrice,
                        lastDigit = liveLastDigit,
                        timestamp = liveTimestamp,
                        isRise = isRise,
                        cardBgColor = customCard,
                        borderColor = customBorder
                    )
                }

                // 3. Next Entry Window Countdown
                item {
                    EntryWindowCard(
                        secondsLeft = nextEntrySeconds,
                        enabled = entryTimerEnabled,
                        cardBgColor = customCard,
                        borderColor = customBorder,
                        accentColor = customAccent
                    )
                }

                // Display only relevant data. Hide all analytics if OVER_UNDER is active.
                if (selectedTradeType == TradeType.MATCHES_DIFFERS) {
                    // 4. Matches / Differs Top Signals
                    item {
                        TopSignalsCard(
                            analysisReport = analysisReport,
                            cardBgColor = customCard,
                            borderColor = customBorder,
                            accentColor = customAccent
                        )
                    }
                    
                    // 5. Digit Frequency Panel
                    item {
                        DigitFrequencyCard(
                            analysisReport = analysisReport,
                            sampleSize = sampleSize,
                            cardBgColor = customCard,
                            borderColor = customBorder,
                            accentColor = customAccent
                        )
                    }

                    // 8. Matches / Differs Details Card
                    item {
                        MatchesDiffersDetailCard(
                            analysisReport = analysisReport,
                            cardBgColor = customCard,
                            borderColor = customBorder,
                            accentColor = customAccent
                        )
                    }

                    // 9. Live Ticks Feed List Card
                    item {
                        LiveTickFeedCard(
                            ticks = top40Ticks,
                            cardBgColor = customCard,
                            borderColor = customBorder,
                            accentColor = customAccent
                        )
                    }
                }

                // 10. Automated Assistant Auto Clicker controls
                item {
                    AutoClickerControlCard(
                        autoClickerEnabled = autoClickerEnabled,
                        onToggleAutoClicker = { viewModel.setAutoClickerEnabled(it) },
                        transactionLogs = transactionLogs,
                        cardBgColor = customCard,
                        borderColor = customBorder,
                        accentColor = customAccent
                    )
                }

                // Credits/Disclaimer Label
                item {
                    Text(
                        text = "Analytical tool for Deriv volatility indices. Past patterns do not guarantee future results. Trade responsibly.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                }
            } // closes LazyColumn
        } // closes Column
    } // closes Scaffold innerPadding ->

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDialog = false },
            title = { Text("Accessibility Service Required", color = Color.White) },
            text = {
                Text(
                    "To simulate automatic clicks on signal confirmation, you must enable the ProTrader Assistive Clicker Service in system settings.\n\n" +
                    "1. Tap 'Go to Settings'\n" +
                    "2. Navigate to 'Downloaded Apps' (or 'Accessibility Services')\n" +
                    "3. Choose 'ProTrader' and switch it ON.",
                    color = Color.LightGray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Cannot open settings: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Go to Settings", color = customAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = customCard,
            textContentColor = Color.LightGray,
            titleContentColor = Color.White
        )
    }
} // closes Box wrapper
} // closes else
} // closes MainScreen

// Subcomponents definitions

@Composable
fun ParametersCard(
    viewModel: DigitAnalysisViewModel,
    currentSymbol: String,
    currentSize: Int,
    currentBarrier: Int,
    timerEnabled: Boolean,
    selectedTradeType: TradeType,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    darkBgColor: Color = DarkBg,
    accentColor: Color = LiveBlue
) {
    var symbolMenuExpanded by remember { mutableStateOf(false) }
    var sizeMenuExpanded by remember { mutableStateOf(false) }

    val activeSymbolName = viewModel.symbols.find { it.first == currentSymbol }?.second ?: currentSymbol

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().testTag("parameters_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🎛️ COGNITIVE TRADE TERMINAL",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
                Box(
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LIVE CONFIG",
                        color = accentColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Divider(color = borderColor)

            // Section 1: Trade Types (Previously TradeTypeChooserCard)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "CONTRACT STRATEGY",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TradeTypeChip(
                            type = TradeType.MATCHES_DIFFERS,
                            isSelected = selectedTradeType == TradeType.MATCHES_DIFFERS,
                            onClick = { viewModel.selectTradeType(TradeType.MATCHES_DIFFERS) },
                            accentColor = accentColor,
                            modifier = Modifier.weight(1f)
                        )
                        TradeTypeChip(
                            type = TradeType.OVER_UNDER,
                            isSelected = selectedTradeType == TradeType.OVER_UNDER,
                            onClick = { viewModel.selectTradeType(TradeType.OVER_UNDER) },
                            accentColor = accentColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TradeTypeChip(
                            type = TradeType.EVEN_ODD,
                            isSelected = selectedTradeType == TradeType.EVEN_ODD,
                            onClick = { viewModel.selectTradeType(TradeType.EVEN_ODD) },
                            accentColor = accentColor,
                            modifier = Modifier.weight(1f)
                        )
                        TradeTypeChip(
                            type = TradeType.RISE_FALL,
                            isSelected = selectedTradeType == TradeType.RISE_FALL,
                            onClick = { viewModel.selectTradeType(TradeType.RISE_FALL) },
                            accentColor = accentColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Divider(color = borderColor)

            // Section 2: Parameters Grid (Symbol & (SampleSize or Barrier))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Column: SYMBOL
                Column(modifier = Modifier.weight(1.2f)) {
                    Text(
                        text = "SYMBOL",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(darkBgColor)
                            .clickable { symbolMenuExpanded = true }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentSymbol.replace("R_", "V_"),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select Symbol",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = symbolMenuExpanded,
                            onDismissRequest = { symbolMenuExpanded = false },
                            modifier = Modifier
                                .background(cardBgColor)
                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        ) {
                            viewModel.symbols.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(text = name, color = Color.White, fontSize = 12.sp) },
                                    onClick = {
                                        viewModel.selectSymbol(code)
                                        symbolMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Right Column: Sample Size OR Barrier Slider
                Column(modifier = Modifier.weight(0.8f)) {
                    if (selectedTradeType == TradeType.OVER_UNDER) {
                        Text(
                            text = "BARRIER: $currentBarrier",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(darkBgColor)
                                .padding(horizontal = 6.dp)
                        ) {
                            Slider(
                                value = currentBarrier.toFloat(),
                                onValueChange = { viewModel.selectBarrier(it.toInt()) },
                                valueRange = 0f..9f,
                                steps = 8,
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = borderColor
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            text = "SAMPLE SIZE",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(darkBgColor)
                                .clickable { sizeMenuExpanded = true }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$currentSize Ticks",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Select Size",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = sizeMenuExpanded,
                                onDismissRequest = { sizeMenuExpanded = false },
                                modifier = Modifier
                                    .background(cardBgColor)
                                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                            ) {
                                listOf(25, 50, 100, 200, 500, 1000).forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text(text = "$size ticks", color = Color.White, fontSize = 12.sp) },
                                        onClick = {
                                            viewModel.selectSampleSize(size)
                                            sizeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Divider(color = borderColor)

            // Section 3: Timer Activation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Timer Icon",
                        tint = if (timerEnabled) accentColor else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ENTRY TIMER COUNTDOWN",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (timerEnabled) "ACTIVE" else "DISABLED",
                        color = if (timerEnabled) accentColor else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Switch(
                        checked = timerEnabled,
                        onCheckedChange = { viewModel.toggleEntryTimer(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accentColor,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = darkBgColor
                        ),
                        modifier = Modifier
                            .scale(0.85f)
                            .testTag("entry_timer_switch")
                    )
                }
            }
        }
    }
}

@Composable
fun LiveTickCard(
    symbol: String,
    price: Double,
    lastDigit: Int,
    timestamp: String,
    isRise: Boolean,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
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
                    text = "${symbol.replace("_", " ")} · LIVE",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (isRise) DarkGreen.copy(alpha = 0.2f) else DarkRed.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isRise) "▲" else "▼",
                        color = if (isRise) DarkGreen else DarkRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (price > 0.0) String.format("%.2f", price) else "---.--",
                color = if (isRise) DarkGreen else DarkRed,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Last digit", color = Color.Gray, fontSize = 11.sp)
                    Text(
                        text = if (lastDigit != -1) lastDigit.toString() else "-",
                        color = DarkGreen,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("live_digit_badge")
                    )
                }

                if (timestamp.isNotEmpty()) {
                    Text(
                        text = timestamp,
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun EntryWindowCard(
    secondsLeft: Int,
    enabled: Boolean,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    accentColor: Color = LiveBlue
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
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
                    text = "NEXT ENTRY WINDOW",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = if (enabled) "running" else "paused",
                    color = if (enabled) DarkGreen else Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "$secondsLeft",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "sec",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (secondsLeft.toFloat() / 5f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = DarkGreen,
                trackColor = borderColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Perfect match entry cycle. Optimized 5-second synchronized entry sequence active with haptic cues.",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun TopSignalsCard(
    analysisReport: AnalysisReport?,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    accentColor: Color = LiveBlue
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "TOP SIGNALS",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Differs Box signal
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(text = "DIFFERS", color = Color.Gray, fontSize = 11.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "${analysisReport?.topDiffersDigit ?: '-'}",
                                color = DarkRed,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${String.format("%.1f", analysisReport?.topDiffersPct ?: 99.0)}%",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }

                // Matches Box signal
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(text = "MATCHES", color = Color.Gray, fontSize = 11.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "${analysisReport?.topMatchesDigit ?: '-'}",
                                color = DarkGreen,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${String.format("%.1f", analysisReport?.topMatchesPct ?: 20.0)}%",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }

            if (analysisReport != null && analysisReport.consolidatedPredictions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = borderColor)
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "PREDICTOR BRANCHES (CONTRIBUTING TO MATCHES DEEP ACCUMULATION)",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    analysisReport.consolidatedPredictions.forEach { pred ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(cardBgColor.copy(alpha = 0.5f))
                                .border(
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (pred.type == "P1") accentColor.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Badge with P1 or P2 label
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (pred.type == "P1") accentColor else DarkOrange)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = pred.type,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(10.dp))
							
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pred.displayName,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = pred.description,
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                            
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "Digit ${pred.digit}",
                                    color = if (pred.type == "P1") DarkGreen else DarkOrange,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "${String.format("%.1f", pred.confidence)}%",
                                    color = Color.Gray,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DigitFrequencyCard(
    analysisReport: AnalysisReport?,
    sampleSize: Int,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    accentColor: Color = LiveBlue
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
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
                    text = "DIGIT FREQUENCY",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$sampleSize ticks",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bars presentation inside Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                val freqs = analysisReport?.digitFrequencies ?: emptyMap()
                val maxPct = freqs.values.maxOrNull()?.coerceAtLeast(1.0) ?: 10.0

                for (digit in 0..9) {
                    val pct = freqs[digit] ?: 0.0
                    val relativeHeight = (pct / maxPct).toFloat().coerceIn(0.01f, 1f)

                    // Find if it's most or least frequent
                    var barColor = accentColor
                    if (freqs.isNotEmpty()) {
                        val minFreq = freqs.values.minOrNull() ?: 0.0
                        val maxFreq = freqs.values.maxOrNull() ?: 10.0
                        if (pct == maxFreq && pct != minFreq) {
                            barColor = DarkGreen
                        } else if (pct == minFreq && pct != maxFreq) {
                            barColor = DarkRed
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            text = "${String.format("%.1f", pct)}%",
                            fontSize = 8.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .fillMaxHeight(relativeHeight)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(barColor)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$digit",
                            fontSize = 12.sp,
                            color = if (barColor == DarkRed) DarkRed else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DarkGreen))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Most frequent", color = Color.Gray, fontSize = 10.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(DarkRed))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Least frequent", color = Color.Gray, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun CurrentStreaksCard(analysisReport: AnalysisReport?) {
    val streaks = analysisReport?.currentStreaks

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "CURRENT STREAKS",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Divider(color = BorderColor)

            // Parity row
            StreakRow(
                label = "PARITY",
                valueText = streaks?.parityType ?: "Odd",
                count = streaks?.parityCount ?: 0
            )

            Divider(color = BorderColor)

            // Barrier row
            StreakRow(
                label = "BARRIER",
                valueText = streaks?.barrierType ?: "Equal",
                count = streaks?.barrierCount ?: 0
            )

            Divider(color = BorderColor)

            // Direction row
            StreakRow(
                label = "DIRECTION",
                valueText = streaks?.directionType ?: "Rise",
                count = streaks?.directionCount ?: 0
            )

            Divider(color = BorderColor)

            // Same digit row
            StreakRow(
                label = "SAME DIGIT",
                valueText = "${streaks?.sameDigitValue ?: 5}",
                count = streaks?.sameDigitCount ?: 0
            )
        }
    }
}

@Composable
fun StreakRow(label: String, valueText: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = label, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(text = valueText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$count",
                color = LiveBlue,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "in a row",
                color = Color.Gray,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
fun OverUnderDistributionCard(analysisReport: AnalysisReport?, barrier: Int) {
    val overPercentage = analysisReport?.overPercentage ?: 50.0
    val underPercentage = analysisReport?.underPercentage ?: 50.0

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "OVER / UNDER (Trend)",
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Over
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "OVER $barrier", color = Color.White, fontSize = 12.sp)
                    Text(text = "${String.format("%.1f", overPercentage)}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Under
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "UNDER $barrier", color = Color.White, fontSize = 12.sp)
                    Text(text = "${String.format("%.1f", underPercentage)}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun EvenOddDistributionCard(analysisReport: AnalysisReport?) {
    val evenPercentage = analysisReport?.evenPercentage ?: 50.0
    val oddPercentage = analysisReport?.oddPercentage ?: 50.0

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "EVEN / ODD",
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Even
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "EVEN", color = Color.White, fontSize = 12.sp)
                    Text(text = "${String.format("%.1f", evenPercentage)}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Odd
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "ODD", color = Color.White, fontSize = 12.sp)
                    Text(text = "${String.format("%.1f", oddPercentage)}%", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun MatchesDiffersDetailCard(
    analysisReport: AnalysisReport?,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    accentColor: Color = LiveBlue
) {
    val predictions = analysisReport?.patternPredictions ?: emptyList()

    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "PATTERN SEQUENCE EXACT PREDICTIONS",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Derived chronologically from matching historical 4-digit prefix sequences in local 1000 ticks.",
                color = Color.Gray,
                fontSize = 10.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (predictions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    predictions.forEachIndexed { index, digit ->
                        if (index > 0) {
                            Text(text = "then", color = Color.Gray, fontSize = 11.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(accentColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$digit",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Pattern detected",
                        tint = DarkGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Historical pattern match located. Signal confidence is boosted.",
                        color = DarkGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cardBgColor.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No matching 4-digit patterns in history yet.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun LiveTickFeedCard(
    ticks: List<TickEntity>,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    accentColor: Color = LiveBlue
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
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
                    text = "LIVE TICK FEED",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "last 40 · newest first",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (ticks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Awaiting ticks...", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                // Render a grid mirroring PDF page 3
                val rows = ticks.chunked(8)
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEach { tick ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(38.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (tick == ticks.first()) DarkGreen.copy(alpha = 0.15f)
                                                else Color.White.copy(alpha = 0.05f)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (tick == ticks.first()) DarkGreen else Color.Transparent,
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${tick.digit}",
                                            color = if (tick == ticks.first()) DarkGreen else Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format("%.2f", tick.price).takeLast(6),
                                        color = Color.Gray,
                                        fontSize = 8.sp,
                                        maxLines = 1,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            // Fill blank columns if the chunk has less than 8 items
                            if (row.size < 8) {
                                repeat(8 - row.size) {
                                    Spacer(modifier = Modifier.width(38.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TradeTypeChooserCard(
    selectedType: TradeType,
    onSelectType: (TradeType) -> Unit,
    cardBgColor: Color = CardBg,
    borderColor: Color = BorderColor,
    accentColor: Color = LiveBlue
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "CONTRACT STRATEGY",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TradeTypeChip(
                        type = TradeType.MATCHES_DIFFERS,
                        isSelected = selectedType == TradeType.MATCHES_DIFFERS,
                        onClick = { onSelectType(TradeType.MATCHES_DIFFERS) },
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                    TradeTypeChip(
                        type = TradeType.OVER_UNDER,
                        isSelected = selectedType == TradeType.OVER_UNDER,
                        onClick = { onSelectType(TradeType.OVER_UNDER) },
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TradeTypeChip(
                        type = TradeType.EVEN_ODD,
                        isSelected = selectedType == TradeType.EVEN_ODD,
                        onClick = { onSelectType(TradeType.EVEN_ODD) },
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                    TradeTypeChip(
                        type = TradeType.RISE_FALL,
                        isSelected = selectedType == TradeType.RISE_FALL,
                        onClick = { onSelectType(TradeType.RISE_FALL) },
                        accentColor = accentColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cardBgColor.copy(alpha = 0.5f))
                    .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Mode: ${selectedType.description}",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TradeTypeChip(
    type: TradeType,
    isSelected: Boolean,
    onClick: () -> Unit,
    accentColor: Color = LiveBlue,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .testTag("trade_type_${type.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) accentColor else DarkBg,
        border = BorderStroke(1.dp, if (isSelected) accentColor else BorderColor)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = type.displayName,
                color = if (isSelected) Color.White else Color.Gray,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RiseFallVisualizerCard(analysisReport: AnalysisReport?) {
    val risePercentage = analysisReport?.risePercentage ?: 50.0
    val fallPercentage = analysisReport?.fallPercentage ?: 50.0
    val streaks = analysisReport?.currentStreaks

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "RISE / FALL MOMENTUM TREND",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Represents direct upward or downward price tick shifts for rise/fall or accumulators contracts.",
                color = Color.Gray,
                fontSize = 10.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DarkBg),
                    border = BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "RISE TREND (UP)", color = Color.Gray, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${String.format("%.1f", risePercentage)}%",
                            color = DarkGreen,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = DarkBg),
                    border = BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "FALL TREND (DOWN)", color = Color.Gray, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${String.format("%.1f", fallPercentage)}%",
                            color = DarkRed,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "FALL DIRECTION", color = DarkRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(text = "RISE DIRECTION", color = DarkGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (risePercentage.toFloat() / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = DarkGreen,
                    trackColor = DarkRed
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider(color = BorderColor)

            Column {
                Text(text = "ACCUMULATOR CONFIDENCE GUIDANCE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val guidanceText = when {
                    streaks?.directionType == "Rise" && streaks.directionCount >= 3 -> "Caution: High rise streak (${streaks.directionCount}). Fall reversal risks are high."
                    streaks?.directionType == "Fall" && streaks.directionCount >= 3 -> "Favorable: High fall streak (${streaks.directionCount}). Upside reversal momentum building."
                    else -> "Stable: Balanced market ticks. Suitable for standard accumulators."
                }
                Text(text = guidanceText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun LiveNowBar(
    text: String,
    flashActive: Boolean,
    isPipSupported: Boolean
) {
    val containerColor = if (flashActive) {
        Color(0xFFFFD600) // Deep neon gold alert background
    } else {
        CardBg
    }
    
    val textColor = if (flashActive) {
        Color.Black
    } else {
        Color.White
    }
    
    val borderColor = if (flashActive) {
        Color.White
    } else {
        LiveBlue.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("live_now_bar"),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (flashActive) DarkRed else LiveBlue)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = if (flashActive) "🔥 ENTRY ALERT NOW!" else "LIVE ALERTS STATUS BAR",
                    color = if (flashActive) DarkRed else LiveBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = text,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            
            if (!isPipSupported) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠️ Picture-In-Picture NOT supported by device. Background notifications and tactile vibration alerts are fully active.",
                    color = if (flashActive) Color.DarkGray else Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun OverUnderTriggerStatusCard(
    bias: String,
    onBiasChange: (String) -> Unit,
    isTriggered: Boolean,
    conditionDesc: String,
    accentColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OVER / UNDER STRATEGY MONITOR",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                // Trigger Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isTriggered) DarkGreen else Color.Gray.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isTriggered) "TRIGGERED" else "WAITING CONDITIONS",
                        color = if (isTriggered) Color.White else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Direction Bias selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SELECT ADVANCED BIAS DIRECTION",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("OVER", "UNDER").forEach { option ->
                        val isSelected = bias == option
                        Surface(
                            onClick = { onBiasChange(option) },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor else Color.Gray.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, if (isSelected) accentColor else Color.Transparent)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = option,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = borderColor.copy(alpha = 0.5f))

            // Condition requirements description
            Column {
                Text(
                    text = "ACCUMULATOR CRITERIA GUIDE & LIVE STATUS",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = conditionDesc,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
            }
        }
    }
}

@Composable
fun MatchesDiffersTriggerStatusCard(
    bias: String,
    onBiasChange: (String) -> Unit,
    accentColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MATCHES / DIFFERS PREFERENCE",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SELECT ACTIVE CONTRACT BIAS",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("MATCHES", "DIFFERS").forEach { option ->
                        val isSelected = bias == option
                        Surface(
                            onClick = { onBiasChange(option) },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor else Color.Gray.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, if (isSelected) accentColor else Color.Transparent)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = option,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = borderColor.copy(alpha = 0.5f))

            Column {
                Text(
                    text = "NOTIFICATIONS STATUS",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val description = if (bias == "MATCHES") {
                    "System will generate alerts and sound signals strictly for high-probability absolute digit MATCHES."
                } else {
                    "System will generate alerts and sound signals strictly for DIFFERS predictions (all digits except the target)."
                }
                Text(
                    text = description,
                    color = Color.White,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun EvenOddTriggerStatusCard(
    bias: String,
    onBiasChange: (String) -> Unit,
    accentColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EVEN / ODD CONTRACT SELECTION",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SELECT ADVANCED PARITY BIAS",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("EVEN", "ODD").forEach { option ->
                        val isSelected = bias == option
                        Surface(
                            onClick = { onBiasChange(option) },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor else Color.Gray.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, if (isSelected) accentColor else Color.Transparent)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = option,
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = borderColor.copy(alpha = 0.5f))

            Column {
                Text(
                    text = "NOTIFICATIONS STATUS",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val description = if (bias == "EVEN") {
                    "System will generate alerts and countdown updates strictly for EVEN contract parity strategies."
                } else {
                    "System will generate alerts and countdown updates strictly for ODD contract parity strategies."
                }
                Text(
                    text = description,
                    color = Color.White,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun RiseFallTriggerStatusCard(
    bias: String,
    onBiasChange: (String) -> Unit,
    accentColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RISE / FALL (ACCUMULATORS) TREND SELECTOR",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SELECT ACTIVE TREND BIAS",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("RISE", "FALL").forEach { option ->
                        val isSelected = bias == option
                        Surface(
                            onClick = { onBiasChange(option) },
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) accentColor else Color.Gray.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, if (isSelected) accentColor else Color.Transparent)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = if (option == "RISE") "ONLY UPS (RISE)" else "ONLY DOWNS (FALL)",
                                    color = if (isSelected) Color.White else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = borderColor.copy(alpha = 0.5f))

            Column {
                Text(
                    text = "NOTIFICATIONS STATUS",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val description = if (bias == "RISE") {
                    "System will generate alerts and momentum updates strictly for ONLY UPS (Rise contracts or bullish accumulator runs)."
                } else {
                    "System will generate alerts and momentum updates strictly for ONLY DOWNS (Fall contracts or bearish accumulator runs)."
                }
                Text(
                    text = description,
                    color = Color.White,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
fun CustomStrategiesDashboardCard(
    strategies: List<CustomStrategy>,
    logs: List<String>,
    onAddStrategy: (CustomStrategy) -> Unit,
    onRemoveStrategy: (String) -> Unit,
    onToggleStrategy: (String) -> Unit,
    accentColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "⚙️ ALGORITHMIC AUTOMATION ENGINE",
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Build mathematical triggers & alarms",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
                
                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = "+ CREATE RULE",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            if (strategies.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No strategies defined. Create a new trigger above!",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    strategies.forEach { strategy ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .border(1.dp, if (strategy.isEnabled) borderColor.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = strategy.name,
                                        color = if (strategy.isEnabled) Color.White else Color.Gray,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                when (strategy.conditionType) {
                                                    "LAST_DIGIT_EQUALS" -> Color(0xFF00B0FF).copy(alpha = 0.2f)
                                                    "CONSECUTIVE_PARITY" -> Color(0xFFAA00FF).copy(alpha = 0.2f)
                                                    "MATH_MODULO" -> Color(0xFFFFAB00).copy(alpha = 0.2f)
                                                    else -> Color(0xFF00E676).copy(alpha = 0.2f)
                                                }
                                            )
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = strategy.conditionType.replace("_", " "),
                                            color = when (strategy.conditionType) {
                                                "LAST_DIGIT_EQUALS" -> Color(0xFF00B0FF)
                                                "CONSECUTIVE_PARITY" -> Color(0xFFAA00FF)
                                                "MATH_MODULO" -> Color(0xFFFFAB00)
                                                else -> Color(0xFF00E676)
                                            },
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = strategy.description,
                                    color = Color.LightGray,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "ACTION: ${strategy.actionType.replace("_", " ")}",
                                    color = accentColor.copy(alpha = 0.7f),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Switch(
                                    checked = strategy.isEnabled,
                                    onCheckedChange = { onToggleStrategy(strategy.id) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = accentColor,
                                        checkedTrackColor = accentColor.copy(alpha = 0.4f),
                                        uncheckedThumbColor = Color.LightGray,
                                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier.scale(0.7f)
                                )
                                
                                IconButton(
                                    onClick = { onRemoveStrategy(strategy.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Rule",
                                        tint = DarkRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = borderColor.copy(alpha = 0.3f))

            // Strategy Logs Console
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "💻 REAL-TIME AUTOMATION TELEMETRY LOGGER",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(85.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Sandbox initialized. Waiting for algorithmic triggers...",
                            color = Color.DarkGray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = if (logLine.contains("Triggered")) Color(0xFF00FF66) else Color.Gray,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateStrategyDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { newStrategy ->
                onAddStrategy(newStrategy)
                showCreateDialog = false
            },
            accentColor = accentColor,
            cardColor = cardColor,
            borderColor = borderColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateStrategyDialog(
    onDismiss: () -> Unit,
    onSave: (CustomStrategy) -> Unit,
    accentColor: Color,
    cardColor: Color,
    borderColor: Color
) {
    var name by remember { mutableStateOf("Smart Rule") }
    var condType by remember { mutableStateOf("LAST_DIGIT_EQUALS") }
    var condParam by remember { mutableStateOf("0") }
    var actionType by remember { mutableStateOf("VIBRATE_AND_NOTIFY") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardColor,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "⚡ Forge Intelligent Strategy",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Rule name
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NAME OF ALGORITHM", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                            unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = accentColor,
                            focusedIndicatorColor = accentColor
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Rule Condition Type
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TRIGGER CONDITION DEFINITION", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    listOf(
                        "LAST_DIGIT_EQUALS" to "Last Digit Equal To",
                        "CONSECUTIVE_PARITY" to "Consecutive Parity (N list)",
                        "MATH_MODULO" to "Modulo Math Formula",
                        "TREND_MOMENTUM" to "Tick Trend Run"
                    ).forEach { (type, label) ->
                        val isSelected = condType == type
                        Surface(
                            onClick = { 
                                condType = type 
                                condParam = when(type) {
                                    "LAST_DIGIT_EQUALS" -> "0"
                                    "CONSECUTIVE_PARITY" -> "3_EVEN"
                                    "MATH_MODULO" -> "1"
                                    "TREND_MOMENTUM" -> "3_RISE"
                                    else -> ""
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isSelected) accentColor else Color.Gray.copy(alpha = 0.2f))
                        ) {
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) accentColor else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Condition Param
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CRITERIA / TARGET VALUE", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    when (condType) {
                        "LAST_DIGIT_EQUALS" -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                (0..9).forEach { num ->
                                    val isSel = condParam == num.toString()
                                    Surface(
                                        onClick = { condParam = num.toString() },
                                        modifier = Modifier.size(24.dp),
                                        shape = CircleShape,
                                        color = if (isSel) accentColor else Color.Gray.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, if (isSel) accentColor else Color.Transparent)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(num.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        "CONSECUTIVE_PARITY" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("3_EVEN" to "3 EVEN", "4_EVEN" to "4 EVEN", "3_ODD" to "3 ODD", "4_ODD" to "4 ODD").forEach { (valStr, lbl) ->
                                    val isSel = condParam == valStr
                                    Surface(
                                        onClick = { condParam = valStr },
                                        modifier = Modifier.weight(1f).height(30.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSel) accentColor else Color.Gray.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, if (isSel) accentColor else Color.Transparent)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(lbl, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        "MATH_MODULO" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("0" to "(Sum % 2) == 0 (Even Sum)", "1" to "(Sum % 2) == 1 (Odd Sum)").forEach { (valStr, lbl) ->
                                    val isSel = condParam == valStr
                                    Surface(
                                        onClick = { condParam = valStr },
                                        modifier = Modifier.weight(1f).height(30.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSel) accentColor else Color.Gray.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, if (isSel) accentColor else Color.Transparent)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(lbl, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        "TREND_MOMENTUM" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("3_RISE" to "3 UPS (📈)", "4_RISE" to "4 UPS (📈)", "3_FALL" to "3 DOWNS (📉)", "4_FALL" to "4 DOWNS (📉)").forEach { (valStr, lbl) ->
                                    val isSel = condParam == valStr
                                    Surface(
                                        onClick = { condParam = valStr },
                                        modifier = Modifier.weight(1f).height(30.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSel) accentColor else Color.Gray.copy(alpha = 0.1f),
                                        border = BorderStroke(1.dp, if (isSel) accentColor else Color.Transparent)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(lbl, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Response Action definition
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RESPONSE TELEPHONE / ACTUATOR", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ONLY_VIBRATE" to "Vibrate Motor", "ONLY_NOTIFY" to "Flash Alert", "VIBRATE_AND_NOTIFY" to "Vib & Alert").forEach { (type, lbl) ->
                            val isSel = actionType == type
                            Surface(
                                onClick = { actionType = type },
                                modifier = Modifier.weight(1f).height(30.dp),
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) accentColor else Color.Gray.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, if (isSel) accentColor else Color.Transparent)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(lbl, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val desc = when (condType) {
                        "LAST_DIGIT_EQUALS" -> "Triggers when the last digit is specifically $condParam."
                        "CONSECUTIVE_PARITY" -> {
                            val spl = condParam.split("_")
                            "Triggers when ${spl.getOrNull(0)} consecutive ${spl.getOrNull(1)} digits appear."
                        }
                        "MATH_MODULO" -> "Triggers when the modulo of the sum of last 2 digits == $condParam."
                        "TREND_MOMENTUM" -> {
                            val spl = condParam.split("_")
                            "Triggers when ${spl.getOrNull(0)} consecutive tick digits follow a ${spl.getOrNull(1)} trend."
                        }
                        else -> "Rule description."
                    }
                    onSave(
                        CustomStrategy(
                            name = name,
                            description = desc,
                            conditionType = condType,
                            conditionParam1 = condParam,
                            actionType = actionType
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
            ) {
                Text("INTEGRATE RULE", color = Color.White, fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.Gray, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun GeminiAIAdvisorChatbotCard(
    chatMessages: List<ChatMessage>,
    chatbotLoading: Boolean,
    livePriceList: List<Double>,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    accentColor: Color,
    cardBgColor: Color,
    borderColor: Color
) {
    var userText by remember { mutableStateOf("") }
    
    // Compute dynamic real-time Price Variance & stability percentage on the fly for the chatbot header
    val variance: Double
    val stability: Double
    val count = livePriceList.size
    
    if (count > 1) {
        val mean = livePriceList.average()
        val computedVar = livePriceList.map { Math.pow(it - mean, 2.0) }.average()
        val stdDev = Math.sqrt(computedVar)
        val stabilityValue = if (mean > 0) (1.0 - (stdDev / mean)) * 100.0 else 0.0
        
        variance = computedVar
        stability = stabilityValue.coerceIn(0.0, 100.0)
    } else {
        variance = 0.0
        stability = 100.0
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("gemini_chatbot_card"),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "🤖", fontSize = 20.sp)
                    Column {
                        Text(
                            text = "Gemini Quant Advisor",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "HFT price-drift context-aware chatbot",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }
                
                IconButton(
                    onClick = onClearChat,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear Chat history",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            Divider(color = borderColor)
            
            // Market Context Header Info Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Variance Info Block
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "PRICE VARIANCE", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = String.format("%.6f", variance),
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                // Stability Info Block
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "MARKET STABILITY", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        val stabilityColor = when {
                            stability >= 95.0 -> Color(0xFF00E676)
                            stability >= 90.0 -> Color(0xFFFFD600)
                            else -> Color(0xFFFF1744)
                        }
                        Text(
                            text = String.format("%.2f%%", stability),
                            color = stabilityColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            // Chat Message list window
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(chatMessages) { msg ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
                        ) {
                            Text(
                                text = if (msg.isUser) "Trader (You)" else "Gemini Advisor",
                                color = if (msg.isUser) accentColor else Color.Gray,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Box(
                                modifier = Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = if (msg.isUser) 12.dp else 0.dp,
                                            topEnd = if (msg.isUser) 0.dp else 12.dp,
                                            bottomStart = 12.dp,
                                            bottomEnd = 12.dp
                                        )
                                    )
                                    .background(if (msg.isUser) accentColor.copy(alpha = 0.2f) else borderColor.copy(alpha = 0.4f))
                                    .border(
                                        1.dp,
                                        if (msg.isUser) accentColor.copy(alpha = 0.4f) else borderColor,
                                        RoundedCornerShape(
                                            topStart = if (msg.isUser) 12.dp else 0.dp,
                                            topEnd = if (msg.isUser) 0.dp else 12.dp,
                                            bottomStart = 12.dp,
                                            bottomEnd = 12.dp
                                        )
                                    )
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = msg.timestamp,
                                color = Color.DarkGray,
                                fontSize = 8.sp
                            )
                        }
                    }
                    
                    if (chatbotLoading) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = accentColor,
                                    strokeWidth = 1.dp
                                )
                                Text(
                                    text = "Gemini is analyzing multi-term price variance...",
                                    color = Color.Gray,
                                    fontSize = 10.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    }
                }
                
                // Auto-scroll to bottom of chat when messages size changes or loading state changes
                LaunchedEffect(chatMessages.size, chatbotLoading) {
                    if (chatMessages.isNotEmpty()) {
                        listState.animateScrollToItem(chatMessages.size - 1)
                    }
                }
            }
            
            // Input field and send button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it },
                    placeholder = { Text("Ask about variance/stability...", fontSize = 11.sp, color = Color.Gray) },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("chatbot_input_field"),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.2f),
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = borderColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 2
                )
                
                Button(
                    onClick = {
                        if (userText.isNotBlank()) {
                            onSendMessage(userText)
                            userText = ""
                        }
                    },
                    enabled = !chatbotLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(50.dp)
                        .testTag("chatbot_send_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        text = "SEND",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AutoClickerControlCard(
    autoClickerEnabled: Boolean,
    onToggleAutoClicker: (Boolean) -> Unit,
    transactionLogs: List<String>,
    cardBgColor: Color,
    borderColor: Color,
    accentColor: Color
) {
    val context = LocalContext.current
    val serviceRunning = DerivAccessibilityService.isServiceEnabled()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("auto_clicker_control_card"),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "🤖 Assistive Auto-Clicker",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Real accessibility clicks on signal match",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        color = if (autoClickerEnabled) accentColor else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Switch(
                        checked = autoClickerEnabled,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                if (DerivAccessibilityService.isServiceEnabled()) {
                                    onToggleAutoClicker(true)
                                } else {
                                    try {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                        android.widget.Toast.makeText(context, "Please enable ProTrader under Downloaded Apps/Accessibility Services", android.widget.Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Could not open Accessibility Settings: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                onToggleAutoClicker(false)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = accentColor,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = borderColor
                        )
                    )
                }
            }
            
            // Large Prominent Play/Stop Toggle Action Button
            Button(
                onClick = {
                    val nextState = !autoClickerEnabled
                    if (nextState) {
                        if (DerivAccessibilityService.isServiceEnabled()) {
                            onToggleAutoClicker(true)
                        } else {
                            try {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                                android.widget.Toast.makeText(context, "Please enable ProTrader under Downloaded Apps/Accessibility Services", android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Could not open Accessibility Settings: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        onToggleAutoClicker(false)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("play_pause_clicker_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoClickerEnabled) Color(0xFFFF3D00) else Color(0xFF00E676)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (autoClickerEnabled) "⏸" else "▶",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (autoClickerEnabled) "STOP / IDLE RUN" else "START AUTOMATED CLICKER",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            
            Divider(color = borderColor)
            
            // Status Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (serviceRunning) Color(0xFF00E676) else Color(0xFFFF5252))
                    )
                    Text(
                        text = if (serviceRunning) "ACCESSIBILITY SERVICE RUNNING" else "ACCESSIBILITY SERVICE DISABLED",
                        color = if (serviceRunning) Color(0xFF00E676) else Color(0xFFFF5252),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                if (!serviceRunning) {
                    Text(
                        text = "TAP TO ENABLE",
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            if (autoClickerEnabled) {
                // Show current spatial coordinates
                val clickTargetX = DerivAccessibilityService.reticleX.toInt()
                val clickTargetY = DerivAccessibilityService.reticleY.toInt()
                Text(
                    text = "🎯 Target: Coordinate tap gestures will be simulated exactly at ($clickTargetX px, $clickTargetY px) relative to the screen coordinates.",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            } else {
                Text(
                    text = "Provide accessibility clicker permission. Enable this to overlay a movable hover crosshair reticle target. Position this reticle over any trade entry buttons (like our platform buttons or external trading layout keys). It will simulate gesture tap inputs perfectly when signals are matched on reset!",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            
            // Click Logs Ledger
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "REAL-TIME AUTOMATION LOGS",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (transactionLogs.isEmpty()) {
                        Text(
                            text = "Auto-click logs will be recorded here...",
                            color = Color.DarkGray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(transactionLogs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = if (logLine.contains("FAILED") || logLine.contains("⚠️")) Color(0xFFFF5252) else Color(0xFF00FF66),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

