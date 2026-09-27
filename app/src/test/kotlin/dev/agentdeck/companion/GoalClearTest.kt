package dev.agentdeck.companion

import dev.agentdeck.companion.data.ComposerStashes
import dev.agentdeck.companion.data.GoalClear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalClearTest {
    private val key = "conversation-1"

    @Test
    fun `an empty composer receives the clear command`() {
        val drafts = GoalClear.prefill(emptyMap(), key, NOW)!!
        assertEquals("/goal clear", drafts[key])
        assertEquals(emptyList<Any>(), ComposerStashes.entries(drafts, key))
    }

    @Test
    fun `a typed message is parked on the stash, not overwritten`() {
        val drafts = GoalClear.prefill(mapOf(key to "also check the retries"), key, NOW)!!
        assertEquals("/goal clear", drafts[key])
        assertEquals(listOf("also check the retries"), ComposerStashes.entries(drafts, key).map { it.text })
    }

    @Test
    fun `a second tap changes nothing`() {
        assertNull(GoalClear.prefill(mapOf(key to "/goal clear"), key, NOW))
    }

    @Test
    fun `another conversation's draft is left alone`() {
        val drafts = GoalClear.prefill(mapOf("other" to "keep me"), key, NOW)!!
        assertEquals("keep me", drafts["other"])
    }

    private companion object {
        const val NOW = 1_780_000_000_000L
    }
}
