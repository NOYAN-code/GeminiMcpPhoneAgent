package com.geminimcp.phoneagent.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class TunnelRuntimeState(
    val connected: Boolean = false,
    val publicMcpUrl: String = "",
    val statusText: String = "Tunnel not configured"
)

class RemoteTunnelClient(
    private val localMcpServer: LocalMcpServer
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Required for persistent WebSocket
        .build()

    private val _state = MutableStateFlow(TunnelRuntimeState())
    val state: StateFlow<TunnelRuntimeState> = _state.asStateFlow()

    @Volatile
    private var activeSocket: WebSocket? = null

    @Volatile
    private var shouldRun = false

    private var reconnectJob: Job? = null
    private var currentBaseUrl: String = ""
    private var currentToken: String = ""
    private var backoffMs: Long = 2_000L

    fun start(tunnelBaseUrl: String, authToken: String) {
        val cleanBase = tunnelBaseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) {
            stop()
            _state.value = TunnelRuntimeState(
                connected = false,
                publicMcpUrl = "",
                statusText = "Local-only mode (add Cloudflare Worker URL in Settings for Official Gemini App)"
            )
            return
        }

        currentBaseUrl = cleanBase
        currentToken = authToken
        shouldRun = true
        backoffMs = 2_000L

        val publicUrl = "$cleanBase/mcp/$authToken"
        _state.value = TunnelRuntimeState(
            connected = false,
            publicMcpUrl = publicUrl,
            statusText = "Connecting to tunnel..."
        )
        connectSocket()
    }

    fun stop() {
        shouldRun = false
        reconnectJob?.cancel()
        reconnectJob = null
        activeSocket?.close(1000, "Stopped by user")
        activeSocket = null
        _state.value = _state.value.copy(
            connected = false,
            statusText = "Tunnel stopped"
        )
    }

    private fun connectSocket() {
        if (!shouldRun) return
        activeSocket?.cancel()

        val wsBase = when {
            currentBaseUrl.startsWith("https://") -> "wss://" + currentBaseUrl.removePrefix("https://")
            currentBaseUrl.startsWith("http://") -> "ws://" + currentBaseUrl.removePrefix("http://")
            else -> "wss://$currentBaseUrl"
        }
        val wsUrl = "$wsBase/ws?token=$currentToken"
        val publicUrl = "$currentBaseUrl/mcp/$currentToken"

        val request = Request.Builder().url(wsUrl).build()
        activeSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 2_000L
                _state.value = TunnelRuntimeState(
                    connected = true,
                    publicMcpUrl = publicUrl,
                    statusText = "Connected — Ready for Official Gemini App"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleIncomingFrame(webSocket, text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = TunnelRuntimeState(
                    connected = false,
                    publicMcpUrl = publicUrl,
                    statusText = "Reconnecting (${t.message ?: "network error"})..."
                )
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = TunnelRuntimeState(
                    connected = false,
                    publicMcpUrl = publicUrl,
                    statusText = "Disconnected ($reason)"
                )
                if (shouldRun) scheduleReconnect()
            }
        })
    }

    private suspend fun handleIncomingFrame(webSocket: WebSocket, rawFrame: String) {
        val obj = try {
            json.parseToJsonElement(rawFrame).jsonObject
        } catch (_: Exception) {
            return
        }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type != "mcp_request") return

        val reqId = obj["reqId"]?.jsonPrimitive?.contentOrNull ?: return
        val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val (statusCode, responseBody, sessionId) = localMcpServer.processJsonRpcRaw(body)
        val reply = buildJsonObject {
            put("type", "mcp_response")
            put("reqId", reqId)
            put("status", statusCode)
            put("sessionId", sessionId)
            put("body", responseBody)
        }
        webSocket.send(reply.toString())
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        reconnectJob?.cancel()
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        reconnectJob = scope.launch {
            delay(wait)
            connectSocket()
        }
    }
}
