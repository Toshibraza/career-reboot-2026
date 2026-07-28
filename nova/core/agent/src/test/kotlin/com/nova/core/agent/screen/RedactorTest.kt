package com.nova.core.agent.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {

    @Test
    fun `a one-time code is masked whichever side the keyword falls`() {
        // Both orders are ordinary. An SMS says one, a banking screen says the other.
        assertFalse("448210" in Redactor.redact("Your OTP is 448210"))
        assertFalse("448210" in Redactor.redact("448210 is your verification code"))
        assertFalse("9921" in Redactor.redact("Enter PIN 9921"))
    }

    @Test
    fun `numbers with no code word nearby are left alone`() {
        // Four to eight digits is also a price, a year and a flight number. Masking those
        // would strip the screen of the detail a planner needs to act on it.
        val screen = "Flight AI2024 departs 1830, gate 22, seat 14C, total 4599"

        assertEquals(screen, Redactor.redact(screen))
    }

    @Test
    fun `card numbers are masked in the forms a payment screen shows them`() {
        assertFalse("4111" in Redactor.redact("4111 1111 1111 1111"))
        assertFalse("4111" in Redactor.redact("4111-1111-1111-1111"))
        assertFalse("4111" in Redactor.redact("Card ending 4111111111111111"))
    }

    @Test
    fun `email addresses are masked`() {
        val redacted = Redactor.redact("Signed in as toshibraza786@gmail.com")

        assertFalse("toshibraza786" in redacted)
        assertTrue("[email]" in redacted)
    }

    @Test
    fun `key shapes are masked`() {
        // Fixtures spell out that they are fake. A realistic-looking key in a test file trips
        // every secret scanner that touches this repository, including the one guarding pushes.
        assertTrue("[api key]" in Redactor.redact("sk-EXAMPLE-NOT-A-REAL-KEY-00000000"))
        assertTrue("[token]" in Redactor.redact("ghp_EXAMPLENOTAREALTOKEN0000"))
        assertTrue("[aws key]" in Redactor.redact("AKIAEXAMPLENOTAREAL0"))
    }

    @Test
    fun `written-out credentials keep the label and lose the value`() {
        val redacted = Redactor.redact("password: hunter2")

        // The label is left so a planner still knows a password field is on screen; only the
        // value goes.
        assertTrue("password" in redacted)
        assertFalse("hunter2" in redacted)
    }

    @Test
    fun `ordinary screens survive untouched`() {
        // Over-redaction is a real cost, not a free safety margin: every masked label is one
        // the planner can no longer tap.
        val screen = """
            App: YouTube
            - button: Search
            - button: Home
            - text: Trending in India
        """.trimIndent()

        assertEquals(screen, Redactor.redact(screen))
    }

    @Test
    fun `the prompt sent to a model is redacted`() {
        // The point of the whole file. This is the string that goes over the network.
        val snapshot = ScreenSnapshot(
            packageName = "com.google.android.apps.messaging",
            appLabel = "Messages",
            elements = listOf(
                ScreenElement("Your OTP is 448210", ElementRole.TEXT, clickable = false),
                ScreenElement("Reply", ElementRole.BUTTON, clickable = true),
            ),
        )

        val prompt = snapshot.toPrompt()

        assertFalse(prompt, "448210" in prompt)
        // Still usable: the planner can see there is a Reply button.
        assertTrue(prompt, "Reply" in prompt)
    }

    @Test
    fun `speaking a screen aloud is not redacted`() {
        // Audio stays in the room. Reading "your code is redacted" to someone who asked for
        // their code would be a bug, not caution.
        val snapshot = ScreenSnapshot(
            packageName = "com.google.android.apps.messaging",
            appLabel = "Messages",
            elements = listOf(
                ScreenElement("Your OTP is 448210", ElementRole.BUTTON, clickable = true),
            ),
        )

        assertTrue("448210" in snapshot.spokenSummary())
    }
}
