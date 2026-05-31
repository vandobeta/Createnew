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
import com.example.deriv.database.SettingsEntity
import com.example.deriv.database.SettingsDao
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

sealed class GeminiAnalysisState {
    object Idle : GeminiAnalysisState()
    object Loading : GeminiAnalysisState()
    data class Success(val adviceMarkdown: String) : GeminiAnalysisState()
    data class Error(val errorMsg: String) : GeminiAnalysisState()
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: String
)

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

    private val _vibrationStrengthThreshold = MutableStateFlow(60f) // 0.0f to 100.0f, default 60%
    val vibrationStrengthThreshold: StateFlow<Float> = _vibrationStrengthThreshold.asStateFlow()

    // Persistent auto clicker state
    private val _autoClickerEnabled = MutableStateFlow(false)
    val autoClickerEnabled: StateFlow<Boolean> = _autoClickerEnabled.asStateFlow()

    // Algorithmic rules - start disabled!
    private val _p1Enabled = MutableStateFlow(false)
    val p1Enabled: StateFlow<Boolean> = _p1Enabled.asStateFlow()
    
    private val _p1TargetDigit = MutableStateFlow(6)
    val p1TargetDigit: StateFlow<Int> = _p1TargetDigit.asStateFlow()
    
    private val _p1MinConfidence = MutableStateFlow(45.0)
    val p1MinConfidence: StateFlow<Double> = _p1MinConfidence.asStateFlow()
    
    private val _p2Enabled = MutableStateFlow(false)
    val p2Enabled: StateFlow<Boolean> = _p2Enabled.asStateFlow()
    
    private val _p2TargetDigit = MutableStateFlow(6)
    val p2TargetDigit: StateFlow<Int> = _p2TargetDigit.asStateFlow()
    
    private val _p2MinConfidence = MutableStateFlow(70.0)
    val p2MinConfidence: StateFlow<Double> = _p2MinConfidence.asStateFlow()
    
    private val _p3Enabled = MutableStateFlow(false)
    val p3Enabled: StateFlow<Boolean> = _p3Enabled.asStateFlow()
    
    private val _p3MinFrequency = MutableStateFlow(12.0)
    val p3MinFrequency: StateFlow<Double> = _p3MinFrequency.asStateFlow()
    
    private val _p3MinAbsence = MutableStateFlow(3)
    val p3MinAbsence: StateFlow<Int> = _p3MinAbsence.asStateFlow()
    
    private val _p4Enabled = MutableStateFlow(false)
    val p4Enabled: StateFlow<Boolean> = _p4Enabled.asStateFlow()
    
    private val _p4MinAppearances = MutableStateFlow(2)
    val p4MinAppearances: StateFlow<Int> = _p4MinAppearances.asStateFlow()
    
    // Limits
    private val _sessionSignalLimit = MutableStateFlow(10) // default 10
    val sessionSignalLimit: StateFlow<Int> = _sessionSignalLimit.asStateFlow()
    
    private val _sessionSignalCount = MutableStateFlow(0)
    val sessionSignalCount: StateFlow<Int> = _sessionSignalCount.asStateFlow()
    
    // Trading timer hours/minutes (Session-Based Timers, three times a day)
    private val _timerSession1 = MutableStateFlow("08:00")
    val timerSession1: StateFlow<String> = _timerSession1.asStateFlow()
    
    private val _timerSession2 = MutableStateFlow("14:00")
    val timerSession2: StateFlow<String> = _timerSession2.asStateFlow()
    
    private val _timerSession3 = MutableStateFlow("20:00")
    val timerSession3: StateFlow<String> = _timerSession3.asStateFlow()

    private val _geminiReportState = MutableStateFlow<GeminiAnalysisState>(GeminiAnalysisState.Idle)
    val geminiReportState: StateFlow<GeminiAnalysisState> = _geminiReportState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage(
            text = "Hello! I am your Gemini Quant Advisor chatbot. Ask me anything about current volatility indices, digit frequency biases, mathematical price variances, or trading stability. I am context-aware of current live data!",
            isUser = false,
            timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        )
    ))
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _chatbotLoading = MutableStateFlow(false)
    val chatbotLoading: StateFlow<Boolean> = _chatbotLoading.asStateFlow()

    private val _signalConfirmedFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val signalConfirmedFlow = _signalConfirmedFlow.asSharedFlow()

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

    fun saveSettingsToDb() {
        viewModelScope.launch {
            try {
                val entity = SettingsEntity(
                    id = 1L,
                    alertPreference = _alertPreference.value,
                    vibrationSetting = _vibrationSetting.value,
                    vibrationStrengthThreshold = _vibrationStrengthThreshold.value,
                    selectedColorTheme = _selectedColorTheme.value,
                    selectedSymbol = _selectedSymbol.value,
                    selectedTradeType = _selectedTradeType.value.name,
                    barrier = _barrier.value,
                    sampleSize = _sampleSize.value,
                    p1Enabled = _p1Enabled.value,
                    p1TargetDigit = _p1TargetDigit.value,
                    p1MinConfidence = _p1MinConfidence.value,
                    p2Enabled = _p2Enabled.value,
                    p2TargetDigit = _p2TargetDigit.value,
                    p2MinConfidence = _p2MinConfidence.value,
                    p3Enabled = _p3Enabled.value,
                    p3MinFrequency = _p3MinFrequency.value,
                    p3MinAbsence = _p3MinAbsence.value,
                    p4Enabled = _p4Enabled.value,
                    p4MinAppearances = _p4MinAppearances.value,
                    sessionSignalLimit = _sessionSignalLimit.value,
                    sessionSignalCount = _sessionSignalCount.value,
                    timerSession1 = _timerSession1.value,
                    timerSession2 = _timerSession2.value,
                    timerSession3 = _timerSession3.value
                )
                db.settingsDao().saveSettings(entity)
            } catch (e: Exception) {
                android.util.Log.e("DigitAnalysisViewModel", "Error saving settings: ${e.message}")
            }
        }
    }

    fun setAutoClickerEnabled(enabled: Boolean) {
        _autoClickerEnabled.value = enabled
        saveSettingsToDb()
    }

    fun setAlertPreference(pref: String) {
        _alertPreference.value = pref
        saveSettingsToDb()
    }

    fun setVibrationSetting(setting: String) {
        _vibrationSetting.value = setting
        saveSettingsToDb()
    }

    fun setVibrationStrengthThreshold(value: Float) {
        _vibrationStrengthThreshold.value = value
        saveSettingsToDb()
    }

    fun setSelectedColorTheme(theme: String) {
        _selectedColorTheme.value = theme
        saveSettingsToDb()
    }

    fun setP1Enabled(enabled: Boolean) {
        _p1Enabled.value = enabled
        saveSettingsToDb()
    }
    fun setP1TargetDigit(digit: Int) {
        _p1TargetDigit.value = digit
        saveSettingsToDb()
    }
    fun setP1MinConfidence(conf: Double) {
        _p1MinConfidence.value = conf
        saveSettingsToDb()
    }
    
    fun setP2Enabled(enabled: Boolean) {
        _p2Enabled.value = enabled
        saveSettingsToDb()
    }
    fun setP2TargetDigit(digit: Int) {
        _p2TargetDigit.value = digit
        saveSettingsToDb()
    }
    fun setP2MinConfidence(conf: Double) {
        _p2MinConfidence.value = conf
        saveSettingsToDb()
    }
    
    fun setP3Enabled(enabled: Boolean) {
        _p3Enabled.value = enabled
        saveSettingsToDb()
    }
    fun setP3MinFrequency(freq: Double) {
        _p3MinFrequency.value = freq
        saveSettingsToDb()
    }
    fun setP3MinAbsence(abs: Int) {
        _p3MinAbsence.value = abs
        saveSettingsToDb()
    }
    
    fun setP4Enabled(enabled: Boolean) {
        _p4Enabled.value = enabled
        saveSettingsToDb()
    }
    fun setP4MinAppearances(app: Int) {
        _p4MinAppearances.value = app
        saveSettingsToDb()
    }
    
    fun setSessionSignalLimit(limit: Int) {
        _sessionSignalLimit.value = limit
        saveSettingsToDb()
    }
    
    fun resetSessionSignalCount() {
        _sessionSignalCount.value = 0
        saveSettingsToDb()
    }
    
    fun incrementSessionSignalCount() {
        _sessionSignalCount.value = _sessionSignalCount.value + 1
        saveSettingsToDb()
    }
    
    fun setTimerSession1(time: String) {
        _timerSession1.value = time
        saveSettingsToDb()
    }
    
    fun setTimerSession2(time: String) {
        _timerSession2.value = time
        saveSettingsToDb()
    }
    
    fun setTimerSession3(time: String) {
        _timerSession3.value = time
        saveSettingsToDb()
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
        "R_25" to "Volatility 25 Index",
        "R_50" to "Volatility 50 Index",
        "R_75" to "Volatility 75 Index",
        "R_100" to "Volatility 100 Index",
        "1HZ10V" to "Volatility 10 (1s) Index",
        "1HZ25V" to "Volatility 25 (1s) Index",
        "1HZ50V" to "Volatility 50 (1s) Index",
        "1HZ75V" to "Volatility 75 (1s) Index",
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
    
    // Ticks feed Flow maintained in-memory to prevent heavy disk-read Room query execution on every live tick
    private val _latestTicksFlow = MutableStateFlow<List<TickEntity>>(emptyList())
    val latestTicksFlow: StateFlow<List<TickEntity>> = _latestTicksFlow.asStateFlow()

    // Calculated analysis state mapped reactively
    val analysisReport: StateFlow<AnalysisReport?> = combine(
        listOf(
            latestTicksFlow,
            _sampleSize,
            _barrier,
            _p1Enabled,
            _p1TargetDigit,
            _p1MinConfidence,
            _p2Enabled,
            _p2TargetDigit,
            _p2MinConfidence,
            _p3Enabled,
            _p3MinFrequency,
            _p3MinAbsence,
            _p4Enabled,
            _p4MinAppearances
        )
    ) { array ->
        val ticks = array[0] as List<TickEntity>
        val size = array[1] as Int
        val bar = array[2] as Int
        val p1En = array[3] as Boolean
        val p1T = array[4] as Int
        val p1C = array[5] as Double
        val p2En = array[6] as Boolean
        val p2T = array[7] as Int
        val p2C = array[8] as Double
        val p3En = array[9] as Boolean
        val p3F = array[10] as Double
        val p3A = array[11] as Int
        val p4En = array[12] as Boolean
        val p4Ap = array[13] as Int
        
        try {
            DigitAnalysisEngine.analyze(
                ticks = ticks,
                sampleSize = size,
                barrier = bar,
                p1Enabled = p1En,
                p1TargetDigit = p1T,
                p1MinConfidence = p1C,
                p2Enabled = p2En,
                p2TargetDigit = p2T,
                p2MinConfidence = p2C,
                p3Enabled = p3En,
                p3MinFrequency = p3F,
                p3MinAbsence = p3A,
                p4Enabled = p4En,
                p4MinAppearances = p4Ap
            )
        } catch (e: Throwable) {
            android.util.Log.e("DigitAnalysisViewModel", "Error calculating DigitAnalysisEngine stats: ${e.message}", e)
            null
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        // Preload settings from DB on startup
        viewModelScope.launch {
            try {
                var loadedSettings = db.settingsDao().getSettings()
                if (loadedSettings == null) {
                    loadedSettings = SettingsEntity()
                    db.settingsDao().saveSettings(loadedSettings)
                }
                
                _alertPreference.value = loadedSettings.alertPreference
                _vibrationSetting.value = loadedSettings.vibrationSetting
                _vibrationStrengthThreshold.value = loadedSettings.vibrationStrengthThreshold
                _selectedColorTheme.value = loadedSettings.selectedColorTheme
                _selectedSymbol.value = loadedSettings.selectedSymbol
                _selectedTradeType.value = TradeType.valueOf(loadedSettings.selectedTradeType)
                _barrier.value = loadedSettings.barrier
                _sampleSize.value = loadedSettings.sampleSize
                
                _p1Enabled.value = loadedSettings.p1Enabled
                _p1TargetDigit.value = loadedSettings.p1TargetDigit
                _p1MinConfidence.value = loadedSettings.p1MinConfidence
                
                _p2Enabled.value = loadedSettings.p2Enabled
                _p2TargetDigit.value = loadedSettings.p2TargetDigit
                _p2MinConfidence.value = loadedSettings.p2MinConfidence
                
                _p3Enabled.value = loadedSettings.p3Enabled
                _p3MinFrequency.value = loadedSettings.p3MinFrequency
                _p3MinAbsence.value = loadedSettings.p3MinAbsence
                
                _p4Enabled.value = loadedSettings.p4Enabled
                _p4MinAppearances.value = loadedSettings.p4MinAppearances
                
                _sessionSignalLimit.value = loadedSettings.sessionSignalLimit
                _sessionSignalCount.value = loadedSettings.sessionSignalCount
                
                _timerSession1.value = loadedSettings.timerSession1
                _timerSession2.value = loadedSettings.timerSession2
                _timerSession3.value = loadedSettings.timerSession3

                // After loading settings, update subscription symbol & reload initial ticks
                socketManager.subscribeToSymbol(loadedSettings.selectedSymbol)
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val initialTicks = repository.getLatest1000Ticks(loadedSettings.selectedSymbol)
                        _latestTicksFlow.value = initialTicks
                    } catch (e: Exception) {
                        android.util.Log.e("DigitAnalysisViewModel", "Failed to pre-load initial ticks: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DigitAnalysisViewModel", "Failed to load settings on init: ${e.message}")
            }
        }

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

        // Preload recent ticks from local database on startup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val initialTicks = repository.getLatest1000Ticks(_selectedSymbol.value)
                _latestTicksFlow.value = initialTicks
            } catch (e: Exception) {
                android.util.Log.e("DigitAnalysisViewModel", "Failed to pre-load initial ticks: ${e.message}")
            }
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

                        // Add new live tick to dynamically maintained memory cache to prevent continuous database re-reads
                        val newEntity = TickEntity(
                            symbol = tick.symbol,
                            price = tick.price,
                            epoch = tick.epoch,
                            digit = tick.lastDigit
                        )
                        _latestTicksFlow.value = (listOf(newEntity) + _latestTicksFlow.value).take(1000)

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
        
        // Load initial 1000 ticks from DB asynchronously (only once per symbol change)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val initialTicks = repository.getLatest1000Ticks(symbolCode)
                _latestTicksFlow.value = initialTicks
            } catch (e: Exception) {
                android.util.Log.e("DigitAnalysisViewModel", "Failed to load ticks on symbol selection: ${e.message}")
            }
        }
        
        socketManager.subscribeToSymbol(symbolCode)
        saveSettingsToDb()
    }

    fun selectTradeType(type: TradeType) {
        _selectedTradeType.value = type
        saveSettingsToDb()
    }

    fun selectSampleSize(size: Int) {
        _sampleSize.value = size
        saveSettingsToDb()
    }

    fun selectBarrier(bar: Int) {
        _barrier.value = bar
        saveSettingsToDb()
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

    fun getSignalStrength(): Double {
        val report = analysisReport.value ?: return 100.0
        return when (_selectedTradeType.value) {
            TradeType.OVER_UNDER -> {
                if (_overUnderBias.value == "OVER") report.overPercentage else report.underPercentage
            }
            TradeType.MATCHES_DIFFERS -> {
                if (_matchesDiffersBias.value == "MATCHES") report.topMatchesPct else report.topDiffersPct
            }
            TradeType.EVEN_ODD -> {
                if (_evenOddBias.value == "EVEN") report.evenPercentage else report.oddPercentage
            }
            TradeType.RISE_FALL -> {
                if (_riseFallBias.value == "RISE") report.risePercentage else report.fallPercentage
            }
        }
    }

    fun notifySignalConfirmed(signalName: String) {
        val count = _sessionSignalCount.value
        val limit = _sessionSignalLimit.value
        
        // Check if count has already reached / exceeded limit
        if (limit != -1 && count >= limit) {
            _autoClickerEnabled.value = false
            saveSettingsToDb()
            addStrategyLog("⚠️ Suppressed: Session Limit reached ($limit signals). Disengaging auto-clicker.")
            return
        }
        
        // Increment and save settings
        _sessionSignalCount.value = count + 1
        saveSettingsToDb()

        // If the new count reaches the limit, proactively turn off auto-clicking for future runs
        if (limit != -1 && _sessionSignalCount.value >= limit) {
            _autoClickerEnabled.value = false
            saveSettingsToDb()
            addStrategyLog("🏁 Limit Met: Session completed ($limit signals). Auto-clicker disengaged.")
        }

        viewModelScope.launch {
            _signalConfirmedFlow.emit(signalName)
        }
    }

    fun vibrateOnSignalIfStrong(durationMs: Long) {
        val strength = getSignalStrength()
        val threshold = _vibrationStrengthThreshold.value
        if (strength >= threshold) {
            triggerVibration(durationMs)
        } else {
            addStrategyLog("Vibrator Filtered: Signal strength (${String.format("%.1f", strength)}%) below threshold (${String.format("%.1f", threshold)}%)")
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
            
            // Trigger haptic vibration (filtered dynamically by minimum signal strength)
            vibrateOnSignalIfStrong(750)
            
            // Notify active auto-clickers
            notifySignalConfirmed("OVER_UNDER")
            
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

                // Notify active auto-clickers
                notifySignalConfirmed("CUSTOM_STRATEGY_${strategy.name}")

                when (strategy.actionType) {
                    "VIBRATE_AND_NOTIFY" -> {
                        vibrateOnSignalIfStrong(1000)
                        showAlertBasedOnSetting(alarmTitle, alarmMessage)
                    }
                    "ONLY_VIBRATE" -> {
                        vibrateOnSignalIfStrong(500)
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
                    
                    val report = analysisReport.value
                    if (report != null) {
                        var title = ""
                        var msgText = ""
                        var nowText = ""
                        var shouldAlert = true
                        
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
                                    shouldAlert = false
                                }
                            }
                        }
                        
                        if (shouldAlert) {
                            // Long tactile alert vibration (900ms) notifying immediate entry on stop!
                            vibrateOnSignalIfStrong(900)
                            
                            if (nowText.isNotEmpty()) {
                                _nowBarText.value = nowText
                                if (nowText.startsWith("🚀 EXECUTE")) {
                                    notifySignalConfirmed(_selectedTradeType.value.name)
                                }
                            }
                            if (title.isNotEmpty() && msgText.isNotEmpty()) {
                                _nowBarFlashActive.value = true
                                showAlertBasedOnSetting(title, msgText)
                            }
                        } else {
                            if (nowText.isNotEmpty()) {
                                _nowBarText.value = nowText
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

    suspend fun callGeminiApi(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Error: Gemini API Key is missing or not configured! Please enter your GEMINI_API_KEY in the AI Studio Secrets panel."
        }
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        
        // Build JSON Payload using native org.json
        val partsObj = org.json.JSONObject().put("text", prompt)
        val partList = org.json.JSONArray().put(partsObj)
        val contentObj = org.json.JSONObject().put("parts", partList)
        val contentsArr = org.json.JSONArray().put(contentObj)
        val requestBodyObj = org.json.JSONObject().put("contents", contentsArr)
        
        // Optional System Instruction
        val sysInstructionParts = org.json.JSONObject().put("text", "You are a professional High-Frequency Trading quantitative analyst. Correlate multi-term high-frequency tick streams to identify patterns, anomalies, structural breaks, and advise on optimal contract entry parameters. Provide clean, highly structured, concise markdown advice.")
        val sysInstructionContent = org.json.JSONObject().put("parts", org.json.JSONArray().put(sysInstructionParts))
        requestBodyObj.put("systemInstruction", sysInstructionContent)
        
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            
        val body = okhttp3.RequestBody.create(mediaType, requestBodyObj.toString())
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(body)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "API Call Failed with status ${response.code}: ${response.message}\nMake sure your GEMINI_API_KEY is correct and configured in the AI Studio Secrets panel."
                }
                val responseBodyString = response.body?.string() ?: return@withContext "Error: Empty response body received from Gemini."
                val jsonResponse = org.json.JSONObject(responseBodyString)
                val candidates = jsonResponse.getJSONArray("candidates")
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val firstPart = parts.getJSONObject(0)
                firstPart.getString("text")
            }
        } catch (e: Exception) {
            "Error executing Gemini request: ${e.message}"
        }
    }

    fun runGeminiTickAnalysis() {
        _geminiReportState.value = GeminiAnalysisState.Loading
        viewModelScope.launch {
            try {
                val symbol = _selectedSymbol.value
                val ticks = repository.getLatest1000Ticks(symbol)
                val totalAvailable = ticks.size
                if (totalAvailable < 10) {
                    _geminiReportState.value = GeminiAnalysisState.Error("Insufficient data in database (Available: $totalAvailable ticks, need at least 10). Wait for more live ticks first.")
                    return@launch
                }
                
                // Collect subset statistics
                val groupSizes = listOf(10, 25, 50, 100, 250, 500, 1000)
                val groupReports = mutableMapOf<Int, String>()
                
                for (size in groupSizes) {
                    val subTicks = ticks.take(size)
                    val countAct = subTicks.size
                    if (countAct == 0) continue
                    
                    // Calculate Parity
                    val evenCount = subTicks.count { it.digit % 2 == 0 }
                    val oddCount = countAct - evenCount
                    val evenPct = (evenCount.toDouble() / countAct) * 100.0
                    val oddPct = (oddCount.toDouble() / countAct) * 100.0
                    
                    // Calculate Digit Frequencies
                    val freqMap = mutableMapOf<Int, Int>()
                    subTicks.forEach { freqMap[it.digit] = (freqMap[it.digit] ?: 0) + 1 }
                    val topDigit = freqMap.maxByOrNull { it.value }?.key ?: -1
                    val topDigitPct = ((freqMap[topDigit] ?: 0).toDouble() / countAct) * 100.0
                    
                    // Calculate Price Trend (Rise vs Fall)
                    var riseCount = 0
                    var fallCount = 0
                    if (countAct >= 2) {
                        val chronological = subTicks.reversed()
                        for (i in 1 until chronological.size) {
                            if (chronological[i].price >= chronological[i - 1].price) riseCount++ else fallCount++
                        }
                    }
                    val totalTrendDays = (riseCount + fallCount).coerceAtLeast(1)
                    val risePct = (riseCount.toDouble() / totalTrendDays) * 100.0
                    val fallPct = (fallCount.toDouble() / totalTrendDays) * 100.0
                    
                    val reportStr = "Group Size: $countAct ticks\n" +
                                    "- Parity Ratio: EVEN: ${String.format("%.1f", evenPct)}% | ODD: ${String.format("%.1f", oddPct)}%\n" +
                                    "- Peak Digit: [$topDigit] (${String.format("%.1f", topDigitPct)}% occurrences)\n" +
                                    "- Price Pattern: RISE: ${String.format("%.1f", risePct)}% | FALL: ${String.format("%.1f", fallPct)}%"
                    
                    groupReports[size] = reportStr
                }
                
                val promptBuilder = StringBuilder()
                promptBuilder.append("We are currently trading volatility index '$symbol' on our digital platform.\n")
                promptBuilder.append("We have nested our historic ticks into seven look-back subsets to isolate high-frequency drift. Please compare and correlate them to advise:\n\n")
                
                groupReports.forEach { (size, statText) ->
                    promptBuilder.append("=== LATEST $size TICKS ===\n")
                    promptBuilder.append(statText).append("\n\n")
                }
                
                promptBuilder.append("Please analyze these multi-term subsets carefully, compare short-term anomalies (10/25 ticks) with long-term baseline distributions (500/1000 ticks) and generate a tidy diagnostic advice report containing:\n")
                promptBuilder.append("1. **Multi-Term Drift & Divergence Analysis** (Highlight shifts in even/odd representation or price momentum)\n")
                promptBuilder.append("2. **Hotspot/Skew Anomalies** (Is there any digit displaying high-frequency probability bumps?)\n")
                promptBuilder.append("3. **Recommended Entry Strategy** (Clearly outline optimal parameters: selected trade type, entry directions/barriers, and bias to trigger immediately!).")
                
                val response = callGeminiApi(promptBuilder.toString())
                _geminiReportState.value = GeminiAnalysisState.Success(response)
            } catch (e: Exception) {
                _geminiReportState.value = GeminiAnalysisState.Error("Analytical processing failed: ${e.message}")
            }
        }
    }

    fun clearChatHistory() {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _chatMessages.value = listOf(
            ChatMessage(
                text = "Chat history cleared. How can I help you analyze the volatility market today?",
                isUser = false,
                timestamp = stamp
            )
        )
    }

    fun sendChatbotMessage(messageText: String) {
        if (messageText.isBlank()) return
        
        val userTimestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val userMsg = ChatMessage(text = messageText, isUser = true, timestamp = userTimestamp)
        
        _chatMessages.value = _chatMessages.value + userMsg
        _chatbotLoading.value = true
        
        viewModelScope.launch {
            try {
                val symbol = _selectedSymbol.value
                val ticks = repository.getLatest1000Ticks(symbol)
                
                // Calculate actual price variance and market stability
                val prices = ticks.map { it.price }
                val count = prices.size
                
                var varianceStr = "0.000000"
                var stabilityStr = "Indeterminate"
                
                if (count > 1) {
                    val mean = prices.average()
                    val variance = prices.map { Math.pow(it - mean, 2.0) }.average()
                    val stdDev = Math.sqrt(variance)
                    val stabilityValue = if (mean > 0) (1.0 - (stdDev / mean)) * 100.0 else 0.0
                    val stability = stabilityValue.coerceIn(0.0, 100.0)
                    
                    varianceStr = String.format("%.6f", variance)
                    stabilityStr = String.format("%.2f%% (where 100%% is perfectly stable/flat, and low percentages represent extreme volatility expansion)", stability)
                }
                
                val latestDigitsBy40 = ticks.take(40).map { it.digit }
                val sampleSizesStr = "Latest DB sample count: $count ticks"
                
                val promptBuilder = StringBuilder()
                promptBuilder.append("You are a professional High-Frequency Trading quantitative chatbot advisor.\n")
                promptBuilder.append("The current trading pair/asset: $symbol\n")
                promptBuilder.append("Latest Price: ${_livePrice.value}\n")
                promptBuilder.append("Statistical Sample Size: $sampleSizesStr\n")
                promptBuilder.append("Computed Mathematical Price Variance: $varianceStr\n")
                promptBuilder.append("Computed Market Stability Factor: $stabilityStr\n")
                promptBuilder.append("Last 40 Digits Stream: $latestDigitsBy40\n\n")
                
                promptBuilder.append("CHAT LOG HISTORY:\n")
                _chatMessages.value.takeLast(10).forEach { msg ->
                    val sender = if (msg.isUser) "Trader (User)" else "Quant Advisor (AI)"
                    promptBuilder.append("[${msg.timestamp}] $sender: ${msg.text}\n")
                }
                
                promptBuilder.append("\nPlease respond to the user's latest query precisely, referencing the computed price variance and market stability. Provide clean, condensed markdown format trading insights.")
                
                val response = callGeminiApi(promptBuilder.toString())
                val replyTimestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                _chatMessages.value = _chatMessages.value + ChatMessage(text = response, isUser = false, timestamp = replyTimestamp)
            } catch (e: Exception) {
                val errorTimestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                _chatMessages.value = _chatMessages.value + ChatMessage(
                    text = "Analytical processing error: ${e.message}",
                    isUser = false,
                    timestamp = errorTimestamp
                )
            } finally {
                _chatbotLoading.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        socketManager.disconnect()
    }
}
