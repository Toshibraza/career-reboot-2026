# Nova

A voice-driven assistant for Android. Phase 1: wake word, speech in, speech out, app launching,
and device controls.

## Status

Phase 1 is built and verified on hardware — a Redmi Note 10 (`M2101K7AI`), Android 12 / MIUI 14.

| Command | Works | Verified on device |
| --- | --- | --- |
| "Open YouTube", "launch WhatsApp", "open chrom" | Yes | Yes — including "whats app" → WhatsApp |
| "Turn on the flashlight" / "off" | Yes | Yes |
| "Set volume to 40 percent", "mute the ringer" | Yes | Yes |
| "Set brightness to 60", "increase brightness" | Yes | Yes — after granting WRITE_SETTINGS in-app |
| "Go home" | Yes | Not yet |
| "Who are you", "hello" | Yes | Yes |
| Spoken "open YouTube" via the mic button | Yes | Yes — transcribed on-device, no network |
| "Open zqxwv" (gibberish) | Declines | Yes — "I couldn't find an app called zqxwv" |
| "Lock the phone", "screenshot", "go back", "close Chrome" | **No** | Declines with a reason — needs Phase 2 |
| Anything else ("explain quantum computing") | **No** | Declines — needs the LLM engine |

**The voice path is verified.** Tapping the mic and saying "open YouTube" transcribed correctly
and launched the app. Notably, the platform honoured `EXTRA_PREFER_OFFLINE` and used Google's
on-device SODA recogniser — no network round-trip:

```
RecognitionServiceImpl: logStartListening: callingApp: com.nova.assistant, locale: en-IN
SodaSpeechRecognizer:   Offline recognizer - start listening
SodaSpeechRecognizer:   #handleFinalResult: 1 hyp
ActivityTaskManager:    START ... com.google.android.youtube ... callingPackage com.nova.assistant
```

Offline transcription is not guaranteed — it depends on the user having the language pack — but
where it exists, Nova gets it for free.

### Driving commands over adb

Debug builds accept a command as an intent extra, which is how the table above was tested:

```bash
adb shell am start -n com.nova.assistant/.MainActivity -e command "'open youtube'"
```

Note the nested quotes — `adb shell` splits on spaces, so an unquoted command arrives truncated.
The extra is ignored in release builds: `MainActivity` is exported, and an open command channel
into the assistant is not something to ship.

## Running it

```bash
./gradlew assembleDebug
./gradlew :core:agent:test :feature:device:testDebugUnitTest   # 20 tests, all green
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain is pinned to what this machine already has cached: Gradle 9.1.0, AGP 8.13.1,
Kotlin 2.3.0, compileSdk 36, minSdk 26. `local.properties` points at the SDK and is gitignored.

On first launch, grant the microphone permission. Brightness and ringer commands will prompt for
their own permissions when first used, each with a button straight to the right settings screen.

## Architecture

```
voice ──► SpeechToText ──► IntentEngine ──► Plan(List<NovaAction>) ──► ActionExecutor ──► device
                                                                            │
                                            AgentRuntime ◄──── ActionResult ┘
                                                  │
                                                  └──► spoken reply ──► Speaker
```

Four modules, split so that each capability can be replaced without touching the others:

| Module | Type | Contains |
| --- | --- | --- |
| `:core:agent` | **pure JVM** | Actions, plans, `IntentEngine`, `ActionExecutor`, `AgentRuntime`, the rule engine |
| `:core:speech` | Android lib | `SpeechToText`, `Speaker`, `WakeWordDetector` + platform implementations |
| `:feature:device` | Android lib | `AppRegistry`, `DeviceController`, `DeviceActionExecutor` |
| `:app` | Android app | Compose UI, ViewModel, foreground service, composition root |

`:core:agent` has no Android dependency at all. That is what makes the rule engine testable in
milliseconds without an emulator, and what will let the same planner drive a Windows client later.

### The two seams that matter

**`IntentEngine`** — turns text into a plan. Today that's `RuleIntentEngine`: ordered regex rules,
deterministic, offline, zero cost. An LLM engine implements the same interface, and
`FallbackIntentEngine` already exists to run rules first and hand the long tail to a model.
Swapping it is one line in [NovaContainer.kt](app/src/main/kotlin/com/nova/assistant/NovaContainer.kt).

**`ActionExecutor`** — carries out one class of action. Executors are a registered list probed in
order, never a `when` block. Adding the Phase 2 accessibility service means writing a new executor
and adding it to that list; `UnsupportedActionExecutor` is the placeholder that currently answers
for what it will take over.

## Known limitations

These are real, and none of them are hidden behind a silent failure:

- **Wake word costs battery.** `TranscriptWakeWordDetector` re-runs the platform recogniser in a
  loop and holds the mic open. It works with no model file, which is what Phase 1 needed, but the
  platform recogniser is not built to run all day. Phase 2 replaces it with a proper keyword
  spotter (Porcupine or openWakeWord) on a small audio ring buffer — same interface, new class.
- **Background activity starts.** Android 10+ blocks starting activities from the background, so
  "open YouTube" spoken to the always-listening service with the app off-screen may do nothing.
  The accessibility service in Phase 2 removes this constraint.
- **`QUERY_ALL_PACKAGES`** is declared so "open anything" can work. Play Store requires a declared
  justification for it.
- Brightness writes the system-wide setting and disables auto-brightness to make the change stick.
- **App matching is deliberately conservative.** Substring matches need four characters, because
  an app named "X" is a substring of nearly every query — before that guard, "open zqxwv" opened
  Twitter. Short labels have to win on an exact match. See `AppMatcherTest`.
- **MIUI specifics.** `pm grant` and `input` over adb both need "USB debugging (Security
  settings)" enabled, which requires a signed-in Xiaomi account. MIUI also has its own autostart
  and background-activity restrictions that will affect the always-listening service.

## Roadmap

Phase 1 is done. The remaining phases, and where each one plugs in:

- **Phase 2 — Accessibility.** `:feature:accessibility` with an `AccessibilityActionExecutor`:
  tap, scroll, type, back, lock, screenshot, and reading the notification stream. Delete
  `UnsupportedActionExecutor` the day it lands.
- **Phase 3 — Screen and camera understanding.** MediaProjection capture, ML Kit OCR, and an
  LLM-backed `IntentEngine` that plans multi-step tasks against what is on screen.
- **Phase 4 — Memory and routines.** SQLite plus a vector store behind a `Memory` interface;
  routines as scheduled plans reusing the same `NovaAction` vocabulary.
- **Phase 5 — Beyond the phone.** A Windows client and IoT control are new `ActionExecutor`
  implementations over the existing `:core:agent`, which is why that module holds no Android types.
