# AI Assistant (native Android, Gemini Live)

A native Kotlin / Jetpack Compose assistant with a user-chosen name, real-time voice through the Gemini Live API,
and accessibility-based phone control.

**Status: source complete, NOT yet compiled or run on a device.** It was written without access to a build
environment. Expect to fix a few compile errors on the first build; paste them back and they are usually one-line fixes.

## Selected versions
| Item | Version |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.11.1 |
| Kotlin (+ Compose compiler plugin) | 2.0.21 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Compose BOM | 2024.12.01 |

## Build with only a phone (GitHub Actions)
1. Create a GitHub repo and add these files with their folder structure (use "Add file > Upload files" for folders
   in desktop-site mode, or open the repo in a Codespace and drag the zip in, then `unzip`).
2. The workflow in `.github/workflows/build-apk.yml` builds a debug APK on every push to `main`
   (or Actions tab > "Build debug APK" > Run workflow).
3. Download the `app-debug-apk` artifact, unzip it, and install `app-debug.apk` (allow "install unknown apps").

With a computer: open the folder in Android Studio (Ladybug or newer) and press Run, or `gradle :app:assembleDebug`.

## First run
1. Name your assistant. 2. Settings > paste a Gemini API key from Google AI Studio > Save > **Test connection**.
3. Start Conversation (grants microphone + notifications when you tap it).
4. For phone control: Home > tap "Phone Control" > Open accessibility settings > enable the service.
   On Android 13+ with a sideloaded APK the switch is greyed out until you do App info > ⋮ > **Allow restricted settings**.

## Model ID
Default is `gemini-3.1-flash-live-preview`, which Google's model page lists. Google's docs also mention newer/other
Live IDs; model names change, so it is editable in Settings and "Test connection" tells you if your account accepts it.
Audio is 16 kHz PCM in, 24 kHz PCM out, per the current Live API docs.

## Architecture
- `voice/` GeminiLiveManager (OkHttp WebSocket, state machine, bounded reconnect, tool calls), AudioInput/OutputManager,
  AssistantRuntime (wiring), VoiceSessionService (microphone foreground service).
- `ai/` instructions (built from the CURRENT saved name on every session start), action schema, strict parser.
- `automation/` PhoneActionRouter (only place actions execute), AppLauncher, ScreenNodeInspector, GestureController,
  ActionPermissionManager (local confirmation/block policy), AccessibilityAutomationService, AutomationLogger.
- `data/`, `memory/` DataStore settings, encrypted API-key store, local transcript. `permissions/`, `ui/`.

## Safety model
The model can only call one function, `phone_action`, with an allowlisted `action` enum. The parser rejects unknown
keys/values; the router re-validates and applies local policy. Confirmation for send/call/buy/delete/share/consent
buttons is a button in the app (the model cannot confirm for you). System permission/installer screens are never
automated, and password fields are never typed into.

## Manual test checklist
- Rename YESMIN -> FRIDAY while connected: session restarts; ask "What is your name?"
- "Open YouTube" (with and without accessibility enabled), "Open Chrome", "Open Settings".
- "Scroll down", "Go back", "Go home", "Show recent apps", "Tap the Search button".
- Tap a "Send"-like button: a Confirm card must appear.
- Barge-in: interrupt mid-answer. Airplane mode mid-session: bounded reconnect, then a visible error.
- End Call / notification "End session": microphone indicator must disappear.
- Wrong key, wrong model ID: clear error from Test connection.

## Known limitations (honest list)
- No wake word. The name is identity only; start with the button. (Integration point: start `AssistantRuntime.startSession()`.)
- Reconnects (including server `goAway`) start a fresh Live session, so spoken context is not carried over
  (session resumption is not implemented). Saved transcript remains on screen.
- "Stop speaking" silences audio locally and discards the rest of that turn; it does not cancel server generation.
- Android may block background app launches; the app verifies with accessibility and reports UNVERIFIED honestly.
  The optional overlay permission helps.
- Typed text into a field appends to existing text via ACTION_SET_TEXT; some custom views ignore it.
- A permanent key in the app is development-only; for release, mint short-lived Live tokens on your own backend.
- Long-term memory is not implemented: only name, settings, and the transcript are stored.
