package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.ui.ModelRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MP-11's three rules, none of which a screenshot can be asked about: what "Default" means on
 * the wire, what happens to a model the catalogue has never heard of, and what a machine that
 * never named a ladder is allowed to render.
 */
class ModelRowsTest {

    private fun hello(
        capabilities: List<String> = listOf(MobileProtocol.Capability.MODELS),
        models: Map<AgentVendor, List<MobileModelOption>> = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("sonnet", "Sonnet 5"),
                MobileModelOption("opus", "Opus 5"),
            ),
        ),
    ) = MobileHello(
        protocolVersion = MobileProtocol.VERSION,
        machineName = "workshop",
        ideName = "IntelliJ IDEA",
        pluginVersion = "1.6.0",
        capabilities = capabilities,
        models = models,
    )

    /**
     * "Default" is first and it is `null` — which is *no `model` field on the wire*, the only
     * way to say "whatever the IDE is set to" and what every phone-started chat did before
     * this control existed. A ladder of named models alone makes that unsayable.
     */
    @Test
    fun `the first row is Default and it carries no slug`() {
        val rows = ModelRows.of(hello(), AgentVendor.CLAUDE, inUse = null)
        assertEquals(ModelRows.DEFAULT_LABEL, rows.first().label)
        assertNull("Default must send no model field", rows.first().value)
        assertEquals(listOf(null, "sonnet", "opus"), rows.map { it.value })
    }

    /**
     * `memory/wiring.md`: a catalogue ported from a vendor's binary is dated evidence. A model
     * the machine no longer offers — shipped since this build, or hidden in Settings › Chat
     * models since the pick — is shown as itself rather than silently rewritten.
     */
    @Test
    fun `a model in use that the catalogue does not list survives as itself`() {
        val rows = ModelRows.of(hello(), AgentVendor.CLAUDE, inUse = "opus-6")
        assertTrue("the stranger is offered", rows.any { it.value == "opus-6" })
        assertEquals("opus-6", rows.last().value)
    }

    /** The picker is gated on the capability, never on the map: an empty ladder is an answer. */
    @Test
    fun `a machine that does not advertise the capability offers nothing`() {
        val silent = hello(capabilities = listOf(MobileProtocol.Capability.SEND))
        assertTrue(ModelRows.of(silent, AgentVendor.CLAUDE, inUse = null).isEmpty())
        assertTrue(ModelRows.of(null, AgentVendor.CLAUDE, inUse = null).isEmpty())
        assertTrue(
            "an advertised machine with no rows for this vendor is still nothing to pick",
            ModelRows.of(hello(models = emptyMap()), AgentVendor.CODEX, inUse = null).isEmpty(),
        )
    }

    /**
     * But a run already naming a model is never left unsaid. Without this the screen would
     * claim the chat is on the machine's default while the request still names `opus-6`.
     */
    @Test
    fun `a model in use is shown even where the machine advertised nothing`() {
        val silent = hello(capabilities = listOf(MobileProtocol.Capability.SEND))
        val rows = ModelRows.of(silent, AgentVendor.CLAUDE, inUse = "opus-6")
        assertEquals(listOf(null, "opus-6"), rows.map { it.value })
    }

    /** Each vendor gets its own ladder, and never the other's. */
    @Test
    fun `the ladder is per vendor`() {
        val both = hello(
            models = mapOf(
                AgentVendor.CLAUDE to listOf(MobileModelOption("opus", "Opus 5")),
                AgentVendor.CODEX to listOf(MobileModelOption("gpt-5.1-codex", "gpt-5.1-codex")),
            ),
        )
        assertEquals(
            listOf(null, "gpt-5.1-codex"),
            ModelRows.of(both, AgentVendor.CODEX, inUse = null).map { it.value },
        )
    }

    /** A slug with no label of its own is labelled by the slug, never blank. */
    @Test
    fun `a row with no label falls back to its slug`() {
        val option = MobileModelOption.fromJson(
            com.google.gson.JsonParser.parseString("""{"slug":"opus"}""").asJsonObject,
        )
        assertEquals("opus", option?.label)
    }
}
