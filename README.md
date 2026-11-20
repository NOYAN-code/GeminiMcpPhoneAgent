# Gemini MCP Phone Agent (Android)

A standalone, voice-controlled Android smartphone automation agent that runs a **localhost-only Model Context Protocol (MCP) Streamable HTTP server** directly on-device and orchestrates multi-step phone tasks via **Google Gemini (`gemini-2.5-flash`)**.

## Selected Architecture

| Layer | Selected Strategy | Why It Wins |
| :--- | :--- | :--- |
| **OS Platform** | Android 11+ (API 30–35, Kotlin + Compose) | Full system-wide UI tree inspection, gesture injection, screenshot capture, and background services (blocked on iOS). |
| **MCP Transport** | **MCP Streamable HTTP** (`POST http://127.0.0.1:8765/mcp`) | Modern JSON-RPC 2.0 MCP standard (`2025-06-18` / `2025-03-26`), replacing deprecated HTTP+SSE. Works both in-app over loopback and externally via `adb forward`. |
| **Local Server Host** | Embedded **Ktor CIO** inside an Android `ForegroundService` | Zero PC/Termux dependency, sub-5ms loopback latency, survives Doze/background eviction, strictly bound to `127.0.0.1` with constant-time Bearer token auth. |
| **OS Execution Bridge** | **Hybrid 4-Layer Bridge**: `AccessibilityService` (UI tree + gestures + Android 11+ `takeScreenshot`) + `NotificationListenerService` + OS Intents & System Managers | 100% rootless and Shizuku-free while delivering semantic UI precision, instant multimodal vision fallback without repeated MediaProjection prompts, and deterministic app/setting control. |
| **Orchestration** | **Gemini 2.5 Flash REST Client** with dynamic `tools/list` discovery, post-action UI-hash verification, and automatic screenshot self-correction | Decoupled MCP tool loading, preserved `thoughtSignature`, automatic recovery when UI does not change, and live RPM + daily USD budget caps. |
| **Voice & Safety** | Android `SpeechRecognizer` (Quick Settings Tile + wake-phrase loop) + `TextToSpeech` + fail-closed **Voice Confirmation Gate** | Instant hands-free control with mandatory spoken/UI confirmation before sending messages, payments, deletions, or calls, plus automatic password redaction and app denylist. |

## Exposed Local MCP Tools (18 Tools + 3 Resources)

- **Perception**: `get_device_state`, `read_ui_tree`, `capture_screen`, `get_notifications`, `list_installed_apps`
- **Atomic UI Control**: `tap_node`, `tap_xy`, `long_press`, `swipe`, `scroll`, `type_text`, `press_key`
- **Apps & System**: `launch_app`, `open_url`, `manage_notification`, `control_system_setting`
- **Composite Workflows**: `wait_for_ui`, `find_and_tap`
- **MCP Resources**: `phone://device/state`, `phone://ui/tree`, `phone://notifications`

## Build & Install

### Option A — Build APK in the Cloud via GitHub Actions (No Android Studio Needed)
1. Create a new GitHub repository and upload the contents of `GeminiMcpPhoneAgent/`.
2. If you upload via browser drag-and-drop and GitHub skips the hidden `.github` folder, go to **Actions → set up a workflow yourself**, name it `.github/workflows/build-apk.yml`, and paste the included workflow file.
3. Run the **Build Android APK** workflow and download `GeminiMcpPhoneAgent-debug-apk` from the workflow Artifacts.
4. Transfer `app-debug.apk` to your Android 11+ phone and install it.

### Option B — Build Locally with Android Studio / Gradle
```bash
cd GeminiMcpPhoneAgent
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-Time Phone Setup
1. Open **Gemini MCP Agent** and grant Microphone and Notification permissions.
2. Tap **Settings** (top-right gear) and paste your **Google Gemini API Key** (stored locally in `EncryptedSharedPreferences`).
3. Tap **Enable Accessibility** → enable **Gemini MCP Phone Bridge**.
4. Tap **Enable Notifs** → enable **Gemini MCP Notification Bridge**.
5. Tap **Start** to launch the local MCP server on `127.0.0.1:8765`.
6. Optional: Add the **Gemini Voice** Quick Settings Tile from your notification shade for one-tap voice activation from any app, or enable the hands-free wake phrase (`"hey gemini"`).

## Connecting an External Desktop MCP Client (Optional)
Because the server implements standard MCP Streamable HTTP on `127.0.0.1:8765`, you can also connect Gemini CLI, Claude Desktop, or any MCP client over USB ADB without exposing your phone on Wi-Fi:

```bash
adb forward tcp:8765 tcp:8765
curl http://127.0.0.1:8765/health
```

Then configure your MCP client with:
- **Endpoint**: `http://127.0.0.1:8765/mcp`
- **Header**: `Authorization: Bearer <your-token-from-app-settings>`
