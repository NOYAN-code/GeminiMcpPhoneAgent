package com.geminimcp.phoneagent.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.geminimcp.phoneagent.accessibility.PhoneAccessibilityService
import com.geminimcp.phoneagent.config.AgentConfigStore
import com.geminimcp.phoneagent.notifications.PhoneNotificationListenerService
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

data class ToolExecutionResult(
    val ok: Boolean,
    val textJson: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

class PhoneToolExecutor(private val context: Context) {

    private var cachedApps: List<Pair<String, String>>? = null
    private var lastCacheTimeMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun execute(toolName: String, args: JsonObject): ToolExecutionResult {
        AgentConfigStore.logCommand("CMD: $toolName")
        return try {
            when (toolName) {
                "run_actions" -> handleRunActions(args)
                "set_alarm" -> handleSetAlarm(args)
                "set_timer" -> handleSetTimer(args)
                "open_url" -> handleOpenUrl(args)
                "search_web" -> handleSearchWeb(args)
                "send_intent" -> handleSendIntent(args)
                "media_control" -> handleMediaControl(args)
                "quick_communicate" -> handleQuickCommunicate(args)
                "clipboard" -> handleClipboard(args)
                "sleep" -> {
                    val ms = (args.int("ms") ?: 300).coerceIn(50, 5000).toLong()
                    delay(ms)
                    okResult("Slept ${ms}ms.")
                }
                "get_device_state" -> handleGetDeviceState()
                "read_ui_tree" -> handleReadUiTree(args)
                "capture_screen" -> handleCaptureScreen()
                "tap_node" -> handleTapNode(args)
                "tap_xy" -> handleTapXy(args)
                "long_press" -> handleLongPress(args)
                "swipe" -> handleSwipe(args)
                "scroll" -> handleScroll(args)
                "type_text" -> handleTypeText(args)
                "press_key" -> handlePressKey(args)
                "launch_app" -> handleLaunchApp(args)
                "list_installed_apps" -> handleListInstalledApps(args)
                "get_notifications" -> handleGetNotifications(args)
                "manage_notification" -> handleManageNotification(args)
                "control_system_setting" -> handleControlSystemSetting(args)
                "wait_for_ui" -> handleWaitForUi(args)
                "find_and_tap" -> handleFindAndTap(args)
                else -> errResult("Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            errResult("Tool '$toolName' failed: ${e.message}")
        }
    }

    // ==================== NEW v2.0 FAST COMMAND & BATCH TOOLS ====================

    private suspend fun handleRunActions(args: JsonObject): ToolExecutionResult {
        val actions = args["actions"]?.jsonArray ?: return errResult("Missing 'actions' array.")
        val delayMs = (args.int("delay_ms") ?: 120).coerceIn(0, 3000).toLong()
        val stopOnError = args.bool("stop_on_error") ?: true
        val returnFinalUi = args.bool("return_final_ui") ?: false

        val stepResults = mutableListOf<JsonObject>()
        var allOk = true

        for ((index, item) in actions.withIndex()) {
            val obj = item.jsonObject
            val subTool = obj.str("tool") ?: return errResult("Action #$index missing 'tool'.")
            if (subTool == "run_actions") {
                return errResult("Nested run_actions is not allowed.")
            }
            val subArgs = obj["args"]?.jsonObject ?: buildJsonObject {}
            val res = execute(subTool, subArgs)
            stepResults.add(buildJsonObject {
                put("step", index + 1)
                put("tool", subTool)
                put("ok", res.ok)
                put("result", res.textJson)
            })
            if (!res.ok) {
                allOk = false
                if (stopOnError) break
            }
            if (delayMs > 0 && index < actions.size - 1) {
                delay(delayMs)
            }
        }

        val finalUi = if (returnFinalUi) {
            PhoneAccessibilityService.instance?.captureUiSnapshot(90)?.toJson()
        } else null

        val payload = buildJsonObject {
            put("ok", allOk)
            put("executed_steps", stepResults.size)
            put("total_steps", actions.size)
            put("steps", JsonArray(stepResults))
            if (finalUi != null) put("final_ui", finalUi)
        }
        return ToolExecutionResult(allOk, payload.toString())
    }

    private suspend fun handleSetAlarm(args: JsonObject): ToolExecutionResult {
        val batch = args["alarms"]?.jsonArray
        val items: List<JsonObject> = if (batch != null && batch.isNotEmpty()) {
            batch.map { it.jsonObject }
        } else {
            val h = args.int("hour") ?: return errResult("Provide 'hour' & 'minute', or an 'alarms' array.")
            val m = args.int("minute") ?: return errResult("Provide 'minute'.")
            listOf(args)
        }

        val scheduled = mutableListOf<String>()
        for ((idx, item) in items.withIndex()) {
            val hour = (item.int("hour") ?: return errResult("Alarm #$idx missing hour")).coerceIn(0, 23)
            val minute = (item.int("minute") ?: return errResult("Alarm #$idx missing minute")).coerceIn(0, 59)
            val message = item.str("message") ?: ""
            val skipUi = item.bool("skip_ui") ?: true

            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                val daysArr = item["days"]?.jsonArray
                if (daysArr != null && daysArr.isNotEmpty()) {
                    val daysList = ArrayList<Int>()
                    daysArr.forEach { it.jsonPrimitive.intOrNull?.let { d -> daysList.add(d) } }
                    if (daysList.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, daysList)
                }
            }
            context.startActivity(intent)
            scheduled.add("%02d:%02d".format(hour, minute) + if (message.isNotBlank()) " ($message)" else "")
            if (items.size > 1 && idx < items.size - 1) {
                delay(280)
            }
        }

        val payload = buildJsonObject {
            put("ok", true)
            put("message", "Scheduled ${scheduled.size} alarm(s) directly via Android OS AlarmClock Intent.")
            put("alarms", buildJsonArray { scheduled.forEach { add(JsonPrimitive(it)) } })
        }
        return ToolExecutionResult(true, payload.toString())
    }

    private fun handleSetTimer(args: JsonObject): ToolExecutionResult {
        val seconds = (args.int("seconds") ?: return errResult("Missing 'seconds'.")).coerceAtLeast(1)
        val message = args.str("message") ?: ""
        val skipUi = args.bool("skip_ui") ?: true

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
        }
        context.startActivity(intent)
        return okResult("Started timer for ${seconds}s" + if (message.isNotBlank()) " ($message)." else ".")
    }

    private fun handleOpenUrl(args: JsonObject): ToolExecutionResult {
        val url = args.str("url") ?: return errResult("Missing 'url'.")
        val pkg = args.str("package_name")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!pkg.isNullOrBlank()) setPackage(pkg)
        }
        context.startActivity(intent)
        return okResult("Opened URL directly: $url")
    }

    private fun handleSearchWeb(args: JsonObject): ToolExecutionResult {
        val query = args.str("query") ?: return errResult("Missing 'query'.")
        val engine = (args.str("engine") ?: "google").lowercase()
        val encoded = URLEncoder.encode(query, "UTF-8")

        val uri = when (engine) {
            "youtube" -> "https://www.youtube.com/results?search_query=$encoded"
            "maps" -> "geo:0,0?q=$encoded"
            "play_store", "play" -> "market://search?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return okResult("Launched $engine search directly for '$query'.")
    }

    private fun handleSendIntent(args: JsonObject): ToolExecutionResult {
        val action = args.str("action") ?: return errResult("Missing 'action'.")
        val dataUri = args.str("data_uri")
        val pkg = args.str("package_name")
        val mime = args.str("mime_type")

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!dataUri.isNullOrBlank() && !mime.isNullOrBlank()) {
                setDataAndType(Uri.parse(dataUri), mime)
            } else if (!dataUri.isNullOrBlank()) {
                data = Uri.parse(dataUri)
            } else if (!mime.isNullOrBlank()) {
                type = mime
            }
            if (!pkg.isNullOrBlank()) setPackage(pkg)

            args["extras"]?.jsonObject?.forEach { (k, v) ->
                val prim = v as? JsonPrimitive ?: return@forEach
                when {
                    prim.isString -> putExtra(k, prim.content)
                    prim.booleanOrNull != null -> putExtra(k, prim.booleanOrNull!!)
                    prim.intOrNull != null -> putExtra(k, prim.intOrNull!!)
                    else -> putExtra(k, prim.content)
                }
            }
        }
        context.startActivity(intent)
        return okResult("Dispatched Intent '$action' directly.")
    }

    private fun handleMediaControl(args: JsonObject): ToolExecutionResult {
        val cmd = (args.str("command") ?: return errResult("Missing 'command'.")).lowercase()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (cmd) {
            "volume_up" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return okResult("Raised media volume.")
            }
            "volume_down" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return okResult("Lowered media volume.")
            }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                return okResult("Toggled media mute.")
            }
        }

        val keyCode = when (cmd) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return errResult("Unsupported media command '$cmd'.")
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return okResult("Executed media command '$cmd'.")
    }

    private fun handleQuickCommunicate(args: JsonObject): ToolExecutionResult {
        val type = (args.str("type") ?: return errResult("Missing 'type'.")).lowercase()
        val rawPhone = args.str("phone") ?: return errResult("Missing 'phone'.")
        val message = args.str("message") ?: ""

        val intent = when (type) {
            "dial", "call" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$rawPhone"))
            "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$rawPhone")).apply {
                if (message.isNotBlank()) putExtra("sms_body", message)
            }
            "whatsapp" -> {
                val digits = rawPhone.filter { it.isDigit() }
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val waUrl = if (message.isNotBlank()) "https://wa.me/$digits?text=$encodedMsg" else "https://wa.me/$digits"
                Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
            }
            else -> return errResult("Unsupported communication type '$type'. Use dial, sms, or whatsapp.")
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return okResult("Opened $type for $rawPhone.")
    }

    private fun handleClipboard(args: JsonObject): ToolExecutionResult {
        val op = (args.str("operation") ?: return errResult("Missing 'operation' (set or get).")).lowercase()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return when (op) {
            "set" -> {
                val text = args.str("text") ?: return errResult("Missing 'text' for clipboard set.")
                mainHandler.post {
                    cm.setPrimaryClip(ClipData.newPlainText("Gemini MCP", text))
                }
                okResult("Copied text to clipboard.")
            }
            "get" -> {
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                val payload = buildJsonObject {
                    put("ok", true)
                    put("text", text)
                }
                ToolExecutionResult(true, payload.toString())
            }
            else -> errResult("Unsupported clipboard operation '$op'. Use set or get.")
        }
    }

    // ==================== CORE DEVICE & ACCESSIBILITY TOOLS ====================

    private fun handleGetDeviceState(): ToolExecutionResult {
        val dm = context.resources.displayMetrics
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volPct = (curVol * 100) / maxVol
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "connected"
        }
        val acc = PhoneAccessibilityService.instance
        val snap = acc?.captureUiSnapshot(60)
        val payload = buildJsonObject {
            put("ok", true)
            put("version", "2.0.0")
            put("active_package", snap?.packageName ?: "unknown")
            put("state_hash", snap?.stateHash ?: "none")
            put("data", buildJsonObject {
                put("accessibility_connected", acc != null)
                put("notification_listener_connected", PhoneNotificationListenerService.isConnected())
                put("screen_width", dm.widthPixels)
                put("screen_height", dm.heightPixels)
                put("battery_percent", battery)
                put("media_volume_percent", volPct)
                put("ringer_mode", ringer)
                put("network", net)
                put("android_sdk", Build.VERSION.SDK_INT)
            })
        }
        return ToolExecutionResult(true, payload.toString())
    }

    private fun handleReadUiTree(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val maxNodes = args.int("max_nodes") ?: 120
        val snap = acc.captureUiSnapshot(maxNodes)
        val payload = buildJsonObject {
            put("ok", true)
            put("active_package", snap.packageName)
            put("state_hash", snap.stateHash)
            put("data", snap.toJson())
        }
        return ToolExecutionResult(true, payload.toString())
    }

    private suspend fun handleCaptureScreen(): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val (b64, msg) = acc.captureScreenshotBase64()
        if (b64 == null) return errResult(msg)
        val payload = buildJsonObject {
            put("ok", true)
            put("message", msg)
            put("active_package", acc.getActivePackage())
        }
        return ToolExecutionResult(true, payload.toString(), imageBase64 = b64, imageMimeType = "image/jpeg")
    }

    private suspend fun handleTapNode(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val prevHash = acc.lastSnapshot.stateHash
        val (ok, msg) = acc.tapNode(args.int("node_id"), args.str("text"), args.str("content_desc"))
        if (!ok) return errResult(msg)
        val after = acc.waitForUiChange(prevHash)
        return buildUiActionResult(ok, msg, prevHash != after.stateHash, after, args.bool("include_ui") ?: false)
    }

    private suspend fun handleTapXy(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val x = args.int("x") ?: return errResult("Missing x.")
        val y = args.int("y") ?: return errResult("Missing y.")
        val prevHash = acc.lastSnapshot.stateHash
        val ok = acc.tapCoordinates(x.toFloat(), y.toFloat())
        val after = acc.waitForUiChange(prevHash)
        return buildUiActionResult(ok, "Tapped ($x, $y).", prevHash != after.stateHash, after, args.bool("include_ui") ?: false)
    }

    private suspend fun handleLongPress(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val (ok, msg) = acc.longPressNode(
            args.int("node_id"),
            args.str("text"),
            args.int("x"),
            args.int("y"),
            (args.int("duration_ms") ?: 650).toLong()
        )
        return if (ok) okResult(msg) else errResult(msg)
    }

    private suspend fun handleSwipe(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val dir = args.str("direction")?.lowercase()
        val (sx, sy, ex, ey) = when (dir) {
            "up" -> listOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.25f)
            "down" -> listOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.75f)
            "left" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "right" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> listOf(
                (args.int("start_x") ?: (w * 0.5f).toInt()).toFloat(),
                (args.int("start_y") ?: (h * 0.7f).toInt()).toFloat(),
                (args.int("end_x") ?: (w * 0.5f).toInt()).toFloat(),
                (args.int("end_y") ?: (h * 0.3f).toInt()).toFloat()
            )
        }
        val dur = (args.int("duration_ms") ?: 220).toLong()
        val ok = acc.swipeGesture(sx, sy, ex, ey, dur)
        return if (ok) okResult("Swiped ${dir ?: "custom"}.") else errResult("Swipe failed.")
    }

    private suspend fun handleScroll(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val prevHash = acc.lastSnapshot.stateHash
        val dir = (args.str("direction") ?: "down").lowercase()
        val swipeDir = when (dir) {
            "down" -> "up"
            "up" -> "down"
            "left" -> "right"
            "right" -> "left"
            else -> "up"
        }
        val res = handleSwipe(buildJsonObject { put("direction", swipeDir); put("duration_ms", 200) })
        val after = acc.waitForUiChange(prevHash)
        return buildUiActionResult(res.ok, "Scrolled $dir.", prevHash != after.stateHash, after, args.bool("include_ui") ?: false)
    }

    private suspend fun handleTypeText(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val text = args.str("text") ?: return errResult("Missing 'text'.")
        val prevHash = acc.lastSnapshot.stateHash
        val (ok, msg) = acc.typeTextIntoNode(
            text = text,
            nodeId = args.int("node_id"),
            clearExisting = args.bool("clear_existing") ?: false,
            pressEnter = args.bool("press_enter") ?: false
        )
        if (!ok) return errResult(msg)
        val after = acc.waitForUiChange(prevHash, 250)
        return buildUiActionResult(true, msg, prevHash != after.stateHash, after, args.bool("include_ui") ?: false)
    }

    private suspend fun handlePressKey(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val key = args.str("key") ?: return errResult("Missing 'key'.")
        val (ok, msg) = acc.performSystemKey(key)
        delay(120)
        return if (ok) okResult(msg) else errResult(msg)
    }

    private suspend fun handleLaunchApp(args: JsonObject): ToolExecutionResult {
        val pkgArg = args.str("package_name")
        val nameArg = args.str("app_name")
        val resolvedPkg = when {
            !pkgArg.isNullOrBlank() -> pkgArg
            !nameArg.isNullOrBlank() -> findPackageByName(nameArg)
                ?: return errResult("App '$nameArg' not found.")
            else -> return errResult("Provide 'app_name' or 'package_name'.")
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(resolvedPkg)
            ?: return errResult("Cannot launch package '$resolvedPkg'.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        val acc = PhoneAccessibilityService.instance
        if (acc != null) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1200) {
                if (acc.getActivePackage() == resolvedPkg) break
                delay(80)
            }
            val snap = acc.captureUiSnapshot(90)
            return buildUiActionResult(true, "Launched $resolvedPkg.", true, snap, args.bool("include_ui") ?: false)
        }
        return okResult("Launched $resolvedPkg.")
    }

    private fun handleListInstalledApps(args: JsonObject): ToolExecutionResult {
        val query = args.str("query")?.lowercase()?.trim() ?: ""
        val apps = getLaunchableApps()
        val filtered = if (query.isEmpty()) apps else apps.filter {
            it.first.lowercase().contains(query) || it.second.lowercase().contains(query)
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("count", filtered.size)
            put("data", buildJsonArray {
                filtered.take(80).forEach { (label, pkg) ->
                    add(buildJsonObject {
                        put("label", label)
                        put("packageName", pkg)
                    })
                }
            })
        }
        return ToolExecutionResult(true, payload.toString())
    }

    private fun handleGetNotifications(args: JsonObject): ToolExecutionResult {
        val svc = PhoneNotificationListenerService.instance
            ?: return errResult("Notification listener not enabled.")
        val limit = args.int("limit") ?: 20
        val payload = buildJsonObject {
            put("ok", true)
            put("notifications", svc.listNotifications(limit))
        }
        return ToolExecutionResult(true, payload.toString())
    }

    private fun handleManageNotification(args: JsonObject): ToolExecutionResult {
        val svc = PhoneNotificationListenerService.instance
            ?: return errResult("Notification listener not enabled.")
        val op = args.str("operation") ?: return errResult("Missing 'operation'.")
        val (ok, msg) = svc.manageNotification(op, args.str("key"), args.str("action_title"))
        return if (ok) okResult(msg) else errResult(msg)
    }

    private fun handleControlSystemSetting(args: JsonObject): ToolExecutionResult {
        val setting = (args.str("setting") ?: return errResult("Missing 'setting'.")).lowercase()
        val value = args.str("value")?.lowercase()?.trim() ?: ""
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (setting) {
            "volume_media" -> setStreamVolume(audio, AudioManager.STREAM_MUSIC, value, "Media")
            "volume_ring" -> setStreamVolume(audio, AudioManager.STREAM_RING, value, "Ring")
            "volume_alarm" -> setStreamVolume(audio, AudioManager.STREAM_ALARM, value, "Alarm")
            "ringer_mode" -> {
                val mode = when (value) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    else -> return errResult("Use silent, vibrate, or normal.")
                }
                audio.ringerMode = mode
                okResult("Set ringer mode to $value.")
            }
            "flashlight" -> {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val camId = cm.cameraIdList.firstOrNull() ?: return errResult("No camera found.")
                val turnOn = value == "on" || value == "true" || value == "1"
                cm.setTorchMode(camId, turnOn)
                okResult("Flashlight turned ${if (turnOn) "ON" else "OFF"}.")
            }
            "wifi_settings", "wifi" -> openSettingsPanel(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi settings")
            "bluetooth_settings", "bluetooth" -> openSettingsPanel(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
            "brightness", "display_settings" -> openSettingsPanel(Settings.ACTION_DISPLAY_SETTINGS, "Display settings")
            "settings" -> openSettingsPanel(Settings.ACTION_SETTINGS, "System settings")
            else -> openSettingsPanel(Settings.ACTION_SETTINGS, "Settings")
        }
    }

    private suspend fun handleWaitForUi(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val targetText = args.str("text")
        val targetPkg = args.str("package_name")
        val timeout = (args.int("timeout_ms") ?: 3000).coerceIn(200, 12000).toLong()
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeout) {
            val snap = acc.captureUiSnapshot(90)
            val pkgOk = targetPkg.isNullOrBlank() || snap.packageName.equals(targetPkg, ignoreCase = true)
            val textOk = targetText.isNullOrBlank() || snap.nodes.any {
                it.text.contains(targetText, ignoreCase = true) || it.desc.contains(targetText, ignoreCase = true)
            }
            if (pkgOk && textOk) {
                return okResult("Condition met in ${snap.packageName}.")
            }
            delay(100)
        }
        return errResult("Timed out waiting for UI condition.")
    }

    private suspend fun handleFindAndTap(args: JsonObject): ToolExecutionResult {
        val acc = PhoneAccessibilityService.instance ?: return errResult("Accessibility service not enabled.")
        val text = args.str("text") ?: return errResult("Missing 'text'.")
        val maxScrolls = (args.int("max_scrolls") ?: 4).coerceIn(0, 10)
        val includeUi = args.bool("include_ui") ?: false

        for (attempt in 0..maxScrolls) {
            val (ok, msg) = acc.tapNode(null, text, text)
            if (ok) {
                val after = acc.waitForUiChange(acc.lastSnapshot.stateHash)
                return buildUiActionResult(true, msg, true, after, includeUi)
            }
            if (attempt < maxScrolls) {
                handleScroll(buildJsonObject { put("direction", "down") })
                delay(180)
            }
        }
        return errResult("Could not find '$text' after $maxScrolls scrolls.")
    }

    // ==================== HELPERS ====================

    private fun getLaunchableApps(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val existing = cachedApps
        if (existing != null && now - lastCacheTimeMs < 60_000L) return existing

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val list = resolved.map {
            val label = it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName
            label to it.activityInfo.packageName
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }

        cachedApps = list
        lastCacheTimeMs = now
        return list
    }

    private fun findPackageByName(query: String): String? {
        val q = query.lowercase().trim()
        val apps = getLaunchableApps()
        return apps.firstOrNull { it.first.lowercase() == q }?.second
            ?: apps.firstOrNull { it.first.lowercase().contains(q) }?.second
            ?: apps.firstOrNull { it.second.lowercase().contains(q) }?.second
    }

    private fun setStreamVolume(audio: AudioManager, stream: Int, value: String, name: String): ToolExecutionResult {
        val pct = value.removeSuffix("%").toIntOrNull()?.coerceIn(0, 100)
            ?: return errResult("Invalid volume percentage '$value'.")
        val max = audio.getStreamMaxVolume(stream)
        val target = (max * pct) / 100
        audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return okResult("Set $name volume to $pct%.")
    }

    private fun openSettingsPanel(action: String, label: String): ToolExecutionResult {
        val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
        return okResult("Opened $label.")
    }

    private fun buildUiActionResult(
        ok: Boolean,
        message: String,
        uiChanged: Boolean,
        snap: com.geminimcp.phoneagent.accessibility.UiSnapshot,
        includeUi: Boolean
    ): ToolExecutionResult {
        val payload = buildJsonObject {
            put("ok", ok)
            put("message", message)
            put("ui_changed", uiChanged)
            put("active_package", snap.packageName)
            put("state_hash", snap.stateHash)
            if (includeUi) put("ui", snap.toJson())
        }
        return ToolExecutionResult(ok, payload.toString())
    }

    private fun okResult(message: String): ToolExecutionResult {
        val json = buildJsonObject {
            put("ok", true)
            put("message", message)
        }.toString()
        return ToolExecutionResult(true, json)
    }

    private fun errResult(message: String): ToolExecutionResult {
        val json = buildJsonObject {
            put("ok", false)
            put("message", message)
        }.toString()
        return ToolExecutionResult(false, json)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
}
