package com.geminimcp.phoneagent.mcp

import android.content.Context
import com.geminimcp.phoneagent.config.AgentConfigStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

class LocalMcpServer(
    private val appContext: Context,
    private val processor: McpRequestProcessor
) {
    private var engine: ApplicationEngine? = null

    fun start(port: Int = AgentConfigStore.getLocalPort(appContext)) {
        if (engine != null) return
        engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/health") {
                    call.respondText(
                        """{"status":"ok","version":"2.0.0"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                post("/mcp") {
                    val body = call.receiveText()
                    val (status, resp) = processor.handleJsonRpc(body)
                    call.respondText(resp, ContentType.Application.Json, HttpStatusCode.fromValue(status))
                }
                post("/mcp/{token}") {
                    val token = call.parameters["token"] ?: ""
                    val expected = AgentConfigStore.getOrCreateToken(appContext)
                    if (token != expected) {
                        call.respondText(
                            """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                        return@post
                    }
                    val body = call.receiveText()
                    val (status, resp) = processor.handleJsonRpc(body)
                    call.respondText(resp, ContentType.Application.Json, HttpStatusCode.fromValue(status))
                }
            }
        }.start(wait = false)
        AgentConfigStore.setServerRunning(true)
    }

    fun stop() {
        try {
            engine?.stop(300, 1000)
        } catch (_: Exception) {
        } finally {
            engine = null
            AgentConfigStore.setServerRunning(false)
        }
    }
}
