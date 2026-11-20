# Gemini MCP Phone Agent + Free HTTPS Tunnel (for Official Gemini App)

Control your Android phone directly from the **official Google Gemini app** (no Gemini API key needed!) via a built-in **on-device MCP Streamable HTTP server** + **free permanent Cloudflare Workers WebSocket HTTPS Tunnel**.

## How It Works
```
Official Gemini App (Cloud)
   │  HTTPS POST /mcp/<secret-token>
   ▼
Cloudflare Worker + SQLite Durable Object (100% Free Tier, permanent HTTPS URL)
   │  Persistent Outbound WebSocket (wss://)
   ▼
Android App (GeminiMcpPhoneAgent) → LocalMcpServer (18 phone tools) → Accessibility & Notification Bridges
```

## 3-Step Setup (100% Free, Zero PC / Zero Termux Needed)

### Step 1 — Deploy the Free Cloudflare Tunnel Relay (2 minutes)
1. Upload this folder to a GitHub repository.
2. Create a free account at [dash.cloudflare.com](https://dash.cloudflare.com) (no credit card required — SQLite Durable Objects are included on the Workers Free plan).
3. In Cloudflare Dashboard → **Compute (Workers) → Workers & Pages → Create → Import a repository**:
   - Select your GitHub repo and set **Root directory** to `cloudflare-tunnel-relay`.
   - Click **Deploy**.
   - Copy your permanent Worker URL, e.g. `https://gemini-phone-mcp-relay.<your-subdomain>.workers.dev`.
   *(Alternatively, add `CLOUDFLARE_API_TOKEN` in GitHub Repo Secrets and run the **Deploy Free Cloudflare MCP Tunnel** workflow in GitHub Actions.)*

### Step 2 — Build & Install the Android APK via GitHub Actions
1. In your GitHub repo → **Actions → Build Android APK → Run workflow**.
   *(If browser drag-and-drop skipped `.github/`, create `.github/workflows/build-apk.yml` using **Actions → set up a workflow yourself** and paste the included file.)*
2. Download `GeminiMcpPhoneAgent-debug-apk`, unzip, and install `app-debug.apk` on your Android 11+ phone.
3. Open the app:
   - Tap **Enable Accessibility** → enable **Gemini MCP Phone Bridge**.
   - Tap **Enable Notifs** → enable **Gemini MCP Notification Bridge**.
   - Tap **Settings (gear icon)** → paste your Cloudflare Worker URL into **Cloudflare Worker Tunnel URL** → tap **Save**. *(Leave Gemini API Key blank if you only use the Official Gemini App!)*
   - Tap **Start**. Wait 2 seconds until it shows **Official Gemini App Tunnel: CONNECTED**.
   - Tap **Copy URL** (copies `https://gemini-phone-mcp-relay.<you>.workers.dev/mcp/<secret-token>`).

### Step 3 — Connect Inside the Official Gemini App
1. Open **Gemini** on desktop web (`gemini.google.com`) → **Settings & help → Connected Apps → Custom apps → Add a custom app**.
2. Paste the copied URL (`https://.../mcp/<secret-token>`) as the **MCP server URL** and save.
3. Open the **official Gemini app on your Android phone** and speak naturally — Gemini will call your phone's 18 MCP tools directly, while the app's **Voice Confirmation Gate** still protects sensitive actions (sending messages, payments, deletions, calls).
