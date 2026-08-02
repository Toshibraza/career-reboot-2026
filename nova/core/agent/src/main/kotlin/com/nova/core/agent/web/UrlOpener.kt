package com.nova.core.agent.web

/**
 * Hands a link to whatever the user browses or watches with.
 *
 * An interface here rather than an Android intent at the call site, so the two things that need
 * it — playing media and searching without an API token — stay testable and stay out of the
 * platform modules.
 */
interface UrlOpener {

    /** False when nothing on the device claimed the link. */
    suspend fun open(url: String): Boolean
}

/** Percent-encoding for a spoken phrase going into a query string. */
fun String.asQueryParameter(): String = buildString {
    for (byte in this@asQueryParameter.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        when {
            char.isLetterOrDigit() && byte.toInt() in 0..127 -> append(char)
            char == '-' || char == '_' || char == '.' || char == '~' -> append(char)
            char == ' ' -> append('+')
            else -> append('%').append("%02X".format(byte))
        }
    }
}
