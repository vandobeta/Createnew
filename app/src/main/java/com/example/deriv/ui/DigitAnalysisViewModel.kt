package com.example.deriv.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.deriv.analytics.AnalysisReport
import com.example.deriv.analytics.DigitAnalysisEngine
import com.example.deriv.database.AppDatabase
import com.example.deriv.database.TickEntity
import com.example.deriv.database.TickRepository
import com.example.deriv.websocket.ConnectionStatus
import com.example.deriv.websocket.DerivWebSocketManager
import com.example.deriv.websocket.LiveTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TradeType(val displayName: String, val description: String) {
    MATCHES_DIFFERS("Matches/Differs", "Predict exact digit matches"),
    OVER_UNDER("Over/Under", "Predict digit is over or under barrier"),
    EVEN_ODD("Even/Odd", "Predict the last digit parity"),
    RISE_FALL("Rise/Fall", "Predict rise/fall price trends")
}

data class CustomStrategy(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val conditionType: String, // "LAST_DIGIT_EQUALS", "CONSECUTIVE_PARITY", "MATH_MODULO", "TREND_MOMENTUM"
    val conditionParam1: String, // Value, or count
    val actionType: String, // "VIBRATE_AND_NOTIFY", "ONLY_VIBRATE", "ONLY_NOTIFY"
    val isEnabled: Boolean = true
)

class DigitAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = TickRepository(db.tickDao())
    private val socketManager = DerivWebSocketManager()

    // --- USER CUSTOMISABLE SETTINGS STATES ---
    private val _alertPreference = MutableStateFlow("BOTH") // "PIP", "SYSTEM_NOTIFICATION", "BOTH"
    val alertPreference: StateFlow<String> = _alertPreference.asStateFlow()

    private val _vibrationSetting = MutableStateFlow("STANDARD") // "STANDARD", "TICK_ONLY", "HEAVY", "OFF"
    val vibrationSetting: StateFlow<String> = _vibrationSetting.asStateFlow()

    private val _selectedColorTheme = MutableStateFlow("SLATE") // "SLATE", "CYBERPUNK", "FOREST", "GOLD"
    val selectedColorTheme: StateFlow<String> = _selectedColorTheme.asStateFlow()

    private val _overUnderBias = MutableStateFlow("OVER") // "OVER", "UNDER"
    val overUnderBias: StateFlow<String> = _overUnderBias.asStateFlow()

    private val _isOverUnderEntryTriggered = MutableStateFlow(false)
    val isOverUnderEntryTriggered: StateFlow<Boolean> = _isOverUnderEntryTriggered.asStateFlow()

    private val _overUnderConditionDesc = MutableStateFlow("Choose bias and barrier to activate.")
    val overUnderConditionDesc: StateFlow<String> = _overUnderConditionDesc.asStateFlow()

    private val _recentDigits = MutableStateFlow<List<Int>>(emptyList())
    val recentDigits: StateFlow<List<Int>> = _recentDigits.asStateFlow()

    private val _customStrategies = MutableStateFlow<List<CustomStrategy>>(listOf(
        CustomStrategy(
            id = "default_zero",
            name = "Golden Zero Hunter",
            description = "Triggers heavier alerts immediately when the last tick digit matches 0.",
            conditionType = "LAST_DIGIT_EQUALS",
            conditionParam1 = "0",
            actionType = "VIBRATE_AND_NOTIFY"
        ),
        CustomStrategy(
            id = "default_triple_even",
            name = "Triple Even Tracker",
            description = "Triggers screen alerts when 3 consecutive even digits occur.",
            conditionType = "CONSECUTIVE_PARITY",
            conditionParam1 = "3_EVEN",
            actionType = "ONLY_NOTIFY"
        ),
        CustomStrategy(
            id = "default_math_odd",
            name = "Sum Parity Algorithm",
            description = "Checks math relation: (Last Digit + Previous Digit) % 2 == 1 (Odd modulo sum).",
            conditionType = "MATH_MODULO",
            conditionParam1 = "1",
            actionType = "ONLY_VIBRATE"
        )
    ))
    val customStrategies: StateFlow<List<CustomStrategy>> = _customStrategies.asStateFlow()

    private val _customStrategyLogs = MutableStateFlow<List<String>>(emptyList())
    val customStrategyLogs: StateFlow<List<String>> = _customStrategyLogs.asStateFlow()

    fun addCustomStrategy(strategy: CustomStrategy) {
        _customStrategies.value = _customStrategies.value + strategy
        addStrategyLog("Created custom strategy: ${strategy.name}")
    }

    fun removeCustomStrategy(id: String) {
        val strategy = _customStrategies.value.find { it.id == id }
        _customStrategies.value = _customStrategies.value.filter { it.id != id }
        strategy?.let { addStrategyLog("Removed strategy: ${it.name}") }
    }

    fun toggleCustomStrategy(id: String) {
        _customStrategies.value = _customStrategies.value.map {
            if (it.id == id) {
                val nextState = !it.isEnabled
                addStrategyLog("${if (nextState) "Enabled" else "Disabled"} strategy: ${it.name}")
                it.copy(isEnabled = nextState)
            } else it
        }
    }

    fun addStrategyLog(logMessage: String) {
        val currentLogs = _customStrategyLogs.value.toMutableList()
        val timeFormatted = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        currentLogs.add(0, "[$timeFormatted] $logMessage")
        if (currentLogs.size > 30) {
            currentLogs.removeAt(currentLogs.size - 1)
        }
        _customStrategyLogs.value = currentLogs
    }

    private val recentDigitsForTrigger = mutableListOf<Int>()

    fun setAlertPreference(pref: String) {
        _alertPreference.value = pref
    }

    fun setVibrationSetting(setting: String) {
        _vibrationSetting.value = setting
    }

    fun setSelectedColorTheme(theme: String) {
        _selectedColorTheme.value = theme
    }

    fun setOverUnderBias(bias: String) {
        _overUnderBias.value = bias
        evaluateOverUnderTrigger(recentDigitsForTrigger)
    }

    private val _matchesDiffersBias = MutableStateFlow("MATCHES") // "MATCHES", "DIFFERS"
    val matchesDiffersBias: StateFlow<String> = _matchesDiffersBias.asStateFlow()

    fun setMatchesDiffersBias(bias: String) {
        _matchesDiffersBias.value = bias
    }

    private val _evenOddBias = MutableStateFlow("EVEN") // "EVEN", "ODD"
    val evenOddBias: StateFlow<String> = _evenOddBias.asStateFlow()

    fun setEvenOddBias(bias: String) {
        _evenOddBias.value = bias
    }

    private val _riseFallBias = MutableStateFlow("RISE") // "RISE", "FALL"
    val riseFallBias: StateFlow<String> = _riseFallBias.asStateFlow()

    fun setRiseFallBias(bias: String) {
        _riseFallBias.value = bias
    }

    // Active trade level type selection
    private val _selectedTradeType = MutableStateFlow(TradeType.MATCHES_DIFFERS)
    val selectedTradeType: StateFlow<TradeType> = _selectedTradeType.asStateFlow()

    // Symbols dropdown representation list
    val symbols = listOf(
        "R_10" to "Volatility 10 Index",
        "1HZ10V" to "Volatility 10 (1s) Index",
        "R_15" to "Volatility 15 Index",
        "1HZ15V" to "Volatility 15 (1s) Index",
        "R_75" to "Volatility 75 Index",
        "1HZ75V" to "Volatility 75 (1s) Index",
        "R_100" to "Volatility 100 Index",
        "1HZ100V" to "Volatility 100 (1s) Index"
    )

    // Selection parameters
    private val _selectedSymbol = MutableStateFlow("R_100")
    val selectedSymbol: StateFlow<String> = _selectedSymbol.asStateFlow()

    private val _sampleSize = MutableStateFlow(50)
    val sampleSize: StateFlow<Int> = _sampleSize.asStateFlow()

    private val _barrier = MutableStateFlow(5)
    val barrier: StateFlow<Int> = _barrier.asStateFlow()

    private val _entryTimerEnabled = MutableStateFlow(true)
    val entryTimerEnabled: StateFlow<Boolean> = _entryTimerEnabled.asStateFlow()

    private val _nextEntrySeconds = MutableStateFlow(5)
    val nextEntrySeconds: StateFlow<Int> = _nextEntrySeconds.asStateFlow()

    // Now bar & live notification banner states
    private val _nowBarText = MutableStateFlow("🎯 Ready for entry signals. Waiting for timer...")
    val nowBarText: StateFlow<String> = _nowBarText.asStateFlow()

    private val _nowBarFlashActive = MutableStateFlow(false)
    val nowBarFlashActive: StateFlow<Boolean> = _nowBarFlashActive.asStateFlow()

    // Local lazy hardware capabilities discovery
    val isPipSupported: Boolean by lazy {
        try {
            getApplication<Application>().packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
            )
        } catch (e: Throwable) {
            false
        }
    }

    // Live socket quotes
    private val _livePrice = MutableStateFlow(0.0)
    val livePrice: StateFlow<Double> = _livePrice.asStateFlow()

    private val _liveLastDigit = MutableStateFlow(-1)
    val liveLastDigit: StateFlow<Int> = _liveLastDigit.asStateFlow()

    private val _liveTimestamp = MutableStateFlow("")
    val liveTimestamp: StateFlow<String> = _liveTimestamp.asStateFlow()

    private val _priceDirectionRise = MutableStateFlow(true)
    val priceDirectionRise: StateFlow<Boolean> = _priceDirectionRise.asStateFlow()

    // Connection states
    val connectionStatus: StateFlow<ConnectionStatus> = socketManager.connectionStatus
    val latency: StateFlow<Long> = socketManager.latency

    // Timer Job
    private var timerJob: Job? = null
    
    // Ticks feed Flow mapped securely from selected symbol
    val latestTicksFlow: StateFlow<List<TickEntity>> = _selectedSymbol
        .flatMapLatest { symbol ->
            repository.getLatest1000TicksFlow(symbol)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calculated analysis state mapped reactively
    val analysisReport: StateFlow<AnalysisReport?> = combine(
        latestTicksFlow,
        _sampleSize,
        _barrier
    ) { ticks, size, bar ->
        try {
            DigitAnalysisEngine.analyze(ticks, size, bar)
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "Error calculating DigitAnalysisEngine stats: ${e.message}", e)
            null
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        try {
            createNotificationChannel()
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "createNotificationChannel crash: ${e.message}")
        }
        
        try {
            socketManager.connect()
            socketManager.subscribeToSymbol(_selectedSymbol.value)
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "socketManager crash: ${e.message}")
        }
        
        try {
            startTimerLoop()
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "startTimerLoop crash: ${e.message}")
        }

        // Handle incoming live ticks
        viewModelScope.launch(Dispatchers.IO) {
            try {
                socketManager.tickFlow.collect { tick ->
                    if (tick.symbol == _selectedSymbol.value) {
                        _priceDirectionRise.value = tick.price >= _livePrice.value
                        _livePrice.value = tick.price
                        _liveLastDigit.value = tick.lastDigit
                        _liveTimestamp.value = formatEpoch(tick.epoch)

                        val currentDigitsList = synchronized(recentDigitsForTrigger) {
                            recentDigitsForTrigger.add(0, tick.lastDigit)
                            while (recentDigitsForTrigger.size > 15) {
                                recentDigitsForTrigger.removeAt(recentDigitsForTrigger.size - 1)
                            }
                            recentDigitsForTrigger.toList()
                        }
                        _recentDigits.value = currentDigitsList

                        // Evaluate Over/Under trigger
                        evaluateOverUnderTrigger(currentDigitsList)

                        // Evaluate Custom fully customisable strategies
                        evaluateCustomStrategies(currentDigitsList, tick.price)

                        // Persist to local database
                        repository.saveTick(
                            symbol = tick.symbol,
                            price = tick.price,
                            epoch = tick.epoch,
                            digit = tick.lastDigit
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DigitAnalysisViewModel", "tick collect exception: ${e.message}")
            }
        }
    }

    fun selectSymbol(symbolCode: String) {
        if (_selectedSymbol.value == symbolCode) return
        _selectedSymbol.value = symbolCode
        
        // Reset short term live state
        _livePrice.value = 0.0
        _liveLastDigit.value = -1
        _liveTimestamp.value = ""
        
        socketManager.subscribeToSymbol(symbolCode)
    }

    fun selectTradeType(type: TradeType) {
        _selectedTradeType.value = type
    }

    fun selectSampleSize(size: Int) {
        _sampleSize.value = size
    }

    fun selectBarrier(bar: Int) {
        _barrier.value = bar
    }

    fun toggleEntryTimer(enabled: Boolean) {
        _entryTimerEnabled.value = enabled
        if (enabled) {
            startTimerLoop()
        } else {
            timerJob?.cancel()
        }
    }

    private val notificationChannelId = "deriv_alerts_channel"
    private val notificationId = 9999

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Deriv Trade Alerts"
            val descriptionText = "Entry alerts for Matches/Differs predictions"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(notificationChannelId, name, importance).apply {
                description = descriptionText
                enableVibration(false) // We control vibration ourselves manually!
                setShowBadge(true)
            }
            val notificationManager = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerVibration(durationMs: Long) {
        val setting = _vibrationSetting.value
        if (setting == "OFF") return
        
        // Custom durations depending on the haptic settings chosen by the user in settings
        val finalDuration = when (setting) {
            "TICK_ONLY" -> 45L
            "HEAVY" -> durationMs * 2
            else -> durationMs
        }
        
        try {
            val context = getApplication<Application>()
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(finalDuration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(finalDuration)
            }
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "Vibration failed: ${e.message}")
        }
    }

    fun vibrateBasedOnSetting(durationMs: Long) {
        triggerVibration(durationMs)
    }

    fun showAlertBasedOnSetting(title: String, msg: String) {
        val alertPref = _alertPreference.value
        if (alertPref == "SYSTEM_NOTIFICATION" || alertPref == "BOTH") {
            showSystemNotification(title, msg)
        }
        _nowBarText.value = "🚨 MATCH: $title - $msg"
        _nowBarFlashActive.value = true
        viewModelScope.launch {
            delay(3000)
            _nowBarFlashActive.value = false
        }
    }

    private var lastTriggerTime = 0L

    fun evaluateOverUnderTrigger(recentTicks: List<Int>) {
        if (recentTicks.isEmpty()) return
        val barrierVal = _barrier.value
        val bias = _overUnderBias.value
        val lastTick = recentTicks.first()
        var triggered = false
        var conditionMsg = ""

        if (bias == "OVER") {
            when (barrierVal) {
                0 -> {
                    triggered = lastTick == 0
                    conditionMsg = "Over 0 Rule: Last Tick is 0. Status: ($lastTick)"
                }
                1 -> {
                    triggered = lastTick in listOf(0, 1)
                    conditionMsg = "Over 1 Rule: Last Tick is 0 or 1. Status: ($lastTick)"
                }
                2 -> {
                    val countNeeded = 2
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 2 }
                        conditionMsg = "Over 2 Rule: Last 2 Ticks <= 2. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting ticks (Need last $countNeeded ticks <= 2)..."
                    }
                }
                3 -> {
                    val countNeeded = 3
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 3 }
                        conditionMsg = "Over 3 Rule: Last 3 Ticks <= 3. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting ticks (Need last $countNeeded ticks <= 3)..."
                    }
                }
                4 -> {
                    val countNeeded = 4
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 4 }
                        conditionMsg = "Over 4 Rule: Last 4 Ticks <= 4. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting ticks (Need last $countNeeded Ticks <= 4)..."
                    }
                }
                5 -> {
                    val countNeeded = 5
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 5 }
                        conditionMsg = "Over 5 Rule: Last 5 Ticks <= 5. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting ticks (Need last $countNeeded Ticks <= 5)..."
                    }
                }
                6 -> {
                    val countNeeded = 6
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 6 }
                        conditionMsg = "Over 6 Rule: Last 6 Ticks <= 6. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks <= 6)..."
                    }
                }
                7 -> {
                    val countNeeded = 7
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 7 }
                        conditionMsg = "Over 7 Rule: Last 7 Ticks <= 7. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks <= 7)..."
                    }
                }
                8 -> {
                    val countNeeded = 8
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it <= 8 }
                        conditionMsg = "Over 8 Rule: Last 8 Ticks <= 8. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks <= 8)..."
                    }
                }
                else -> {
                    triggered = lastTick > barrierVal
                    conditionMsg = "Standard Over Mode. Last Tick: $lastTick > Barrier: $barrierVal"
                }
            }
        } else { // UNDER
            when (barrierVal) {
                9 -> {
                    triggered = lastTick == 9
                    conditionMsg = "Under 9 Rule: Last Tick is 9. Status: ($lastTick)"
                }
                8 -> {
                    triggered = lastTick in listOf(8, 9)
                    conditionMsg = "Under 8 Rule: Last Tick is 8 or 9. Status: ($lastTick)"
                }
                7 -> {
                    val countNeeded = 2
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 7 }
                        conditionMsg = "Under 7 Rule: Last 2 Ticks >= 7. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 7)..."
                    }
                }
                6 -> {
                    val countNeeded = 3
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 6 }
                        conditionMsg = "Under 6 Rule: Last 3 Ticks >= 6. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 6)..."
                    }
                }
                5 -> {
                    val countNeeded = 4
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 5 }
                        conditionMsg = "Under 5 Rule: Last 4 Ticks >= 5. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 5)..."
                    }
                }
                4 -> {
                    val countNeeded = 5
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 4 }
                        conditionMsg = "Under 4 Rule: Last 5 Ticks >= 4. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 4)..."
                    }
                }
                3 -> {
                    val countNeeded = 6
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 3 }
                        conditionMsg = "Under 3 Rule: Last 6 Ticks >= 3. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 3)..."
                    }
                }
                2 -> {
                    val countNeeded = 7
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 2 }
                        conditionMsg = "Under 2 Rule: Last 7 Ticks >= 2. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 2)..."
                    }
                }
                1 -> {
                    val countNeeded = 8
                    if (recentTicks.size >= countNeeded) {
                        val sub = recentTicks.take(countNeeded)
                        triggered = sub.all { it >= 1 }
                        conditionMsg = "Under 1 Rule: Last 8 Ticks >= 1. Ticks: $sub"
                    } else {
                        conditionMsg = "Collecting Ticks (Need last $countNeeded Ticks >= 1)..."
                    }
                }
                else -> {
                    triggered = lastTick < barrierVal
                    conditionMsg = "Standard Under Mode. Last Tick: $lastTick < Barrier: $barrierVal"
                }
            }
        }

        _isOverUnderEntryTriggered.value = triggered
        _overUnderConditionDesc.value = conditionMsg

        // If newly triggered, trigger matching alerts based on haptics + alert preference
        val now = System.currentTimeMillis()
        if (triggered && _selectedTradeType.value == TradeType.OVER_UNDER && (now - lastTriggerTime > 3000)) {
            lastTriggerTime = now
            val modeTitle = "🔥 OVER/UNDER TRIGGER ACTIVE!"
            val modeMsg = "Sequence condition on barrier $barrierVal matched! Execute contract immediately."
            
            // Trigger haptic vibration
            vibrateBasedOnSetting(750)
            
            // Show alert
            showAlertBasedOnSetting(modeTitle, modeMsg)
        }
    }

    private val lastCustomTriggerTime = mutableMapOf<String, Long>()

    fun evaluateCustomStrategies(recentTicks: List<Int>, currentPrice: Double) {
        if (recentTicks.isEmpty()) return
        val lastDigit = recentTicks.first()
        val now = System.currentTimeMillis()

        _customStrategies.value.forEach { strategy ->
            if (!strategy.isEnabled) return@forEach

            val lastTime = lastCustomTriggerTime[strategy.id] ?: 0L
            if (now - lastTime < 3000) return@forEach

            var isTriggered = false
            var triggerDesc = ""

            when (strategy.conditionType) {
                "LAST_DIGIT_EQUALS" -> {
                    val targetDigit = strategy.conditionParam1.toIntOrNull()
                    if (targetDigit != null && lastDigit == targetDigit) {
                        isTriggered = true
                        triggerDesc = "Last Digit matches target $targetDigit!"
                    }
                }
                "CONSECUTIVE_PARITY" -> {
                    val param = strategy.conditionParam1 // "3_EVEN", "4_ODD", etc
                    val parts = param.split("_")
                    if (parts.size == 2) {
                        val count = parts[0].toIntOrNull() ?: 3
                        val parity = parts[1]
                        if (recentTicks.size >= count) {
                            val sub = recentTicks.take(count)
                            isTriggered = if (parity == "EVEN") {
                                sub.all { it % 2 == 0 }
                            } else {
                                sub.all { it % 2 != 0 }
                            }
                            if (isTriggered) {
                                triggerDesc = "Consecutive Parity Match: $count $parity ($sub)!"
                            }
                        }
                    }
                }
                "MATH_MODULO" -> {
                    // (Last Digit + Previous Digit) % 2 == modulo
                    if (recentTicks.size >= 2) {
                        val digit1 = recentTicks[0]
                        val digit2 = recentTicks[1]
                        val targetMod = strategy.conditionParam1.toIntOrNull() ?: 0
                        if ((digit1 + digit2) % 2 == targetMod) {
                            isTriggered = true
                            triggerDesc = "Modulo sum ($digit1 + $digit2) % 2 == $targetMod!"
                        }
                    }
                }
                "TREND_MOMENTUM" -> {
                    val param = strategy.conditionParam1 // e.g. "3_RISE", "3_FALL"
                    val parts = param.split("_")
                    if (parts.size == 2) {
                        val count = parts[0].toIntOrNull() ?: 3
                        val trend = parts[1]
                        if (recentTicks.size >= count) {
                            val sub = recentTicks.take(count)
                            isTriggered = true
                            for (i in 0 until count - 1) {
                                if (trend == "RISE") {
                                    if (sub[i] <= sub[i + 1]) {
                                        isTriggered = false
                                        break
                                    }
                                } else {
                                    if (sub[i] >= sub[i + 1]) {
                                        isTriggered = false
                                        break
                                    }
                                }
                            }
                            if (isTriggered) {
                                triggerDesc = "Momentum Trend Match: $count consecutive $trend digits ($sub)!"
                            }
                        }
                    }
                }
            }

            if (isTriggered) {
                lastCustomTriggerTime[strategy.id] = now
                val alarmTitle = "🤖 CUSTOM STRATEGY: ${strategy.name}"
                val alarmMessage = triggerDesc

                when (strategy.actionType) {
                    "VIBRATE_AND_NOTIFY" -> {
                        triggerVibration(1000)
                        showAlertBasedOnSetting(alarmTitle, alarmMessage)
                    }
                    "ONLY_VIBRATE" -> {
                        triggerVibration(500)
                        _nowBarText.value = "🤖 VIBRATE ACTION: ${strategy.name} - $triggerDesc"
                    }
                    "ONLY_NOTIFY" -> {
                        showAlertBasedOnSetting(alarmTitle, alarmMessage)
                    }
                }

                addStrategyLog("Triggered: ${strategy.name} - $triggerDesc")
            }
        }
    }

    fun showSystemNotification(title: String, message: String) {
        try {
            val context = getApplication<Application>()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "Deriv Trade Alerts"
                val descriptionText = "Entry alerts for Matches/Differs predictions"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(notificationChannelId, name, importance).apply {
                    description = descriptionText
                    enableVibration(false)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val builder = androidx.core.app.NotificationCompat.Builder(context, notificationChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            
            notificationManager.notify(notificationId, builder.build())
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "Failed to show system alert notification: ${e.message}")
        }
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_entryTimerEnabled.value) {
                delay(1000)
                val currentSec = _nextEntrySeconds.value
                if (currentSec > 1) {
                    val nextSec = currentSec - 1
                    _nextEntrySeconds.value = nextSec
                    
                    // Short tactile pulsation (80ms) per second of countdown!
                    triggerVibration(80)
                    
                    // Update Now Bar info text to show active countdown
                    val report = analysisReport.value
                    if (report != null) {
                        when (_selectedTradeType.value) {
                            TradeType.MATCHES_DIFFERS -> {
                                if (_matchesDiffersBias.value == "MATCHES") {
                                    val digit = report.topMatchesDigit
                                    val pct = String.format("%.1f", report.topMatchesPct)
                                    _nowBarText.value = "⏳ Analysing MATCHES: Matches prediction is Digit [$digit] ($pct%). Entry in $nextSec seconds..."
                                } else {
                                    val digit = report.topDiffersDigit
                                    val pct = String.format("%.1f", report.topDiffersPct)
                                    _nowBarText.value = "⏳ Analysing DIFFERS: Differs prediction is Digit [$digit] ($pct%). Entry in $nextSec seconds..."
                                }
                            }
                            TradeType.OVER_UNDER -> {
                                val bias = _overUnderBias.value
                                val barVal = _barrier.value
                                val statPct = if (bias == "OVER") report.overPercentage else report.underPercentage
                                _nowBarText.value = "⏳ Analysing OVER/UNDER: $bias barrier $barVal (${String.format("%.1f", statPct)}%). Entry in $nextSec seconds..."
                            }
                            TradeType.EVEN_ODD -> {
                                val bias = _evenOddBias.value
                                val statPct = if (bias == "EVEN") report.evenPercentage else report.oddPercentage
                                _nowBarText.value = "⏳ Analysing EVEN/ODD: Preferred is $bias (${String.format("%.1f", statPct)}%). Entry in $nextSec seconds..."
                            }
                            TradeType.RISE_FALL -> {
                                val bias = _riseFallBias.value
                                val statPct = if (bias == "RISE") report.risePercentage else report.fallPercentage
                                _nowBarText.value = "⏳ Analysing RISE/FALL: Preferred is $bias (${String.format("%.1f", statPct)}%). Entry in $nextSec seconds..."
                            }
                        }
                    } else {
                        _nowBarText.value = "⏳ Collecting tick metrics. Entry in $nextSec seconds..."
                    }
                } else {
                    _nextEntrySeconds.value = 5 // reset to perfect 5 seconds timer!
                    
                    // Long tactile alert vibration (900ms) notifying immediate entry on stop!
                    triggerVibration(900)
                    
                    val report = analysisReport.value
                    if (report != null) {
                        var title = ""
                        var msgText = ""
                        var nowText = ""
                        
                        when (_selectedTradeType.value) {
                            TradeType.MATCHES_DIFFERS -> {
                                if (_matchesDiffersBias.value == "MATCHES") {
                                    val predDigit = report.topMatchesDigit
                                    val predPct = String.format("%.1f", report.topMatchesPct)
                                    title = "🔥 MATCHES SIGNAL: DIGIT $predDigit NOW!"
                                    msgText = "Execute matches contract immediately after vibration stops (Model confidence: $predPct%)"
                                    nowText = "🚀 EXECUTE CONTRACT NOW: MATCHES ON $predDigit ($predPct% CONFIDENCE)"
                                } else {
                                    val predDigit = report.topDiffersDigit
                                    val predPct = String.format("%.1f", report.topDiffersPct)
                                    title = "🔥 DIFFERS SIGNAL: DIGIT $predDigit NOW!"
                                    msgText = "Execute differs contract immediately after vibration stops (Model confidence: $predPct%)"
                                    nowText = "🚀 EXECUTE CONTRACT NOW: DIFFERS ON $predDigit ($predPct% CONFIDENCE)"
                                }
                            }
                            TradeType.EVEN_ODD -> {
                                val bias = _evenOddBias.value
                                val pct = String.format("%.1f", if (bias == "EVEN") report.evenPercentage else report.oddPercentage)
                                title = "🔥 PARITY SIGNAL: $bias NOW!"
                                msgText = "Execute $bias contract immediately. Parity trend is high (Confidence: $pct%)"
                                nowText = "🚀 EXECUTE CONTRACT NOW: TRADING $bias ($pct% CONFIDENCE)"
                            }
                            TradeType.RISE_FALL -> {
                                val bias = _riseFallBias.value
                                val pct = String.format("%.1f", if (bias == "RISE") report.risePercentage else report.fallPercentage)
                                val trendWord = if (bias == "RISE") "UPS" else "DOWNS"
                                title = "🔥 TREND SIGNAL: $trendWord NOW!"
                                msgText = "Execute $bias contract immediately. Momentum trend is high (Confidence: $pct%)"
                                nowText = "🚀 EXECUTE CONTRACT NOW: TRADING $bias ($pct% CONFIDENCE)"
                            }
                            TradeType.OVER_UNDER -> {
                                val bias = _overUnderBias.value
                                val barVal = _barrier.value
                                val isTriggered = _isOverUnderEntryTriggered.value
                                val pct = String.format("%.1f", if (bias == "OVER") report.overPercentage else report.underPercentage)
                                if (isTriggered) {
                                    title = "🔥 OVER/UNDER SIGNAL: $bias $barVal NOW!"
                                    msgText = "Sequence matched for $bias $barVal. Execute contract immediately (Trend confidence: $pct%)"
                                    nowText = "🚀 EXECUTE $bias $barVal NOW (Trend confidence: $pct%)"
                                } else {
                                    nowText = "⏳ OVER/UNDER WAITING: Criteria Guide status not matched."
                                }
                            }
                        }
                        
                        if (nowText.isNotEmpty()) {
                            _nowBarText.value = nowText
                            if (title.isNotEmpty() && msgText.isNotEmpty()) {
                                _nowBarFlashActive.value = true
                                showAlertBasedOnSetting(title, msgText)
                            }
                        }
                    } else {
                        _nowBarText.value = "🎯 Ready for entry signals. Waiting for timer..."
                    }
                    
                    // Clear the flash status after 2 seconds
                    launch {
                        delay(2000)
                        _nowBarFlashActive.value = false
                    }
                }
            }
        }
    }

    private fun formatEpoch(epoch: Long): String {
        return try {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date(epoch * 1000))
        } catch (e: Exception) {
            ""
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        socketManager.disconnect()
    }
}
