package com.nova.core.agent.screen

/** Screen coordinates in pixels. Kept as plain data so `:core:agent` stays free of Android. */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * What a user can do with an element, inferred from the accessibility node.
 *
 * Coarser than the platform's class names on purpose: a planner needs to know "this can be
 * pressed" and "this accepts text", not that it is a `com.miui.SomeCustomButton`.
 */
enum class ElementRole {
    BUTTON,
    TEXT_FIELD,
    TEXT,
    IMAGE,
    CHECKABLE,
    CONTAINER,
}

/** One thing on screen worth telling a planner about. */
data class ScreenElement(
    val label: String,
    val role: ElementRole,
    val clickable: Boolean,
    val editable: Boolean = false,
    val checked: Boolean? = null,
    val bounds: Bounds? = null,
)

/**
 * A structured read of what is currently on screen.
 *
 * This is Nova's eyes. It comes from the accessibility node tree rather than pixels, which
 * makes it exact where OCR would be a guess — real labels, real roles, real coordinates, no
 * transcription errors. OCR still has a place for images and camera input, but not for
 * understanding an app's own UI.
 */
data class ScreenSnapshot(
    val packageName: String?,
    val appLabel: String?,
    val elements: List<ScreenElement>,
) {
    val isEmpty: Boolean get() = elements.isEmpty()

    /** Things a plan can actually act on. */
    fun actionable(): List<ScreenElement> = elements.filter { it.clickable || it.editable }

    /**
     * A short line for speaking aloud.
     *
     * Reading fifty labels at someone is useless, so this names the app and a handful of the
     * things they could act on. Anyone wanting the full picture is looking at the screen.
     */
    fun spokenSummary(limit: Int = 6): String {
        val where = appLabel ?: packageName ?: "This screen"
        val options = actionable()
            .map { it.label.speakable() }
            // A label with no letters is a badge count like "9+" or "26". Saying those aloud
            // tells the listener nothing about what they can do.
            .filter { it.any(Char::isLetter) }
            .distinct()
            .take(limit)

        return when {
            options.isEmpty() && isEmpty -> "I can't read this screen."
            options.isEmpty() -> "$where. Nothing I can tap here."
            else -> "$where. I can see ${options.joinToString(", ")}."
        }
    }

    /**
     * Compact rendering for an LLM prompt.
     *
     * Deliberately terse: screen dumps are large, and every token spent describing chrome is a
     * token not spent on reasoning. Only actionable elements and visible text survive, and
     * coordinates are dropped — a planner should name what it wants pressed, not where, so the
     * tap stays label-based and auditable.
     *
     * Secrets are masked on the way out. This is the seam where the user's screen becomes text
     * for a model, so redacting here rather than at the call site means a future planner cannot
     * skip it by forgetting. It costs nothing for a local model and is the only thing standing
     * between a one-time code and an API when the cloud planner is in use. Speaking a screen
     * aloud goes through [spokenSummary] and is left alone — that audio never leaves the room.
     */
    fun toPrompt(maxElements: Int = 40): String {
        val header = buildString {
            append("App: ")
            append(appLabel ?: packageName ?: "unknown")
        }

        val lines = elements
            .filter { it.label.isNotBlank() }
            .take(maxElements)
            .map { element ->
                val kind = when {
                    element.editable -> "field"
                    element.clickable -> "button"
                    element.role == ElementRole.CHECKABLE -> "toggle"
                    else -> "text"
                }
                val state = element.checked?.let { if (it) " [on]" else " [off]" } ?: ""
                "- $kind: ${element.label}$state"
            }

        return Redactor.redact((listOf(header) + lines).joinToString("\n"))
    }

    private companion object {
        const val MAX_SPOKEN_LABEL = 32

        /**
         * The part of a label worth saying out loud.
         *
         * Content descriptions are routinely comma-joined state dumps — WhatsApp's filter chip
         * is literally "Unread filter, 26, unselected". Read back inside a comma-separated
         * list those become unparseable, and the leading segment is both the useful part and
         * what a user would say to tap it. Matching still sees the full label, so "tap unread"
         * keeps working.
         */
        fun String.speakable(): String =
            substringBefore(',').trim().take(MAX_SPOKEN_LABEL).trim()
    }
}
