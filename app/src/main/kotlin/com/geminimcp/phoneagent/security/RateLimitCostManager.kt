package com.geminimcp.phoneagent.security

import com.geminimcp.phoneagent.config.AgentConfigStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.ArrayDeque

data class UsageMetrics(
    val requestsToday: Int = 0,
    val promptTokensToday: Long = 0,
    val outputTokensToday: Long = 0,
    val estimatedCostUsdToday: Double = 0.0,
    val lastResetDate: String = LocalDate.now().toString()
)

class RateLimitCostManager(private val configStore: AgentConfigStore) {

    private val lock = Any()
    private val recentRequestTimestamps = ArrayDeque<Long>()

    private val _metrics = MutableStateFlow(UsageMetrics())
    val metrics: StateFlow<UsageMetrics> = _metrics.asStateFlow()

    suspend fun acquireSlotOrThrow() {
        val settings = configStore.getSettings()
        resetIfNewDay()

        if (_metrics.value.estimatedCostUsdToday >= settings.dailyBudgetUsd) {
            throw IllegalStateException(
                "Daily Gemini API budget cap (\$${"%.2f".format(settings.dailyBudgetUsd)}) reached. " +
                    "Increase the budget in Settings to continue."
            )
        }

        while (true) {
            val waitMs = synchronized(lock) {
                val now = System.currentTimeMillis()
                val windowStart = now - 60_000L
                while (recentRequestTimestamps.isNotEmpty() && recentRequestTimestamps.first() < windowStart) {
                    recentRequestTimestamps.removeFirst()
                }
                if (recentRequestTimestamps.size < settings.maxRequestsPerMinute) {
                    recentRequestTimestamps.addLast(now)
                    0L
                } else {
                    (recentRequestTimestamps.first() + 60_000L - now).coerceAtLeast(250L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    fun recordUsage(model: String, promptTokens: Long, outputTokens: Long) {
        resetIfNewDay()
        // Approximate per-1M token pricing (USD) for Gemini 2.5 Flash / Pro
        val (inRatePerM, outRatePerM) = when {
            model.contains("pro", ignoreCase = true) -> 1.25 to 10.00
            model.contains("lite", ignoreCase = true) -> 0.10 to 0.40
            else -> 0.30 to 2.50 // gemini-2.5-flash default
        }
        val deltaCost = (promptTokens / 1_000_000.0) * inRatePerM +
            (outputTokens / 1_000_000.0) * outRatePerM

        synchronized(lock) {
            val cur = _metrics.value
            _metrics.value = cur.copy(
                requestsToday = cur.requestsToday + 1,
                promptTokensToday = cur.promptTokensToday + promptTokens,
                outputTokensToday = cur.outputTokensToday + outputTokens,
                estimatedCostUsdToday = cur.estimatedCostUsdToday + deltaCost
            )
        }
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now().toString()
        synchronized(lock) {
            if (_metrics.value.lastResetDate != today) {
                _metrics.value = UsageMetrics(lastResetDate = today)
            }
        }
    }
}
