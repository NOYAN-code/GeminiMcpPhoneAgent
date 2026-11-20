package com.geminimcp.phoneagent.config

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

object AgentConfigStore {
    private const val PREFS_NAME = "gemini_mcp_agent_prefs"
    private const val KEY_TUNNEL_URL = "cloudflare_tunnel_url"
    private const val KEY_TUNNEL_TOKEN = "secret_tunnel_token"
    private const val KEY_PORT = "local_port"

    private val _tunnelStatus = MutableStateFlow("DISCONNECTED")
    val tunnelStatus: StateFlow<String> = _tunnelStatus

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning

    private val _lastCommandLog = MutableStateFlow("Ready for commands.")
    val lastCommandLog: StateFlow<String> = _lastCommandLog

    fun setTunnelStatus(status: String) {
        _tunnelStatus.value = status
    }

    fun setServerRunning(running: Boolean) {
        _serverRunning.value = running
    }

    fun logCommand(message: String) {
        _lastCommandLog.value = message
    }

    fun getTunnelUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TUNNEL_URL, "")?.trim()?.trimEnd('/') ?: ""
    }

    fun setTunnelUrl(context: Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TUNNEL_URL, cleaned)
            .apply()
    }

    fun getOrCreateToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TUNNEL_TOKEN, null)
        if (!existing.isNullOrBlank() && existing.length >= 16) return existing
        val generated = generateToken()
        prefs.edit().putString(KEY_TUNNEL_TOKEN, generated).apply()
        return generated
    }

    fun setCustomToken(context: Context, token: String) {
        val cleaned = token.trim()
        if (cleaned.length >= 16) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TUNNEL_TOKEN, cleaned)
                .apply()
        }
    }

    fun rotateToken(context: Context): String {
        val generated = generateToken()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TUNNEL_TOKEN, generated)
            .apply()
        return generated
    }

    fun getLocalPort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PORT, 8765)
    }

    fun buildOfficialMcpUrl(context: Context): String {
        val base = getTunnelUrl(context)
        if (base.isBlank()) return ""
        val token = getOrCreateToken(context)
        return "$base/mcp/$token"
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
