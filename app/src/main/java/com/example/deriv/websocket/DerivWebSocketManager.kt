package com.example.deriv.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class LiveTick(
    val symbol: String,
    val price: Double,
    val epoch: Long,
    val lastDigit: Int,
    val id: String
)

class DerivWebSocketManager {
    private val client = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val parentJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + parentJob)
    private var pingJob: Job? = null
    
    private var lastPingTime = 0L
    private var currentSubscribedSymbol: String? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _latency = MutableStateFlow<Long>(-1L)
    val latency: StateFlow<Long> = _latency.asStateFlow()

    private val _tickFlow = MutableSharedFlow<LiveTick>(replay = 1)
    val tickFlow: SharedFlow<LiveTick> = _tickFlow.asSharedFlow()

    fun connect() {
        if (_connectionStatus.value == ConnectionStatus.CONNECTED || _connectionStatus.value == ConnectionStatus.CONNECTING) {
            return
        }
        
        _connectionStatus.value = ConnectionStatus.CONNECTING
        val request = Request.Builder()
            .url("wss://ws.derivws.com/websockets/v3?app_id=1089")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("DerivWS", "WebSocket connection opened")
                _connectionStatus.value = ConnectionStatus.CONNECTED
                startPingLoop()
                
                // Resubscribe if there was an active symbol
                currentSubscribedSymbol?.let { symbol ->
                    subscribeToSymbolInternal(symbol)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("msg_type")
                    
                    if (msgType == "ping") {
                        if (lastPingTime > 0) {
                            val now = System.currentTimeMillis()
                            val diff = now - lastPingTime
                            _latency.value = diff
                        }
                    } else if (msgType == "tick") {
                        val tickJson = json.optJSONObject("tick")
                        if (tickJson != null) {
                            val symbol = tickJson.getString("symbol")
                            val quote = tickJson.getDouble("quote")
                            val epoch = tickJson.getLong("epoch")
                            val id = tickJson.optString("id", "")
                            
                            val rawQuote = tickJson.opt("quote")
                            val quoteStr = rawQuote?.toString() ?: ""
                            val lastChar = quoteStr.trim().lastOrNull { it.isDigit() }
                            val digit = if (lastChar != null) {
                                lastChar.toString().toInt()
                            } else {
                                (rawQuote as? Number)?.toDouble()?.let {
                                    val formatted = String.format(Locale.US, "%.10f", it)
                                    formatted.trim().lastOrNull { c -> c.isDigit() }?.toString()?.toInt()
                                } ?: 0
                            }

                            scope.launch {
                                _tickFlow.emit(
                                    LiveTick(
                                        symbol = symbol,
                                        price = quote,
                                        epoch = epoch,
                                        lastDigit = digit,
                                        id = id
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DerivWS", "Error parsing websocket message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("DerivWS", "WebSocket closing: $code / $reason")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                stopPingLoop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("DerivWS", "WebSocket closed")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                stopPingLoop()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("DerivWS", "WebSocket Failure: ${t.message}")
                _connectionStatus.value = ConnectionStatus.ERROR
                stopPingLoop()
                scope.launch {
                    delay(3000)
                    connect()
                }
            }
        })
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                try {
                     lastPingTime = System.currentTimeMillis()
                     val pingMessage = JSONObject().apply {
                         put("ping", 1)
                     }
                     webSocket?.send(pingMessage.toString())
                } catch (e: Exception) {
                     Log.e("DerivWS", "Error sending ping: ${e.message}")
                }
                delay(10000)
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
        _latency.value = -1L
    }

    fun subscribeToSymbol(symbol: String) {
        val previousSymbol = currentSubscribedSymbol
        currentSubscribedSymbol = symbol
        
        if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
            scope.launch {
                if (previousSymbol != null) {
                    val forgetMsg = JSONObject().apply {
                        put("forget_all", "ticks")
                    }
                    webSocket?.send(forgetMsg.toString())
                    delay(300)
                }
                subscribeToSymbolInternal(symbol)
            }
        }
    }

    private fun subscribeToSymbolInternal(symbol: String) {
        val subscribeMsg = JSONObject().apply {
            put("ticks", symbol)
            put("subscribe", 1)
        }
        webSocket?.send(subscribeMsg.toString())
        Log.d("DerivWS", "Subscribed to: $symbol")
    }

    fun disconnect() {
        stopPingLoop()
        try {
            parentJob.cancel()
        } catch (e: Exception) {
            Log.e("DerivWS", "Error canceling parent job: ${e.message}")
        }
        webSocket?.close(1000, "App closed")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
    }
}
