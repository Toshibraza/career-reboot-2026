package com.nova.core.agent.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmationSlotTest {

    private val amit = Contact("Amit", "+91 98765 43210")
    private var now = 0L
    private val slot = ConfirmationSlot(expiryMillis = 60_000, clock = { now })

    @Test
    fun `nothing is pending by default`() {
        // A bare "yes" with no question asked must never reach anything.
        assertNull(slot.take())
    }

    @Test
    fun `an offer can be taken once`() {
        slot.offer(PendingAction.Call(amit, createdAt = now))

        assertEquals(PendingAction.Call(amit, 0L), slot.take())
        // Taken means spent — a second "yes" must not place a second call.
        assertNull(slot.take())
    }

    @Test
    fun `a stale confirmation is refused`() {
        // The failure this prevents: "yes" said an hour later, to a person in the room or to
        // a podcast, placing a call the user never asked for.
        slot.offer(PendingAction.Call(amit, createdAt = now))
        now += 61_000

        assertNull(slot.take())
    }

    @Test
    fun `a confirmation just inside the window still works`() {
        slot.offer(PendingAction.Call(amit, createdAt = now))
        now += 59_000

        assertEquals(PendingAction.Call(amit, 0L), slot.take())
    }

    @Test
    fun `cancelling leaves nothing to confirm`() {
        slot.offer(PendingAction.Sms(amit, "on my way", createdAt = now))
        slot.clear()

        assertNull(slot.take())
    }

    @Test
    fun `a new offer replaces the old one`() {
        // Otherwise "call Amit… no wait, text Priya… yes" would ring Amit.
        val priya = Contact("Priya", "+91 91234 56789")
        slot.offer(PendingAction.Call(amit, createdAt = now))
        slot.offer(PendingAction.Sms(priya, "running late", createdAt = now))

        assertEquals(PendingAction.Sms(priya, "running late", 0L), slot.take())
    }

    @Test
    fun `peek does not consume`() {
        slot.offer(PendingAction.Call(amit, createdAt = now))

        assertEquals(PendingAction.Call(amit, 0L), slot.peek())
        assertEquals(PendingAction.Call(amit, 0L), slot.take())
    }
}
