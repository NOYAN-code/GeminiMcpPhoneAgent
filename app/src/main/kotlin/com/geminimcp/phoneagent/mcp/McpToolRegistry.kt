package com.geminimcp.phoneagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object McpToolRegistry {

    fun allTools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "get_device_state",
            description = "Returns active app package, screen resolution, battery, volume, ringer mode, network type, and UI state hash.",
            inputSchema = emptyObjectSchema()
        ),
        McpToolDefinition(
            name = "read_ui_tree",
            description = "Captures the live pruned Android Accessibility UI hierarchy with node IDs, labels, bounds, centers, and interactive flags. Passwords are automatically redacted.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "max_nodes" to prop("integer", "Maximum visible nodes to return (20-150, default 110).")
                )
            )
        ),
        McpToolDefinition(
            name = "capture_screen",
            description = "Captures a downscaled JPEG screenshot of the current screen for multimodal vision fallback when UI nodes are missing, unlabelled, or rendered inside WebViews/Canvas.",
            inputSchema = emptyObjectSchema()
        ),
        McpToolDefinition(
            name = "tap_node",
            description = "Taps a UI element by node_id from read_ui_tree, or by matching visible text or content_desc.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "node_id" to prop("integer", "Node ID from the latest read_ui_tree snapshot."),
                    "text" to prop("string", "Visible text label to match if node_id is omitted."),
                    "content_desc" to prop("string", "Accessibility content description to match.")
                )
            )
        ),
        McpToolDefinition(
            name = "tap_xy",
            description = "Taps exact pixel coordinates (x, y) on screen. Useful after visual inspection with capture_screen.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "x" to prop("integer", "Horizontal screen pixel coordinate."),
                    "y" to prop("integer", "Vertical screen pixel coordinate.")
                ),
                required = listOf("x", "y")
            )
        ),
        McpToolDefinition(
            name = "long_press",
            description = "Long-presses a node (by node_id or text) or exact coordinates (x, y).",
            inputSchema = objectSchema(
                properties = mapOf(
                    "node_id" to prop("integer", "Optional node ID from read_ui_tree."),
                    "text" to prop("string", "Optional visible text to match."),
                    "x" to prop("integer", "Optional X coordinate."),
                    "y" to prop("integer", "Optional Y coordinate."),
                    "duration_ms" to prop("integer", "Press duration in ms (default 800).")
                )
            )
        ),
        McpToolDefinition(
            name = "swipe",
            description = "Performs a swipe gesture either by direction (up, down, left, right) or explicit start/end pixel coordinates.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "direction" to prop("string", "Direction: up, down, left, or right."),
                    "start_x" to prop("integer", "Optional start X coordinate."),
                    "start_y" to prop("integer", "Optional start Y coordinate."),
                    "end_x" to prop("integer", "Optional end X coordinate."),
                    "end_y" to prop("integer", "Optional end Y coordinate."),
                    "duration_ms" to prop("integer", "Swipe duration in ms (default 320).")
                )
            )
        ),
        McpToolDefinition(
            name = "scroll",
            description = "Scrolls the primary scrollable container on screen (with physical swipe fallback).",
            inputSchema = objectSchema(
                properties = mapOf(
                    "direction" to prop("string", "Scroll direction: down, up, left, or right (default down).")
                )
            )
        ),
        McpToolDefinition(
            name = "type_text",
            description = "Focuses an editable field (by node_id, text/hint, or currently focused input) and enters text via ACTION_SET_TEXT with clipboard fallback.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "text" to prop("string", "Text to input."),
                    "node_id" to prop("integer", "Optional editable node ID to focus first."),
                    "clear_existing" to prop("boolean", "If true, replaces existing field content."),
                    "press_enter" to prop("boolean", "If true, sends IME Enter/Search action after typing.")
                ),
                required = listOf("text")
            )
        ),
        McpToolDefinition(
            name = "press_key",
            description = "Executes an Android system navigation or global key action.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "key" to prop(
                        "string",
                        "One of: back, home, recents, notifications, quick_settings, power_dialog, lock_screen."
                    )
                ),
                required = listOf("key")
            )
        ),
        McpToolDefinition(
            name = "launch_app",
            description = "Launches an installed Android application by human-readable app name (fuzzy matched) or exact package name.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "app_name" to prop("string", "Human-readable app name, e.g. 'WhatsApp', 'YouTube', 'Settings'."),
                    "package_name" to prop("string", "Optional exact Android package name.")
                )
            )
        ),
        McpToolDefinition(
            name = "list_installed_apps",
            description = "Lists launchable installed Android apps and their package names, optionally filtered by query.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "query" to prop("string", "Optional substring filter on app name or package.")
                )
            )
        ),
        McpToolDefinition(
            name = "open_url",
            description = "Opens an HTTP/HTTPS URL or Android deep-link URI using ACTION_VIEW.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "url" to prop("string", "Full URL or URI scheme to open.")
                ),
                required = listOf("url")
            )
        ),
        McpToolDefinition(
            name = "get_notifications",
            description = "Lists active status bar notifications including package, title, message text, key, and available action buttons.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "limit" to prop("integer", "Maximum notifications to return (default 20).")
                )
            )
        ),
        McpToolDefinition(
            name = "manage_notification",
            description = "Dismisses a notification, dismisses all clearable notifications, or invokes a notification action button.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "operation" to prop("string", "One of: dismiss, dismiss_all, invoke_action."),
                    "key" to prop("string", "Notification key from get_notifications (required for dismiss/invoke_action)."),
                    "action_title" to prop("string", "Exact action button title (required for invoke_action).")
                ),
                required = listOf("operation")
            )
        ),
        McpToolDefinition(
            name = "control_system_setting",
            description = "Controls device audio volume, ringer mode, flashlight, screen brightness, or opens quick settings panels.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "setting" to prop(
                        "string",
                        "One of: volume_media, volume_ring, volume_alarm, volume_call, ringer_mode, flashlight, brightness, wifi_panel, bluetooth_settings, volume_panel."
                    ),
                    "value" to prop(
                        "string",
                        "Setting value: 0-100 for volume/brightness; normal/vibrate/silent for ringer_mode; on/off for flashlight."
                    )
                ),
                required = listOf("setting")
            )
        ),
        McpToolDefinition(
            name = "wait_for_ui",
            description = "Composite tool: waits up to timeout_ms until specified text or package name appears on screen.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "text" to prop("string", "Substring to wait for in UI text or content description."),
                    "package_name" to prop("string", "Optional package name substring to wait for."),
                    "timeout_ms" to prop("integer", "Max wait time in ms (500-15000, default 5000).")
                )
            )
        ),
        McpToolDefinition(
            name = "find_and_tap",
            description = "Composite tool: searches the current screen for matching text/description, scrolling down up to max_scrolls times if needed, then taps it.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "text" to prop("string", "Visible text or description to find and tap."),
                    "max_scrolls" to prop("integer", "Maximum scroll attempts (0-6, default 3).")
                ),
                required = listOf("text")
            )
        )
    )

    fun allResources(): List<McpResourceDefinition> = listOf(
        McpResourceDefinition(
            uri = "phone://device/state",
            name = "Live Device State",
            description = "Active app, screen dimensions, battery, audio, and connectivity."
        ),
        McpResourceDefinition(
            uri = "phone://ui/tree",
            name = "Live Accessibility UI Tree",
            description = "Current pruned semantic UI hierarchy with password redaction."
        ),
        McpResourceDefinition(
            uri = "phone://notifications",
            name = "Active Notifications",
            description = "Current status bar notifications."
        )
    )

    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    private fun objectSchema(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList()
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { (k, v) -> put(k, v) }
        })
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }
}
