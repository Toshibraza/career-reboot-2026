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
| `:feature:memory` | Android lib | `SqliteMemory`, `MemoryActionExecutor` |
| `:feature:localllm` | Android lib | `LocalChatClient`, `LocalModelStore` |
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

### Memory

"Remember my parking spot is B2" → "where is my parking spot". Stored in SQLite on the device,
answered with no model and no network.

Recall reuses `FuzzyMatcher` — the same scoring that resolves "whats app" to an installed app
resolves "parking spot" to "my parking spot". Asking about something is the same shape of
problem as naming an app, and it should not have a second, subtly different implementation.

Three decisions:

- **A new value replaces the old one.** Being told a new parking spot means the previous one is
  wrong, not that there are now two of them.
- **An unmatched question says so.** Reading out the nearest unrelated fact is worse than
  admitting nothing matched — this table holds things the user cannot get back.
- **Memory is never added to an LLM prompt automatically.** It is exactly the place a gate code
  or a door PIN ends up, and quietly shipping all of it to an API on every unrecognised command
  would be indefensible. If a task genuinely needs a stored fact, that should be an explicit
  recall step whose result enters the history, not a blanket disclosure.

`remember X is Y` is the supported shape. "Remember to buy milk" is a reminder — it is about
*when*, not *what* — so it declines rather than filing it as a fact and losing the part that
matters.

### Notifications

"Read my notifications", "what did I miss". Needs notification access, granted under Settings →
Notifications → Special app access.

**Nothing is stored.** `onNotificationPosted` is deliberately not used to build a history — these
are other people's messages as much as the user's, and a transcript of them on disk would be a
liability with no matching benefit. The shade is read live at the moment the user asks.

- **Three states, not a boolean.** "Not granted", "granted but not bound yet" and "nothing new"
  get different answers. Collapsing the first two tells someone to grant access they already
  gave; collapsing "not granted" with "nothing new" makes a missing permission look like an
  empty inbox, which is the one wrong answer that really matters here.
- **Bodies are clipped at a word boundary.** A marketing email arrives as three hundred
  characters of prose. Read out in full it buries the message that mattered — a real Outlook
  notification on device was the test case.
- **Ongoing notifications are skipped.** The music player, the navigation bar, Nova's own
  listening notice — those are status, not news.

### Routines and reminders

```
"every morning at 8 open spotify"     -> I'll open spotify every day at 8 am.
"remind me to buy milk at 6 pm"       -> I'll remind you to buy milk at 6 pm.
"list my routines"                    -> open spotify every day at 8 am. remind you to buy milk at 6 pm
"cancel the reminder to buy milk"     -> Cancelled remind you to buy milk.
```

**A routine stores the utterance, not a parsed plan.** "Every morning at 8 open spotify" is kept
as the words `open spotify`, run through the full agent when the alarm fires. So the entire
command vocabulary works inside a routine for free, and one created today benefits from every
later improvement to the parser instead of freezing whatever it understood at the time.

A reminder is the same machinery: `remind me to buy milk` is stored as the command `say buy
milk`, scheduled once. That is also why `say` exists as a command in its own right.

Decisions:

- **Scheduling rules are evaluated before everything else.** They wrap another command, and the
  flashlight rule matches "flashlight" anywhere — so "every day at 10 pm turn on the flashlight"
  would otherwise switch the torch on immediately instead of scheduling it. Pinned by a test.
- **Inexact alarms.** Exact alarms need `SCHEDULE_EXACT_ALARM`, a permission the user must grant
  on a settings screen since Android 12, and demanding that to play music at 8 am is
  disproportionate. The cost is drift: a reminder set for 00:28 fired at **00:28:46** on device.
  Fine for "every morning", worth knowing before trusting it with medication.
- **Daily routines are re-armed after each firing** rather than using a repeating alarm, so one
  deleted at noon does not come back at eight tomorrow.
- **The schedule is read back aloud.** A reminder set for the wrong time is worse than one that
  failed outright, and hearing "at 6 pm" is how the user catches a mis-parse.
- **Alarms do not survive a reboot**, so `RoutineReceiver` re-arms everything on
  `BOOT_COMPLETED` and after the app is updated.

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

### Running the planner on the phone

`ChatClient` has two implementations. `LocalChatClient` runs a quantised model through
MediaPipe's LLM Inference API with no network; `OpenAiClient` calls the API. `NovaContainer`
picks per request: **if a model file is installed, the on-device model is used**, otherwise the
API.

A local failure deliberately does **not** fall back to the cloud. Someone who put a model on
their phone did it so their screen contents stay on their phone — quietly posting that screen to
an API because the local model ran out of memory would betray exactly the choice they made.

#### Installing a model

The model is not bundled: it is hundreds of megabytes, and the good ones carry licences a person
has to accept. Side-load it instead:

```bash
adb push model.task /sdcard/Android/data/com.nova.assistant/files/llm/model.task
```

The row under **Multi-step tasks** then reads "On-device model, N MB — private, no network".

#### Choosing a model for the device you have

Sizing must come from **free** memory, not total. This Redmi Note 10 reports 5.7 GB total and
routinely has under 2 GB available; the loader only cares about the latter. Weights have to be
resident alongside the KV cache and runtime, so budget roughly 1.5× the file size.

| Model (LiteRT `.task`, q8) | File | Needs free | Fits this device? |
| --- | --- | --- | --- |
| Qwen2.5-0.5B-Instruct | 521 MB | ~780 MB | **Yes** |
| Qwen2.5-1.5B-Instruct | 1524 MB | ~2.3 GB | No |
| Gemma3-1B-IT | — | — | Gated: needs a Hugging Face token and accepting Google's licence |

Qwen is Apache-2.0 and ungated. Gemma needs you to accept its terms on Hugging Face first.

#### Measured on this device

Both models, same goal ("open settings and search for bluetooth"), Snapdragon 678, CPU
inference, screen visible on every step:

| | Qwen2.5-0.5B q8 (521 MB) | Gemma3-1B-IT int4 (529 MB) |
| --- | --- | --- |
| First generation | ~14 s | ~88 s — building the xnnpack cache |
| Warm, per step | ~14 s | ~19 s |
| Reply quality | valid, wordy | valid, terse — cleaner |
| Result | `open_app Settings` × 8 | `open_app Settings` × 8 |

**Both run. Neither can plan.** Each chose the *correct first action* and then repeated it until
the step cap stopped them, with `screen visible: true` on every call and the prompt growing 2736
→ 3039 characters as history accumulated. They receive the feedback and cannot act on it.

Gemma writes better JSON and reasons more tersely than Qwen, and being twice the parameters did
**not** buy the ability to use its own history. That is the finding: on this hardware the
limitation is not which 1B-class model you pick.

Note the cold-start trap when benchmarking: MediaPipe writes a `model.task.xnnpack_cache` beside
the model on first load. Measure the first generation and you get ~88 s; measure the second and
you get ~19 s. Delete that cache when swapping models, or the next one loads against the wrong
compiled graph.

Two prompt bugs were found and fixed on the way, both worth knowing if you swap the model:

- Listing allowed values as `"action":"open_app|tap|type|..."` inside the JSON template made it
  copy the placeholder verbatim. Allowed values now appear as prose, not inside the template.
- A rule alone ("choose one word") did not work; an explicit **WRONG/RIGHT** example pair did.
  Small models imitate shape far more reliably than they follow instructions.

So on this hardware the useful split is: **rules handle the daily commands offline and
instantly**, and multi-step planning needs a model larger than this phone can hold. Both 1B-class
candidates that fit were tried and both failed the same way. Anything bigger — Qwen2.5-1.5B needs
~2.3 GB free against ~1.9 GB available — does not load at all.

Everything else in Nova already runs offline: speech in, speech out, wake word, every device
control, every cross-app action, and screen reading. Multi-step planning is the one capability
that is not offline-capable on this class of hardware.

The MediaPipe native libraries take the APK from about 18 MB to about 66 MB.

What did work exactly as designed: the step cap stopped the loop at 8 rather than letting it tap
forever, the malformed-reply guard refused to invent an action, and the failure was reported
plainly instead of silently doing nothing.

### Enabling the API planner

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
