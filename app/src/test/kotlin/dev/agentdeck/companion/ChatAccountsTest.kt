package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import dev.agentdeck.companion.data.ChatAccounts
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.fixture.DeckFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAccountsTest {

    private val personal = MobileScheduleAccountOption("default", "Personal")
    private val work = MobileScheduleAccountOption("work", "Work team")
    private val workshop = MobileScheduleAccountOption("shop", "Workshop")

    private fun hello(caps: Set<String>, accounts: Map<AgentVendor, List<MobileScheduleAccountOption>>): MobileHello =
        DeckFixtures.byName("convo-composer-pills")!!.hello!!.let { it.copy(capabilities = caps.toList(), accounts = accounts) }

    @Test
    fun `an agent's accounts are offered only when the machine lists several`() {
        val caps = setOf(MobileProtocol.Capability.ACCOUNTS)
        val both = mapOf(AgentVendor.CLAUDE to listOf(personal, work), AgentVendor.CODEX to listOf(personal))
        assertEquals(listOf(personal, work), ChatAccounts.offered(hello(caps, both), AgentVendor.CLAUDE))
        assertTrue(ChatAccounts.offered(hello(caps, both), AgentVendor.CODEX).isEmpty())
        assertTrue(ChatAccounts.offered(hello(emptySet(), both), AgentVendor.CLAUDE).isEmpty())
        assertTrue(ChatAccounts.offered(null, AgentVendor.CLAUDE).isEmpty())
    }

    @Test
    fun `a name finds the exact label before a prefix and a prefix before a substring`() {
        val rows = listOf(workshop, work, personal)
        assertEquals(work, ChatAccounts.find(rows, " work team "))
        assertEquals(workshop, ChatAccounts.find(rows, "WORK"))
        assertEquals(personal, ChatAccounts.find(rows, "sonal"))
        assertNull(ChatAccounts.find(rows, "billing"))
        assertNull(ChatAccounts.find(rows, "  "))
    }

    @Test
    fun `the new chat opens in the chat's project and agent on the picked account and drops the old agent's picks`() {
        val last = NewChatTarget("/last", AgentVendor.CODEX, model = "gpt-5", effort = "high")
        assertEquals(
            NewChatTarget("/repo", AgentVendor.CLAUDE, accountId = "work"),
            ChatAccounts.target(last, "/repo", AgentVendor.CLAUDE, "work"),
        )
        assertEquals(
            NewChatTarget("/last", AgentVendor.CODEX, model = "gpt-5", effort = "high", accountId = "work"),
            ChatAccounts.target(last, " ", null, "work"),
        )
    }
}
