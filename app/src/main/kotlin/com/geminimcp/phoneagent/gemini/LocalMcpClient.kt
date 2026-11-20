package com.geminimcp.phoneagent.gemini

import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.mcp.JsonRpcRequest
import com.geminimcp.phoneagent.mcp.JsonRpcResponse
import com.geminimcp.phoneagent.mcp.McpCallToolResult
import com.geminimcp.phoneagent.mcp.McpToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalMcpClient(private val configStore: AgentConfigStore) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val requestId = AtomicInteger(1)
    private var sessionId: String? = null
    private var initialized = false

    suspend fun ensureInitialized() {
        if (initialized) return
        val params = buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject {
                put("name", "gemini-android-orchestrator")
                put("version", "1.0.0")
            })
        }
        sendRpc("initialize", params)
        // Fire initialized notification
        sendNotification("notifications/initialized")
        initialized = true
    }

    suspend fun listTools(): List<McpToolDefinition> {
        ensureInitialized()
        val resp = sendRpc("tools/list", null)
        val toolsArray = resp.result?.jsonObject?.get("tools")?.jsonArray ?: return emptyList()
        return toolsArray.map { json.decodeFromJsonElement<McpToolDefinition>(it) }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallToolResult {
        ensureInitialized()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val resp = sendRpc("tools/call", params)
        if (resp.error != null) {
            throw IllegalStateException("MCP error ${resp.error.code}: ${resp.error.message}")
        }
        val resultEl = resp.result ?: throw IllegalStateException("Empty MCP tools/call result.")
        return json.decodeFromJsonElement(resultEl)
    }

    private suspend fun sendRpc(method: String, params: JsonObject?): JsonRpcResponse {
        val settings = configStore.getSettings()
        val url = "http://127.0.0.1:${settings.mcpPort}/mcp"
        val rpc = JsonRpcRequest(
            id = JsonPrimitive(requestId.getAndIncrement()),
            method = method,
            params = params
        )
        val payload = json.encodeToString(rpc)

        return withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${settings.mcpAuthToken}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            sessionId?.let { builder.header("Mcp-Session-Id", it) }

            http.newCall(builder.build()).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let { sessionId = it }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Local MCP HTTP ${resp.code}: $raw")
                }
                json.decodeFromString<JsonRpcResponse>(raw)
            }
        }
    }

    private suspend fun sendNotification(method: String) {
        val settings = configStore.getSettings()
        val url = "http://127.0.0.1:${settings.mcpPort}/mcp"
        val rpc = JsonRpcRequest(id = null, method = method, params = null)
        val payload = json.encodeToString(rpc)

        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${settings.mcpAuthToken}")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            http.newCall(builder.build()).execute().close()
        }
    }
}
