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
    description: "FAST BATCH PIPELINE: Runs multiple phone tools sequentially on-device in ONE single MCP round-trip.",
    inputSchema: {
      type: "object",
      properties: {
        actions: { type: "array", items: { type: "object", properties: { tool: { type: "string" }, args: { type: "object" } }, required: ["tool"] } },
        delay_ms: { type: "integer" },
        stop_on_error: { type: "boolean" },
        return_final_ui: { type: "boolean" }
      },
      required: ["actions"]
    }
  },
  {
    name: "set_alarm",
    description: "FAST DIRECT COMMAND: Sets one or multiple alarms instantly via Android OS AlarmClock Intent (zero UI clicking).",
    inputSchema: {
      type: "object",
      properties: {
        hour: { type: "integer" },
        minute: { type: "integer" },
        message: { type: "string" },
        days: { type: "array", items: { type: "integer" } },
        skip_ui: { type: "boolean" },
        alarms: {
          type: "array",
          items: { type: "object", properties: { hour: { type: "integer" }, minute: { type: "integer" }, message: { type: "string" }, days: { type: "array", items: { type: "integer" } }, skip_ui: { type: "boolean" } }, required: ["hour", "minute"] }
        }
      }
    }
  },
  { name: "set_timer", description: "Starts a countdown timer instantly via Android OS Intent.", inputSchema: { type: "object", properties: { seconds: { type: "integer" }, message: { type: "string" }, skip_ui: { type: "boolean" } }, required: ["seconds"] } },
  { name: "open_url", description: "Opens any URL or deep link directly on the phone.", inputSchema: { type: "object", properties: { url: { type: "string" }, package_name: { type: "string" } }, required: ["url"] } },
  { name: "search_web", description: "Instantly launches search in google, youtube, maps, or play_store.", inputSchema: { type: "object", properties: { query: { type: "string" }, engine: { type: "string" } }, required: ["query"] } },
  { name: "send_intent", description: "Fires any Android Intent directly (action, data_uri, package_name, mime_type, extras).", inputSchema: { type: "object", properties: { action: { type: "string" }, data_uri: { type: "string" }, package_name: { type: "string" }, mime_type: { type: "string" }, extras: { type: "object" } }, required: ["action"] } },
  { name: "media_control", description: "Controls playback (play_pause, play, pause, next, previous, stop, volume_up, volume_down, mute).", inputSchema: { type: "object", properties: { command: { type: "string" } }, required: ["command"] } },
  { name: "quick_communicate", description: "Opens dialer, SMS, or WhatsApp chat directly.", inputSchema: { type: "object", properties: { type: { type: "string" }, phone: { type: "string" }, message: { type: "string" } }, required: ["type", "phone"] } },
  { name: "clipboard", description: "Reads or writes Android clipboard (set or get).", inputSchema: { type: "object", properties: { operation: { type: "string" }, text: { type: "string" } }, required: ["operation"] } },
  { name: "get_device_state", description: "Returns active app, screen size, battery, volume, ringer mode, network, and UI hash.", inputSchema: { type: "object", properties: {} } },
  { name: "read_ui_tree", description: "Captures the live pruned Android Accessibility UI hierarchy.", inputSchema: { type: "object", properties: { max_nodes: { type: "integer" } } } },
  { name: "capture_screen", description: "Captures a downscaled JPEG screenshot for vision fallback.", inputSchema: { type: "object", properties: {} } },
  { name: "tap_node", description: "Taps a UI element by node_id, text, or content_desc.", inputSchema: { type: "object", properties: { node_id: { type: "integer" }, text: { type: "string" }, content_desc: { type: "string" }, include_ui: { type: "boolean" } } } },
  { name: "tap_xy", description: "Taps exact pixel coordinates (x, y).", inputSchema: { type: "object", properties: { x: { type: "integer" }, y: { type: "integer" }, include_ui: { type: "boolean" } }, required: ["x", "y"] } },
  { name: "long_press", description: "Long-presses a node or coordinates.", inputSchema: { type: "object", properties: { node_id: { type: "integer" }, text: { type: "string" }, x: { type: "integer" }, y: { type: "integer" }, duration_ms: { type: "integer" } } } },
  { name: "swipe", description: "Performs a swipe gesture.", inputSchema: { type: "object", properties: { direction: { type: "string" }, start_x: { type: "integer" }, start_y: { type: "integer" }, end_x: { type: "integer" }, end_y: { type: "integer" }, duration_ms: { type: "integer" } } } },
  { name: "scroll", description: "Scrolls the screen (down, up, left, right).", inputSchema: { type: "object", properties: { direction: { type: "string" }, include_ui: { type: "boolean" } } } },
  { name: "type_text", description: "Types text into an input field.", inputSchema: { type: "object", properties: { text: { type: "string" }, node_id: { type: "integer" }, clear_existing: { type: "boolean" }, press_enter: { type: "boolean" }, include_ui: { type: "boolean" } }, required: ["text"] } },
  { name: "press_key", description: "Executes system key: back, home, recents, notifications, quick_settings, lock_screen.", inputSchema: { type: "object", properties: { key: { type: "string" } }, required: ["key"] } },
  { name: "launch_app", description: "Launches an installed Android app.", inputSchema: { type: "object", properties: { app_name: { type: "string" }, package_name: { type: "string" }, include_ui: { type: "boolean" } } } },
  { name: "list_installed_apps", description: "Lists launchable installed Android apps.", inputSchema: { type: "object", properties: { query: { type: "string" } } } },
  { name: "get_notifications", description: "Lists active notifications.", inputSchema: { type: "object", properties: { limit: { type: "integer" } } } },
  { name: "manage_notification", description: "Dismisses or invokes action on a notification.", inputSchema: { type: "object", properties: { operation: { type: "string" }, key: { type: "string" }, action_title: { type: "string" } }, required: ["operation"] } },
  { name: "control_system_setting", description: "Controls volume, ringer_mode, flashlight, brightness, or settings panels.", inputSchema: { type: "object", properties: { setting: { type: "string" }, value: { type: "string" } }, required: ["setting"] } },
  { name: "wait_for_ui", description: "Waits until text or package appears on screen.", inputSchema: { type: "object", properties: { text: { type: "string" }, package_name: { type: "string" }, timeout_ms: { type: "integer" } } } },
  { name: "find_and_tap", description: "Scrolls to find matching text and taps it.", inputSchema: { type: "object", properties: { text: { type: "string" }, max_scrolls: { type: "integer" }, include_ui: { type: "boolean" } }, required: ["text"] } }
];

const PC_TOOLS = [
  {
    name: "run_actions",
    description: "FAST BATCH PIPELINE: Runs multiple Windows PC commands sequentially in ONE round-trip.",
    inputSchema: {
      type: "object",
      properties: {
        actions: { type: "array", items: { type: "object", properties: { tool: { type: "string" }, args: { type: "object" } }, required: ["tool"] } },
        delay_ms: { type: "integer" },
        stop_on_error: { type: "boolean" }
      },
      required: ["actions"]
    }
  },
  { name: "run_command", description: "Runs a PowerShell or CMD command directly on the Windows PC.", inputSchema: { type: "object", properties: { command: { type: "string" }, shell: { type: "string", description: "powershell or cmd" }, timeout_s: { type: "integer" } }, required: ["command"] } },
  { name: "open_url", description: "Opens any URL directly in the default PC browser (Brave).", inputSchema: { type: "object", properties: { url: { type: "string" } }, required: ["url"] } },
  { name: "search_web", description: "Opens a direct search on google, youtube, github, or maps in the PC browser.", inputSchema: { type: "object", properties: { query: { type: "string" }, engine: { type: "string" } }, required: ["query"] } },
  { name: "launch_app", description: "Launches a Windows app (brave, notepad, calc, code, explorer, cmd, settings, or exe path).", inputSchema: { type: "object", properties: { app: { type: "string" } }, required: ["app"] } },
  { name: "list_windows", description: "Lists currently open desktop windows on the Windows PC.", inputSchema: { type: "object", properties: {} } },
  { name: "focus_window", description: "Brings a window matching title text to the foreground.", inputSchema: { type: "object", properties: { title: { type: "string" } }, required: ["title"] } },
  { name: "media_control", description: "Controls PC media & volume (play_pause, next, previous, stop, volume_up, volume_down, mute).", inputSchema: { type: "object", properties: { command: { type: "string" }, steps: { type: "integer" } }, required: ["command"] } },
  { name: "system_power", description: "Executes lock, minimize_all, or sleep on the Windows PC.", inputSchema: { type: "object", properties: { action: { type: "string" } }, required: ["action"] } },
  { name: "clipboard", description: "Reads or writes the Windows clipboard (set or get).", inputSchema: { type: "object", properties: { operation: { type: "string" }, text: { type: "string" } }, required: ["operation"] } },
  { name: "list_files", description: "Lists files in a PC folder (supports shortcuts: Desktop, Downloads, Documents).", inputSchema: { type: "object", properties: { path: { type: "string" } } } },
  { name: "read_file", description: "Reads text content from a file on the Windows PC.", inputSchema: { type: "object", properties: { path: { type: "string" } }, required: ["path"] } },
  { name: "write_file", description: "Writes or appends text to a file on the Windows PC.", inputSchema: { type: "object", properties: { path: { type: "string" }, content: { type: "string" }, append: { type: "boolean" } }, required: ["path", "content"] } },
  { name: "capture_screen", description: "Captures a downscaled JPEG screenshot of the Windows PC desktop.", inputSchema: { type: "object", properties: {} } },
  { name: "mouse_click", description: "Clicks at (x, y) on the Windows PC screen.", inputSchema: { type: "object", properties: { x: { type: "integer" }, y: { type: "integer" }, button: { type: "string" }, clicks: { type: "integer" } }, required: ["x", "y"] } },
  { name: "type_text", description: "Types Arabic or English text into the active PC window.", inputSchema: { type: "object", properties: { text: { type: "string" }, press_enter: { type: "boolean" } }, required: ["text"] } },
  { name: "press_hotkey", description: "Presses a keyboard shortcut (e.g. 'ctrl+c', 'alt+tab', 'win+d').", inputSchema: { type: "object", properties: { keys: { type: "string" } }, required: ["keys"] } },
  { name: "get_pc_status", description: "Returns PC hostname, OS, CPU/RAM/Battery %, screen resolution, and active window.", inputSchema: { type: "object", properties: {} } }
];

const PHONE_RESOURCES = [
  { uri: "phone://device/state", name: "Live Device State", description: "Active app, screen dimensions, battery, audio, and network.", mimeType: "application/json" },
  { uri: "phone://ui/tree", name: "Live Accessibility UI Tree", description: "Current pruned UI hierarchy.", mimeType: "application/json" },
  { uri: "phone://notifications", name: "Active Notifications", description: "Current status bar notifications.", mimeType: "application/json" }
];

const PC_RESOURCES = [
  { uri: "pc://system/status", name: "Live Windows PC Status", description: "CPU, RAM, battery, active window, and resolution.", mimeType: "application/json" }
];

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return new Response(
        JSON.stringify({ status: "ok", service: "gemini-phone-mcp-relay", version: "2.1.0", phone_tools: PHONE_TOOLS.length, pc_tools: PC_TOOLS.length }),
        { status: 200, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
      );
    }

    const isPc = url.pathname === "/ws-pc" || url.pathname === "/pc" || url.pathname.startsWith("/pc/");
    let token = url.searchParams.get("token");
    if (!token && url.pathname.startsWith("/mcp/")) {
      token = url.pathname.slice("/mcp/".length).split("/")[0];
    } else if (!token && url.pathname.startsWith("/pc/")) {
      token = url.pathname.slice("/pc/".length).split("/")[0];
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

    const roomKey = isPc ? `pc:${token}` : token;
    const id = env.PHONE_TUNNEL.idFromName(roomKey);
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
    const isPc = url.pathname === "/ws-pc" || url.pathname === "/pc" || url.pathname.startsWith("/pc/");
    const socketTag = isPc ? "pc" : "phone";

    if (url.pathname === "/ws" || url.pathname === "/ws-pc") {
      if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
        return new Response("Expected WebSocket", { status: 426 });
      }
      for (const existing of this.ctx.getWebSockets(socketTag)) {
        try { existing.close(1000, "Replaced"); } catch (_) {}
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, [socketTag]);
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === "/mcp" || url.pathname.startsWith("/mcp/") || url.pathname === "/pc" || url.pathname.startsWith("/pc/")) {
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
            serverInfo: {
              name: isPc ? "gemini-windows-pc-mcp-server" : "gemini-android-mcp-server",
              version: isPc ? "1.0.0" : "2.0.0"
            }
          }
        });
      }
      if (rpc.method === "ping") {
        return this.jsonRpc({ jsonrpc: "2.0", id: rpc.id ?? null, result: {} });
      }
      if (rpc.method === "tools/list") {
        return this.jsonRpc({ jsonrpc: "2.0", id: rpc.id ?? null, result: { tools: isPc ? PC_TOOLS : PHONE_TOOLS } });
      }
      if (rpc.method === "resources/list") {
        return this.jsonRpc({ jsonrpc: "2.0", id: rpc.id ?? null, result: { resources: isPc ? PC_RESOURCES : PHONE_RESOURCES } });
      }

      const sockets = this.ctx.getWebSockets(socketTag);
      if (!sockets || sockets.length === 0) {
        const offlineMsg = isPc
          ? "Windows PC offline: run start_pc_mcp.bat on your PC."
          : "Phone offline: open Gemini MCP Agent on your Android phone and tap Start.";
        return this.jsonRpc({
          jsonrpc: "2.0",
          id: rpc.id ?? null,
          result: { content: [{ type: "text", text: JSON.stringify({ ok: false, message: offlineMsg }) }], isError: true }
        });
      }

      const targetSocket = sockets[sockets.length - 1];
      const reqId = crypto.randomUUID();
      const responsePromise = new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pendingRequests.delete(reqId);
          reject(new Error(`${isPc ? "PC" : "Phone"} timed out after 30s.`));
        }, 30_000);
        this.pendingRequests.set(reqId, {
          resolve: (p) => { clearTimeout(timer); resolve(p); },
          reject: (e) => { clearTimeout(timer); reject(e); }
        });
      });

      try {
        targetSocket.send(JSON.stringify({ type: "mcp_request", reqId, body: rawBody }));
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
      waiter.reject(new Error("Device disconnected."));
      this.pendingRequests.delete(reqId);
    }
  }
}
