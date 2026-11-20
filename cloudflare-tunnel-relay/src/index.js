import { DurableObject } from "cloudflare:workers";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, Accept, Mcp-Session-Id",
  "Access-Control-Expose-Headers": "Mcp-Session-Id"
};

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response(
        JSON.stringify({ status: "ok", service: "gemini-phone-mcp-relay" }),
        { status: 200, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
      );
    }

    // Extract secret token either from /ws?token=... or /mcp/<token> or Bearer header
    let token = url.searchParams.get("token");
    if (!token && url.pathname.startsWith("/mcp/")) {
      token = url.pathname.slice("/mcp/".length).split("/")[0];
    }
    if (!token) {
      const auth = request.headers.get("Authorization") || "";
      if (auth.startsWith("Bearer ")) {
        token = auth.slice(7).trim();
      }
    }

    if (!token || token.length < 16) {
      return new Response(
        JSON.stringify({
          jsonrpc: "2.0",
          id: null,
          error: { code: -32001, message: "Unauthorized: missing or invalid tunnel token." }
        }),
        { status: 401, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
      );
    }

    // Route each unique phone token to its own isolated Durable Object instance
    const id = env.PHONE_TUNNEL.idFromName(token);
    const stub = env.PHONE_TUNNEL.get(id);
    return stub.fetch(request);
  }
};

export class PhoneTunnelRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pendingRequests = new Map();
  }

  async fetch(request) {
    const url = new URL(request.url);

    // 1. Outbound WebSocket connection from the Android phone
    if (url.pathname === "/ws") {
      const upgrade = request.headers.get("Upgrade");
      if (!upgrade || upgrade.toLowerCase() !== "websocket") {
        return new Response("Expected WebSocket upgrade", { status: 426 });
      }

      // Close any stale previous phone socket so only the latest connection is active
      for (const existing of this.ctx.getWebSockets("phone")) {
        try {
          existing.close(1000, "Replaced by new phone connection");
        } catch (_) {}
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.ctx.acceptWebSocket(server, ["phone"]);

      return new Response(null, { status: 101, webSocket: client });
    }

    // 2. Incoming MCP Streamable HTTP POST from Official Gemini App (or any MCP client)
    if (request.method === "POST" && (url.pathname === "/mcp" || url.pathname.startsWith("/mcp/"))) {
      const sockets = this.ctx.getWebSockets("phone");
      if (!sockets || sockets.length === 0) {
        return new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: null,
            error: {
              code: -32002,
              message: "Phone offline: open Gemini MCP Agent on your Android phone and tap Start."
            }
          }),
          { status: 503, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
        );
      }

      const phoneSocket = sockets[sockets.length - 1];
      const rawBody = await request.text();
      const reqId = crypto.randomUUID();

      const responsePromise = new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          this.pendingRequests.delete(reqId);
          reject(new Error("Phone MCP execution timed out after 25s."));
        }, 25_000);

        this.pendingRequests.set(reqId, {
          resolve: (payload) => {
            clearTimeout(timer);
            resolve(payload);
          },
          reject: (err) => {
            clearTimeout(timer);
            reject(err);
          }
        });
      });

      try {
        phoneSocket.send(JSON.stringify({ type: "mcp_request", reqId, body: rawBody }));
      } catch (err) {
        this.pendingRequests.delete(reqId);
        return new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: null,
            error: { code: -32003, message: "Failed to forward request to phone: " + String(err) }
          }),
          { status: 502, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
        );
      }

      try {
        const result = await responsePromise;
        const headers = {
          ...CORS_HEADERS,
          "Content-Type": "application/json"
        };
        if (result.sessionId) {
          headers["Mcp-Session-Id"] = result.sessionId;
        }
        return new Response(result.body ?? "", {
          status: result.status || 200,
          headers
        });
      } catch (err) {
        return new Response(
          JSON.stringify({
            jsonrpc: "2.0",
            id: null,
            error: { code: -32004, message: err.message || "Tunnel timeout" }
          }),
          { status: 504, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } }
        );
      }
    }

    return new Response("Not Found", { status: 404, headers: CORS_HEADERS });
  }

  async webSocketMessage(ws, message) {
    try {
      const data = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
      if (data.type === "ping") {
        ws.send(JSON.stringify({ type: "pong" }));
        return;
      }
      if (data.type === "mcp_response" && data.reqId) {
        const waiter = this.pendingRequests.get(data.reqId);
        if (waiter) {
          this.pendingRequests.delete(data.reqId);
          waiter.resolve(data);
        }
      }
    } catch (_) {
      // Ignore malformed frames
    }
  }

  async webSocketClose(ws) {
    for (const [reqId, waiter] of this.pendingRequests.entries()) {
      waiter.reject(new Error("Phone tunnel disconnected mid-request."));
      this.pendingRequests.delete(reqId);
    }
  }
}
