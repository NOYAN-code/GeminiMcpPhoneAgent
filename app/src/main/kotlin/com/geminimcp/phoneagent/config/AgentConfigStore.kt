package com.geminimcp.phoneagent.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

data class AgentSettings(
    val geminiApiKey: String,
    val geminiModel: String,
    val mcpPort: Int,
    val mcpAuthToken: String,
    val tunnelBaseUrl: String,
    val wakePhrase: String,
    val continuousWakeEnabled: Boolean,
    val maxStepsPerTask: Int,
    val maxRequestsPerMinute: Int,
    val dailyBudgetUsd: Double,
    val blockedPackages: Set<String>,
    val requireVoiceConfirmation: Boolean
)

class AgentConfigStore(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getSettings(): AgentSettings {
        val token = prefs.getString(KEY_MCP_TOKEN, null) ?: generateAndSaveToken()
        return AgentSettings(
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            mcpPort = prefs.getInt(KEY_MCP_PORT, DEFAULT_PORT),
            mcpAuthToken = token,
            tunnelBaseUrl = prefs.getString(KEY_TUNNEL_BASE_URL, "") ?: "",
            wakePhrase = prefs.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE) ?: DEFAULT_WAKE_PHRASE,
            continuousWakeEnabled = prefs.getBoolean(KEY_CONTINUOUS_WAKE, false),
            maxStepsPerTask = prefs.getInt(KEY_MAX_STEPS, 15).coerceIn(3, 40),
            maxRequestsPerMinute = prefs.getInt(KEY_MAX_RPM, 15).coerceIn(2, 60),
            dailyBudgetUsd = prefs.getFloat(KEY_DAILY_BUDGET, 0.50f).toDouble(),
            blockedPackages = prefs.getStringSet(KEY_BLOCKED_PACKAGES, DEFAULT_BLOCKED_PACKAGES)
                ?: DEFAULT_BLOCKED_PACKAGES,
            requireVoiceConfirmation = prefs.getBoolean(KEY_REQUIRE_CONFIRM, true)
        )
    }

    fun saveGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun saveGeminiModel(model: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL, model.trim().ifEmpty { DEFAULT_MODEL }).apply()
    }

    fun saveMcpPort(port: Int) {
        prefs.edit().putInt(KEY_MCP_PORT, port.coerceIn(1024, 65535)).apply()
    }

    fun saveTunnelBaseUrl(url: String) {
        prefs.edit().putString(KEY_TUNNEL_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    fun rotateMcpAuthToken(): String = generateAndSaveToken()

    fun saveWakePhrase(phrase: String) {
        prefs.edit().putString(KEY_WAKE_PHRASE, phrase.trim().lowercase().ifEmpty { DEFAULT_WAKE_PHRASE }).apply()
    }

    fun setContinuousWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTINUOUS_WAKE, enabled).apply()
    }

    fun setRequireVoiceConfirmation(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_CONFIRM, required).apply()
    }

    fun saveBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
    }

    fun saveRateLimits(maxSteps: Int, maxRpm: Int, dailyBudgetUsd: Double) {
        prefs.edit()
            .putInt(KEY_MAX_STEPS, maxSteps.coerceIn(3, 40))
            .putInt(KEY_MAX_RPM, maxRpm.coerceIn(2, 60))
            .putFloat(KEY_DAILY_BUDGET, dailyBudgetUsd.coerceIn(0.05, 50.0).toFloat())
            .apply()
    }

    private fun generateAndSaveToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY_MCP_TOKEN, token).apply()
        return token
    }

    companion object {
        private const val PREFS_NAME = "gemini_mcp_agent_secure_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_MCP_PORT = "mcp_port"
        private const val KEY_MCP_TOKEN = "mcp_auth_token"
        private const val KEY_TUNNEL_BASE_URL = "tunnel_base_url"
        private const val KEY_WAKE_PHRASE = "wake_phrase"
        private const val KEY_CONTINUOUS_WAKE = "continuous_wake"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_MAX_RPM = "max_rpm"
        private const val KEY_DAILY_BUDGET = "daily_budget_usd"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val KEY_REQUIRE_CONFIRM = "require_voice_confirmation"

        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_PORT = 8765
        const val DEFAULT_WAKE_PHRASE = "hey gemini"

        val DEFAULT_BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.google.android.apps.walletnfcrel"
        )
    }
}
