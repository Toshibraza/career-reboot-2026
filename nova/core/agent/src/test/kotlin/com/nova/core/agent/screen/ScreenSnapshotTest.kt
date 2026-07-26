package com.nova.core.agent.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSnapshotTest {

    private val settings = ScreenSnapshot(
        packageName = "com.android.settings",
        appLabel = "Settings",
        elements = listOf(
            ScreenElement("Settings", ElementRole.TEXT, clickable = false),
            ScreenElement("Search", ElementRole.TEXT_FIELD, clickable = true, editable = true),
            ScreenElement("Wi-Fi", ElementRole.BUTTON, clickable = true),
            ScreenElement("Bluetooth", ElementRole.CHECKABLE, clickable = true, checked = false),
            ScreenElement("Battery", ElementRole.BUTTON, clickable = true),
            ScreenElement("", ElementRole.IMAGE, clickable = false),
        ),
    )

    @Test
    fun `actionable skips inert text`() {
        val labels = settings.actionable().map { it.label }
        assertEquals(listOf("Search", "Wi-Fi", "Bluetooth", "Battery"), labels)
    }

    @Test
    fun `spoken summary names the app and what can be tapped`() {
        assertEquals(
            "Settings. I can see Search, Wi-Fi, Bluetooth, Battery.",
            settings.spokenSummary(),
        )
    }

    @Test
    fun `spoken summary stays short`() {
        // Reading fifty labels aloud is useless; anyone wanting the full picture is looking
        // at the screen already.
        val crowded = ScreenSnapshot(
            packageName = "x",
            appLabel = "Crowded",
            elements = (1..50).map { ScreenElement("Item $it", ElementRole.BUTTON, clickable = true) },
        )
        val summary = crowded.spokenSummary(limit = 3)
        assertEquals("Crowded. I can see Item 1, Item 2, Item 3.", summary)
    }

    @Test
    fun `comma-joined content descriptions are trimmed to their useful part`() {
        // Verbatim from WhatsApp on a real device. Read back inside a comma-separated list,
        // the full descriptions are unparseable mush.
        val whatsapp = ScreenSnapshot(
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            elements = listOf(
                ScreenElement("Ask Meta AI or Search", ElementRole.BUTTON, clickable = true),
                ScreenElement("All filter, , selected", ElementRole.BUTTON, clickable = true),
                ScreenElement("Unread filter, 26, unselected", ElementRole.BUTTON, clickable = true),
                ScreenElement("Groups filter, 9, unselected", ElementRole.BUTTON, clickable = true),
            ),
        )
        assertEquals(
            "WhatsApp. I can see Ask Meta AI or Search, All filter, Unread filter, Groups filter.",
            whatsapp.spokenSummary(),
        )
    }

    @Test
    fun `badge counts are not read aloud`() {
        // From YouTube: a bare "9+" notification badge is a separate node and says nothing
        // useful to someone listening.
        val youtube = ScreenSnapshot(
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            elements = listOf(
                ScreenElement("Notifications", ElementRole.BUTTON, clickable = true),
                ScreenElement("9+", ElementRole.TEXT, clickable = true),
                ScreenElement("Search", ElementRole.BUTTON, clickable = true),
            ),
        )
        assertEquals("YouTube. I can see Notifications, Search.", youtube.spokenSummary())
    }

    @Test
    fun `very long labels are capped`() {
        val wordy = ScreenSnapshot(
            packageName = "x",
            appLabel = "App",
            elements = listOf(
                ScreenElement("a".repeat(80), ElementRole.BUTTON, clickable = true),
            ),
        )
        assertTrue(wordy.spokenSummary().length < 60)
    }

    @Test
    fun `unreadable screen says so rather than pretending`() {
        val blank = ScreenSnapshot("com.bank.secure", "Bank", emptyList())
        assertEquals("I can't read this screen.", blank.spokenSummary())
    }

    @Test
    fun `screen with nothing tappable is distinguished from unreadable`() {
        val readOnly = ScreenSnapshot(
            packageName = "com.reader",
            appLabel = "Reader",
            elements = listOf(ScreenElement("Chapter one", ElementRole.TEXT, clickable = false)),
        )
        assertEquals("Reader. Nothing I can tap here.", readOnly.spokenSummary())
    }

    @Test
    fun `prompt rendering labels kinds and drops blank elements`() {
        val prompt = settings.toPrompt()
        assertEquals(
            """
            App: Settings
            - text: Settings
            - field: Search
            - button: Wi-Fi
            - button: Bluetooth [off]
            - button: Battery
            """.trimIndent(),
            prompt,
        )
    }

    @Test
    fun `prompt rendering is capped`() {
        val crowded = ScreenSnapshot(
            packageName = "x",
            appLabel = "Crowded",
            elements = (1..100).map { ScreenElement("Item $it", ElementRole.BUTTON, clickable = true) },
        )
        // Header plus the cap — screen dumps are large and every wasted token is one not
        // spent on reasoning.
        assertEquals(11, crowded.toPrompt(maxElements = 10).lines().size)
    }

    @Test
    fun `prompt omits coordinates so plans name what to press`() {
        val withBounds = ScreenSnapshot(
            packageName = "x",
            appLabel = "App",
            elements = listOf(
                ScreenElement("Send", ElementRole.BUTTON, clickable = true, bounds = Bounds(0, 0, 100, 50)),
            ),
        )
        assertTrue("100" !in withBounds.toPrompt())
    }
}
