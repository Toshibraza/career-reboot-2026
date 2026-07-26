package com.nova.core.agent.comms

/** A person Nova could reach. */
data class Contact(val name: String, val number: String)

/**
 * Something Nova has offered to do and is waiting to be told to carry out.
 *
 * Only ever created for actions that cannot be taken back — placing a call, sending a message.
 * Everything else in Nova executes immediately, because turning on a torch is undoable and
 * asking permission for it would be theatre.
 */
sealed interface PendingAction {

    val contact: Contact
    val createdAt: Long

    data class Call(
        override val contact: Contact,
        override val createdAt: Long,
    ) : PendingAction

    data class Sms(
        override val contact: Contact,
        val message: String,
        override val createdAt: Long,
    ) : PendingAction
}

/**
 * Holds at most one proposal, and forgets it after a while.
 *
 * Expiry is the point. Without it a "yes" said an hour later — to a person in the room, to a
 * podcast, to nothing — would place a call the user never asked for. A confirmation only means
 * anything while the question is still fresh in their mind.
 */
class ConfirmationSlot(
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var pending: PendingAction? = null

    fun offer(action: PendingAction) {
        pending = action
    }

    /** The live proposal, or null when there is none or it has gone stale. */
    fun take(): PendingAction? {
        val current = pending ?: return null
        pending = null

        return if (clock() - current.createdAt > expiryMillis) null else current
    }

    fun peek(): PendingAction? = pending?.takeIf { clock() - it.createdAt <= expiryMillis }

    fun clear() {
        pending = null
    }

    companion object {
        /**
         * Long enough to hear the question and answer it, short enough that a stray "yes"
         * later in a conversation cannot reach it.
         */
        const val DEFAULT_EXPIRY_MILLIS = 60_000L
    }
}
