package com.geminimcp.phoneagent.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PhoneNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: PhoneNotificationListenerService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun listNotifications(limit: Int = 20): JsonArray {
        val active: Array<StatusBarNotification> = try {
            activeNotifications ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        return buildJsonArray {
            active.take(limit.coerceIn(1, 50)).forEach { sbn ->
                val extras = sbn.notification?.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                val actions = sbn.notification?.actions?.mapNotNull { it.title?.toString() } ?: emptyList()

                add(buildJsonObject {
                    put("key", sbn.key)
                    put("packageName", sbn.packageName)
                    put("postTime", sbn.postTime)
                    if (title.isNotBlank()) put("title", title)
                    if (text.isNotBlank()) put("text", text)
                    if (bigText.isNotBlank() && bigText != text) put("bigText", bigText)
                    if (actions.isNotEmpty()) {
                        put("actions", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
                    }
                })
            }
        }
    }

    fun manageNotification(operation: String, key: String?, actionTitle: String?): Pair<Boolean, String> {
        return when (operation.lowercase()) {
            "dismiss" -> {
                if (key.isNullOrBlank()) return false to "Missing notification key."
                cancelNotification(key)
                true to "Dismissed notification."
            }
            "dismiss_all" -> {
                cancelAllNotifications()
                true to "Cleared all clearable notifications."
            }
            "action" -> {
                if (key.isNullOrBlank() || actionTitle.isNullOrBlank()) {
                    return false to "Missing notification key or action_title."
                }
                val target = activeNotifications?.firstOrNull { it.key == key }
                    ?: return false to "Notification not found."
                val action = target.notification?.actions?.firstOrNull {
                    it.title?.toString()?.equals(actionTitle, ignoreCase = true) == true
                } ?: return false to "Action '$actionTitle' not found."
                try {
                    action.actionIntent.send()
                    true to "Triggered notification action '$actionTitle'."
                } catch (e: Exception) {
                    false to "Failed to trigger action: ${e.message}"
                }
            }
            else -> false to "Unknown operation '$operation'. Use dismiss, dismiss_all, or action."
        }
    }
}
