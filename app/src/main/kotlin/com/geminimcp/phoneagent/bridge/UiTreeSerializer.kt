package com.geminimcp.phoneagent.bridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class UiNodeSnapshot(
    val id: Int,
    val cls: String,
    val text: String? = null,
    val desc: String? = null,
    val resId: String? = null,
    val bounds: List<Int>, // [left, top, right, bottom]
    val center: List<Int>, // [cx, cy]
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val focused: Boolean = false,
    val enabled: Boolean = true
)

@Serializable
data class UiTreeSnapshot(
    val packageName: String,
    val stateHash: String,
    val nodeCount: Int,
    val truncated: Boolean,
    val nodes: List<UiNodeSnapshot>
)

object UiTreeSerializer {

    private const val MAX_NODES = 110
    private const val MAX_TEXT_LEN = 120

    fun serialize(root: AccessibilityNodeInfo?, maxNodes: Int = MAX_NODES): UiTreeSnapshot {
        if (root == null) {
            return UiTreeSnapshot(
                packageName = "unknown",
                stateHash = "empty",
                nodeCount = 0,
                truncated = false,
                nodes = emptyList()
            )
        }

        val packageName = root.packageName?.toString() ?: "unknown"
        val collected = mutableListOf<UiNodeSnapshot>()
        var truncated = false
        val rect = Rect()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 32) return
            if (collected.size >= maxNodes) {
                truncated = true
                return
            }
            if (!node.isVisibleToUser) return

            node.getBoundsInScreen(rect)
            val width = rect.width()
            val height = rect.height()
            val hasValidBounds = width > 0 && height > 0

            val rawText = if (node.isPassword) {
                "[REDACTED_PASSWORD]"
            } else {
                node.text?.toString()?.trim()?.take(MAX_TEXT_LEN)?.takeIf { it.isNotEmpty() }
            }
            val rawDesc = node.contentDescription?.toString()?.trim()?.take(MAX_TEXT_LEN)
                ?.takeIf { it.isNotEmpty() }
            val shortResId = node.viewIdResourceName?.substringAfterLast('/')
            val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "View"

            val isInteractive = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isScrollable || node.isCheckable || node.isFocusable
            val hasSemanticContent = rawText != null || rawDesc != null

            if (hasValidBounds && (isInteractive || hasSemanticContent)) {
                val id = collected.size + 1
                collected.add(
                    UiNodeSnapshot(
                        id = id,
                        cls = shortClass,
                        text = rawText,
                        desc = rawDesc,
                        resId = shortResId,
                        bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
                        center = listOf(rect.centerX(), rect.centerY()),
                        clickable = node.isClickable || node.isLongClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        focused = node.isFocused,
                        enabled = node.isEnabled
                    )
                )
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        traverse(root, 0)

        val hashInput = buildString {
            append(packageName)
            for (n in collected) {
                append('|').append(n.cls).append(':').append(n.text).append(':').append(n.desc)
                    .append(':').append(n.bounds.joinToString(","))
            }
        }
        val stateHash = sha256Short(hashInput)

        return UiTreeSnapshot(
            packageName = packageName,
            stateHash = stateHash,
            nodeCount = collected.size,
            truncated = truncated,
            nodes = collected
        )
    }

    private fun sha256Short(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
