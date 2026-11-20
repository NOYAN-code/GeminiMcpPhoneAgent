package com.geminimcp.phoneagent.tunnel

import android.content.Context
import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.mcp.McpRequestProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

class RemoteTunnelClient(
    private val context: Context,
    private val processor: McpRequestProcessor
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var active = false
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    fun start() {
        val baseUrl = AgentConfigStore.getTunnelUrl(context)
        if (baseUrl.isBlank()) {
            AgentConfigStore.setTunnelStatus("NOT CONFIGURED")
            return
        }
        active = true
        connectNow()
    }

    fun stop() {
        active = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "Stopped by user")
        webSocket = null
        AgentConfigStore.setTunnelStatus("DISCONNECTED")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun connectNow() {
        if (!active) return
        val baseUrl = AgentConfigStore.getTunnelUrl(context)
        if (baseUrl.isBlank()) {
            AgentConfigStore.setTunnelStatus("NOT CONFIGURED")
            return
        }
        val token = AgentConfigStore.getOrCreateToken(context)
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> "wss://$baseUrl"
        }
        val wsUrl = "$wsBase/ws?token=$token"
        AgentConfigStore.setTunnelStatus("CONNECTING")

        val request = Request.Builder().url(wsUrl).build()
        webSocket?.cancel()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                AgentConfigStore.setTunnelStatus("CONNECTED")
                startKeepalive(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                scope.launch {
                    handleIncomingMessage(ws, text)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                AgentConfigStore.setTunnelStatus("RECONNECTING")
                scheduleReconnect()
            }
        })
    }

    private suspend fun handleIncomingMessage(ws: WebSocket, raw: String) {
        val msg = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }
        val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type == "pong") return

        if (type == "mcp_request") {
            val reqId = msg["reqId"]?.jsonPrimitive?.contentOrNull ?: return
            val body = msg["body"]?.jsonPrimitive?.contentOrNull ?: return

            // Direct in-memory dispatch — eliminates local HTTP hop for maximum speed!
            val (status, respBody) = processor.handleJsonRpc(body)
            val reply = buildJsonObject {
                put("type", "mcp_response")
                put("reqId", reqId)
                put("status", status)
                put("body", respBody)
            }.toString()
            ws.send(reply)
        }
    }

    private fun startKeepalive(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && active) {
                delay(15_000L)
                try {
                    ws.send("""{"type":"ping"}""")
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        pingJob?.cancel()
        if (!active) {
            AgentConfigStore.setTunnelStatus("DISCONNECTED")
            return
        }
        AgentConfigStore.setTunnelStatus("RECONNECTING")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2500L)
            if (active) connectNow()
        }
    }
}
