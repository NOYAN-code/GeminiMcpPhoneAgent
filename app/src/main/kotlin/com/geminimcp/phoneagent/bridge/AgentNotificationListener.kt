package com.geminimcp.phoneagent.bridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.serialization.Serializable

@Serializable
data class NotificationItem(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val clearable: Boolean,
    val actions: List<String>
)

class AgentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    fun listActiveNotifications(limit: Int = 25): List<NotificationItem> {
        val active: Array<StatusBarNotification> = activeNotifications ?: return emptyList()
        return active.take(limit).map { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val actions = sbn.notification.actions?.mapNotNull { it.title?.toString() } ?: emptyList()

            NotificationItem(
                key = sbn.key,
                packageName = sbn.packageName,
                title = title.take(120),
                text = text.take(300),
                postTime = sbn.postTime,
                clearable = sbn.isClearable,
                actions = actions
            )
        }
    }

    fun dismissNotification(key: String): Boolean {
        return try {
            cancelNotification(key)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun dismissAllClearable(): Boolean {
        return try {
            cancelAllNotifications()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun invokeNotificationAction(key: String, actionTitle: String): Boolean {
        val target = activeNotifications?.firstOrNull { it.key == key } ?: return false
        val action = target.notification.actions?.firstOrNull {
            it.title?.toString()?.equals(actionTitle, ignoreCase = true) == true
        } ?: return false

        return try {
            action.actionIntent.send()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: AgentNotificationListener? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
