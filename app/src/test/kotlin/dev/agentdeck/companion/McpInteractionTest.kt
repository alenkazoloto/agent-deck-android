package dev.agentdeck.companion

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileMcpAdd
import com.github.claudeagents.core.mobile.MobileMcpHealth
import com.github.claudeagents.core.mobile.MobileMcpProject
import com.github.claudeagents.core.mobile.MobileMcpRemove
import com.github.claudeagents.core.mobile.MobileMcpServer
import com.github.claudeagents.core.mobile.MobileMcpServers
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.mcpCredentialLine
import dev.agentdeck.companion.ui.mcpServerLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Resources › "MCP servers" is the desk's server tables, read-only: offered only by a machine
 * that advertises `mcp-servers`, read again each time it opens and when another project is picked, every
 * state a word, and never more than the machine's redacted description.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String?>()

    private val figma = MobileMcpServer("claude", "figma", "user", "http", "https://mcp.figma.com", secretKeys = listOf("Authorization"))
    private val db = MobileMcpServer("claude", "db", "project", "stdio", "npx", secretKeys = listOf("DB_PASSWORD", "DB_USER"))
    private val docs = MobileMcpServer("codex", "docs", transport = "http", target = "https://docs.example.com", status = "Signed in")
    private val old = MobileMcpServer("codex", "old", transport = "stdio", target = "srv", status = "Off — requires approval")
    private val alpha = MobileMcpProject("/work/alpha", "alpha")
    private val beta = MobileMcpProject("/work/beta", "beta")

    private val checkedFor = mutableListOf<String?>()
    private val removed = mutableListOf<MobileMcpRemove>()
    private val added = mutableListOf<MobileMcpAdd>()

    private fun show(
        capable: Boolean = true,
        check: (suspend (String?) -> MobileMcpHealth?)? = null,
        remove: (suspend (MobileMcpRemove) -> String?)? = null,
        add: (suspend (MobileMcpAdd) -> String?)? = null,
        answer: suspend (String?) -> MobileMcpServers?,
    ) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            val extra = setOfNotNull(
                MobileProtocol.Capability.MCP_SERVERS,
                MobileProtocol.Capability.MCP_HEALTH.takeIf { check != null },
                MobileProtocol.Capability.MCP_REMOVE.takeIf { remove != null },
                MobileProtocol.Capability.MCP_ADD.takeIf { add != null },
            )
            base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + extra))
        } else {
            base
        }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {},
                    onLoadMcpServers = { project -> asked += project; answer(project) },
                    onCheckMcpHealth = { project -> checkedFor += project; check?.invoke(project) },
                    onRemoveMcpServer = { request -> removed += request; remove?.invoke(request) },
                    onAddMcpServer = { request -> added += request; add?.invoke(request) },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("MCP servers").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        show(capable = false) { null }

        assertEquals(0, compose.onAllNodesWithText("MCP servers").fetchSemanticsNodes().size)
    }

    @Test
    fun `the sheet lists both agents' servers with scope, target, status and credential names in words`() {
        show { MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma, db, docs, old)) }
        open()

        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p31/mcp-sheet.png", RECORD)
        compose.onNodeWithText("figma").assertIsDisplayed()
        compose.onNodeWithText("Claude · user scope · http · https://mcp.figma.com").assertIsDisplayed()
        compose.onNodeWithText("Credentials in Authorization").assertIsDisplayed()
        compose.onNodeWithText("Claude · project scope · stdio · npx").assertIsDisplayed()
        compose.onNodeWithText("Codex · http · https://docs.example.com").assertIsDisplayed()
        compose.onNodeWithText("Signed in").assertIsDisplayed()
        compose.onNodeWithText("Off — requires approval").assertIsDisplayed()
        assertEquals("one project, so no chips", 0, compose.onAllNodesWithText("alpha").fetchSemanticsNodes().size)
    }

    @Test
    fun `a second open project shows chips, and picking one reads that project`() {
        show { project ->
            MobileMcpServers(project ?: "/work/alpha", listOf(alpha, beta), if (project == "/work/beta") listOf(db) else listOf(figma))
        }
        open()
        compose.onNodeWithText("figma").assertIsDisplayed()

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("db").assertIsDisplayed()
        assertEquals(listOf<String?>(null, "/work/beta"), asked)
    }

    @Test
    fun `what the machine could not read is said, not shown as an empty list`() {
        show { MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma), listOf("Claude's local scope could not be read, so its servers are not listed.")) }
        open()

        compose.onNodeWithText("Claude's local scope could not be read, so its servers are not listed.").assertIsDisplayed()
        compose.onNodeWithText("figma").assertIsDisplayed()
    }

    @Test
    fun `a machine without mcp-health offers no check`() {
        show { MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Check connections").fetchSemanticsNodes().size)
    }

    @Test
    fun `opening the sheet checks nothing, and Check connections puts the CLI's words under Claude's servers only`() {
        show(check = { MobileMcpHealth("/work/alpha", listOf(MobileMcpHealth.Row("figma", "connected"), MobileMcpHealth.Row("db", "needs-auth"))) }) {
            MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma, db, docs))
        }
        open()
        assertEquals("a read must not start the machine's connections", emptyList<String?>(), checkedFor)

        compose.onNodeWithText("Check connections").performClick()
        compose.waitForIdle()

        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p31/mcp-health.png", RECORD)
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Needs authentication").assertIsDisplayed()
        assertEquals(listOf<String?>("/work/alpha"), checkedFor)
        assertEquals("Codex's own status stays its own", 1, compose.onAllNodesWithText("Signed in").fetchSemanticsNodes().size)
    }

    @Test
    fun `a check the machine could not run says so in its sentence and the button stays available`() {
        show(check = { MobileMcpHealth("/work/alpha", failure = "The machine could not check Claude's MCP connections. See Settings › MCP in the IDE.") }) {
            MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma))
        }
        open()

        compose.onNodeWithText("Check connections").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The machine could not check Claude's MCP connections. See Settings › MCP in the IDE.").assertIsDisplayed()
        compose.onNodeWithTag("mcp-check").assertIsEnabled()
    }

    @Test
    fun `a check nobody answered says so, and another project does not inherit an old answer`() {
        show(check = { null }) {
            MobileMcpServers(it ?: "/work/alpha", listOf(alpha, beta), listOf(figma))
        }
        open()

        compose.onNodeWithText("Check connections").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The machine did not answer. Try again.").assertIsDisplayed()

        compose.onNodeWithText("beta").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("The machine did not answer. Try again.").fetchSemanticsNodes().size)
    }

    @Test
    fun `a machine or phone without mcp-remove offers no Remove`() {
        show { MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Remove…").fetchSemanticsNodes().size)
    }

    @Test
    fun `Remove asks first naming the agent and scope, sends that server alone, and reads the list again`() {
        val configured = mutableListOf(figma, db, docs)
        show(remove = { request -> configured.removeAll { it.name == request.name && it.scope == request.scope }; null }) {
            MobileMcpServers("/work/alpha", listOf(alpha), configured.toList())
        }
        open()

        compose.onNodeWithTag("mcp-remove-claude-user-figma").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p31/mcp-remove.png", RECORD)
        compose.onNodeWithText("Remove \"figma\"?").assertIsDisplayed()
        compose.onNodeWithText("Removes it from Claude's user scope on the machine. Chats started afterwards will not have it; adding it back is done in the IDE.").assertIsDisplayed()
        assertEquals("nothing is sent before the confirmation", emptyList<MobileMcpRemove>(), removed)

        compose.onNodeWithTag("mcp-remove-confirm").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileMcpRemove("claude", "figma", "user", "/work/alpha")), removed)
        assertEquals(0, compose.onAllNodesWithText("figma").fetchSemanticsNodes().size)
        compose.onNodeWithText("db").assertIsDisplayed()
        assertEquals("read on open and again after the removal", listOf<String?>(null, null), asked)
    }

    @Test
    fun `Keep removes nothing`() {
        show(remove = { null }) { MobileMcpServers("/work/alpha", listOf(alpha), listOf(docs)) }
        open()

        compose.onNodeWithTag("mcp-remove-codex--docs").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Removes it from Codex's configuration on the machine. Chats started afterwards will not have it; adding it back is done in the IDE.").assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<MobileMcpRemove>(), removed)
        compose.onNodeWithText("docs").assertIsDisplayed()
    }

    @Test
    fun `a removal the machine did not make says why in its sentence and the row stays`() {
        show(remove = { "The machine could not remove that server. See Settings › MCP in the IDE." }) {
            MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma))
        }
        open()

        compose.onNodeWithTag("mcp-remove-claude-user-figma").performClick()
        compose.onNodeWithTag("mcp-remove-confirm").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The machine could not remove that server. See Settings › MCP in the IDE.").assertIsDisplayed()
        compose.onNodeWithText("figma").assertIsDisplayed()
    }

    @Test
    fun `a machine or phone without mcp-add offers no Add server`() {
        show { MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma)) }
        open()

        assertEquals(0, compose.onAllNodesWithText("Add server…").fetchSemanticsNodes().size)
    }

    @Test
    fun `Add stays off until the name and address are ones the machine accepts, and sends exactly what was typed`() {
        show(add = { null }) { MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma)) }
        open()

        compose.onNodeWithTag("mcp-add").performClick()
        compose.onNodeWithTag("mcp-add-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-add-name").performTextInput("docs-site")
        compose.onNodeWithTag("mcp-add-url").performTextInput("npx evil-server")
        compose.onNodeWithTag("mcp-add-confirm").assertIsNotEnabled()
        compose.onNodeWithText("An http:// or https:// address with no user name or password in it.").assertIsDisplayed()
        compose.onNodeWithTag("mcp-add-url").performTextClearance()
        compose.onNodeWithTag("mcp-add-url").performTextInput("https://docs.example.com/mcp")
        compose.onNodeWithTag("mcp-add-variable").performTextInput("DOCS_TOKEN")
        compose.onNodeWithTag("mcp-add-scope-local").performClick()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/outputs/p31/mcp-add.png", RECORD)
        compose.onNodeWithTag("mcp-add-confirm").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(MobileMcpAdd("claude", "docs-site", "https://docs.example.com/mcp", "local", "/work/alpha", "DOCS_TOKEN")), added)
        assertEquals("the list is read again once it is added", 2, asked.size)
        assertEquals(0, compose.onAllNodesWithTag("mcp-add-confirm").fetchSemanticsNodes().size)
    }

    @Test
    fun `a refused add keeps everything typed and shows the machine's sentence`() {
        show(add = { "A server with that name is already configured there. Pick another name." }) {
            MobileMcpServers("/work/alpha", listOf(alpha), listOf(figma))
        }
        open()

        compose.onNodeWithTag("mcp-add").performClick()
        compose.onNodeWithTag("mcp-add-codex").performClick()
        compose.onNodeWithTag("mcp-add-name").performTextInput("figma")
        compose.onNodeWithTag("mcp-add-url").performTextInput("https://mcp.figma.com/mcp")
        compose.onNodeWithTag("mcp-add-confirm").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("mcp-add-failure").assertIsDisplayed()
        compose.onNodeWithText("A server with that name is already configured there. Pick another name.").assertIsDisplayed()
        compose.onNodeWithTag("mcp-add-name").assert(hasText("figma"))
        compose.onNodeWithTag("mcp-add-url").assert(hasText("https://mcp.figma.com/mcp"))
        assertEquals(listOf(MobileMcpAdd("codex", "figma", "https://mcp.figma.com/mcp")), added)
        assertEquals("nothing was added, so no re-read", 1, asked.size)
    }

    @Test
    fun `with no project open only the user scope is offered`() {
        show(add = { null }) { MobileMcpServers() }
        open()

        compose.onNodeWithTag("mcp-add").performClick()

        compose.onNodeWithTag("mcp-add-scope-user").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("mcp-add-scope-project").fetchSemanticsNodes().size)
    }

    @Test
    fun `a machine with nothing configured says so`() {
        show { MobileMcpServers() }
        open()

        compose.onNodeWithText("No MCP servers are configured on this machine.").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }
        open()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }

    @Test
    fun `the row lines are words and omit what a server does not have`() {
        assertEquals("Claude · user scope · http · https://mcp.figma.com", mcpServerLine(figma))
        assertEquals("Codex · stdio · srv", mcpServerLine(old))
        assertEquals("Credentials in DB_PASSWORD, DB_USER", mcpCredentialLine(db))
        assertNull(mcpCredentialLine(docs))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
