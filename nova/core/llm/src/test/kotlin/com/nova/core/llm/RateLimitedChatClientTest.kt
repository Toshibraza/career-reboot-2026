package com.nova.core.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitedChatClientTest {

    private var now = 0L
    private var calls = 0

    private val counting = object : ChatClient {
        override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String {
            calls++
            return "ok"
        }
    }

    private fun limiter(perHour: Int = 3, perDay: Int = 5) =
        RateLimitedChatClient(counting, perHour, perDay) { now }

    private fun call(client: RateLimitedChatClient) = runBlocking { client.complete("s", "u") }

    @Test
    fun `calls pass until the hourly cap`() {
        val client = limiter()
        repeat(3) { call(client) }

        assertEquals(3, calls)
        assertThrows(RateLimitedException::class.java) { call(client) }
        // Refused, not silently swallowed — the underlying client is never reached.
        assertEquals(3, calls)
    }

    @Test
    fun `the hourly window slides`() {
        val client = limiter()
        repeat(3) { call(client) }

        now += RateLimitedChatClient.HOUR + 1
        call(client)

        assertEquals(4, calls)
    }

    @Test
    fun `the daily cap still applies after hours pass`() {
        // The point of two windows: waiting out the hour must not grant unlimited calls.
        val client = limiter(perHour = 3, perDay = 5)

        repeat(3) { call(client) }
        now += RateLimitedChatClient.HOUR + 1
        repeat(2) { call(client) }

        assertEquals(5, calls)
        now += RateLimitedChatClient.HOUR + 1
        assertThrows(RateLimitedException::class.java) { call(client) }
    }

    @Test
    fun `a failing call still counts`() {
        // Otherwise a request that always fails could be retried forever for free, which is
        // precisely the runaway this guards against.
        val failing = object : ChatClient {
            override suspend fun complete(system: String, user: String, schema: ResponseSchema?): String =
                throw RuntimeException("boom")
        }
        val client = RateLimitedChatClient(failing, perHour = 2, perDay = 9) { now }

        repeat(2) { runCatching { runBlocking { client.complete("s", "u") } } }
        assertThrows(RateLimitedException::class.java) { runBlocking { client.complete("s", "u") } }
    }

    @Test
    fun `remaining reports the tighter window`() {
        val client = limiter(perHour = 3, perDay = 5)
        assertEquals(3, client.remaining())

        call(client)
        assertEquals(2, client.remaining())
    }

    @Test
    fun `the message says when it resets`() {
        val client = limiter(perHour = 1, perDay = 9)
        call(client)

        val thrown = assertThrows(RateLimitedException::class.java) { call(client) }
        assertTrue("in an hour" in thrown.until)
    }
}
