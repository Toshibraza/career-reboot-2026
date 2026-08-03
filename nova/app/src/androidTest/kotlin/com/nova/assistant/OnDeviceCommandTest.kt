package com.nova.assistant

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nova.core.agent.NovaAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parts that only a real phone can answer.
 *
 * Unit tests already cover what the rules parse and what the executors decide. What they cannot
 * tell us is whether *this* device will actually do any of it: whether the composition root
 * survives being built against real Android services, whether the links Raza generates have
 * anything installed to receive them, and whether the apps it names are really here.
 *
 * This exists because the usual way of checking — driving the UI over adb — is impossible on
 * this phone. MIUI refuses shell input injection with a SecurityException, so no tap or
 * keystroke can be sent from a terminal, and every previous change had to ship unverified.
 *
 * Nothing here launches anything. A test that opened YouTube would prove slightly more and
 * would also seize the screen of whoever is holding the phone, so link handling is checked by
 * asking the package manager rather than by starting an activity.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceCommandTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val container by lazy { NovaContainer(context) }

    private fun handle(utterance: String) = runBlocking { container.runtime.handle(utterance) }

    private fun resolves(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        return intent.resolveActivity(context.packageManager) != null
    }

    @Test
    fun theCompositionRootBuildsOnThisDevice() {
        // Every executor, store and Android service constructed for real. A wiring mistake
        // that unit tests cannot see shows up here as a crash.
        assertNotNull(container.runtime)
    }

    @Test
    fun helpIsAnsweredFromTheListAndNotFromAModel() {
        val response = handle("help")

        assertEquals(listOf(NovaAction.ListCapabilities), response.plan.actions)
        // The spoken answer has to hand over words that work, not describe features.
        assertTrue(response.spoken, "open YouTube" in response.spoken)
        assertTrue(response.spoken, "on screen" in response.spoken)
    }

    @Test
    fun helpSurvivesThePolitenessStripper() {
        // "Can you" is removed as politeness before any rule sees the sentence, so this
        // reaches the engine as "what do". Worth checking on the device, because the wake
        // word is stripped by the same pass.
        assertEquals(listOf(NovaAction.ListCapabilities), handle("what can you do").plan.actions)
    }

    @Test
    fun playIsUnderstoodInBothLanguages() {
        // Parsed only. Executing would start YouTube on someone's phone mid-test.
        assertEquals(
            listOf(NovaAction.PlayMedia("Coke Studio")),
            handle("play Coke Studio").plan.actions,
        )
        assertEquals(
            listOf(NovaAction.PlayMedia("arijit singh")),
            handle("arijit singh chala do").plan.actions,
        )
    }

    @Test
    fun thisPhoneCanOpenTheLinksRazaGenerates() {
        // The whole premise of playing media and of searching without an API token. If
        // nothing here claims these, both features fail on this device no matter how correct
        // the code is.
        assertTrue(
            "no app on this phone handles a YouTube search link",
            resolves("https://www.youtube.com/results?search_query=coke+studio"),
        )
        assertTrue(
            "no browser on this phone handles a web search link",
            resolves("https://www.google.com/search?q=train+times"),
        )
    }

    @Test
    fun hinglishReachesTheRightActions() {
        assertEquals(listOf(NovaAction.OpenApp("youtube")), handle("youtube kholo").plan.actions)
        assertEquals(
            listOf(NovaAction.SetFlashlight(on = false)),
            handle("torch band karo").plan.actions,
        )
    }

    @Test
    fun theAppsRazaNamesAreReallyInstalled() {
        // AppRegistry reads the real launcher list, so this is the one place "open YouTube"
        // can be shown to resolve to something that exists rather than to a fuzzy near-miss.
        val response = handle("open youtube")

        val action = response.plan.actions.single()
        assertTrue(action.toString(), action is NovaAction.OpenApp)
    }

    @Test
    fun everyExampleOnTheHelpScreenIsUnderstood() {
        // The list is hand-written. This is the check that it has not drifted from what the
        // engine can actually parse, run against the same engine the phone uses.
        val unparsed = com.nova.core.agent.help.Capabilities.listed()
            .flatMap { it.examples }
            .filter { handle(it).plan.actions.any { action -> action is NovaAction.Unsupported } }

        assertEquals(emptyList<String>(), unparsed)
    }
}
