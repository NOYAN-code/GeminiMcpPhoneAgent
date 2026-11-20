package com.geminimcp.phoneagent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.geminimcp.phoneagent.gemini.AgentStepEvent
import com.geminimcp.phoneagent.voice.VoiceState

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTriggerIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AgentDashboardScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBridgeStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTriggerIntent(intent)
    }

    private fun handleTriggerIntent(intent: Intent?) {
        if (intent?.action == "com.geminimcp.phoneagent.ACTION_VOICE_TRIGGER") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.triggerVoiceCommand()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentDashboardScreen(viewModel: AgentViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val transcript by viewModel.partialTranscript.collectAsState()
    val metrics by viewModel.usageMetrics.collectAsState()
    val pendingConfirm by viewModel.pendingConfirmation.collectAsState()

    var commandInput by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshBridgeStatus()
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val officialGeminiUrl = remember(uiState.settings.tunnelBaseUrl, uiState.settings.mcpAuthToken) {
        val base = uiState.settings.tunnelBaseUrl.trim().trimEnd('/')
        if (base.isNotBlank()) "$base/mcp/${uiState.settings.mcpAuthToken}" else ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemini MCP Phone Agent", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (uiState.isExecuting) {
                        viewModel.cancelActiveTask()
                    } else {
                        viewModel.triggerVoiceCommand()
                    }
                },
                containerColor = if (uiState.isExecuting || voiceState == VoiceState.COMMAND_LISTENING) {
                    Color(0xFFD32F2F)
                } else {
                    MaterialTheme.colorScheme.primary
                }
            ) {
                Icon(
                    imageVector = if (uiState.isExecuting) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Voice Command"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Local MCP Server & OS Bridge Status Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = if (serverState.running) "MCP Server: RUNNING" else "MCP Server: STOPPED",
                                fontWeight = FontWeight.Bold,
                                color = if (serverState.running) Color(0xFF81C784) else Color(0xFFE57373)
                            )
                            Text(
                                text = "Local: 127.0.0.1:${uiState.settings.mcpPort}/mcp",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = {
                            if (serverState.running) viewModel.stopServer() else viewModel.startServer()
                        }) {
                            Icon(
                                if (serverState.running) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Text(if (serverState.running) " Stop" else " Start", maxLines = 1, softWrap = false)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (uiState.accessibilityConnected) "Accessibility: ON" else "Enable Accessibility")
                        }
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (uiState.notificationsConnected) "Notifications: ON" else "Enable Notifs")
                        }
                    }
                }
            }

            // Official Gemini App HTTPS Tunnel Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (serverState.tunnelConnected) Color(0xFF1B3A26) else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (serverState.tunnelConnected) {
                            "Official Gemini App Tunnel: CONNECTED"
                        } else {
                            "Official Gemini App Tunnel"
                        },
                        fontWeight = FontWeight.Bold,
                        color = if (serverState.tunnelConnected) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = serverState.tunnelStatusText,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (officialGeminiUrl.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = officialGeminiUrl,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Gemini MCP URL", officialGeminiUrl))
                                Toast.makeText(context, "Copied Official Gemini MCP URL", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Text(" Copy URL")
                            }
                        }
                    } else {
                        TextButton(onClick = { showSettings = true }) {
                            Text("Set Cloudflare Worker Tunnel URL in Settings →")
                        }
                    }
                }
            }

            // Optional In-App Voice / Text Command Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (voiceState == VoiceState.COMMAND_LISTENING) "Listening... $transcript"
                            else "Optional: test command locally..."
                        )
                    },
                    singleLine = true
                )
                Button(
                    enabled = !uiState.isExecuting && commandInput.isNotBlank(),
                    onClick = {
                        viewModel.executeGoal(commandInput)
                        commandInput = ""
                    }
                ) {
                    Text("Run")
                }
            }

            if (uiState.lastSummary.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.lastSummary,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Execution Trace", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Local API calls: ${metrics.requestsToday}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(uiState.steps) { step ->
                    StepLogCard(step)
                }
            }
        }
    }

    pendingConfirm?.let { req ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConfirmation(false) },
            title = { Text("Confirm Sensitive Action") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tool: ${req.toolName}", fontWeight = FontWeight.Bold)
                    Text(req.summary)
                    Text(
                        "Say \"confirm\" or \"cancel\", or tap below.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.resolveConfirmation(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.resolveConfirmation(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSettings) {
        SettingsDialog(
            settings = uiState.settings,
            onDismiss = { showSettings = false },
            onRotateToken = { viewModel.rotateAuthToken() },
            onSave = { key, model, port, tunnelUrl, wake, confirm, steps, rpm, budget ->
                viewModel.saveConfiguration(key, model, port, tunnelUrl, wake, confirm, steps, rpm, budget)
                showSettings = false
            }
        )
    }
}

@Composable
private fun StepLogCard(step: AgentStepEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (step.isError) Color(0xFF3E1C1C) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "[Step ${step.stepIndex}] ${step.title}",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(text = step.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: com.geminimcp.phoneagent.config.AgentSettings,
    onDismiss: () -> Unit,
    onRotateToken: () -> Unit,
    onSave: (String, String, Int, String, String, Boolean, Int, Int, Double) -> Unit
) {
    var tunnelBaseUrl by remember { mutableStateOf(settings.tunnelBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.geminiApiKey) }
    var model by remember { mutableStateOf(settings.geminiModel) }
    var portText by remember { mutableStateOf(settings.mcpPort.toString()) }
    var wakePhrase by remember { mutableStateOf(settings.wakePhrase) }
    var requireConfirm by remember { mutableStateOf(settings.requireVoiceConfirmation) }
    var maxSteps by remember { mutableStateOf(settings.maxStepsPerTask.toString()) }
    var maxRpm by remember { mutableStateOf(settings.maxRequestsPerMinute.toString()) }
    var budget by remember { mutableStateOf(settings.dailyBudgetUsd.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tunnel & MCP Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tunnelBaseUrl,
                    onValueChange = { tunnelBaseUrl = it },
                    label = { Text("Cloudflare Worker Tunnel URL") },
                    placeholder = { Text("https://gemini-phone-mcp-relay.xxx.workers.dev") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key (Optional for Official App)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Local Agent Model") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("MCP Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = wakePhrase,
                        onValueChange = { wakePhrase = it },
                        label = { Text("Wake Phrase") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxSteps,
                        onValueChange = { maxSteps = it },
                        label = { Text("Max Steps") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = maxRpm,
                        onValueChange = { maxRpm = it },
                        label = { Text("Max RPM") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = budget,
                        onValueChange = { budget = it },
                        label = { Text("Daily $") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Voice Confirm Sensitive Actions", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = requireConfirm, onCheckedChange = { requireConfirm = it })
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Secret MCP Token: ${settings.mcpAuthToken.take(12)}...",
                    style = MaterialTheme.typography.labelSmall
                )
                TextButton(onClick = onRotateToken) {
                    Text("Rotate Secret Tunnel Token")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    apiKey,
                    model,
                    portText.toIntOrNull() ?: 8765,
                    tunnelBaseUrl,
                    wakePhrase,
                    requireConfirm,
                    maxSteps.toIntOrNull() ?: 15,
                    maxRpm.toIntOrNull() ?: 15,
                    budget.toDoubleOrNull() ?: 0.50
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
