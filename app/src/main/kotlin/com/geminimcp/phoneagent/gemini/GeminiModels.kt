package com.geminimcp.phoneagent.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GeminiRequest(
    @SerialName("system_instruction")
    val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val tools: List<GeminiTool>? = null,
    @SerialName("generation_config")
    val generationConfig: GeminiGenerationConfig? = null
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double = 0.2,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int = 1024
)

@Serializable
data class GeminiContent(
    val role: String? = null, // "user" or "model"
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
    val thoughtSignature: String? = null
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject
)

@Serializable
data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>
)

@Serializable
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsageMetadata? = null,
    val error: GeminiApiError? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@Serializable
data class GeminiUsageMetadata(
    val promptTokenCount: Long = 0,
    val candidatesTokenCount: Long = 0,
    val totalTokenCount: Long = 0
)

@Serializable
data class GeminiApiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)
