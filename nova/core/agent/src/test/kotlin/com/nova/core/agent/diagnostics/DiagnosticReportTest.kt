package com.nova.core.agent.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {

    private fun ok(name: String) = Check(name, CheckStatus.OK, "granted")
    private fun broken(name: String, detail: String) = Check(name, CheckStatus.NEEDS_ACTION, detail)
    private fun optional(name: String, detail: String) = Check(name, CheckStatus.OPTIONAL, detail)

    @Test
    fun `a healthy report says so plainly`() {
        val report = DiagnosticReport(listOf(ok("Microphone"), ok("Accessibility")))
        assertEquals("Everything looks fine.", report.spoken())
    }

    @Test
    fun `optional checks are not problems`() {
        // "No API key" is a choice, not a fault — everyday commands work without one, and
        // nagging about it would train the user to ignore the report.
        val report = DiagnosticReport(
            listOf(ok("Microphone"), optional("Multi-step tasks", "no model or API key")),
        )
        assertEquals("Everything looks fine.", report.spoken())
    }

    @Test
    fun `one problem is phrased singly`() {
        val report = DiagnosticReport(
            listOf(ok("Microphone"), broken("Accessibility", "my accessibility service is off")),
        )
        assertEquals(
            "One thing needs attention. my accessibility service is off",
            report.spoken(),
        )
    }

    @Test
    fun `several problems are counted and named`() {
        val report = DiagnosticReport(
            listOf(
                broken("Microphone", "I can't hear you"),
                broken("Accessibility", "I can't tap"),
                ok("Notifications"),
            ),
        )

        val spoken = report.spoken()
        assertTrue(spoken.startsWith("2 things need attention."))
        assertTrue("I can't hear you" in spoken)
        assertTrue("I can't tap" in spoken)
    }

    @Test
    fun `a long list is capped and the rest counted`() {
        // Spoken aloud, eight failures in a row is noise. The count keeps it honest without
        // reciting everything.
        val report = DiagnosticReport((1..6).map { broken("Check $it", "problem $it") })

        val spoken = report.spoken(limit = 2)
        assertTrue(spoken.startsWith("6 things need attention."))
        assertTrue("And 4 more." in spoken)
        assertTrue("problem 3" !in spoken)
    }

    @Test
    fun `problems lists only what needs action`() {
        val report = DiagnosticReport(
            listOf(ok("A"), optional("B", "off"), broken("C", "broken")),
        )
        assertEquals(listOf("C"), report.problems.map { it.name })
    }
}
