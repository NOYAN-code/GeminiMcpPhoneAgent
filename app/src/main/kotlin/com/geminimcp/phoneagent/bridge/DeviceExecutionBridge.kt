package com.geminimcp.phoneagent.bridge

import android.content.Context
import com.geminimcp.phoneagent.security.RiskLevel
import com.geminimcp.phoneagent.security.SafetyGuard
import com.geminimcp.phoneagent.voice.VoiceConfirmationManager
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolExecutionResult(
    val ok: Boolean,
    val message: String,
    val dataJson: String? = null,
    val base64Jpeg: String? = null,
    val uiChanged: Boolean? = null,
    val activePackage: String? = null,
    val stateHash: String? = null
)

class DeviceExecutionBridge(
    context: Context,
    private val safetyGuard: SafetyGuard,
    private val confirmationManager: VoiceConfirmationManager
) {
    private val systemBridge = SystemControlBridge(context)
    private val json = Json { encodeDefaults = false; prettyPrint = false }

    suspend fun executeTool(name: String, args: JsonObject): ToolExecutionResult {
        val accessibility = PhoneAccessibilityService.instance
        val activePackage = accessibility?.currentPackageName

        // Resolve node label ahead of time for sensitive-action safety inspection
        val targetNode = resolveTargetNode(accessibility, args)
        val targetLabel = listOfNotNull(targetNode?.text, targetNode?.desc, targetNode?.resId)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

        val safety = safetyGuard.assessToolCall(name, args, activePackage, targetLabel)
        when (safety.riskLevel) {
            RiskLevel.BLOCKED -> {
                return ToolExecutionResult(
                    ok = false,
                    message = "BLOCKED BY PRIVACY SANDBOX: ${safety.reason}",
                    activePackage = activePackage
                )
            }
            RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED -> {
                val approved = confirmationManager.requestConfirmation(
                    toolName = name,
                    summary = safety.reason,
                    spokenPrompt = safety.spokenPrompt
                )
                if (!approved) {
                    return ToolExecutionResult(
                        ok = false,
                        message = "USER CANCELLED OR TIMED OUT sensitive action confirmation: ${safety.reason}",
                        activePackage = activePackage
                    )
                }
            }
            RiskLevel.SAFE -> Unit
        }

        return when (name) {
            "get_device_state" -> handleGetDeviceState(accessibility)
            "read_ui_tree" -> handleReadUiTree(accessibility, args)
            "capture_screen" -> handleCaptureScreen(accessibility)
            "tap_node" -> handleTapNode(accessibility, args, targetNode)
            "tap_xy" -> handleTapXy(accessibility, args)
            "long_press" -> handleLongPress(accessibility, args, targetNode)
            "swipe" -> handleSwipe(accessibility, args)
            "scroll" -> handleScroll(accessibility, args)
            "type_text" -> handleTypeText(accessibility, args, targetNode)
            "press_key" -> handlePressKey(accessibility, args)
            "launch_app" -> handleLaunchApp(accessibility, args)
            "list_installed_apps" -> handleListApps(args)
            "open_url" -> handleOpenUrl(accessibility, args)
            "get_notifications" -> handleGetNotifications(args)
            "manage_notification" -> handleManageNotification(args)
            "control_system_setting" -> handleControlSystemSetting(args)
            "wait_for_ui" -> handleWaitForUi(accessibility, args)
            "find_and_tap" -> handleFindAndTap(accessibility, args)
            else -> ToolExecutionResult(ok = false, message = "Unknown MCP tool: $name")
        }
    }

    private fun handleGetDeviceState(a11y: PhoneAccessibilityService?): ToolExecutionResult {
        val telemetry = systemBridge.getDeviceTelemetry()
        val (w, h) = a11y?.getScreenDimensions() ?: (0 to 0)
        val snapshot = a11y?.captureUiTree(maxNodes = 40)
        val payload = buildJsonObject {
            put("accessibility_connected", a11y != null)
            put("notification_listener_connected", AgentNotificationListener.isRunning())
            put("active_package", snapshot?.packageName ?: "unknown")
            put("screen_width", w)
            put("screen_height", h)
            put("ui_state_hash", snapshot?.stateHash ?: "unavailable")
            put("visible_node_count", snapshot?.nodeCount ?: 0)
            telemetry.forEach { (k, v) -> put(k, v) }
        }
        return ToolExecutionResult(
            ok = true,
            message = "Device state retrieved.",
            dataJson = payload.toString(),
            activePackage = snapshot?.packageName,
            stateHash = snapshot?.stateHash
        )
    }

    private fun handleReadUiTree(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val maxNodes = args["max_nodes"]?.jsonPrimitive?.intOrNull?.coerceIn(20, 150) ?: 110
        val snapshot = a11y.captureUiTree(maxNodes)
        if (safetyGuard.isPackageBlocked(snapshot.packageName)) {
            return ToolExecutionResult(
                ok = false,
                message = "BLOCKED BY PRIVACY SANDBOX: Cannot read UI tree of protected app ${snapshot.packageName}.",
                activePackage = snapshot.packageName
            )
        }
        return ToolExecutionResult(
            ok = true,
            message = "Captured ${snapshot.nodeCount} UI nodes in ${snapshot.packageName}.",
            dataJson = json.encodeToString(snapshot),
            activePackage = snapshot.packageName,
            stateHash = snapshot.stateHash
        )
    }

    private suspend fun handleCaptureScreen(a11y: PhoneAccessibilityService?): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        if (safetyGuard.isPackageBlocked(a11y.currentPackageName)) {
            return ToolExecutionResult(
                ok = false,
                message = "BLOCKED BY PRIVACY SANDBOX: Screenshot blocked on protected app ${a11y.currentPackageName}."
            )
        }
        val b64 = a11y.takeScreenshotBase64Jpeg()
            ?: return ToolExecutionResult(
                ok = false,
                message = "Failed to capture screenshot. Ensure Android 11+ Accessibility screenshot permission is active."
            )
        val (w, h) = a11y.getScreenDimensions()
        val meta = buildJsonObject {
            put("active_package", a11y.currentPackageName)
            put("screen_width", w)
            put("screen_height", h)
            put("mime_type", "image/jpeg")
        }
        return ToolExecutionResult(
            ok = true,
            message = "Captured screen JPEG for multimodal vision analysis (${w}x${h}).",
            dataJson = meta.toString(),
            base64Jpeg = b64,
            activePackage = a11y.currentPackageName
        )
    }

    private suspend fun handleTapNode(
        a11y: PhoneAccessibilityService?,
        args: JsonObject,
        resolvedNode: UiNodeSnapshot?
    ): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val beforeHash = a11y.lastSnapshot?.stateHash ?: a11y.captureUiTree().stateHash
        val node = resolvedNode ?: return ToolExecutionResult(
            ok = false,
            message = "Target node not found on screen. Call read_ui_tree or capture_screen to inspect current UI.",
            activePackage = a11y.currentPackageName,
            stateHash = beforeHash
        )
        val tapped = a11y.tapCoordinates(node.center[0].toFloat(), node.center[1].toFloat())
        return verifyAfterMutation(a11y, beforeHash, tapped, "Tapped node #${node.id} (${node.text ?: node.desc ?: node.cls})")
    }

    private suspend fun handleTapXy(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val x = args["x"]?.jsonPrimitive?.intOrNull ?: return ToolExecutionResult(false, "Missing integer 'x'.")
        val y = args["y"]?.jsonPrimitive?.intOrNull ?: return ToolExecutionResult(false, "Missing integer 'y'.")
        val beforeHash = a11y.captureUiTree().stateHash
        val tapped = a11y.tapCoordinates(x.toFloat(), y.toFloat())
        return verifyAfterMutation(a11y, beforeHash, tapped, "Tapped coordinates ($x, $y)")
    }

    private suspend fun handleLongPress(
        a11y: PhoneAccessibilityService?,
        args: JsonObject,
        resolvedNode: UiNodeSnapshot?
    ): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val beforeHash = a11y.captureUiTree().stateHash
        val x = resolvedNode?.center?.get(0) ?: args["x"]?.jsonPrimitive?.intOrNull
        val y = resolvedNode?.center?.get(1) ?: args["y"]?.jsonPrimitive?.intOrNull
        if (x == null || y == null) {
            return ToolExecutionResult(false, "Provide either node_id/text or x,y coordinates for long_press.")
        }
        val duration = args["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 800L
        val ok = a11y.tapCoordinates(x.toFloat(), y.toFloat(), duration)
        return verifyAfterMutation(a11y, beforeHash, ok, "Long-pressed ($x, $y) for ${duration}ms")
    }

    private suspend fun handleSwipe(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val beforeHash = a11y.captureUiTree().stateHash
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull
        val ok = if (direction != null) {
            a11y.scrollDirection(direction)
        } else {
            val sx = args["start_x"]?.jsonPrimitive?.intOrNull
            val sy = args["start_y"]?.jsonPrimitive?.intOrNull
            val ex = args["end_x"]?.jsonPrimitive?.intOrNull
            val ey = args["end_y"]?.jsonPrimitive?.intOrNull
            if (sx == null || sy == null || ex == null || ey == null) {
                return ToolExecutionResult(false, "Provide 'direction' (up/down/left/right) or start_x/start_y/end_x/end_y.")
            }
            val duration = args["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 320L
            a11y.swipeCoordinates(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), duration)
        }
        return verifyAfterMutation(a11y, beforeHash, ok, "Performed swipe")
    }

    private suspend fun handleScroll(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val beforeHash = a11y.captureUiTree().stateHash
        val ok = a11y.scrollDirection(direction)
        return verifyAfterMutation(a11y, beforeHash, ok, "Scrolled $direction")
    }

    private suspend fun handleTypeText(
        a11y: PhoneAccessibilityService?,
        args: JsonObject,
        resolvedNode: UiNodeSnapshot?
    ): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult(false, "Missing 'text' parameter.")
        val clear = args["clear_existing"]?.jsonPrimitive?.booleanOrNull ?: false
        val pressEnter = args["press_enter"]?.jsonPrimitive?.booleanOrNull ?: false
        val beforeHash = a11y.captureUiTree().stateHash
        val ok = a11y.typeTextIntoNode(resolvedNode, text, clear, pressEnter)
        return verifyAfterMutation(a11y, beforeHash, ok, "Typed text into input field")
    }

    private suspend fun handlePressKey(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val key = args["key"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult(false, "Missing 'key' (back, home, recents, notifications, quick_settings, lock_screen).")
        val beforeHash = a11y.captureUiTree().stateHash
        val ok = a11y.performGlobalKey(key)
        return verifyAfterMutation(a11y, beforeHash, ok, "Pressed global key '$key'")
    }

    private suspend fun handleLaunchApp(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        val target = args["app_name"]?.jsonPrimitive?.contentOrNull
            ?: args["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult(false, "Provide 'app_name' or 'package_name'.")
        val beforeHash = a11y?.captureUiTree()?.stateHash ?: ""
        val (ok, msg) = systemBridge.launchApp(target)
        if (!ok) return ToolExecutionResult(false, msg)
        delay(900L) // Allow target app window to launch
        val after = a11y?.captureUiTree()
        return ToolExecutionResult(
            ok = true,
            message = "$msg. Active app is now ${after?.packageName ?: "unknown"}.",
            uiChanged = after != null && after.stateHash != beforeHash,
            activePackage = after?.packageName,
            stateHash = after?.stateHash
        )
    }

    private fun handleListApps(args: JsonObject): ToolExecutionResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull
        val apps = systemBridge.listLaunchableApps(query = query)
        return ToolExecutionResult(
            ok = true,
            message = "Found ${apps.size} launchable apps.",
            dataJson = json.encodeToString(apps)
        )
    }

    private suspend fun handleOpenUrl(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult(false, "Missing 'url' parameter.")
        val (ok, msg) = systemBridge.openUrl(url)
        delay(700L)
        val after = a11y?.captureUiTree()
        return ToolExecutionResult(ok = ok, message = msg, activePackage = after?.packageName, stateHash = after?.stateHash)
    }

    private fun handleGetNotifications(args: JsonObject): ToolExecutionResult {
        val listener = AgentNotificationListener.instance
            ?: return ToolExecutionResult(false, "Notification Listener permission is not enabled in Android Settings.")
        val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 50) ?: 20
        val items = listener.listActiveNotifications(limit)
        return ToolExecutionResult(
            ok = true,
            message = "Retrieved ${items.size} active notifications.",
            dataJson = json.encodeToString(items)
        )
    }

    private fun handleManageNotification(args: JsonObject): ToolExecutionResult {
        val listener = AgentNotificationListener.instance
            ?: return ToolExecutionResult(false, "Notification Listener permission is not enabled.")
        val operation = args["operation"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: return ToolExecutionResult(false, "Missing 'operation' (dismiss, dismiss_all, invoke_action).")
        return when (operation) {
            "dismiss_all" -> ToolExecutionResult(listener.dismissAllClearable(), "Dismissed all clearable notifications.")
            "dismiss" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolExecutionResult(false, "Missing notification 'key'.")
                ToolExecutionResult(listener.dismissNotification(key), "Dismissed notification $key.")
            }
            "invoke_action" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolExecutionResult(false, "Missing notification 'key'.")
                val title = args["action_title"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolExecutionResult(false, "Missing 'action_title'.")
                val ok = listener.invokeNotificationAction(key, title)
                ToolExecutionResult(ok, if (ok) "Invoked notification action '$title'." else "Action '$title' not found.")
            }
            else -> ToolExecutionResult(false, "Unsupported operation '$operation'.")
        }
    }

    private fun handleControlSystemSetting(args: JsonObject): ToolExecutionResult {
        val setting = args["setting"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult(false, "Missing 'setting'.")
        val value = args["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val (ok, msg) = systemBridge.controlSetting(setting, value)
        return ToolExecutionResult(ok = ok, message = msg)
    }

    private suspend fun handleWaitForUi(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val expectedText = args["text"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val expectedPkg = args["package_name"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.intOrNull?.toLong()?.coerceIn(500L, 15_000L) ?: 5_000L
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val snap = a11y.captureUiTree()
            val pkgMatch = expectedPkg == null || snap.packageName.lowercase().contains(expectedPkg)
            val textMatch = expectedText == null || snap.nodes.any {
                it.text?.lowercase()?.contains(expectedText) == true ||
                    it.desc?.lowercase()?.contains(expectedText) == true
            }
            if (pkgMatch && textMatch) {
                return ToolExecutionResult(
                    ok = true,
                    message = "Condition met in ${snap.packageName}.",
                    activePackage = snap.packageName,
                    stateHash = snap.stateHash
                )
            }
            delay(350L)
        }
        val finalSnap = a11y.captureUiTree()
        return ToolExecutionResult(
            ok = false,
            message = "Timed out after ${timeoutMs}ms waiting for UI condition.",
            activePackage = finalSnap.packageName,
            stateHash = finalSnap.stateHash
        )
    }

    private suspend fun handleFindAndTap(a11y: PhoneAccessibilityService?, args: JsonObject): ToolExecutionResult {
        if (a11y == null) return accessibilityMissingResult()
        val query = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult(false, "Missing 'text' for find_and_tap.")
        val maxScrolls = args["max_scrolls"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 6) ?: 3

        for (attempt in 0..maxScrolls) {
            val snap = a11y.captureUiTree()
            val match = findBestMatchingNode(snap, query)
            if (match != null) {
                val tapped = a11y.tapCoordinates(match.center[0].toFloat(), match.center[1].toFloat())
                return verifyAfterMutation(a11y, snap.stateHash, tapped, "Found and tapped '${match.text ?: match.desc}' on scroll #$attempt")
            }
            if (attempt < maxScrolls) {
                a11y.scrollDirection("down")
                delay(450L)
            }
        }
        return ToolExecutionResult(
            ok = false,
            message = "Could not find '$query' after $maxScrolls scrolls.",
            activePackage = a11y.currentPackageName
        )
    }

    private suspend fun verifyAfterMutation(
        a11y: PhoneAccessibilityService,
        beforeHash: String,
        actionSucceeded: Boolean,
        summary: String
    ): ToolExecutionResult {
        if (!actionSucceeded) {
            return ToolExecutionResult(
                ok = false,
                message = "Action failed: $summary",
                uiChanged = false,
                activePackage = a11y.currentPackageName,
                stateHash = beforeHash
            )
        }
        delay(420L)
        val after = a11y.captureUiTree()
        val changed = after.stateHash != beforeHash
        return ToolExecutionResult(
            ok = true,
            message = "$summary. UI ${if (changed) "updated" else "unchanged"} (app: ${after.packageName}).",
            uiChanged = changed,
            activePackage = after.packageName,
            stateHash = after.stateHash
        )
    }

    private fun resolveTargetNode(a11y: PhoneAccessibilityService?, args: JsonObject): UiNodeSnapshot? {
        if (a11y == null) return null
        val snap = a11y.lastSnapshot ?: a11y.captureUiTree()
        val nodeId = args["node_id"]?.jsonPrimitive?.intOrNull
        if (nodeId != null) {
            return snap.nodes.firstOrNull { it.id == nodeId }
        }
        val query = args["text"]?.jsonPrimitive?.contentOrNull
            ?: args["content_desc"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return findBestMatchingNode(snap, query)
    }

    private fun findBestMatchingNode(snapshot: UiTreeSnapshot, query: String): UiNodeSnapshot? {
        val q = query.trim().lowercase()
        return snapshot.nodes.firstOrNull {
            it.text?.lowercase() == q || it.desc?.lowercase() == q
        } ?: snapshot.nodes.firstOrNull {
            it.text?.lowercase()?.contains(q) == true || it.desc?.lowercase()?.contains(q) == true
        }
    }

    private fun accessibilityMissingResult(): ToolExecutionResult = ToolExecutionResult(
        ok = false,
        message = "Accessibility Service is not enabled. Enable 'Gemini MCP Phone Bridge' in Android Accessibility Settings."
    )
}
