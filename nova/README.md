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
| Anything else ("explain quantum computing") | **No** | Declines — needs the LLM engine |

Phase 2 adds the commands that need the accessibility service. They require the user to switch
Nova on under Settings → Accessibility. All verified on the same device, driving MIUI's Settings
app from the background:

| Command | Verified |
| --- | --- |
| "Go back" | Yes — returned from Battery to Settings |
| "Lock the phone" | Yes — screen went `ON → OFF`, `mDreamingLockscreen=true` (needs Android 9+) |
| "Take a screenshot" | Yes — file written to `DCIM/Screenshots` (needs Android 11+) |
| "Show recent apps", "open notifications" | Yes |
| "Tap Battery", "tap search settings" | Yes — navigated to the Battery screen, focused the search box |
| "Scroll down" | Yes — scrolled Settings while Nova stayed backgrounded |
| "Type battery saver" | Yes — text landed in the field and returned real results |
| "Close Chrome" | Goes home — Android has no API to close another app |
| "What's on screen", "read the screen" | Yes — Settings, YouTube, WhatsApp and the launcher |

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

Cross-app commands need the other form, because `am start` would make Nova the foreground app and
"tap send" would then search Nova's own screen:

```bash
adb shell am broadcast -p com.nova.assistant -a com.nova.assistant.COMMAND -e command "'scroll down'"
adb logcat -s NovaCommand:I     # what Nova understood and what it answered
```

`NovaCommandReceiver` lives in the `debug` source set, so it and its manifest entry are absent
from a release build entirely rather than relying on a runtime flag.

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
| `:core:agent` | **pure JVM** | Actions, plans, `IntentEngine`, `ActionExecutor`, `AgentRuntime`, the rule engine, `FuzzyMatcher` |
| `:core:llm` | **pure JVM** | `TaskPrompt`, `OpenAiClient`, `OpenAiTaskPlanner` |
| `:core:speech` | Android lib | `SpeechToText`, `Speaker`, `WakeWordDetector` + platform implementations |
| `:feature:device` | Android lib | `AppRegistry`, `DeviceController`, `DeviceActionExecutor` |
| `:feature:accessibility` | Android lib | `NovaAccessibilityService`, `ScreenNodes`, `AccessibilityActionExecutor` |
| `:app` | Android app | Compose UI, ViewModel, foreground service, composition root |

`:core:agent` has no Android dependency at all. That is what makes the rule engine testable in
milliseconds without an emulator, and what will let the same planner drive a Windows client later.

### Seeing the screen

`ScreenReader` returns a `ScreenSnapshot`: the app, and a list of elements with their label,
role, and whether they can be pressed or typed into. It comes from the **accessibility node
tree, not pixels** — real labels, real roles, no transcription step to get them wrong. OCR over a
MediaProjection capture is the right tool for photos, documents and canvas-rendered apps that
expose nothing to accessibility, and it slots in behind this same interface when it arrives.

Two decisions worth knowing:

- **The screen is a provider on `AgentContext`, not a value.** Reading the screen means
  inspecting whatever app the user has open, which is privacy-relevant rather than free
  context. The rule engine never calls it, so "open YouTube" reads nothing.
- **`toPrompt()` drops coordinates.** A planner should name the control it wants pressed, not a
  pixel — that keeps taps label-based, auditable, and resilient to layout changes.

### Multi-step tasks

Anything the rule engine can't parse escalates to a `TaskPlanner`, which drives an **observe-act
loop**: read the screen, choose one action, execute it, look again. One step at a time, never a
batch — after a tap the screen is different, and further steps planned against the old screen
would be guesses. Sending a message means tapping a contact, waiting for a screen that did not
exist when planning started, typing, then finding a send button whose label you could not have
known in advance.

`OpenAiTaskPlanner` implements it. `TaskPrompt` — the prompt wording and the reply mapping, which
is the part most likely to be wrong — is pure Kotlin in `:core:llm` and fully unit-tested with no
network.

The guardrails are the interesting part, and each exists for a reason:

- **A step cap (8).** A planner that misreads a screen would otherwise tap forever, and every
  step is a real touch on someone's phone.
- **A malformed reply blocks, it never guesses.** Unparseable JSON, an unknown action, or an
  action missing its argument all become "I can't do that" rather than a random tap.
- **A missing permission ends the run immediately.** Retrying will not grant it, and the user
  should hear about it now rather than after eight wasted steps.
- **The planner may only tap labels that appear in the screen listing**, and is told not to take
  destructive or irreversible actions unless the goal explicitly asked for one.
- **Rules decline chained commands** so they escalate. Without that, "open whatsapp and message
  Amit" is read as a request to launch an app named "whatsapp and message amit". The guard keys
  on a verb after "and", so real names like "Sound and vibration" still resolve.

### Enabling the planner

Either paste a key into the app — **Multi-step tasks → Set key** — or set a build-time default
in `nova/local.properties`, which is gitignored:

```properties
openai.apiKey=sk-...
```

A pasted key wins over the build-time one and takes effect on the next command, because the key
is resolved per request rather than captured at construction. That matters: the build-time key
is baked into the APK, and the only safe response to a leaked key is to replace it quickly —
which should be a paste, not a rebuild and reinstall.

The pasted key lives in Nova's private preferences. That keeps it away from other apps but is
not encrypted at rest, so a rooted device or a backup could reach it. Fine for a personal build;
a published app should not hold a provider key on the device at all and should call a backend
that holds it instead.

With no key at all, unrecognised commands answer "I don't have an API key yet" rather than
failing obscurely.

### The two seams that matter

**`IntentEngine`** — turns text into a plan. Today that's `RuleIntentEngine`: ordered regex rules,
deterministic, offline, zero cost. An LLM engine implements the same interface, and
`FallbackIntentEngine` already exists to run rules first and hand the long tail to a model.
Swapping it is one line in [NovaContainer.kt](app/src/main/kotlin/com/nova/assistant/NovaContainer.kt).

**`ActionExecutor`** — carries out one class of action. Executors are a registered list probed in
order, never a `when` block. Phase 2 proved this out: the accessibility service arrived as one new
class plus one line in `NovaContainer`, and the placeholder it replaced was deleted outright. No
existing executor changed. Every action has exactly one owner, so the list order is documentation
rather than precedence.

## Known limitations

These are real, and none of them are hidden behind a silent failure:

- **Hands-free has two routes, and the cheap one is better.** Setting Nova as the device
  assistant means a power-button hold or corner swipe opens the mic, with **nothing running in
  the background at all**. Most people asking for "wake on my voice" actually want "reach Nova
  without touching the screen", and the gesture does that for free.
- **The wake word still costs battery, less than it did.** `GatedWakeWordDetector` watches raw
  microphone energy — arithmetic over a small buffer — and only starts the speech recogniser
  once it hears a voice, so in a quiet room the recogniser never runs. In a room with a
  conversation in it, it runs repeatedly, because it is still full transcription deciding
  whether the phrase was said. The correct fix is a purpose-trained keyword spotter (Porcupine,
  or openWakeWord) running a 1–2 MB model over a rolling buffer, answering "was that the
  phrase" without transcribing. That is a new implementation of `WakeWordDetector`; nothing
  that collects `detections()` changes. `TranscriptWakeWordDetector` remains as a fallback for
  devices where the raw-audio gate cannot open the microphone.
- **Background activity starts.** Android 10+ blocks starting activities from the background, so
  "open YouTube" spoken to the always-listening service with the app off-screen may do nothing.
- **Nothing can close another app.** Force-stop is reserved for the system, a device owner, or
  root. "Close Chrome" goes home and says so, rather than implying the app was killed.
- **Tapping confirms out loud.** A fuzzy match against on-screen labels that silently hits the
  wrong control in a banking app is the worst thing Nova could do, so a successful tap always
  reports which label it pressed. That confirmation is what caught the bug below.
- **Accessible names are not the visible text.** MIUI's Settings search box reads "Search
  settings" on screen but is named `Search` to accessibility, and its hint is not exposed at
  all. Tapping therefore searches actionable nodes first and only falls back to inert ones —
  without that, the page heading "Settings" outscored the real search box. Headings must never
  beat buttons when the whole point is to press something.
- **The accessibility service reads nothing in the background.** `onAccessibilityEvent` is
  deliberately empty; every screen read is pull-based, triggered by a command the user gave.
- **`QUERY_ALL_PACKAGES`** is declared so "open anything" can work. Play Store requires a declared
  justification for it.
- Brightness writes the system-wide setting and disables auto-brightness to make the change stick.
- **App matching is deliberately conservative.** Substring matches need four characters, because
  an app named "X" is a substring of nearly every query — before that guard, "open zqxwv" opened
  Twitter. Short labels have to win on an exact match. See `AppMatcherTest`.
- **MIUI specifics.** `pm grant` and `input` over adb both need "USB debugging (Security
  settings)" enabled, which requires a signed-in Xiaomi account. MIUI also has its own autostart
  and background-activity restrictions that will affect the always-listening service.
- **Reinstalling disables the accessibility service.** Android drops accessibility privileges
  when an app is updated, so after every `adb install -r` the switch under Settings →
  Accessibility must be turned back on. Observed directly: `enabled_accessibility_services` goes
  to `null`. Nothing in the app can re-grant it, and nothing should be able to.

## Roadmap

Phases 1 and 2 are done. The remaining phases, and where each one plugs in:

- **Phase 3 — Screen and camera understanding.** MediaProjection capture, ML Kit OCR, and an
  LLM-backed `IntentEngine` that plans multi-step tasks against what is on screen.
- **Phase 4 — Memory and routines.** SQLite plus a vector store behind a `Memory` interface;
  routines as scheduled plans reusing the same `NovaAction` vocabulary.
- **Phase 5 — Beyond the phone.** A Windows client and IoT control are new `ActionExecutor`
  implementations over the existing `:core:agent`, which is why that module holds no Android types.
