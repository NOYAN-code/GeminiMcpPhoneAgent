package com.geminimcp.phoneagent.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.geminimcp.phoneagent.PhoneAgentApp
import com.geminimcp.phoneagent.bridge.DeviceExecutionBridge
import com.geminimcp.phoneagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerRuntimeState(
    val running: Boolean = false,
    val port: Int = 8765,
    val tunnelConnected: Boolean = false,
    val publicMcpUrl: String = "",
    val tunnelStatusText: String = "Tunnel not configured",
    val lastError: String? = null
)

class McpForegroundService : Service() {

    private var mcpServer: LocalMcpServer? = null
    private var tunnelClient: RemoteTunnelClient? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndService()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val settings = PhoneAgentApp.instance.configStore.getSettings()
        val notification = buildForegroundNotification(settings.mcpPort, settings.tunnelBaseUrl.isNotBlank())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (intent?.action == ACTION_RECONNECT_TUNNEL) {
            tunnelClient?.start(settings.tunnelBaseUrl, settings.mcpAuthToken)
        } else {
            startServerAndTunnelIfNeeded(settings.mcpPort, settings.tunnelBaseUrl, settings.mcpAuthToken)
        }
        return START_STICKY
    }

    private fun startServerAndTunnelIfNeeded(port: Int, tunnelBaseUrl: String, authToken: String) {
        if (mcpServer == null) {
            try {
                val app = PhoneAgentApp.instance
                val bridge = DeviceExecutionBridge(
                    context = applicationContext,
                    safetyGuard = app.safetyGuard,
                    confirmationManager = app.confirmationManager
                )
                val server = LocalMcpServer(
                    port = port,
                    bridge = bridge,
                    safetyGuard = app.safetyGuard
                )
                server.start()
                mcpServer = server

                val client = RemoteTunnelClient(server)
                tunnelClient = client
                serviceScope.launch {
                    client.state.collect { tState ->
                        _state.value = _state.value.copy(
                            tunnelConnected = tState.connected,
                            publicMcpUrl = tState.publicMcpUrl,
                            tunnelStatusText = tState.statusText
                        )
                    }
                }
                _state.value = _state.value.copy(running = true, port = port, lastError = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, port = port, lastError = e.message)
                return
            }
        }
        tunnelClient?.start(tunnelBaseUrl, authToken)
    }

    private fun stopServerAndService() {
        tunnelClient?.stop()
        tunnelClient = null
        mcpServer?.stop()
        mcpServer = null
        _state.value = _state.value.copy(running = false, tunnelConnected = false, tunnelStatusText = "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tunnelClient?.stop()
        mcpServer?.stop()
        serviceScope.cancel()
        _state.value = _state.value.copy(running = false, tunnelConnected = false)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gemini Local MCP Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the local MCP server and Official Gemini App HTTPS tunnel alive."
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(port: Int, hasTunnel: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, McpForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val subtitle = if (hasTunnel) {
            "Local 127.0.0.1:$port + HTTPS Tunnel Active"
        } else {
            "Listening locally on 127.0.0.1:$port"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Gemini MCP Server Active")
            .setContentText(subtitle)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gemini_mcp_server_channel"
        private const val NOTIFICATION_ID = 4107
        const val ACTION_START = "com.geminimcp.phoneagent.ACTION_START_MCP"
        const val ACTION_STOP = "com.geminimcp.phoneagent.ACTION_STOP_MCP"
        const val ACTION_RECONNECT_TUNNEL = "com.geminimcp.phoneagent.ACTION_RECONNECT_TUNNEL"

        private val _state = MutableStateFlow(ServerRuntimeState())
        val state: StateFlow<ServerRuntimeState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun reconnectTunnel(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply { action = ACTION_RECONNECT_TUNNEL }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
