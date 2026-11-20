package com.geminimcp.phoneagent.mcp

import com.geminimcp.phoneagent.bridge.DeviceExecutionBridge
import com.geminimcp.phoneagent.security.SafetyGuard
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class LocalMcpServer(
    private val port: Int,
    private val bridge: DeviceExecutionBridge,
    private val safetyGuard: SafetyGuard
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var server: EmbeddedServer<*, *>? = null
    private val activeSessionId = UUID.randomUUID().toString()

    fun start() {
        if (server != null) return
        // STRICT LOOPBACK BINDING: 127.0.0.1 only — never exposed to Wi-Fi/LAN
        server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/health") {
                    val body = buildJsonObject {
                        put("status", "ok")
                        put("transport", "streamable-http")
                        put("bind", "127.0.0.1:$port")
                        put("protocolVersions", buildJsonArray {
                            add(JsonPrimitive("2025-06-18"))
                            add(JsonPrimitive("2025-03-26"))
                        })
                    }
                    call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }

                post("/mcp") {
                    handleMcpPost(call)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        server = null
    }

    private suspend fun handleMcpPost(call: ApplicationCall) {
        val authHeader = call.request.header(HttpHeaders.Authorization)
        val bearer = authHeader?.removePrefix("Bearer ")?.trim()
        if (!safetyGuard.isTokenValid(bearer)) {
            val err = JsonRpcResponse(
                id = null,
                error = JsonRpcError(-32001, "Unauthorized: invalid or missing local Bearer token.")
            )
            call.respondText(json.encodeToString(err), ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return
        }

        val rawBody = call.receiveText()
        val req = try {
            json.decodeFromString<JsonRpcRequest>(rawBody)
        } catch (e: Exception) {
            val err = JsonRpcResponse(
                id = null,
                error = JsonRpcError(-32700, "Parse error: ${e.message}")
            )
            call.respondText(json.encodeToString(err), ContentType.Application.Json, HttpStatusCode.BadRequest)
            return
        }

        // JSON-RPC notifications (no id) return 202 Accepted per MCP Streamable HTTP spec
        if (req.method.startsWith("notifications/")) {
            call.response.header("Mcp-Session-Id", activeSessionId)
            call.respondText("", ContentType.Application.Json, HttpStatusCode.Accepted)
            return
        }

        val response = when (req.method) {
            "initialize" -> handleInitialize(req)
            "ping" -> JsonRpcResponse(id = req.id, result = buildJsonObject {})
            "tools/list" -> handleToolsList(req)
            "tools/call" -> handleToolsCall(req)
            "resources/list" -> handleResourcesList(req)
            "resources/read" -> handleResourcesRead(req)
            else -> JsonRpcResponse(
                id = req.id,
                error = JsonRpcError(-32601, "Method not found: ${req.method}")
            )
        }

        call.response.header("Mcp-Session-Id", activeSessionId)
        call.respondText(json.encodeToString(response), ContentType.Application.Json, HttpStatusCode.OK)
    }

    private fun handleInitialize(req: JsonRpcRequest): JsonRpcResponse {
        val requestedVersion = req.params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
        val negotiatedVersion = if (requestedVersion in setOf("2025-03-26", "2025-06-18", "2026-07-28")) {
            requestedVersion!!
        } else {
            "2025-06-18"
        }
        val result = buildJsonObject {
            put("protocolVersion", negotiatedVersion)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
                put("resources", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", "gemini-android-mcp-server")
                put("version", "1.0.0")
            })
        }
        return JsonRpcResponse(id = req.id, result = result)
    }

    private fun handleToolsList(req: JsonRpcRequest): JsonRpcResponse {
        val toolsJson = json.encodeToJsonElement(McpToolRegistry.allTools())
        val result = buildJsonObject {
            put("tools", toolsJson)
        }
        return JsonRpcResponse(id = req.id, result = result)
    }

    private suspend fun handleToolsCall(req: JsonRpcRequest): JsonRpcResponse {
        val params = req.params ?: return JsonRpcResponse(
            id = req.id,
            error = JsonRpcError(-32602, "Missing params for tools/call")
        )
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return JsonRpcResponse(
            id = req.id,
            error = JsonRpcError(-32602, "Missing tool 'name'")
        )
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        val exec = bridge.executeTool(name, arguments)

        val blocks = mutableListOf<McpContentBlock>()
        val summaryPayload = buildJsonObject {
            put("ok", exec.ok)
            put("message", exec.message)
            exec.uiChanged?.let { put("ui_changed", it) }
            exec.activePackage?.let { put("active_package", it) }
            exec.stateHash?.let { put("state_hash", it) }
            exec.dataJson?.let { raw ->
                try {
                    put("data", json.parseToJsonElement(raw))
                } catch (_: Exception) {
                    put("data", raw)
                }
            }
        }
        blocks.add(McpContentBlock(type = "text", text = summaryPayload.toString()))

        if (!exec.base64Jpeg.isNullOrBlank()) {
            blocks.add(
                McpContentBlock(
                    type = "image",
                    data = exec.base64Jpeg,
                    mimeType = "image/jpeg"
                )
            )
        }

        val callResult = McpCallToolResult(content = blocks, isError = !exec.ok)
        return JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(callResult))
    }

    private fun handleResourcesList(req: JsonRpcRequest): JsonRpcResponse {
        val result = buildJsonObject {
            put("resources", json.encodeToJsonElement(McpToolRegistry.allResources()))
        }
        return JsonRpcResponse(id = req.id, result = result)
    }

    private suspend fun handleResourcesRead(req: JsonRpcRequest): JsonRpcResponse {
        val uri = req.params?.get("uri")?.jsonPrimitive?.contentOrNull
            ?: return JsonRpcResponse(id = req.id, error = JsonRpcError(-32602, "Missing 'uri'"))
        val exec = when (uri) {
            "phone://device/state" -> bridge.executeTool("get_device_state", JsonObject(emptyMap()))
            "phone://ui/tree" -> bridge.executeTool("read_ui_tree", JsonObject(emptyMap()))
            "phone://notifications" -> bridge.executeTool("get_notifications", JsonObject(emptyMap()))
            else -> return JsonRpcResponse(id = req.id, error = JsonRpcError(-32602, "Unknown resource URI: $uri"))
        }
        val result = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("uri", uri)
                    put("mimeType", "application/json")
                    put("text", exec.dataJson ?: exec.message)
                })
            })
        }
        return JsonRpcResponse(id = req.id, result = result)
    }
}
