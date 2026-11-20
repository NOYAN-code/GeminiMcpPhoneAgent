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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerRuntimeState(
    val running: Boolean = false,
    val port: Int = 8765,
    val lastError: String? = null
)

class McpForegroundService : Service() {

    private var mcpServer: LocalMcpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndService()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val settings = PhoneAgentApp.instance.configStore.getSettings()
        val notification = buildForegroundNotification(settings.mcpPort)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startServerIfNeeded(settings.mcpPort)
        return START_STICKY
    }

    private fun startServerIfNeeded(port: Int) {
        if (mcpServer != null) return
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
            _state.value = ServerRuntimeState(running = true, port = port, lastError = null)
        } catch (e: Exception) {
            _state.value = ServerRuntimeState(running = false, port = port, lastError = e.message)
        }
    }

    private fun stopServerAndService() {
        mcpServer?.stop()
        mcpServer = null
        _state.value = _state.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mcpServer?.stop()
        mcpServer = null
        _state.value = _state.value.copy(running = false)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gemini Local MCP Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the localhost MCP phone automation server active in background."
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val voiceIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.geminimcp.phoneagent.ACTION_VOICE_TRIGGER"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val voicePending = PendingIntent.getActivity(
            this, 1, voiceIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, McpForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Gemini MCP Server Active")
            .setContentText("Listening locally on 127.0.0.1:$port")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_btn_speak_now, "Voice Command", voicePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gemini_mcp_server_channel"
        private const val NOTIFICATION_ID = 4107
        const val ACTION_START = "com.geminimcp.phoneagent.ACTION_START_MCP"
        const val ACTION_STOP = "com.geminimcp.phoneagent.ACTION_STOP_MCP"

        private val _state = MutableStateFlow(ServerRuntimeState())
        val state: StateFlow<ServerRuntimeState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
