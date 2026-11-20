package com.geminimcp.phoneagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geminimcp.phoneagent.PhoneAgentApp
import com.geminimcp.phoneagent.bridge.AgentNotificationListener
import com.geminimcp.phoneagent.bridge.PhoneAccessibilityService
import com.geminimcp.phoneagent.config.AgentSettings
import com.geminimcp.phoneagent.gemini.AgentStepEvent
import com.geminimcp.phoneagent.gemini.GeminiAgentOrchestrator
import com.geminimcp.phoneagent.gemini.GeminiApiClient
import com.geminimcp.phoneagent.gemini.LocalMcpClient
import com.geminimcp.phoneagent.mcp.McpForegroundService
import com.geminimcp.phoneagent.mcp.ServerRuntimeState
import com.geminimcp.phoneagent.security.UsageMetrics
import com.geminimcp.phoneagent.voice.PendingConfirmation
import com.geminimcp.phoneagent.voice.VoiceController
import com.geminimcp.phoneagent.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentUiState(
    val settings: AgentSettings,
    val accessibilityConnected: Boolean = false,
    val notificationsConnected: Boolean = false,
    val isExecuting: Boolean = false,
    val steps: List<AgentStepEvent> = emptyList(),
    val lastSummary: String = ""
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = PhoneAgentApp.instance
    private val geminiApi = GeminiApiClient(app.configStore, app.rateLimitCostManager)
    private val mcpClient = LocalMcpClient(app.configStore)
    private val orchestrator = GeminiAgentOrchestrator(app.configStore, geminiApi, mcpClient)

    private val voiceController = VoiceController(application.applicationContext) { spokenCommand ->
        executeGoal(spokenCommand)
    }

    private val _uiState = MutableStateFlow(
        AgentUiState(settings = app.configStore.getSettings())
    )
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    val serverState: StateFlow<ServerRuntimeState> = McpForegroundService.state
    val voiceState: StateFlow<VoiceState> = voiceController.state
    val partialTranscript: StateFlow<String> = voiceController.partialTranscript
    val usageMetrics: StateFlow<UsageMetrics> = app.rateLimitCostManager.metrics
    val pendingConfirmation: StateFlow<PendingConfirmation?> = app.confirmationManager.pending

    private var activeJob: Job? = null

    init {
        app.confirmationManager.onSpeakAndListenPrompt = { prompt, onReply ->
            voiceController.speakAndListenForConfirmation(prompt, onReply)
        }
        refreshBridgeStatus()
    }

    fun refreshBridgeStatus() {
        _uiState.value = _uiState.value.copy(
            settings = app.configStore.getSettings(),
            accessibilityConnected = PhoneAccessibilityService.isRunning(),
            notificationsConnected = AgentNotificationListener.isRunning()
        )
    }

    fun startServer() {
        McpForegroundService.start(getApplication())
    }

    fun stopServer() {
        McpForegroundService.stop(getApplication())
    }

    fun triggerVoiceCommand() {
        if (!serverState.value.running) {
            startServer()
        }
        voiceController.startCommandListening()
    }

    fun toggleWakeWord(enabled: Boolean) {
        val current = app.configStore.getSettings()
        app.configStore.setContinuousWakeEnabled(enabled)
        voiceController.setWakeWordEnabled(enabled, current.wakePhrase)
        refreshBridgeStatus()
    }

    fun executeGoal(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return

        if (!serverState.value.running) {
            startServer()
        }

        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isExecuting = true,
            steps = listOf(AgentStepEvent(0, "User Goal", trimmed)),
            lastSummary = ""
        )

        activeJob = viewModelScope.launch {
            val result = orchestrator.runTask(trimmed) { event ->
                _uiState.value = _uiState.value.copy(
                    steps = _uiState.value.steps + event
                )
            }
            _uiState.value = _uiState.value.copy(
                isExecuting = false,
                lastSummary = result.spokenSummary
            )
            voiceController.speak(result.spokenSummary)
        }
    }

    fun cancelActiveTask() {
        activeJob?.cancel()
        activeJob = null
        app.confirmationManager.resolve(false)
        voiceController.stopAll()
        _uiState.value = _uiState.value.copy(
            isExecuting = false,
            lastSummary = "Task cancelled."
        )
    }

    fun resolveConfirmation(approved: Boolean) {
        app.confirmationManager.resolve(approved)
    }

    fun saveConfiguration(
        apiKey: String,
        model: String,
        port: Int,
        tunnelBaseUrl: String,
        wakePhrase: String,
        requireConfirm: Boolean,
        maxSteps: Int,
        maxRpm: Int,
        dailyBudgetUsd: Double
    ) {
        app.configStore.saveGeminiApiKey(apiKey)
        app.configStore.saveGeminiModel(model)
        app.configStore.saveMcpPort(port)
        app.configStore.saveTunnelBaseUrl(tunnelBaseUrl)
        app.configStore.saveWakePhrase(wakePhrase)
        app.configStore.setRequireVoiceConfirmation(requireConfirm)
        app.configStore.saveRateLimits(maxSteps, maxRpm, dailyBudgetUsd)
        refreshBridgeStatus()
        if (serverState.value.running) {
            McpForegroundService.reconnectTunnel(getApplication())
        }
    }

    fun rotateAuthToken() {
        app.configStore.rotateMcpAuthToken()
        refreshBridgeStatus()
        if (serverState.value.running) {
            McpForegroundService.reconnectTunnel(getApplication())
        }
    }

    override fun onCleared() {
        app.confirmationManager.onSpeakAndListenPrompt = null
        voiceController.release()
        super.onCleared()
    }
}
