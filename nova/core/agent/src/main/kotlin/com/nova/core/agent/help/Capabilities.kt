package com.nova.core.agent.help

/**
 * What Raza can do, in the user's words.
 *
 * Every one of these documents lists a `help` command, and it is the one thing a voice assistant
 * most needs and least often has. On a screen you can read the buttons; spoken, there is nothing
 * to look at, so an assistant that cannot say what it does can only be used by whoever built it.
 *
 * Phrased as things to say rather than features. "Screen reading via the accessibility node
 * tree" tells a user nothing; "what's on screen" is a sentence they can repeat.
 *
 * This is a hand-written list and it will drift from the rules if nobody maintains it. That is
 * accepted deliberately: generating it from the rule patterns would produce regex fragments, not
 * English, and a wrong-but-readable list is more useful out loud than a correct unreadable one.
 * There is a test that fails if the counts here stop matching the rule engine's real coverage.
 */
object Capabilities {

    data class Group(val title: String, val examples: List<String>)

    val groups: List<Group> = listOf(
        Group(
            "Apps and getting around",
            listOf("Open YouTube", "Close WhatsApp", "Go home", "Show my notifications"),
        ),
        Group(
            "This phone",
            listOf(
                "Turn on the flashlight",
                "Set volume to 40 percent",
                "Increase brightness",
                "Lock the screen",
            ),
        ),
        Group(
            "Playing and looking things up",
            listOf("Play Coke Studio", "Search for train times", "What is the capital of France"),
        ),
        Group(
            "Remembering",
            listOf("Remember my parking spot is B4", "Where did I park", "Forget my parking spot"),
        ),
        Group(
            "People",
            listOf("Call Amit", "Text Amit saying I'm late"),
        ),
        Group(
            "Reminders",
            listOf("Remind me to call the bank at 6", "Every day at 8 am say good morning"),
        ),
        Group(
            "Reading the screen",
            listOf("What's on screen", "What does this say", "Tap Search"),
        ),
        Group(
            "In Hinglish too",
            listOf("YouTube kholo", "Awaaz kam karo", "Amit ko call karo"),
        ),
    )

    /**
     * The spoken answer.
     *
     * Reading forty examples aloud is worse than reading none — by the fourth the listener has
     * forgotten the first. So this names the areas and offers a handful of openers, and points
     * at the screen for the rest, which is where a long list actually works.
     */
    fun spoken(): String {
        val areas = groups.dropLast(1).joinToString(", ") { it.title.lowercase() }
        return "I can help with $areas. Try \"open YouTube\", \"remind me to call the bank at 6\", " +
            "or just ask me a question. There's a full list on screen."
    }

    /** Everything, for the eyes rather than the ears. */
    fun listed(): List<Group> = groups
}
