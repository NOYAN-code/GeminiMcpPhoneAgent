# GeminiMcpPhoneAgent v2.0 (Fast Command & Batch Edition)

Ultra-fast Android MCP Server + Cloudflare Worker Relay for controlling your Android phone directly from the official Google Gemini app.

## What's New in v2.0
1. **Batch Command Pipeline (`run_actions`)**: Execute 2–10 actions sequentially on-device in ONE MCP round-trip.
2. **Direct OS Intent Commands (Zero UI Clicking)**:
   - `set_alarm`: Schedule 1 or multiple alarms instantly via `AlarmClock.ACTION_SET_ALARM` (`EXTRA_SKIP_UI=true`).
   - `set_timer`: Start countdown timers directly.
   - `open_url`: Open any URL, YouTube video, WhatsApp link, or deep link directly.
   - `search_web`: Instant search in Google, YouTube, Maps, or Play Store in 1 step.
   - `send_intent`: Dispatch any Android Intent (`action`, `data_uri`, `package_name`, `extras`).
   - `media_control`: Instant `play_pause`, `next`, `previous`, `volume_up`, `volume_down`, `mute`.
   - `quick_communicate`: Open dialer, SMS, or WhatsApp chat directly.
   - `clipboard`: Read/write system clipboard.
3. **Zero-Hop In-Memory WebSocket Dispatch**: Incoming Cloudflare tunnel requests execute directly in memory without a localhost HTTP hop.
4. **Adaptive Fast UI Settling & Inline UI Return (`include_ui=true`)**: Cuts multi-step UI navigation round-trips in half.
