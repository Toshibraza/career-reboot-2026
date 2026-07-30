package com.nova.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCommandsTest {

    @Test
    fun `cancelAll stops every tracked job`() = runTest {
        val commands = ActiveCommands()
        val started = CompletableDeferred<Unit>()

        val first = launch { CompletableDeferred<Unit>().await() }
        val second = launch {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        commands.track(first)
        commands.track(second)
        started.await()

        commands.cancelAll()
        first.join()
        second.join()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }

    @Test
    fun `a finished command is not cancelled retroactively`() = runTest {
        val commands = ActiveCommands()

        val finished = launch { }
        commands.track(finished)
        finished.join()

        commands.cancelAll()

        assertFalse(finished.isCancelled)
    }

    @Test
    fun `cancelAll with nothing running is a no-op`() {
        // The stop button can be tapped while the assistant is idle; that must not throw.
        ActiveCommands().cancelAll()
    }

    @Test
    fun `commands started after a stop are unaffected`() = runTest {
        val commands = ActiveCommands()
        commands.cancelAll()

        val later = launch { }
        commands.track(later)
        later.join()

        assertFalse(later.isCancelled)
    }
}
