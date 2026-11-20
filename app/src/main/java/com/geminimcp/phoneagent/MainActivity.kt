package com.geminimcp.phoneagent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geminimcp.phoneagent.accessibility.PhoneAccessibilityService
import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.notifications.PhoneNotificationListenerService
import com.geminimcp.phoneagent.service.McpHostService

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0E1117)) {
                    DashboardScreen()
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen() {
    val context = LocalContext.current
    val serverRunning by AgentConfigStore.serverRunning.collectAsState()
    val tunnelStatus by AgentConfigStore.tunnelStatus.collectAsState()
    val lastCmd by AgentConfigStore.lastCommandLog.collectAsState()

    var accConnected by remember { mutableStateOf(PhoneAccessibilityService.isConnected()) }
    var notifConnected by remember { mutableStateOf(PhoneNotificationListenerService.isConnected()) }
    var showSettings by remember { mutableStateOf(false) }

    val officialUrl = remember(tunnelStatus, showSettings) {
        AgentConfigStore.buildOfficialMcpUrl(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Gemini MCP Agent",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "v2.0 Fast Command & Batch Engine (26 tools)",
                    fontSize = 12.sp,
                    color = Color(0xFF7DD3FC)
                )
            }
            OutlinedButton(onClick = { showSettings = true }) {
                Text("Settings", maxLines = 1, softWrap = false)
            }
        }

        // Local Server Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Local MCP Server: ${if (serverRunning) "RUNNING" else "STOPPED"}",
                    fontWeight = FontWeight.SemiBold,
                    color = if (serverRunning) Color(0xFF4ADE80) else Color(0xFFF87171)
                )
                Text(
                    text = "Last activity: $lastCmd",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            accConnected = PhoneAccessibilityService.isConnected()
                            notifConnected = PhoneNotificationListenerService.isConnected()
                            McpHostService.start(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("Start", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { McpHostService.stop(context) }
                    ) {
                        Text("Stop", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        // Cloudflare Tunnel Card
        val isConnected = tunnelStatus == "CONNECTED"
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected) Color(0xFF0F291E) else Color(0xFF161B22)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Official Gemini App Tunnel: $tunnelStatus",
                    fontWeight = FontWeight.SemiBold,
                    color = if (isConnected) Color(0xFF4ADE80) else Color(0xFFFBBF24)
                )
                if (officialUrl.isNotBlank()) {
                    Text(
                        text = officialUrl,
                        fontSize = 11.sp,
                        color = Color(0xFFD1D5DB),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Gemini MCP URL", officialUrl))
                            Toast.makeText(context, "Copied Official Gemini MCP URL", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                    ) {
                        Text("Copy URL", maxLines = 1, softWrap = false)
                    }
                } else {
                    Text(
                        text = "Open Settings to enter your Cloudflare Worker URL.",
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
            }
        }

        // Permissions Bridge Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Android Bridges",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Accessibility: ${if (accConnected) "ON" else "OFF"}",
                        color = if (accConnected) Color(0xFF4ADE80) else Color(0xFFF87171)
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text("Enable", maxLines = 1, softWrap = false)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Notifications: ${if (notifConnected) "ON" else "OFF"}",
                        color = if (notifConnected) Color(0xFF4ADE80) else Color(0xFFF87171)
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text("Enable", maxLines = 1, softWrap = false)
                    }
                }
                TextButton(onClick = {
                    accConnected = PhoneAccessibilityService.isConnected()
                    notifConnected = PhoneNotificationListenerService.isConnected()
                }) {
                    Text("Refresh Status")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showSettings) {
        SettingsDialog(
            context = context,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun SettingsDialog(context: Context, onDismiss: () -> Unit) {
    var workerUrl by remember { mutableStateOf(AgentConfigStore.getTunnelUrl(context)) }
    var secretToken by remember { mutableStateOf(AgentConfigStore.getOrCreateToken(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tunnel & Server Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = workerUrl,
                    onValueChange = { workerUrl = it },
                    label = { Text("Cloudflare Worker Tunnel URL") },
                    placeholder = { Text("https://gemini-phone-mcp-relay.xxx.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secretToken,
                    onValueChange = { secretToken = it },
                    label = { Text("Secret Tunnel Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    secretToken = AgentConfigStore.rotateToken(context)
                }) {
                    Text("Rotate Secret Token")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                AgentConfigStore.setTunnelUrl(context, workerUrl)
                AgentConfigStore.setCustomToken(context, secretToken)
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
