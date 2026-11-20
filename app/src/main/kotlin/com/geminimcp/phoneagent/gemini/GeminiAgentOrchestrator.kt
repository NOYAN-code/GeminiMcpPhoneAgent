package com.geminimcp.phoneagent.gemini

import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.mcp.McpToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AgentStepEvent(
    val stepIndex: Int,
    val title: String,
    val detail: String,
    val isError: Boolean = false
)

data class AgentRunResult(
    val completed: Boolean,
    val spokenSummary: String,
    val stepsExecuted: Int
)

class GeminiAgentOrchestrator(
    private val configStore: AgentConfigStore,
    private val geminiApi: GeminiApiClient,
    private val mcpClient: LocalMcpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val systemPrompt = """
        You are an autonomous, voice-controlled Android smartphone agent connected to a local MCP server.
        Follow this strict execution protocol:
        1. ALWAYS inspect state first (read_ui_tree or get_device_state) before tapping unknown elements.
        2. Prefer deterministic tools (launch_app, open_url, control_system_setting, tap_node, find_and_tap) over blind coordinate taps.
        3. After every UI mutation, verify the returned ui_changed flag and active_package. If ui_changed is false or a step fails:
           - Re-read the UI tree or call capture_screen to visually inspect the screen.
           - Self-correct by trying an alternate node, scrolling, pressing back, or using tap_xy from the screenshot.
        4. If a tool returns BLOCKED BY PRIVACY SANDBOX or USER CANCELLED, respect it immediately and inform the user concisely.
        5. Keep your final response short, natural, and suitable for spoken TTS feedback (1-2 sentences).
    """.trimIndent()

    suspend fun runTask(
        userGoal: String,
        onStep: (AgentStepEvent) -> Unit
    ): AgentRunResult {
        val settings = configStore.getSettings()
        val maxSteps = settings.maxStepsPerTask

        onStep(AgentStepEvent(0, "Connecting to Local MCP", "Discovering phone-control tools on 127.0.0.1:${settings.mcpPort}"))
        val mcpTools = mcpClient.listTools()
        val geminiTools = listOf(
            GeminiTool(functionDeclarations = mcpTools.map { it.toGeminiDeclaration() })
        )

        val conversation = mutableListOf(
            GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = "User voice command: $userGoal"))
            )
        )

        var consecutiveStalls = 0
        var stepsExecuted = 0

        for (step in 1..maxSteps) {
            stepsExecuted = step
            val response = try {
                geminiApi.generateContent(
                    GeminiRequest(
                        systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
                        contents = conversation,
                        tools = geminiTools,
                        generationConfig = GeminiGenerationConfig(temperature = 0.15, maxOutputTokens = 1024)
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                onStep(AgentStepEvent(step, "Gemini API Error", e.message ?: "Unknown API error", isError = true))
                return AgentRunResult(false, "I couldn't reach Gemini: ${e.message}", stepsExecuted)
            }

            val modelContent = response.candidates.firstOrNull()?.content
            if (modelContent == null || modelContent.parts.isEmpty()) {
                return AgentRunResult(false, "Gemini returned an empty response.", stepsExecuted)
            }

            // Append full model turn (preserving thoughtSignature and functionCall parts)
            conversation.add(modelContent.copy(role = "model"))

            val functionCalls = modelContent.parts.mapNotNull { it.functionCall }
            if (functionCalls.isEmpty()) {
                val finalText = modelContent.parts.mapNotNull { it.text }.joinToString(" ").trim()
                    .ifEmpty { "Task completed." }
                onStep(AgentStepEvent(step, "Completed", finalText))
                return AgentRunResult(true, finalText, stepsExecuted)
            }

            val responseParts = mutableListOf<GeminiPart>()
            var pendingScreenshotB64: String? = null
            var stepHadFailureOrStall = false

            for (fc in functionCalls) {
                onStep(AgentStepEvent(step, "MCP Tool: ${fc.name}", fc.args.toString()))

                val toolResult = try {
                    mcpClient.callTool(fc.name, fc.args)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    stepHadFailureOrStall = true
                    responseParts.add(
                        GeminiPart(
                            functionResponse = GeminiFunctionResponse(
                                name = fc.name,
                                response = buildJsonObject {
                                    put("ok", false)
                                    put("error", e.message ?: "MCP call failed")
                                }
                            )
                        )
                    )
                    onStep(AgentStepEvent(step, "Tool Error: ${fc.name}", e.message ?: "Failed", isError = true))
                    continue
                }

                val textPayload = toolResult.content.firstOrNull { it.type == "text" }?.text.orEmpty()
                val imageBlock = toolResult.content.firstOrNull { it.type == "image" && !it.data.isNullOrBlank() }
                if (imageBlock != null) {
                    pendingScreenshotB64 = imageBlock.data
                }

                val parsedObj = try {
                    json.parseToJsonElement(textPayload).jsonObject
                } catch (_: Exception) {
                    buildJsonObject { put("raw", textPayload) }
                }

                val ok = parsedObj["ok"]?.jsonPrimitive?.booleanOrNull ?: !toolResult.isError
                val uiChanged = parsedObj["ui_changed"]?.jsonPrimitive?.booleanOrNull
                if (!ok || (uiChanged == false && fc.name in MUTATING_UI_TOOLS)) {
                    stepHadFailureOrStall = true
                }

                responseParts.add(
                    GeminiPart(
                        functionResponse = GeminiFunctionResponse(
                            name = fc.name,
                            response = parsedObj
                        )
                    )
                )
            }

            // Automatic post-action self-correction & multimodal vision fallback
            if (stepHadFailureOrStall) {
                consecutiveStalls++
            } else {
                consecutiveStalls = 0
            }

            if (consecutiveStalls >= 2 && pendingScreenshotB64 == null) {
                onStep(
                    AgentStepEvent(
                        step,
                        "Auto Self-Correction",
                        "UI unchanged or action failed twice — capturing screenshot for Gemini vision fallback."
                    )
                )
                try {
                    val shot = mcpClient.callTool("capture_screen", JsonObject(emptyMap()))
                    pendingScreenshotB64 = shot.content.firstOrNull { it.type == "image" }?.data
                } catch (_: Exception) {
                    // Continue with text recovery hint
                }
            }

            if (pendingScreenshotB64 != null) {
                responseParts.add(
                    GeminiPart(
                        inlineData = GeminiInlineData(
                            mimeType = "image/jpeg",
                            data = pendingScreenshotB64
                        )
                    )
                )
            }

            if (stepHadFailureOrStall) {
                responseParts.add(
                    GeminiPart(
                        text = "Verification notice: the previous action failed or did not change the UI. " +
                            "Inspect the updated UI tree or attached screenshot and choose a corrective step."
                    )
                )
            }

            conversation.add(GeminiContent(role = "user", parts = responseParts))
        }

        val limitMsg = "Stopped after reaching the $maxSteps-step safety limit."
        onStep(AgentStepEvent(stepsExecuted, "Step Limit Reached", limitMsg, isError = true))
        return AgentRunResult(false, limitMsg, stepsExecuted)
    }

    private fun McpToolDefinition.toGeminiDeclaration(): GeminiFunctionDeclaration =
        GeminiFunctionDeclaration(
            name = name,
            description = description,
            parameters = inputSchema
        )

    companion object {
        private val MUTATING_UI_TOOLS = setOf("tap_node", "tap_xy", "long_press", "swipe", "scroll", "type_text", "find_and_tap")
    }
}
