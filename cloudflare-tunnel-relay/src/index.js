import { DurableObject } from "cloudflare:workers";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, Accept, Mcp-Session-Id, MCP-Protocol-Version",
  "Access-Control-Expose-Headers": "Mcp-Session-Id"
};

const PHONE_TOOLS = [
  {
    name: "run_actions",
    description: "FAST BATCH PIPELINE: Runs multiple phone tools sequentially on-device in ONE single MCP round-trip (eliminating network latency). Prefer this whenever performing 2+ steps (e.g. launch app -> wait -> tap -> type). Supported tools include any tool in this server.",
    inputSchema: {
      type: "object",
      properties: {
        actions: {
          type: "array",
          description: "Ordered list of actions to execute on-device.",
          items: {
            type: "object",
            properties: {
              tool: { type: "string", description: "Tool name (e.g. launch_app, wait_for_ui, tap_node, type_text, open_url, set_alarm, media_control, press_key, scroll, sleep)." },
              args: { type: "object", description: "Arguments object for that tool." }
            },
            required: ["tool"]
          }
        },
        delay_ms: { type: "integer", description: "Optional pause in ms between actions (default 120ms)." },
        stop_on_error: { type: "boolean", description: "Stop pipeline if an action fails (default true)." },
        return_final_ui: { type: "boolean", description: "Include compact live UI tree after the last action (default false)." }
      },
      required: ["actions"]
    }
  },
  {
    name: "set_alarm",
    description: "FAST DIRECT COMMAND: Sets one or multiple alarms instantly via Android OS AlarmClock Intent (zero UI clicking). Pass either a single hour/minute or an 'alarms' array to schedule many alarms in one call.",
    inputSchema: {
      type: "object",
      properties: {
        hour: { type: "integer", description: "24-hour format (0-23) for a single alarm." },
        minute: { type: "integer", description: "Minute (0-59) for a single alarm." },
        message: { type: "string", description: "Optional alarm label." },
        days: { type: "array", items: { type: "integer" }, description: "Optional Calendar day numbers (1=Sun..7=Sat)." },
        skip_ui: { type: "boolean", description: "Skip clock UI for instant silent creation (default true)." },
        alarms: {
          type: "array",
          description: "Batch list of alarms to create in one call.",
          items: {
            type: "object",
            properties: {
              hour: { type: "integer" },
              minute: { type: "integer" },
              message: { type: "string" },
              days: { type: "array", items: { type: "integer" } },
              skip_ui: { type: "boolean" }
            },
            required: ["hour", "minute"]
          }
        }
      }
    }
  },
  {
    name: "set_timer",
    description: "FAST DIRECT COMMAND: Starts a countdown timer instantly via Android OS Intent.",
    inputSchema: {
      type: "object",
      properties: {
        seconds: { type: "integer", description: "Duration in seconds." },
        message: { type: "string", description: "Optional timer label." },
        skip_ui: { type: "boolean", description: "Start immediately without UI confirmation (default true)." }
      },
      required: ["seconds"]
    }
  },
  {
    name: "open_url",
    description: "FAST DIRECT COMMAND: Opens any URL, deep link, YouTube video, WhatsApp link, tel:, mailto:, or app URI directly.",
    inputSchema: {
      type: "object",
      properties: {
        url: { type: "string", description: "Full URL or deep link URI." },
        package_name: { type: "string", description: "Optional target package name to force opening inside a specific app." }
      },
      required: ["url"]
    }
  },
  {
    name: "search_web",
    description: "FAST DIRECT COMMAND: Instantly launches a search in Google, YouTube, Google Maps, or Play Store in 1 step (no app navigation or typing needed).",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Search query text." },
        engine: { type: "string", description: "Target: google, youtube, maps, or play_store (default google)." }
      },
      required: ["query"]
    }
  },
  {
    name: "send_intent",
    description: "FAST DIRECT COMMAND: Fires any Android OS Intent directly (action, data_uri, package_name, mime_type, extras) for instant system/app commands.",
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", description: "Android Intent action (e.g. android.intent.action.VIEW, android.settings.WIFI_SETTINGS)." },
        data_uri: { type: "string", description: "Optional URI data string." },
        package_name: { type: "string", description: "Optional target app package name." },
        mime_type: { type: "string", description: "Optional MIME type." },
        extras: { type: "object", description: "Optional key-value map of string, integer, or boolean Intent extras." }
      },
      required: ["action"]
    }
  },
  {
    name: "media_control",
    description: "FAST DIRECT COMMAND: Instantly controls audio/video playback without opening any app (play_pause, play, pause, next, previous, stop, volume_up, volume_down, mute).",
    inputSchema: {
      type: "object",
      properties: {
        command: { type: "string", description: "play_pause, play, pause, next, previous, stop, volume_up, volume_down, or mute." }
      },
      required: ["command"]
    }
  },
  {
    name: "quick_communicate",
    description: "FAST DIRECT COMMAND: Opens phone dialer, pre-filled SMS composer, or direct WhatsApp chat in 1 step.",
    inputSchema: {
      type: "object",
      properties: {
        type: { type: "string", description: "dial, sms, or whatsapp." },
        phone: { type: "string", description: "Phone number (include country code for whatsapp, e.g. 2010xxxxxxxx)." },
        message: { type: "string", description: "Optional pre-filled message for sms or whatsapp." }
      },
      required: ["type", "phone"]
    }
  },
  {
    name: "clipboard",
    description: "FAST DIRECT COMMAND: Reads or writes the Android system clipboard.",
    inputSchema: {
      type: "object",
      properties: {
        operation: { type: "string", description: "set or get." },
        text: { type: "string", description: "Text to copy when operation is set." }
      },
      required: ["operation"]
    }
  },
  {
    name: "get_device_state",
    description: "Returns active app, screen size, battery, volume, ringer mode, network, and UI hash.",
    inputSchema: { type: "object", properties: {} }
  },
  {
    name: "read_ui_tree",
    description: "Captures the live pruned Android Accessibility UI hierarchy with node IDs, text, and bounds.",
    inputSchema: { type: "object", properties: { max_nodes: { type: "integer" } } }
  },
  {
    name: "capture_screen",
    description: "Captures a downscaled JPEG screenshot of the current phone screen for vision fallback.",
    inputSchema: { type: "object", properties: {} }
  },
  {
    name: "tap_node",
    description: "Taps a UI element by node_id, visible text, or content_desc. Set include_ui=true to get the updated UI tree immediately without a second call.",
    inputSchema: {
      type: "object",
      properties: {
        node_id: { type: "integer" },
        text: { type: "string" },
        content_desc: { type: "string" },
        include_ui: { type: "boolean" }
      }
    }
  },
  {
    name: "tap_xy",
    description: "Taps exact pixel coordinates (x, y) on screen. Set include_ui=true to return updated UI tree immediately.",
    inputSchema: {
      type: "object",
      properties: {
        x: { type: "integer" },
        y: { type: "integer" },
        include_ui: { type: "boolean" }
      },
      required: ["x", "y"]
    }
  },
  {
    name: "long_press",
    description: "Long-presses a node or coordinates (x, y).",
    inputSchema: {
      type: "object",
      properties: {
        node_id: { type: "integer" },
        text: { type: "string" },
        x: { type: "integer" },
        y: { type: "integer" },
        duration_ms: { type: "integer" }
      }
    }
  },
  {
    name: "swipe",
    description: "Performs a swipe gesture by direction (up, down, left, right) or coordinates.",
    inputSchema: {
      type: "object",
      properties: {
        direction: { type: "string" },
        start_x: { type: "integer" },
        start_y: { type: "integer" },
        end_x: { type: "integer" },
        end_y: { type: "integer" },
        duration_ms: { type: "integer" }
      }
    }
  },
  {
    name: "scroll",
    description: "Scrolls the screen (down, up, left, right). Set include_ui=true to get updated UI tree immediately.",
    inputSchema: {
      type: "object",
      properties: {
        direction: { type: "string" },
        include_ui: { type: "boolean" }
      }
    }
  },
  {
    name: "type_text",
    description: "Focuses an input field and types text. Set include_ui=true to return updated UI tree immediately.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string" },
        node_id: { type: "integer" },
        clear_existing: { type: "boolean" },
        press_enter: { type: "boolean" },
        include_ui: { type: "boolean" }
      },
      required: ["text"]
    }
  },
  {
    name: "press_key",
    description: "Executes system key: back, home, recents, notifications, quick_settings, lock_screen, power_dialog.",
    inputSchema: {
      type: "object",
      properties: {
        key: { type: "string" }
      },
      required: ["key"]
    }
  },
  {
    name: "launch_app",
    description: "Launches an installed Android app by name or package name. Set include_ui=true to return the app's UI tree immediately.",
    inputSchema: {
      type: "object",
      properties: {
        app_name: { type: "string" },
        package_name: { type: "string" },
        include_ui: { type: "boolean" }
      }
    }
  },
  {
    name: "list_installed_apps",
    description: "Lists launchable installed Android apps.",
    inputSchema: { type: "object", properties: { query: { type: "string" } } }
  },
  {
    name: "get_notifications",
    description: "Lists active status bar notifications on the phone.",
    inputSchema: { type: "object", properties: { limit: { type: "integer" } } }
  },
  {
    name: "manage_notification",
    description: "Dismisses a notification or invokes a notification action.",
    inputSchema: {
      type: "object",
      properties: {
        operation: { type: "string" },
        key: { type: "string" },
        action_title: { type: "string" }
      },
      required: ["operation"]
    }
  },
  {
    name: "control_system_setting",
    description: "Controls volume_media, volume_ring, volume_alarm, ringer_mode, flashlight, brightness, wifi_settings, bluetooth_settings, or settings panels.",
    inputSchema: {
      type: "object",
      properties: {
        setting: { type: "string" },
        value: { type: "string" }
      },
      required: ["setting"]
    }
  },
  {
    name: "wait_for_ui",
    description: "Waits until specified text or app package appears on screen.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string" },
        package_name: { type: "string" },
        timeout_ms: { type: "integer" }
      }
    }
  },
  {
    name: "find_and_tap",
    description: "Scrolls to find matching text on screen and taps it.",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string" },
        max_scrolls: { type: "integer" },
        include_ui: { type: "boolean" }
      },
      required: ["text"]
    }
  }
];

const PHONE_RESOURCES = [
  { uri: "phone://device/state", name: "Live Device State", description: "Active app, screen dimensions, battery, audio, and network.", mimeType: "application/json" },
  { uri: "phone://ui/tree", name: "Live Accessibility UI Tree", description: "Current pruned UI hierarchy.", mimeType: "application/json" },
  { uri: "phone://notifications", name: "Active Notifications", description: "Current status bar notifications.", mimeType: "application/json" }
];

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return new Response(
        JSON.stringify({ status: "ok", service: "gemini-phone-mcp-relay", version: "2.0.0", tools: PHONE_TOOLS.length }),
        { status: 200, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
      );
    }

    let token = url.searchParams.get("token");
    if (!token && url.pathname.startsWith("/mcp/")) {
      token = url.pathname.slice("/mcp/".length).split("/")[0];
    }
    if (!token) {
      const auth = request.headers.get("Authorization") || "";
      if (auth.startsWith("Bearer ")) token = auth.slice(7).trim();
    }

    if (!token || token.length < 16) {
      return new Response(
        JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32001, message: "Unauthorized" } }),
        { status: 401, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
      );
    }

    const id = env.PHONE_TUNNEL.idFromName(token);
    return env.PHONE_TUNNEL.get(id).fetch(request);
  }
};

export class PhoneTunnelRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pendingRequests = new Map();
    this.sessionId = crypto.randomUUID();
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/ws") {
      if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
      }
      for (const existing of this.ctx.getWebSockets("phone")) {
        try { existing.close(1000, "Replaced"); } catch (_) {}
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, ["phone"]);
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === "/mcp" || url.pathname.startsWith("/mcp/")) {
      if (request.method === "GET") {
        return new Response("Method Not Allowed", { status: 405, headers: { ...CORS_HEADERS, Allow: "POST, DELETE, OPTIONS" } });
      }
      if (request.method === "DELETE") {
        return new Response(null, { status: 200, headers: CORS_HEADERS });
      }
      if (request.method !== "POST") {
        return new Response("Method Not Allowed", { status: 405, headers: CORS_HEADERS });
      }

      const rawBody = await request.text();
      let rpc;
      try {
        rpc = JSON.parse(rawBody);
      } catch (e) {
        return this.jsonRpc({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
      }

      if (rpc.method && rpc.method.startsWith("notifications/")) {
        return new Response(null, { status: 202, headers: { ...CORS_HEADERS, "Mcp-Session-Id": this.sessionId } });
      }
      if (rpc.method === "initialize") {
        const requested = rpc.params?.protocolVersion || "2025-06-18";
        return this.jsonRpc({
          jsonrpc: "2.0",
          id: rpc.id ?? null,
          result: {
            protocolVersion: requested,
            capabilities: { tools: { listChanged: false }, resources: { listChanged: false } },
            serverInfo: { name: "gemini-android-mcp-server", version: "2.0.0" }
          }
        });
      }
      if (rpc.method === "ping") {
        return this.jsonRpc({ jsonrpc: "2.0", id: rpc.id ?? null, result: {} });
      }
      if (rpc.method === "tools/list") {
        return this.jsonRpc({ jsonrpc: "2.0", id: rpc.id ?? null, result: { tools: PHONE_TOOLS } });
      }
      if (rpc.method === "resources/list") {
        return this.jsonRpc({ jsonrpc: "2.0", id: rpc.id ?? null, result: { resources: PHONE_RESOURCES } });
      }

      const sockets = this.ctx.getWebSockets("phone");
      if (!sockets || sockets.length === 0) {
        return this.jsonRpc({
          jsonrpc: "2.0",
          id: rpc.id ?? null,
          result: {
            content: [{ type: "text", text: JSON.stringify({ ok: false, message: "Phone offline: open Gemini MCP Agent on your Android phone and tap Start." }) }],
            isError: true
          }
        });
      }

      const phoneSocket = sockets[sockets.length - 1];
      const reqId = crypto.randomUUID();
      const responsePromise = new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pendingRequests.delete(reqId);
          reject(new Error("Phone timed out after 30s."));
        }, 30_000);
        this.pendingRequests.set(reqId, {
          resolve: (p) => { clearTimeout(timer); resolve(p); },
          reject: (e) => { clearTimeout(timer); reject(e); }
        });
      });

      try {
        phoneSocket.send(JSON.stringify({ type: "mcp_request", reqId, body: rawBody }));
        const result = await responsePromise;
        return new Response(result.body ?? "", {
          status: result.status || 200,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json", "Mcp-Session-Id": this.sessionId }
        });
      } catch (err) {
        this.pendingRequests.delete(reqId);
        return this.jsonRpc({
          jsonrpc: "2.0",
          id: rpc.id ?? null,
          result: { content: [{ type: "text", text: JSON.stringify({ ok: false, message: String(err.message || err) }) }], isError: true }
        });
      }
    }

    return new Response("Not Found", { status: 404, headers: CORS_HEADERS });
  }

  jsonRpc(payload, status = 200) {
    return new Response(JSON.stringify(payload), {
      status,
      headers: { ...CORS_HEADERS, "Content-Type": "application/json", "Mcp-Session-Id": this.sessionId }
    });
  }

  async webSocketMessage(ws, message) {
    try {
      const data = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
      if (data.type === "ping") { ws.send(JSON.stringify({ type: "pong" })); return; }
      if (data.type === "mcp_response" && data.reqId) {
        const waiter = this.pendingRequests.get(data.reqId);
        if (waiter) { this.pendingRequests.delete(data.reqId); waiter.resolve(data); }
      }
    } catch (_) {}
  }

  async webSocketClose() {
    for (const [reqId, waiter] of this.pendingRequests.entries()) {
      waiter.reject(new Error("Phone disconnected."));
      this.pendingRequests.delete(reqId);
    }
  }
}
