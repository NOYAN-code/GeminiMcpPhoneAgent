package com.geminimcp.phoneagent.bridge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.Serializable

@Serializable
data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

class SystemControlBridge(private val context: Context) {

    fun listLaunchableApps(query: String? = null, limit: Int = 60): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        val q = query?.trim()?.lowercase().orEmpty()
        return resolveInfos.map { info ->
            InstalledAppInfo(
                label = info.loadLabel(pm)?.toString().orEmpty(),
                packageName = info.activityInfo.packageName
            )
        }
            .distinctBy { it.packageName }
            .filter {
                q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
            .sortedBy { it.label.lowercase() }
            .take(limit)
    }

    fun launchApp(appNameOrPackage: String): Pair<Boolean, String> {
        val pm = context.packageManager
        // 1. Direct package match
        pm.getLaunchIntentForPackage(appNameOrPackage)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return true to "Launched package $appNameOrPackage"
        }

        // 2. Fuzzy label match across launchable apps
        val candidates = listLaunchableApps(limit = 300)
        val normalized = appNameOrPackage.trim().lowercase()
        val exact = candidates.firstOrNull { it.label.lowercase() == normalized }
        val startsWith = candidates.firstOrNull { it.label.lowercase().startsWith(normalized) }
        val contains = candidates.firstOrNull {
            it.label.lowercase().contains(normalized) || it.packageName.lowercase().contains(normalized)
        }
        val matched = exact ?: startsWith ?: contains
            ?: return false to "No installed app matched '$appNameOrPackage'."

        val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
            ?: return false to "App '${matched.label}' (${matched.packageName}) has no launch intent."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true to "Launched ${matched.label} (${matched.packageName})"
    }

    fun openUrl(url: String): Pair<Boolean, String> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true to "Opened URI: $url"
        } catch (e: Exception) {
            false to "Failed to open URI '$url': ${e.message}"
        }
    }

    fun controlSetting(setting: String, value: String): Pair<Boolean, String> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (setting.lowercase()) {
            "volume_media", "volume_ring", "volume_alarm", "volume_call" -> {
                val stream = when (setting.lowercase()) {
                    "volume_media" -> AudioManager.STREAM_MUSIC
                    "volume_ring" -> AudioManager.STREAM_RING
                    "volume_alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_VOICE_CALL
                }
                val max = audio.getStreamMaxVolume(stream)
                val pct = value.toIntOrNull()?.coerceIn(0, 100)
                    ?: return false to "Volume value must be an integer percentage 0-100."
                val target = ((pct / 100.0) * max).toInt().coerceIn(0, max)
                audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                true to "Set $setting to $pct% ($target/$max)"
            }

            "ringer_mode" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (value.equals("silent", true) && !nm.isNotificationPolicyAccessGranted) {
                    return false to "Do Not Disturb policy permission required to set silent mode."
                }
                val mode = when (value.lowercase()) {
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    else -> return false to "Invalid ringer_mode '$value'. Use normal, vibrate, or silent."
                }
                audio.ringerMode = mode
                true to "Set ringer mode to $value"
            }

            "flashlight" -> {
                val enable = value.equals("on", true) || value.equals("true", true)
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return false to "No flashlight hardware found."
                cm.setTorchMode(cameraId, enable)
                true to "Flashlight turned ${if (enable) "ON" else "OFF"}"
            }

            "brightness" -> {
                if (!Settings.System.canWrite(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return false to "WRITE_SETTINGS permission not granted. Opened permission screen."
                }
                val pct = value.toIntOrNull()?.coerceIn(0, 100)
                    ?: return false to "Brightness must be an integer 0-100."
                val raw = ((pct / 100.0) * 255).toInt().coerceIn(1, 255)
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
                true to "Set brightness to $pct%"
            }

            "wifi_panel", "internet_panel" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true to "Opened Internet/Wi-Fi quick panel"
            }

            "bluetooth_settings" -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true to "Opened Bluetooth settings"
            }

            "volume_panel" -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_VOLUME
                } else {
                    Settings.ACTION_SOUND_SETTINGS
                }
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true to "Opened Volume panel"
            }

            else -> false to "Unsupported setting '$setting'."
        }
    }

    fun getDeviceTelemetry(): Map<String, String> {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mediaVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        return mapOf(
            "battery_percent" to batteryPct.toString(),
            "media_volume_percent" to ((mediaVol * 100) / mediaMax).toString(),
            "ringer_mode" to ringer,
            "network" to network,
            "android_sdk" to Build.VERSION.SDK_INT.toString()
        )
    }
}
