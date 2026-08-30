package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three navigation decisions that used to be inside the view model, where nothing could
 * see them: where back goes, what a link addresses, and what survives process death.
 */
class NavigationTest {

    private val conversation = Screen.Conversation(
        key = "claude|/Users/dev/My Plugin|9f2c",
        title = "Fix the flaky pairing test",
        vendor = AgentVendor.CODEX,
        projectPath = "/Users/dev/My Plugin",
    )

    @Test
    fun `a conversation link survives the round trip, key and all`() {
        // The key is structural and carries `|` and spaces; a link that did not encode it
        // would address a different conversation, or none.
        val link = Navigation.link(conversation)!!
        val parsed = Navigation.parse(link) as DeepLink.Conversation
        assertEquals(conversation.key, parsed.key)
        assertEquals(conversation.title, parsed.title)
        assertEquals(AgentVendor.CODEX, parsed.vendor)
        assertEquals(conversation.projectPath, parsed.projectPath)
    }

    @Test
    fun `a bare conversation link is enough`() {
        // What a notification is *required* to carry. Everything else is enrichment from the
        // snapshot, which is newer than a link tapped a day later.
        val parsed = Navigation.parse("agentdeck://conversation/abc") as DeepLink.Conversation
        assertEquals("abc", parsed.key)
        assertNull(parsed.title)
        assertNull(parsed.vendor)
    }

    @Test
    fun `anything that is not ours is refused`() {
        listOf(
            null,
            "",
            "https://example.com/conversation/abc",
            "agentdeck://conversation/",
            "agentdeck://nowhere",
        ).forEach { assertNull("should not parse: $it", Navigation.parse(it)) }
    }

    @Test
    fun `back pops the stack rather than always returning to the fleet`() {
        // The bug this replaces: `back()` set Screen.Fleet from everywhere, so a conversation
        // opened from Scheduled dropped the user somewhere they had never been.
        val stack = listOf(Screen.Scheduled, conversation)
        assertEquals(listOf(Screen.Scheduled), Navigation.popped(stack))
        assertNull("a root destination hands the gesture to the system", Navigation.popped(listOf(Screen.Fleet)))
        assertNull(Navigation.popped(emptyList()))
    }

    @Test
    fun `a conversation is restored after process death, a half-typed new chat is not`() {
        val restored = Navigation.fromJson(Navigation.toJson(conversation))
        assertEquals(conversation, restored)
        assertNull(Navigation.toJson(Screen.NewChat))
        assertNull(Navigation.toJson(Screen.Pair))
        assertEquals(Screen.Settings, Navigation.fromJson(Navigation.toJson(Screen.Settings)))
    }

    @Test
    fun `the bar hides exactly where a composer or a back arrow needs the room`() {
        assertTrue(Navigation.showsBar(Screen.Fleet))
        assertTrue(Navigation.showsBar(Screen.Scheduled))
        assertTrue(Navigation.showsBar(Screen.Settings))
        assertFalse(Navigation.showsBar(conversation))
        assertFalse(Navigation.showsBar(Screen.NewChat))
        assertFalse(Navigation.showsBar(Screen.Pair))
    }

    @Test
    fun `the launcher shortcut's link opens the composer and is never restored into it`() {
        // `res/xml/shortcuts.xml` hardcodes this URI — a launcher shortcut can only address an
        // app by Intent, so the string in that file has to keep parsing.
        assertEquals(DeepLink.NewChat, Navigation.parse("agentdeck://new"))
        // And the composer stays out of what survives process death: a restore that landed on
        // an empty new chat would put the user in a screen they never opened.
        assertNull(Navigation.fromJson(Navigation.toJson(Screen.NewChat)))
    }

    @Test
    fun `a conversation belongs to the fleet tab, not to a fourth one`() {
        assertEquals(Destination.FLEET, Navigation.destinationOf(conversation))
        assertEquals(Destination.FLEET, Navigation.destinationOf(Screen.NewChat))
        assertEquals(Destination.SCHEDULED, Navigation.destinationOf(Screen.Scheduled))
        assertEquals(Destination.SETTINGS, Navigation.destinationOf(Screen.Settings))
        assertNull(Navigation.destinationOf(Screen.Pair))
    }
}
