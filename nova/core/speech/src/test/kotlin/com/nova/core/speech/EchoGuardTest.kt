package com.nova.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {

    private var now = 1_000_000L
    private val guard = EchoGuard(clock = { now })

    @Test
    fun `hearing back exactly what was just said is an echo`() {
        guard.spoke("I've opened YouTube for you.")

        assertTrue(guard.isEcho("I've opened YouTube for you"))
    }

    @Test
    fun `a mangled transcript of the reply is still an echo`() {
        // The microphone never returns the reply cleanly. Words drop, casing goes, and the
        // recogniser guesses at the rest — which is why this compares word overlap rather
        // than looking for the sentence.
        guard.spoke("I've opened YouTube for you.")

        assertTrue(guard.isEcho("opened you tube for you"))
        assertTrue(guard.isEcho("I opened YouTube for you now"))
    }

    @Test
    fun `a real command is not an echo`() {
        guard.spoke("I've opened YouTube for you.")

        assertFalse(guard.isEcho("turn on the flashlight"))
        assertFalse(guard.isEcho("call my brother"))
    }

    @Test
    fun `short commands are always let through`() {
        // The moment a user most needs to be heard is while Raza is still talking, and "stop"
        // is short enough to collide with a reply by chance. Refusing to judge is the safer
        // error — a stop command that gets swallowed is a worse bug than one echo getting in.
        guard.spoke("Stop. I have cancelled that for you.")

        assertFalse(guard.isEcho("stop"))
        assertFalse(guard.isEcho("stop it"))
    }

    @Test
    fun `the window closes so a later reply is judged on its own`() {
        guard.spoke("I've opened YouTube for you.")

        now += 5_000

        // Said five seconds later, this is a person repeating themselves, not a speaker
        // still ringing. Rejecting it would make Raza deaf to anyone who echoes its phrasing.
        assertFalse(guard.isEcho("I've opened YouTube for you"))
    }

    @Test
    fun `nothing is an echo before anything has been said`() {
        assertFalse(guard.isEcho("open youtube"))
    }

    @Test
    fun `clearing forgets the last utterance`() {
        guard.spoke("I've opened YouTube for you.")
        guard.clear()

        assertFalse(guard.isEcho("I've opened YouTube for you"))
    }

    @Test
    fun `a command that borrows a word from the reply still gets through`() {
        // Partial overlap is normal and must not be enough. "Open YouTube" after "I've opened
        // YouTube" is a user asking again, probably because they did not hear it.
        guard.spoke("I've opened YouTube for you.")

        assertFalse(guard.isEcho("open YouTube music instead"))
    }

    @Test
    fun `blank input is never an echo`() {
        guard.spoke("Done.")

        assertFalse(guard.isEcho(""))
        assertFalse(guard.isEcho("   "))
    }
}
