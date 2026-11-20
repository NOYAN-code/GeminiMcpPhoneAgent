package com.geminimcp.phoneagent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

@Serializable
data class McpContentBlock(
    val type: String, // "text" or "image"
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContentBlock>,
    val isError: Boolean = false
)

@Serializable
data class McpResourceDefinition(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json"
)
