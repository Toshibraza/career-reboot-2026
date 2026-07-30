package com.nova.core.llm

import java.io.IOException

/** The call budget was already spent. */
class RateLimitedException(val until: String) : IOException("Rate limit reached, resets $until")

/**
 * Caps how many model calls can be made per hour and per day.
 *
 * A single multi-step task makes up to eight calls, and a planner that misreads a screen can
 * burn all of them getting nowhere. That is a few tenths of a cent once — and a bill if
 * something loops unattended, or if a routine fires a task on a schedule.
 *
 * Two windows rather than one, because they catch different failures: the hourly cap stops a
 * runaway loop this afternoon, and the daily cap stops a slow leak nobody notices for a week.
 *
 * Only the cloud client is wrapped. On-device inference costs nothing but time, and rationing
 * it would be pure friction.
 */
class RateLimitedChatClient(
    private val delegate: ChatClient,
    private val perHour: Int = DEFAULT_PER_HOUR,
    private val perDay: Int = DEFAULT_PER_DAY,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatClient {

    private val calls = ArrayDeque<Long>()

    override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String {
        val now = clock()

        synchronized(calls) {
            // Anything older than the widest window can never matter again.
            while (calls.isNotEmpty() && now - calls.first() > DAY) calls.removeFirst()

            val inLastHour = calls.count { now - it <= HOUR }
            if (inLastHour >= perHour) throw RateLimitedException("in an hour")
            if (calls.size >= perDay) throw RateLimitedException("tomorrow")

            // Counted before the call, not after. A request that fails still cost something
            // and, more importantly, a failing request retried in a loop is exactly the
            // runaway this exists to stop.
            calls.addLast(now)
        }

        return delegate.complete(system, user, schema)
    }

    /** Calls left in the tighter of the two windows. For diagnostics. */
    fun remaining(): Int = synchronized(calls) {
        val now = clock()
        val inLastHour = calls.count { now - it <= HOUR }
        val inLastDay = calls.count { now - it <= DAY }
        minOf(perHour - inLastHour, perDay - inLastDay).coerceAtLeast(0)
    }

    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR

        /**
         * Generous for a person, tight for a loop. Around a dozen full multi-step tasks an
         * hour — far more than anyone issues by voice, and far less than a stuck agent would.
         */
        const val DEFAULT_PER_HOUR = 100
        const val DEFAULT_PER_DAY = 1000
    }
}
