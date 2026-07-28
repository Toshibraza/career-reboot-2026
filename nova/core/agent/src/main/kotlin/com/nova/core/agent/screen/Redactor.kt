package com.nova.core.agent.screen

/**
 * Masks secrets in screen text before it is sent to a model.
 *
 * Raza's cloud planner is given the screen so it can decide what to tap. That screen is whatever
 * the user happens to be looking at, which on a phone routinely includes a one-time code, a card
 * number, or someone's email address — posted verbatim to a third party, in the app whose stated
 * reason for existing is that things stay on the device.
 *
 * Adapted from isair/jarvis, which scrubs the same shapes before anything is written to disk.
 * Its rules are tuned for shell output and log files; these are tuned for what appears on a
 * phone, where a verification code matters far more than an AWS key.
 *
 * ### What this is and is not
 *
 * Structural pattern matching, not comprehension. It catches things with a recognisable shape
 * and will miss a secret written in prose. It is a floor, not a guarantee — the guarantee is
 * installing a local model, which sends nothing anywhere.
 */
object Redactor {

    private val rules: List<Pair<Regex, String>> = listOf(
        // Email addresses. A real cost is accepted here: a planner asked to tap an address in
        // a list can no longer see it. Rare, the user can tap it themselves, and it is a poor
        // trade against handing someone's contacts to an API.
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[email]",

        // Card numbers, including the spaced and hyphenated forms a payment screen shows.
        Regex("""\b(?:\d[ -]*?){13,19}\b""") to "[card number]",

        // Key shapes. Unlikely on a phone screen, but they have no false positives and a
        // developer reading a dashboard in Chrome is not a strange thing to be doing.
        Regex("""\bsk-[A-Za-z0-9\-_]{20,}""") to "[api key]",
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b""") to "[token]",
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""") to "[aws key]",
        Regex("""\bAIza[0-9A-Za-z_\-]{35}\b""") to "[api key]",
        Regex("""\beyJ[0-9A-Za-z._\-]{20,}""") to "[token]",

        // Written-out credentials: "password: hunter2", "token = abc123".
        Regex(
            """\b(pass(?:word)?|passcode|secret|token|api[_ ]?key)\b\s*[:=]\s*\S+""",
            RegexOption.IGNORE_CASE,
        ) to "$1: [redacted]",
    )

    /**
     * Codes are matched by the words around them rather than by shape.
     *
     * Four to eight digits on their own is also a price, a year, a step count and a flight
     * number. Requiring "OTP", "code" or "PIN" nearby is what separates a secret from a
     * number, and both orders occur in the wild — "your OTP is 448210" and "448210 is your
     * verification code".
     */
    private val codeBeforeKeyword = Regex(
        """\b\d{4,8}\b(?=[^\n]{0,24}\b(?:otp|code|pin|passcode|verification)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val codeAfterKeyword = Regex(
        """(?<=\b(?:otp|code|pin|passcode|verification)\b)([^\n]{0,24}?)\b\d{4,8}\b""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(text: String): String {
        var scrubbed = text

        for ((pattern, replacement) in rules) {
            scrubbed = pattern.replace(scrubbed, replacement)
        }

        scrubbed = codeBeforeKeyword.replace(scrubbed, "[code]")
        scrubbed = codeAfterKeyword.replace(scrubbed) { match ->
            match.groupValues[1] + "[code]"
        }

        return scrubbed
    }
}
