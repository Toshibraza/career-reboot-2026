package com.nova.core.agent.rules

import com.nova.core.agent.AgentContext
import com.nova.core.agent.LevelChange
import com.nova.core.agent.NovaAction
import com.nova.core.agent.ScrollDirection
import com.nova.core.agent.VolumeStream
import com.nova.core.agent.routine.RoutineTrigger
import com.nova.core.agent.routine.TimeOfDay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleIntentEngineTest {

    private val engine = RuleIntentEngine()

    // runBlocking, not runTest: runTest returns TestResult, and these helpers need the value.
    private fun parse(utterance: String): NovaAction = runBlocking {
        engine.plan(utterance, AgentContext()).actions.single()
    }

    // --- Apps --------------------------------------------------------------------------

    @Test
    fun `opens an app by name`() {
        assertEquals(NovaAction.OpenApp("youtube"), parse("open YouTube"))
        assertEquals(NovaAction.OpenApp("whatsapp"), parse("launch WhatsApp"))
        assertEquals(NovaAction.OpenApp("calculator"), parse("start the calculator app"))
    }

    @Test
    fun `strips the wake word and politeness`() {
        assertEquals(NovaAction.OpenApp("camera"), parse("Hey Raza, please open the camera"))
    }

    @Test
    fun `wake word near-misses are stripped too`() {
        // "Raza" is not in the recogniser's vocabulary, so it comes back as whatever real word
        // sounds closest. Stripping only the exact spelling is why a wake word appears not to
        // work when it was heard perfectly well.
        assertEquals(NovaAction.OpenApp("youtube"), parse("razor open youtube"))
        assertEquals(NovaAction.OpenApp("youtube"), parse("hey rasa, open youtube"))
    }

    @Test
    fun `closes a named app and the current one`() {
        assertEquals(NovaAction.CloseApp("chrome"), parse("close Chrome"))
        assertEquals(NovaAction.CloseApp(""), parse("close app"))
    }

    // --- Torch -------------------------------------------------------------------------

    @Test
    fun `toggles the flashlight in both directions`() {
        assertEquals(NovaAction.SetFlashlight(on = true), parse("turn on the flashlight"))
        assertEquals(NovaAction.SetFlashlight(on = false), parse("turn off the torch"))
    }

    @Test
    fun `torch beats the close-app rule`() {
        assertEquals(NovaAction.SetFlashlight(on = false), parse("close the flashlight"))
    }

    // --- Volume ------------------------------------------------------------------------

    @Test
    fun `sets volume from digits and from words`() {
        assertEquals(
            NovaAction.SetVolume(VolumeStream.MEDIA, LevelChange.Absolute(50)),
            parse("set volume to 50 percent"),
        )
        assertEquals(
            NovaAction.SetVolume(VolumeStream.MEDIA, LevelChange.Absolute(70)),
            parse("set the volume to seventy"),
        )
        assertEquals(
            NovaAction.SetVolume(VolumeStream.MEDIA, LevelChange.Absolute(25)),
            parse("set volume to twenty five"),
        )
    }

    @Test
    fun `steps volume up and down`() {
        assertEquals(
            NovaAction.SetVolume(VolumeStream.MEDIA, LevelChange.Relative(10)),
            parse("volume up"),
        )
        assertEquals(
            NovaAction.SetVolume(VolumeStream.MEDIA, LevelChange.Relative(-10)),
            parse("turn the volume down"),
        )
    }

    @Test
    fun `routes to the stream named in the utterance`() {
        assertEquals(
            NovaAction.SetVolume(VolumeStream.RING, LevelChange.Min),
            parse("mute the ringer"),
        )
        assertEquals(
            NovaAction.SetVolume(VolumeStream.ALARM, LevelChange.Absolute(30)),
            parse("set alarm volume to 30"),
        )
    }

    @Test
    fun `maxes the volume`() {
        assertEquals(
            NovaAction.SetVolume(VolumeStream.MEDIA, LevelChange.Max),
            parse("max volume"),
        )
    }

    // --- Brightness --------------------------------------------------------------------

    @Test
    fun `sets and steps brightness`() {
        assertEquals(NovaAction.SetBrightness(LevelChange.Absolute(40)), parse("set brightness to 40"))
        assertEquals(NovaAction.SetBrightness(LevelChange.Relative(20)), parse("increase brightness"))
        assertEquals(NovaAction.SetBrightness(LevelChange.Relative(-20)), parse("dim the screen a bit"))
    }

    // --- Navigation --------------------------------------------------------------------

    @Test
    fun `handles device navigation`() {
        assertEquals(NovaAction.LockScreen, parse("lock the phone"))
        assertEquals(NovaAction.TakeScreenshot, parse("take a screenshot"))
        assertEquals(NovaAction.GoHome, parse("go home"))
        assertEquals(NovaAction.GoBack, parse("go back"))
    }

    @Test
    fun `does not mistake app names for navigation`() {
        assertEquals(NovaAction.OpenApp("google home"), parse("open google home"))
        assertEquals(NovaAction.OpenApp("lock screen"), parse("open lock screen app"))
    }

    // --- On-screen control -------------------------------------------------------------

    @Test
    fun `taps a labelled control`() {
        assertEquals(NovaAction.TapLabel("send"), parse("tap send"))
        assertEquals(NovaAction.TapLabel("login"), parse("click the login button"))
        assertEquals(NovaAction.TapLabel("continue"), parse("press on continue"))
    }

    @Test
    fun `scrolls in a named direction`() {
        assertEquals(NovaAction.ScrollScreen(ScrollDirection.DOWN), parse("scroll down"))
        assertEquals(NovaAction.ScrollScreen(ScrollDirection.UP), parse("scroll up a bit"))
        assertEquals(NovaAction.ScrollScreen(ScrollDirection.LEFT), parse("swipe left"))
    }

    @Test
    fun `scroll without a direction declines`() = runTest {
        val plan = engine.plan("scroll", AgentContext())
        assertTrue(plan.actions.single() is NovaAction.Unsupported)
    }

    @Test
    fun `dictated text keeps its punctuation and casing`() {
        // The normalised utterance has already lost the comma and question mark, so the rule
        // has to recover the text from the raw utterance or the message is sent mangled.
        assertEquals(
            NovaAction.TypeText("Hello, how are you?"),
            parse("type Hello, how are you?"),
        )
        assertEquals(
            NovaAction.TypeText("I'm reaching in 10 minutes"),
            parse("write I'm reaching in 10 minutes"),
        )
    }

    @Test
    fun `opens recents and notifications`() {
        assertEquals(NovaAction.OpenRecents, parse("show recent apps"))
        assertEquals(NovaAction.OpenNotifications, parse("open my notifications"))
    }

    @Test
    fun `screen control does not swallow app launches`() {
        assertEquals(NovaAction.OpenApp("telegram"), parse("open telegram"))
        assertEquals(NovaAction.OpenApp("press reader"), parse("open press reader"))
    }

    // --- Chained commands --------------------------------------------------------------

    @Test
    fun `chained commands decline so the planner gets them`() = runTest {
        // Without this, the app name becomes "whatsapp and message amit".
        listOf(
            "open whatsapp and message amit",
            "open chrome and search for flights",
            "tap send and then close the app",
        ).forEach { utterance ->
            val plan = engine.plan(utterance, AgentContext())
            assertTrue(
                "expected '$utterance' to decline",
                plan.actions.single() is NovaAction.Unsupported,
            )
        }
    }

    @Test
    fun `app names containing and still resolve`() {
        // A bare "and" guard would wrongly reject these.
        assertEquals(NovaAction.OpenApp("black and white"), parse("open black and white"))
        assertEquals(NovaAction.OpenApp("sound and vibration"), parse("open sound and vibration"))
    }

    // --- Calls and messages ------------------------------------------------------------

    @Test
    fun `calling names a person, not an app`() {
        assertEquals(NovaAction.CallContact("Mom"), parse("call Mom"))
        assertEquals(NovaAction.CallContact("Amit Kumar"), parse("call Amit Kumar"))
    }

    @Test
    fun `call with no one named declines`() = runTest {
        // "Call back" names nobody, and guessing who would be the worst possible reading.
        listOf("call back", "call again", "call someone").forEach {
            val plan = engine.plan(it, AgentContext())
            assertTrue("expected '$it' to decline", plan.actions.single() is NovaAction.Unsupported)
        }
    }

    @Test
    fun `messages separate the name from the text`() {
        assertEquals(
            NovaAction.SendSms("Amit", "I'm reaching in 10 minutes"),
            parse("text Amit saying I'm reaching in 10 minutes"),
        )
        assertEquals(
            NovaAction.SendSms("Amit", "on my way"),
            parse("message Amit on my way"),
        )
    }

    @Test
    fun `message text keeps its casing and punctuation`() {
        val action = parse("text Priya saying Running late, see you at 7!") as NovaAction.SendSms
        assertEquals("Running late, see you at 7!", action.message)
    }

    @Test
    fun `yes and no map to confirm and cancel`() {
        assertEquals(NovaAction.ConfirmPending, parse("yes"))
        assertEquals(NovaAction.ConfirmPending, parse("go ahead"))
        assertEquals(NovaAction.CancelPending, parse("cancel"))
        assertEquals(NovaAction.CancelPending, parse("never mind"))
    }

    @Test
    fun `confirmation words are exact matches only`() = runTest {
        // A stray leading "yes" must never confirm a pending call. It is fine for this to be
        // unrecognised — what matters is that it is not treated as consent.
        listOf("yes open youtube", "yes please call him", "no thanks I meant later").forEach {
            val action = engine.plan(it, AgentContext()).actions.single()
            assertTrue(
                "'$it' must not be read as confirmation",
                action != NovaAction.ConfirmPending,
            )
        }
    }

    // --- Routines ----------------------------------------------------------------------

    @Test
    fun `schedules a daily routine around an existing command`() {
        assertEquals(
            NovaAction.CreateRoutine(
                trigger = RoutineTrigger.Daily(TimeOfDay(8, 0)),
                command = "open spotify",
                spokenSchedule = "every day at 8 am",
            ),
            parse("every morning at 8 open spotify"),
        )
    }

    @Test
    fun `a routine stores the utterance, not a parsed plan`() {
        // Storing the words means a routine created today benefits from every later
        // improvement to the parser, and the whole command vocabulary works inside one.
        val action = parse("every day at 10 pm, turn on the flashlight") as NovaAction.CreateRoutine
        assertEquals("turn on the flashlight", action.command)
        assertEquals(RoutineTrigger.Daily(TimeOfDay(22, 0)), action.trigger)
    }

    @Test
    fun `reminders work in both phrasings and fire once`() {
        val toAt = parse("remind me to buy milk at 6 pm") as NovaAction.CreateRoutine
        assertEquals(RoutineTrigger.OnceAt(TimeOfDay(18, 0)), toAt.trigger)
        assertEquals("say buy milk", toAt.command)

        val atTo = parse("remind me at 6 pm to buy milk") as NovaAction.CreateRoutine
        assertEquals(toAt, atTo)
    }

    @Test
    fun `a reminder with no time declines rather than firing never`() {
        // Scheduling it for an invented time would be worse than admitting it wasn't understood.
        val plan = runBlocking { engine.plan("remind me to buy milk", AgentContext()) }
        assertTrue(plan.actions.single() is NovaAction.Unsupported)
    }

    @Test
    fun `say speaks the words back with casing intact`() {
        assertEquals(NovaAction.Speak("Buy milk on the way home"), parse("say Buy milk on the way home"))
    }

    @Test
    fun `schedules on a battery threshold`() {
        assertEquals(
            NovaAction.CreateRoutine(
                trigger = RoutineTrigger.BatteryBelow(20),
                command = "turn on battery saver",
                spokenSchedule = "when the battery drops below 20 percent",
            ),
            parse("when battery is below 20 percent, turn on battery saver"),
        )
        assertEquals(
            RoutineTrigger.BatteryBelow(15),
            (parse("if the battery drops under 15%, open settings") as NovaAction.CreateRoutine).trigger,
        )
    }

    @Test
    fun `an impossible battery threshold declines`() = runTest {
        // 0 and 100 are not thresholds anyone means, and a routine that can never fire — or
        // fires forever — is worse than one that was not created.
        listOf(
            "when battery is below 0 percent, open settings",
            "when battery is below 100 percent, open settings",
        ).forEach {
            val plan = engine.plan(it, AgentContext())
            assertTrue("expected '$it' to decline", plan.actions.single() is NovaAction.Unsupported)
        }
    }

    @Test
    fun `schedules on plugging in and unplugging`() {
        assertEquals(
            RoutineTrigger.PowerConnected,
            (parse("when i plug in the charger, read my notifications") as NovaAction.CreateRoutine).trigger,
        )
        assertEquals(
            RoutineTrigger.PowerDisconnected,
            (parse("when i unplug the charger, turn on battery saver") as NovaAction.CreateRoutine).trigger,
        )
    }

    @Test
    fun `lists and deletes routines`() {
        assertEquals(NovaAction.ListRoutines, parse("list my routines"))
        assertEquals(NovaAction.DeleteRoutine("buy milk"), parse("cancel the reminder to buy milk"))
    }

    // --- Diagnostics and search --------------------------------------------------------

    @Test
    fun `diagnostics is not mistaken for launching an app`() {
        // "run" is a launch verb, so "run diagnostics" opened an app called "diagnostics"
        // until the rule was moved ahead of the app rules.
        assertEquals(NovaAction.RunDiagnostics, parse("run diagnostics"))
        assertEquals(NovaAction.RunDiagnostics, parse("what is wrong"))
        assertEquals(NovaAction.RunDiagnostics, parse("are you ok"))
    }

    @Test
    fun `search takes explicit verbs and keeps the query intact`() {
        assertEquals(NovaAction.SearchWeb("kotlin coroutines"), parse("search for kotlin coroutines"))
        assertEquals(NovaAction.SearchWeb("best phone under 20000"), parse("google best phone under 20000"))
        assertEquals(NovaAction.SearchWeb("Ramanujan"), parse("look up Ramanujan"))
    }

    @Test
    fun `asking what Raza knows never becomes a web search`() {
        // Memory questions must stay local. Turning "what is my gate code" into a web request
        // would send a private fact to a third party.
        assertEquals(NovaAction.Recall("gate code"), parse("what is my gate code"))
    }

    // --- Memory ------------------------------------------------------------------------

    @Test
    fun `remembers a subject and a fact`() {
        assertEquals(
            NovaAction.Remember("my parking spot", "B2"),
            parse("remember my parking spot is B2"),
        )
        assertEquals(
            NovaAction.Remember("Amit", "my brother"),
            parse("remember that Amit is my brother"),
        )
    }

    @Test
    fun `stored detail keeps its casing`() {
        // A door code or password is worthless once it has been lowercased.
        assertEquals(
            NovaAction.Remember("the gate code", "4B7x"),
            parse("remember the gate code is 4B7x"),
        )
    }

    @Test
    fun `a reminder is not filed as a fact`() = runTest {
        // "Remember to buy milk" is about when, not what. Storing it as a fact would lose the
        // part that matters, so it declines and escalates instead.
        val plan = engine.plan("remember to buy milk", AgentContext())
        assertTrue(plan.actions.single() is NovaAction.Unsupported)
    }

    @Test
    fun `recalls by subject`() {
        assertEquals(NovaAction.Recall("parking spot"), parse("where is my parking spot"))
        assertEquals(NovaAction.Recall("gate code"), parse("what is the gate code"))
        assertEquals(NovaAction.RecallAll, parse("what do you remember"))
    }

    @Test
    fun `forgets by subject`() {
        assertEquals(NovaAction.ForgetMemory("parking spot"), parse("forget my parking spot"))
    }

    @Test
    fun `memory rules do not shadow existing commands`() {
        // These all start with question words or would otherwise be caught by the recall
        // pattern, and each must keep its original meaning.
        assertEquals(NovaAction.ReadScreen, parse("what is on screen"))
        assertEquals(NovaAction.Speak("I'm Raza, your assistant on this phone."), parse("who are you"))
    }

    // --- Fallback ----------------------------------------------------------------------

    @Test
    fun `unknown commands are unsupported with zero confidence`() = runTest {
        val plan = engine.plan("explain quantum computing to me", AgentContext())
        assertEquals(0f, plan.confidence, 0.001f)
        assertTrue(plan.actions.single() is NovaAction.Unsupported)
    }

    @Test
    fun `set volume with no number declines rather than guessing`() = runTest {
        val plan = engine.plan("set the volume", AgentContext())
        // Falls through the absolute rule to the step rule, which also declines without a direction.
        assertTrue(plan.actions.single() is NovaAction.Unsupported)
    }
}
