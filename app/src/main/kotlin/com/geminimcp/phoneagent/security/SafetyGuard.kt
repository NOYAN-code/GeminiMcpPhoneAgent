package com.geminimcp.phoneagent.security

import com.geminimcp.phoneagent.config.AgentConfigStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

enum class RiskLevel {
    SAFE,
    SENSITIVE_CONFIRMATION_REQUIRED,
    BLOCKED
}

data class SafetyAssessment(
    val riskLevel: RiskLevel,
    val reason: String,
    val spokenPrompt: String = ""
)

class SafetyGuard(private val configStore: AgentConfigStore) {

    private val sensitiveActionKeywords = listOf(
        "send", "pay", "buy", "purchase", "checkout", "place order", "confirm order",
        "transfer", "wire", "delete", "remove", "erase", "uninstall", "factory reset",
        "format", "call", "dial", "post", "publish", "submit", "authorize", "approve",
        "grant", "allow", "sign out", "log out", "clear data", "wipe"
    )

    private val paymentOrMessagingPackages = listOf(
        "whatsapp", "telegram", "messaging", "mms", "sms", "gmail", "mail",
        "paypal", "venmo", "cashapp", "bank", "wallet", "pay", "amazon", "uber"
    )

    fun isTokenValid(providedBearerToken: String?): Boolean {
        if (providedBearerToken.isNullOrBlank()) return false
        val expected = configStore.getSettings().mcpAuthToken
        return MessageDigest.isEqual(
            providedBearerToken.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    fun isPackageBlocked(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return configStore.getSettings().blockedPackages.contains(packageName)
    }

    fun assessToolCall(
        toolName: String,
        args: JsonObject,
        activePackage: String?,
        targetNodeLabel: String? = null
    ): SafetyAssessment {
        val settings = configStore.getSettings()

        if (isPackageBlocked(activePackage)) {
            return SafetyAssessment(
                riskLevel = RiskLevel.BLOCKED,
                reason = "Active app '$activePackage' is in the local privacy denylist. Automation is blocked."
            )
        }

        if (toolName == "launch_app") {
            val requestedPkg = args["package_name"]?.jsonPrimitive?.contentOrNull
            if (isPackageBlocked(requestedPkg)) {
                return SafetyAssessment(
                    riskLevel = RiskLevel.BLOCKED,
                    reason = "Requested app '$requestedPkg' is blocked by your privacy denylist."
                )
            }
        }

        if (!settings.requireVoiceConfirmation) {
            return SafetyAssessment(RiskLevel.SAFE, "Voice confirmation disabled by user.")
        }

        val combinedTargetText = buildString {
            append(targetNodeLabel.orEmpty())
            append(" ")
            append(args["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            append(" ")
            append(args["content_desc"]?.jsonPrimitive?.contentOrNull.orEmpty())
            append(" ")
            append(args["action_title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            append(" ")
            append(args["setting"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.lowercase()

        // High-risk direct tools
        if (toolName == "manage_notification") {
            val operation = args["operation"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            if (operation == "dismiss_all" || operation == "invoke_action") {
                val label = args["action_title"]?.jsonPrimitive?.contentOrNull ?: operation
                return SafetyAssessment(
                    riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                    reason = "Notification operation '$label' modifies or replies to notifications.",
                    spokenPrompt = "I'm about to execute notification action $label. Say confirm to proceed, or cancel to stop."
                )
            }
        }

        if (toolName == "open_url") {
            val url = args["url"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.contains("pay")) {
                return SafetyAssessment(
                    riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                    reason = "Opening sensitive URI scheme: $url",
                    spokenPrompt = "I'm about to open $url. Say confirm to proceed, or cancel to stop."
                )
            }
        }

        // Sensitive UI tap / find_and_tap / long_press / type_text+enter
        if (toolName in setOf("tap_node", "find_and_tap", "tap_xy", "long_press", "type_text")) {
            val matchedKeyword = sensitiveActionKeywords.firstOrNull { kw ->
                Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(combinedTargetText)
            }
            val pressEnterInSensitiveApp = toolName == "type_text" &&
                (args["press_enter"]?.jsonPrimitive?.contentOrNull == "true") &&
                paymentOrMessagingPackages.any { activePackage?.lowercase()?.contains(it) == true }

            if (matchedKeyword != null || pressEnterInSensitiveApp) {
                val appShort = activePackage?.substringAfterLast('.') ?: "the current app"
                val readableTarget = targetNodeLabel?.takeIf { it.isNotBlank() }
                    ?: args["text"]?.jsonPrimitive?.contentOrNull
                    ?: matchedKeyword
                    ?: "this action"
                return SafetyAssessment(
                    riskLevel = RiskLevel.SENSITIVE_CONFIRMATION_REQUIRED,
                    reason = "Detected sensitive or irreversible UI action ('$readableTarget') in $appShort.",
                    spokenPrompt = "Hold on. I'm about to trigger $readableTarget in $appShort. Say confirm to proceed, or cancel to abort."
                )
            }
        }

        return SafetyAssessment(RiskLevel.SAFE, "Permitted local action.")
    }
}
