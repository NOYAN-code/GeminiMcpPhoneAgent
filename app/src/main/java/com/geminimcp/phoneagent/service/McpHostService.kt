package com.geminimcp.phoneagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.geminimcp.phoneagent.MainActivity
import com.geminimcp.phoneagent.mcp.LocalMcpServer
import com.geminimcp.phoneagent.mcp.McpRequestProcessor
import com.geminimcp.phoneagent.tunnel.RemoteTunnelClient

class McpHostService : Service() {

    companion object {
        private const val CHANNEL_ID = "gemini_mcp_host_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.geminimcp.phoneagent.START"
        const val ACTION_STOP = "com.geminimcp.phoneagent.STOP"

        fun start(context: Context) {
            val intent = Intent(context, McpHostService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpHostService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    private var localServer: LocalMcpServer? = null
    private var tunnelClient: RemoteTunnelClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val processor = McpRequestProcessor(applicationContext)
        localServer = LocalMcpServer(applicationContext, processor)
        tunnelClient = RemoteTunnelClient(applicationContext, processor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tunnelClient?.stop()
            localServer?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        localServer?.start()
        tunnelClient?.stop()
        tunnelClient?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        tunnelClient?.destroy()
        localServer?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gemini MCP Server v2.0",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini MCP Server v2.0 Active")
            .setContentText("Fast Command & Batch Engine ready on phone")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}
