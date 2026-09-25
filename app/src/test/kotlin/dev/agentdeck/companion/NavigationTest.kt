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
    fun `a restored conversation always has a route to chats`() {
        val restored = Navigation.fromJson(Navigation.toJson(conversation))!!
        assertEquals(listOf(Screen.Fleet), Navigation.restoredBackStack(restored))
        assertEquals(Screen.Fleet, Navigation.backTarget(restored, emptyList()))
        assertEquals(Screen.Fleet, Navigation.backTarget(Screen.NewChat, emptyList()))
    }

    @Test
    fun `back respects the real origin and root destinations still leave the app`() {
        assertEquals(Screen.Scheduled, Navigation.backTarget(conversation, listOf(Screen.Scheduled)))
        listOf(Screen.Fleet, Screen.Settings, Screen.Scheduled, Screen.Pair).forEach {
            assertTrue(Navigation.restoredBackStack(it).isEmpty())
            assertNull(Navigation.backTarget(it, emptyList()))
        }
    }

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

    /**
     * The Usage page is a *page* of Settings: it survives process death like Settings does, it
     * belongs to that tab, it gives the width back to its own back arrow, and Back out of it
     * lands on Settings even when the app was killed and it was restored straight from a link.
     */
    @Test
    fun `the usage page is a page of Settings, not a fourth destination`() {
        assertEquals(Screen.Usage, Navigation.fromJson(Navigation.toJson(Screen.Usage)))
        assertEquals(DeepLink.Usage, Navigation.parse("agentdeck://usage"))
        assertEquals(Destination.SETTINGS, Navigation.destinationOf(Screen.Usage))
        assertFalse(Navigation.showsBar(Screen.Usage))
        assertEquals(Screen.Settings, Navigation.backTarget(Screen.Usage, emptyList()))
    }

    @Test
    fun `a conversation belongs to the fleet tab, not to a fourth one`() {
        assertEquals(Destination.FLEET, Navigation.destinationOf(conversation))
        assertEquals(Destination.FLEET, Navigation.destinationOf(Screen.NewChat))
        assertEquals(Destination.SCHEDULED, Navigation.destinationOf(Screen.Scheduled))
        assertEquals(Destination.SETTINGS, Navigation.destinationOf(Screen.Settings))
        assertEquals(Destination.REVIEW, Navigation.destinationOf(Screen.Review))
        assertNull(Navigation.destinationOf(Screen.Pair))
    }

    @Test
    fun `four roots in reading order, labelled as the redesign names them`() {
        assertEquals(listOf("Chats", "Review", "Schedule", "Workspace"), Destination.entries.map { it.label })
        Destination.entries.forEach { destination ->
            val root = Navigation.screenOf(destination)
            assertEquals(destination, Navigation.destinationOf(root))
            assertTrue(Navigation.showsBar(root))
            assertTrue(Navigation.restoredBackStack(root).isEmpty())
        }
    }

    @Test
    fun `review is a restorable root whose link older installs refuse rather than misroute`() {
        assertEquals(Screen.Review, Navigation.fromJson(Navigation.toJson(Screen.Review)))
        assertEquals(DeepLink.Review, Navigation.parse("agentdeck://review"))
        // It reads the `review` capability, so a restore straight onto it owes a hello.
        assertTrue(Navigation.needsHello(Screen.Review))
    }

    @Test
    fun `screens persisted by the three-tab app restore onto the renamed roots`() {
        // 1.35 wrote these exact strings; the redesign renames labels, never the stored links.
        fun stored(link: String) = com.google.gson.JsonObject().apply {
            addProperty("v", 1)
            addProperty("link", link)
        }
        assertEquals(Screen.Scheduled, Navigation.fromJson(stored("agentdeck://scheduled")))
        assertEquals(Destination.SCHEDULED, Navigation.destinationOf(Screen.Scheduled))
        assertEquals(Screen.Settings, Navigation.fromJson(stored("agentdeck://settings")))
        assertEquals("Workspace", Navigation.destinationOf(Screen.Settings)!!.label)
        assertEquals(Screen.Usage, Navigation.fromJson(stored("agentdeck://usage")))
        assertEquals(Screen.Fleet, Navigation.fromJson(stored("agentdeck://fleet")))
    }

    @Test
    fun `a task opened from Review keeps Review lit, a restored one falls back to Chats`() {
        assertEquals(Destination.REVIEW, Navigation.destinationOf(conversation, listOf(Screen.Review)))
        assertEquals(Destination.SCHEDULED, Navigation.destinationOf(conversation, listOf(Screen.Scheduled)))
        assertEquals(Destination.FLEET, Navigation.destinationOf(conversation, emptyList()))
        // Usage is a page of Workspace wherever it was pushed from.
        assertEquals(Destination.SETTINGS, Navigation.destinationOf(Screen.Usage, listOf(Screen.Fleet)))
    }

    @Test
    fun `bottom bar drops its words only where Workspace no longer fits`() {
        assertTrue(Navigation.labelsBar(1.0f))
        assertTrue(Navigation.labelsBar(1.3f))
        assertFalse(Navigation.labelsBar(1.5f))
        assertFalse(Navigation.labelsBar(2.0f))
    }
}
