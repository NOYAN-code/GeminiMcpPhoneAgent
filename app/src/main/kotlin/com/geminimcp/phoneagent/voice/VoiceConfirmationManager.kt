package com.geminimcp.phoneagent.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

data class PendingConfirmation(
    val toolName: String,
    val summary: String,
    val spokenPrompt: String
)

class VoiceConfirmationManager {

    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    @Volatile
    private var activeDecision: CompletableDeferred<Boolean>? = null

    @Volatile
    var onSpeakAndListenPrompt: ((spokenPrompt: String, onVoiceReply: (String) -> Unit) -> Unit)? = null

    suspend fun requestConfirmation(
        toolName: String,
        summary: String,
        spokenPrompt: String,
        timeoutMs: Long = 18_000L
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        activeDecision = deferred
        _pending.value = PendingConfirmation(toolName, summary, spokenPrompt)

        onSpeakAndListenPrompt?.invoke(spokenPrompt) { transcript ->
            val clean = transcript.trim().lowercase()
            when {
                POSITIVE_WORDS.any { clean.contains(it) } -> resolve(true)
                NEGATIVE_WORDS.any { clean.contains(it) } -> resolve(false)
            }
        }

        val approved = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            activeDecision = null
            _pending.value = null
        }
        return approved
    }

    fun resolve(approved: Boolean) {
        activeDecision?.complete(approved)
    }

    companion object {
        private val POSITIVE_WORDS = listOf("confirm", "yes", "approve", "proceed", "go ahead", "do it")
        private val NEGATIVE_WORDS = listOf("cancel", "no", "stop", "abort", "deny", "don't")
    }
}
