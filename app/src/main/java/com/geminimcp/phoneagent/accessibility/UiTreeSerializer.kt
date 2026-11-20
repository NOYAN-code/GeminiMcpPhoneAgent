package com.geminimcp.phoneagent.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

data class IndexedUiNode(
    val id: Int,
    val cls: String,
    val text: String,
    val desc: String,
    val resId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean
)

data class UiSnapshot(
    val packageName: String,
    val stateHash: String,
    val nodes: List<IndexedUiNode>,
    val truncated: Boolean
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("packageName", packageName)
        put("stateHash", stateHash)
        put("nodeCount", nodes.size)
        put("truncated", truncated)
        put("nodes", buildJsonArray {
            nodes.forEach { n ->
                add(buildJsonObject {
                    put("id", n.id)
                    if (n.cls.isNotEmpty()) put("cls", n.cls)
                    if (n.text.isNotEmpty()) put("text", n.text)
                    if (n.desc.isNotEmpty()) put("desc", n.desc)
                    if (n.resId.isNotEmpty()) put("resId", n.resId)
                    put("bounds", buildJsonArray {
                        add(JsonPrimitive(n.bounds.left))
                        add(JsonPrimitive(n.bounds.top))
                        add(JsonPrimitive(n.bounds.right))
                        add(JsonPrimitive(n.bounds.bottom))
                    })
                    put("center", buildJsonArray {
                        add(JsonPrimitive(n.bounds.centerX()))
                        add(JsonPrimitive(n.bounds.centerY()))
                    })
                    if (n.clickable) put("clickable", true)
                    if (n.editable) put("editable", true)
                    if (n.scrollable) put("scrollable", true)
                    if (n.checked != null) put("checked", n.checked)
                    if (n.focused) put("focused", true)
                })
            }
        })
    }
}

object UiTreeSerializer {

    fun capture(root: AccessibilityNodeInfo?, maxNodes: Int = 120): UiSnapshot {
        if (root == null) {
            return UiSnapshot("unknown", "empty", emptyList(), false)
        }
        val pkg = root.packageName?.toString() ?: "unknown"
        val collected = ArrayList<IndexedUiNode>(minOf(maxNodes, 128))
        var truncated = false
        val limit = maxNodes.coerceIn(20, 250)

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28) return
            if (collected.size >= limit) {
                truncated = true
                return
            }

            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 0 && rect.height() > 0

                val isPassword = node.isPassword
                val rawText = if (isPassword) "••••" else (node.text?.toString()?.trim() ?: "")
                val rawDesc = if (isPassword) "" else (node.contentDescription?.toString()?.trim() ?: "")
                val shortCls = node.className?.toString()?.substringAfterLast('.') ?: ""
                val shortRes = node.viewIdResourceName?.substringAfterLast('/') ?: ""

                val interesting = validBounds && (
                    rawText.isNotEmpty() ||
                    rawDesc.isNotEmpty() ||
                    node.isClickable ||
                    node.isEditable ||
                    node.isScrollable ||
                    node.isCheckable
                )

                if (interesting) {
                    collected.add(
                        IndexedUiNode(
                            id = collected.size + 1,
                            cls = shortCls,
                            text = rawText.take(140),
                            desc = rawDesc.take(140),
                            resId = shortRes,
                            bounds = rect,
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            checked = if (node.isCheckable) node.isChecked else null,
                            focused = node.isFocused
                        )
                    )
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                if (collected.size >= limit) {
                    truncated = true
                    break
                }
                val child = node.getChild(i)
                if (child != null) {
                    traverse(child, depth + 1)
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }

        traverse(root, 0)
        val hash = computeHash(pkg, collected)
        return UiSnapshot(pkg, hash, collected, truncated)
    }

    private fun computeHash(pkg: String, nodes: List<IndexedUiNode>): String {
        val sb = StringBuilder(pkg.length + nodes.size * 24)
        sb.append(pkg).append('|')
        for (n in nodes) {
            sb.append(n.text).append(':').append(n.desc).append(':').append(n.bounds.centerX()).append(',').append(n.bounds.centerY()).append(';')
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
