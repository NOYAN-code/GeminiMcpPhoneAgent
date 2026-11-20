package com.geminimcp.phoneagent.gemini

import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.security.RateLimitCostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiApiClient(
    private val configStore: AgentConfigStore,
    private val rateLimitCostManager: RateLimitCostManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun generateContent(requestPayload: GeminiRequest): GeminiResponse {
        val settings = configStore.getSettings()
        if (settings.geminiApiKey.isBlank()) {
            throw IllegalStateException("Gemini API key is missing. Add your API key in Settings.")
        }

        val model = settings.geminiModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val bodyStr = json.encodeToString(requestPayload)

        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            rateLimitCostManager.acquireSlotOrThrow()

            val result = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", settings.geminiApiKey)
                    .header("Content-Type", "application/json")
                    .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    Triple(resp.code, resp.isSuccessful, raw)
                }
            }

            val (code, ok, raw) = result
            if (ok) {
                val parsed = json.decodeFromString<GeminiResponse>(raw)
                parsed.usageMetadata?.let { usage ->
                    rateLimitCostManager.recordUsage(
                        model = model,
                        promptTokens = usage.promptTokenCount,
                        outputTokens = usage.candidatesTokenCount
                    )
                }
                return parsed
            }

            if (code == 429 || code == 500 || code == 503) {
                val backoffMs = (1000L shl attempt) + (0..300).random()
                lastError = IllegalStateException("Gemini API transient HTTP $code: ${raw.take(200)}")
                delay(backoffMs)
                continue
            }

            throw IllegalStateException("Gemini API HTTP $code: ${raw.take(300)}")
        }

        throw lastError ?: IllegalStateException("Gemini API failed after retries.")
    }
}
