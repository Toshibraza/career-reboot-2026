package com.nova.core.agent.rules

import com.nova.core.agent.AgentContext
import com.nova.core.agent.LevelChange
import com.nova.core.agent.NovaAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hinglish commands.
 *
 * Kept in its own file because these test one property the English tests cannot: word order.
 * Hindi puts the object first and the verb last, so the same intent needs the capture group in
 * a different place — and the two orders have to coexist without stealing each other's
 * sentences.
 */
class HinglishRuleTest {

    private val engine = RuleIntentEngine()

    private fun parse(utterance: String): NovaAction = runBlocking {
        engine.plan(utterance, AgentContext()).actions.single()
    }

    // --- Apps ----------------------------------------------------------------------------

    @Test
    fun `opening an app with the verb last`() {
        assertEquals(NovaAction.OpenApp("youtube"), parse("youtube kholo"))
        assertEquals(NovaAction.OpenApp("whatsapp"), parse("whatsapp khol do"))
        assertEquals(NovaAction.OpenApp("instagram"), parse("instagram chalu karo"))
        assertEquals(NovaAction.OpenApp("phonepe"), parse("phonepe open karo"))
    }

    @Test
    fun `closing an app with the verb last`() {
        assertEquals(NovaAction.CloseApp("whatsapp"), parse("whatsapp band karo"))
        assertEquals(NovaAction.CloseApp("youtube"), parse("youtube band kar do"))
    }

    @Test
    fun `an app name is not swallowed by the verb`() {
        // "Band" is a real word in app names. The rule anchors it to the end of the sentence
        // followed by "kar…", so a name containing it survives.
        assertEquals(NovaAction.OpenApp("band camp"), parse("band camp kholo"))
    }

    // --- Devices -------------------------------------------------------------------------

    @Test
    fun `the torch takes precedence over closing an app called torch`() {
        // The exact collision this ordering exists for, and the Hinglish twin of a bug the
        // English rules already had to fix: "close the torch" is not an app.
        assertEquals(NovaAction.SetFlashlight(on = false), parse("torch band karo"))
        assertEquals(NovaAction.SetFlashlight(on = false), parse("batti band karo"))
        assertEquals(NovaAction.SetFlashlight(on = true), parse("torch chalu karo"))
    }

    @Test
    fun `volume up and down`() {
        assertEquals(
            LevelChange.Relative(10),
            (parse("volume badhao") as NovaAction.SetVolume).level,
        )
        assertEquals(
            LevelChange.Relative(-10),
            (parse("awaaz kam karo") as NovaAction.SetVolume).level,
        )
    }

    @Test
    fun `silencing is the volume switched off, not an app called awaaz`() {
        // Without the OFF arm on the mute rule this fell through every volume rule and was
        // read as "close the app called awaaz".
        assertEquals(LevelChange.Min, (parse("awaaz band karo") as NovaAction.SetVolume).level)

        // The same gap existed in English and was never noticed.
        assertEquals(LevelChange.Min, (parse("turn off the volume") as NovaAction.SetVolume).level)
    }

    @Test
    fun `volume set to a number`() {
        assertEquals(
            LevelChange.Absolute(40),
            (parse("awaaz 40 percent karo") as NovaAction.SetVolume).level,
        )
    }

    @Test
    fun `brightness up and down`() {
        assertEquals(
            LevelChange.Relative(20),
            (parse("roshni badhao") as NovaAction.SetBrightness).level,
        )
        assertEquals(
            LevelChange.Relative(-20),
            (parse("brightness kam karo") as NovaAction.SetBrightness).level,
        )
    }

    // --- People --------------------------------------------------------------------------

    @Test
    fun `calling someone with the verb last`() {
        assertEquals(NovaAction.CallContact("Amit"), parse("Amit ko call karo"))
        assertEquals(NovaAction.CallContact("Mummy"), parse("Mummy ko phone lagao"))
        assertEquals(NovaAction.CallContact("Amit Kumar"), parse("Amit Kumar ko milao"))
    }

    @Test
    fun `a called name keeps its capitals`() {
        // Read back to the user before dialling, so "call amit kumar?" looks like a bug.
        assertEquals(NovaAction.CallContact("Amit Kumar"), parse("Amit Kumar ko call karo"))
    }

    @Test
    fun `messaging needs the content marker`() {
        assertEquals(
            NovaAction.SendSms("Amit", "main late hoon"),
            parse("Amit ko message bhejo ki main late hoon"),
        )

        // No "ki" means nothing separates the name from the message. Guessing the split would
        // send a real message to the wrong person, so this declines instead.
        assertTrue(parse("Amit ko message bhejo") is NovaAction.Unsupported)
    }

    // --- Media ---------------------------------------------------------------------------

    @Test
    fun `playing something with the verb last`() {
        assertEquals(NovaAction.PlayMedia("coke studio"), parse("coke studio chala do"))
        assertEquals(NovaAction.PlayMedia("arijit singh"), parse("arijit singh bajao"))
    }

    @Test
    fun `playing is not opening an app of that name`() {
        // "Play Coke Studio" names something to watch. Sent to the app rules it becomes a
        // search for an installed app called "Coke Studio", which does not exist.
        assertEquals(NovaAction.PlayMedia("Coke Studio"), parse("play Coke Studio"))
    }

    // --- Confirmation --------------------------------------------------------------------

    @Test
    fun `confirming and cancelling in Hinglish`() {
        // These gate every call and message. Someone who says "nahi" to a proposed call must
        // be understood, or Raza dials anyway.
        assertEquals(NovaAction.ConfirmPending, parse("haan"))
        assertEquals(NovaAction.ConfirmPending, parse("theek hai"))
        assertEquals(NovaAction.CancelPending, parse("nahi"))
        assertEquals(NovaAction.CancelPending, parse("rehne do"))
    }

    // --- Coexistence ---------------------------------------------------------------------

    @Test
    fun `English commands are untouched`() {
        assertEquals(NovaAction.OpenApp("youtube"), parse("open youtube"))
        assertEquals(NovaAction.CloseApp("whatsapp"), parse("close whatsapp"))
        assertEquals(NovaAction.SetFlashlight(on = true), parse("turn on the flashlight"))
        assertEquals(NovaAction.CallContact("Amit"), parse("call Amit"))
    }

    @Test
    fun `a mixed sentence works, because that is how people actually speak`() {
        assertEquals(
            LevelChange.Relative(10),
            (parse("volume thoda badhao") as NovaAction.SetVolume).level,
        )
        assertEquals(NovaAction.OpenApp("youtube"), parse("youtube open karo"))
    }
}
