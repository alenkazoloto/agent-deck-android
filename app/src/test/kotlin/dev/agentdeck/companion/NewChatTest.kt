package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewChatTest {

    private fun row(
        key: String,
        project: String,
        lastActivityMs: Long,
        vendor: AgentVendor = AgentVendor.CLAUDE,
    ) = MobileFleetRow(
        key = key,
        vendor = vendor,
        accountId = "default",
        projectPath = project,
        projectName = project.substringAfterLast('/'),
        gitBranch = null,
        title = key,
        attention = null,
        waitingReason = null,
        lastActivityMs = lastActivityMs,
        costUsd = 0.0,
        costKnown = true,
        contextPct = null,
        messageCount = 1,
    )

    /**
     * The machine refuses a prompt for a project it does not have open (`NO_OPEN_PROJECT`),
     * so a composer with no open project has no destination it could offer — and inventing
     * one from the fleet's own rows would produce a Start button that can only be refused.
     */
    @Test
    fun `no open project means no target at all`() {
        assertNull(NewChat.defaultTarget(emptyList(), listOf(row("a", "/p/one", 10))))
    }

    @Test
    fun `the composer opens on the project the user was most recently working in`() {
        val target = NewChat.defaultTarget(
            openProjects = listOf("/p/one", "/p/two"),
            rows = listOf(
                row("old", "/p/one", 10),
                row("newest", "/p/two", 300, AgentVendor.CODEX),
            ),
        )
        assertEquals(NewChatTarget("/p/two", AgentVendor.CODEX), target)
    }

    /** A closed project's rows are not a destination — the pick has to be open right now. */
    @Test
    fun `a recent project the IDE has since closed is not offered`() {
        val target = NewChat.defaultTarget(
            openProjects = listOf("/p/one"),
            rows = listOf(row("newest", "/p/closed", 300), row("older", "/p/one", 10)),
        )
        assertEquals("/p/one", target?.projectPath)
    }

    @Test
    fun `a previous pick is kept while it is still open and dropped when it is not`() {
        val rows = listOf(row("newest", "/p/two", 300))
        assertEquals(
            NewChatTarget("/p/one", AgentVendor.CODEX),
            NewChat.defaultTarget(
                openProjects = listOf("/p/one", "/p/two"),
                rows = rows,
                previous = NewChatTarget("/p/one", AgentVendor.CODEX),
            ),
        )
        // The same previous pick, against a machine that closed it: the composer moves rather
        // than staying aimed at a destination that can only be refused.
        assertEquals(
            "/p/two",
            NewChat.defaultTarget(
                openProjects = listOf("/p/two"),
                rows = rows,
                previous = NewChatTarget("/p/one", AgentVendor.CODEX),
            )?.projectPath,
        )
    }

    @Test
    fun `an empty machine still offers an agent to start with`() {
        assertEquals(listOf(AgentVendor.CLAUDE), NewChat.vendorOptions(emptyList()))
        assertEquals(
            listOf(AgentVendor.CLAUDE, AgentVendor.CODEX),
            NewChat.vendorOptions(
                listOf(row("a", "/p/one", 1, AgentVendor.CODEX), row("b", "/p/one", 2)),
            ),
        )
    }

    /** Falls back only when nothing else is known — never to a project that is not open. */
    @Test
    fun `an open project with no conversations yet is still a valid destination`() {
        assertEquals(
            NewChatTarget("/p/fresh", AgentVendor.CLAUDE),
            NewChat.defaultTarget(openProjects = listOf("/p/fresh"), rows = emptyList()),
        )
    }
    // ---- account (PLAN-MOBILE-PARITY M1) ------------------------------------------------

    private fun hello(
        claude: List<String> = listOf("default", "work"),
        active: String? = "work",
        advertised: Boolean = true,
    ) = MobileHello(
        protocolVersion = MobileProtocol.VERSION, machineName = "m", ideName = "IDE", pluginVersion = "1",
        capabilities = listOfNotNull(MobileProtocol.Capability.SEND, MobileProtocol.Capability.ACCOUNTS.takeIf { advertised }),
        accounts = mapOf(
            AgentVendor.CLAUDE to claude.map { MobileScheduleAccountOption(it, it.uppercase()) },
            AgentVendor.CODEX to listOf(MobileScheduleAccountOption("codex-default", "ChatGPT")),
        ),
        activeAccounts = listOfNotNull(active?.let { AgentVendor.CLAUDE to it }).toMap(),
    )

    @Test
    fun `the account picker appears at two accounts and not at one`() {
        assertEquals(listOf("default", "work"), NewChat.accountOptions(hello(), AgentVendor.CLAUDE).map { it.id })
        assertEquals(emptyList<MobileScheduleAccountOption>(), NewChat.accountOptions(hello(), AgentVendor.CODEX))
        assertEquals(
            emptyList<MobileScheduleAccountOption>(),
            NewChat.accountOptions(hello(claude = listOf("default")), AgentVendor.CLAUDE),
        )
    }

    /** An older plugin ignores the id and starts on its default; offering a pick would be a lie. */
    @Test
    fun `a machine that does not advertise accounts gets no picker and no id`() {
        val older = hello(advertised = false)
        assertEquals(emptyList<MobileScheduleAccountOption>(), NewChat.accountOptions(older, AgentVendor.CLAUDE))
        assertNull(NewChat.accountFor(older, NewChatTarget("/p", AgentVendor.CLAUDE, accountId = "work")))
        assertNull(NewChat.accountFor(null, NewChatTarget("/p")))
    }

    @Test
    fun `the request names the machine's active account until the user picks another`() {
        assertEquals("work", NewChat.accountFor(hello(), NewChatTarget("/p")))
        assertEquals("default", NewChat.accountFor(hello(), NewChatTarget("/p", accountId = "default")))
        // One account is still named, with no picker: the machine re-validates it, and naming it
        // is what keeps an updated phone on the account the desktop's + would use.
        assertEquals("codex-default", NewChat.accountFor(hello(), NewChatTarget("/p", AgentVendor.CODEX)))
    }

    /** A pick the machine no longer lists falls back rather than sending a dead id. */
    @Test
    fun `a pick the machine stopped listing falls back to the active account`() {
        val after = hello(claude = listOf("default", "other"), active = "default")
        assertEquals("default", NewChat.accountFor(after, NewChatTarget("/p", accountId = "work")))
        assertEquals("default", NewChat.accountFor(hello(active = null), NewChatTarget("/p", accountId = "gone")))
    }

    @Test
    fun `an account pick survives reopening, and never crosses to another agent`() {
        val previous = NewChatTarget("/p/one", AgentVendor.CLAUDE, accountId = "work")
        assertEquals("work", NewChat.defaultTarget(listOf("/p/one"), emptyList(), previous)?.accountId)
        assertEquals(
            "a Claude pick reached a Codex request",
            "codex-default",
            NewChat.accountFor(hello(), previous.copy(vendor = AgentVendor.CODEX)),
        )
    }

    @Test
    fun `an account at its limit says so and when it resets`() {
        val clock = { at: Long, _: Long -> "at $at" }
        assertEquals("Work", NewChat.accountLabel(MobileScheduleAccountOption("w", "Work"), 0, clock))
        assertEquals(
            "Work · limit reached, resets at 90",
            NewChat.accountLabel(MobileScheduleAccountOption("w", "Work", resetAtMs = 90), 0, clock),
        )
        assertEquals(
            "Work · weekly limit reached, resets at 90",
            NewChat.accountLabel(MobileScheduleAccountOption("w", "Work", resetAtMs = 90, weeklyLimit = true), 0, clock),
        )
    }
}
