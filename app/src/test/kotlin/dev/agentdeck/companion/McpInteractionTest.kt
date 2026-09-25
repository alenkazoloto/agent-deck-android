package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileMcpHealth
import com.github.claudeagents.core.mobile.MobileMcpProject
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

    private fun show(
        capable: Boolean = true,
        check: (suspend (String?) -> MobileMcpHealth?)? = null,
        answer: suspend (String?) -> MobileMcpServers?,
    ) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            val extra = setOfNotNull(MobileProtocol.Capability.MCP_SERVERS, MobileProtocol.Capability.MCP_HEALTH.takeIf { check != null })
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
