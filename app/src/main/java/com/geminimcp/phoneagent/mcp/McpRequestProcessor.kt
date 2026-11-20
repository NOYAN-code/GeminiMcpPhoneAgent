package com.geminimcp.phoneagent.mcp

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class McpRequestProcessor(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val executor = PhoneToolExecutor(context)

    suspend fun handleJsonRpc(rawBody: String): Pair<Int, String> {
        val rpc = try {
            json.parseToJsonElement(rawBody).jsonObject
        } catch (e: Exception) {
            return 400 to errorResponse(JsonNull, -32700, "Parse error: ${e.message}")
        }

        val id: JsonElement = rpc["id"] ?: JsonNull
        val method = rpc["method"]?.jsonPrimitive?.contentOrNull ?: ""
        val params = rpc["params"]?.jsonObject ?: buildJsonObject {}

        if (method.startsWith("notifications/")) {
            return 202 to ""
        }

        return when (method) {
            "initialize" -> {
                val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: "2025-06-18"
                200 to resultResponse(id, buildJsonObject {
                    put("protocolVersion", requested)
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", false) })
                        put("resources", buildJsonObject { put("listChanged", false) })
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", "gemini-android-mcp-server")
                        put("version", "2.0.0")
                    })
                })
            }
            "ping" -> 200 to resultResponse(id, buildJsonObject {})
            "tools/list" -> 200 to resultResponse(id, buildJsonObject {
                put("tools", buildToolDefinitions())
            })
            "tools/call" -> {
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                    ?: return 200 to errorResponse(id, -32602, "Missing tool name.")
                val toolArgs = params["arguments"]?.jsonObject ?: buildJsonObject {}
                val result = executor.execute(toolName, toolArgs)
                val content = buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", result.textJson)
                    })
                    if (result.imageBase64 != null && result.imageMimeType != null) {
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", result.imageBase64)
                            put("mimeType", result.imageMimeType)
                        })
                    }
                }
                200 to resultResponse(id, buildJsonObject {
                    put("content", content)
                    put("isError", !result.ok)
                })
            }
            "resources/list" -> 200 to resultResponse(id, buildJsonObject {
                put("resources", buildJsonArray {
                    add(resourceItem("phone://device/state", "Live Device State", "Active app, screen, battery, audio, network."))
                    add(resourceItem("phone://ui/tree", "Live UI Tree", "Current pruned Accessibility hierarchy."))
                    add(resourceItem("phone://notifications", "Active Notifications", "Current status bar notifications."))
                })
            })
            "resources/read" -> {
                val uri = params["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                val res = when (uri) {
                    "phone://device/state" -> executor.execute("get_device_state", buildJsonObject {})
                    "phone://ui/tree" -> executor.execute("read_ui_tree", buildJsonObject {})
                    "phone://notifications" -> executor.execute("get_notifications", buildJsonObject {})
                    else -> return 200 to errorResponse(id, -32602, "Unknown resource URI: $uri")
                }
                200 to resultResponse(id, buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("uri", uri)
                            put("mimeType", "application/json")
                            put("text", res.textJson)
                        })
                    })
                })
            }
            else -> 200 to errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun resultResponse(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun errorResponse(id: JsonElement, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    private fun resourceItem(uri: String, name: String, desc: String): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("description", desc)
        put("mimeType", "application/json")
    }

    private fun toolDef(name: String, desc: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", desc)
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        })
    }

    private fun buildToolDefinitions() = buildJsonArray {
        add(toolDef("run_actions", "Executes multiple tools sequentially on-device in ONE round-trip."))
        add(toolDef("set_alarm", "Sets one or multiple alarms directly via OS AlarmClock Intent."))
        add(toolDef("set_timer", "Starts a countdown timer directly via OS Intent."))
        add(toolDef("open_url", "Opens any URL or deep link directly."))
        add(toolDef("search_web", "Launches Google, YouTube, Maps, or Play Store search directly."))
        add(toolDef("send_intent", "Fires any Android Intent directly."))
        add(toolDef("media_control", "Controls media playback directly (play_pause, next, previous, volume)."))
        add(toolDef("quick_communicate", "Opens dialer, SMS, or WhatsApp chat directly."))
        add(toolDef("clipboard", "Reads or writes Android clipboard."))
        add(toolDef("get_device_state", "Returns active app, screen size, battery, volume, and network."))
        add(toolDef("read_ui_tree", "Captures pruned Android Accessibility UI tree."))
        add(toolDef("capture_screen", "Captures downscaled JPEG screenshot for vision fallback."))
        add(toolDef("tap_node", "Taps UI element by node_id, text, or content_desc."))
        add(toolDef("tap_xy", "Taps exact pixel coordinates (x, y)."))
        add(toolDef("long_press", "Long-presses a node or coordinates."))
        add(toolDef("swipe", "Performs a swipe gesture."))
        add(toolDef("scroll", "Scrolls screen (down, up, left, right)."))
        add(toolDef("type_text", "Types text into input field."))
        add(toolDef("press_key", "Executes system key (back, home, recents, notifications, quick_settings, lock_screen)."))
        add(toolDef("launch_app", "Launches installed Android app by name or package."))
        add(toolDef("list_installed_apps", "Lists launchable installed Android apps."))
        add(toolDef("get_notifications", "Lists active notifications."))
        add(toolDef("manage_notification", "Dismisses or invokes action on a notification."))
        add(toolDef("control_system_setting", "Controls volume, ringer, flashlight, or settings panels."))
        add(toolDef("wait_for_ui", "Waits for text or package to appear on screen."))
        add(toolDef("find_and_tap", "Scrolls to find matching text and taps it."))
    }
}
