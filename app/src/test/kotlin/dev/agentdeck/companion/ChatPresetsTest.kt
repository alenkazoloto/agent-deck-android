package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePreset
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.ChatPresets
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.fixture.DeckFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresetsTest {

    private val review = MobilePreset("Review", AgentVendor.CLAUDE, model = "opus", effort = "high", permissionMode = "plan")
    private val reviewer = MobilePreset("Code reviewer", AgentVendor.CLAUDE)
    private val codex = MobilePreset("Codex deep", AgentVendor.CODEX)

    private fun hello(caps: Set<String>, vararg rows: MobilePreset): MobileHello =
        DeckFixtures.byName("convo-composer-pills")!!.hello!!.let { it.copy(capabilities = caps.toList(), presets = rows.toList()) }

    @Test
    fun `only this agent's presets are offered and only on a machine that advertises them`() {
        val caps = setOf(MobileProtocol.Capability.AGENT_PRESETS)
        assertEquals(listOf(review), ChatPresets.offered(hello(caps, review, codex), AgentVendor.CLAUDE))
        assertTrue(ChatPresets.offered(hello(emptySet(), review), AgentVendor.CLAUDE).isEmpty())
        assertTrue(ChatPresets.offered(null, AgentVendor.CLAUDE).isEmpty())
    }

    @Test
    fun `a name finds the exact row before a prefix and a prefix before a substring`() {
        val rows = listOf(reviewer, review)
        assertEquals(review, ChatPresets.find(rows, " review "))
        assertEquals(reviewer, ChatPresets.find(rows, "code"))
        assertEquals(reviewer, ChatPresets.find(rows, "VIEWER"))
        assertNull(ChatPresets.find(rows, "deploy"))
        assertNull(ChatPresets.find(rows, "  "))
    }

    @Test
    fun `an empty cell is Default and the account is never carried`() {
        assertEquals(
            listOf(ComposerPicks.Field.MODEL to "opus", ComposerPicks.Field.EFFORT to "high", ComposerPicks.Field.MODE to "plan"),
            ChatPresets.picks(review),
        )
        assertEquals(
            listOf(ComposerPicks.Field.MODEL to null, ComposerPicks.Field.EFFORT to null, ComposerPicks.Field.MODE to null),
            ChatPresets.picks(reviewer.copy(accountId = "work")),
        )
    }
}
