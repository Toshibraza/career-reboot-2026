package com.nova.core.agent.diagnostics

/** One thing that either works or doesn't. */
data class Check(
    val name: String,
    val status: CheckStatus,
    /** Said aloud when the check is not [CheckStatus.OK] — so it must name the fix. */
    val detail: String,
)

enum class CheckStatus {
    OK,

    /** Works, but degraded or absent by choice — not something to nag about. */
    OPTIONAL,

    /** Something the user has to do before a capability works at all. */
    NEEDS_ACTION,
}

/**
 * Everything Raza can tell about its own health.
 *
 * Borrowed from zclaw's `get_diagnostics`: an assistant that acts on your phone through half a
 * dozen separately-granted permissions will spend most of its failures on "something is
 * switched off somewhere". Without this the only way to find out is a developer with a USB
 * cable, which is exactly how every problem in this project was diagnosed.
 */
data class DiagnosticReport(val checks: List<Check>) {

    val problems: List<Check> get() = checks.filter { it.status == CheckStatus.NEEDS_ACTION }

    /**
     * One line to say out loud.
     *
     * Leads with the count, then names the problems — a spoken report that buries the failures
     * among the passes is no better than silence. Healthy checks are not read out at all;
     * they are visible on screen for anyone who wants them.
     */
    fun spoken(limit: Int = 3): String {
        if (problems.isEmpty()) return "Everything looks fine."

        val named = problems.take(limit).joinToString(". ") { it.detail }
        val rest = problems.size - limit

        val lead = if (problems.size == 1) "One thing needs attention" else "${problems.size} things need attention"
        return if (rest > 0) "$lead. $named. And $rest more." else "$lead. $named"
    }
}
