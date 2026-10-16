package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileSessionMcpServer
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.SessionMcpSheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.SessionMcpDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Claude row's "MCP servers…" is offered only where the machine serves it (a phone holding the permission
 * grant) and never on a Codex row; the dialog lists every server with a tick, one 48dp row each, and
 * offers "Use all servers" only while the chat is limited.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionMcpInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val toggled = mutableListOf<String>()
    private var all = 0

    private fun showRows(canMcp: Boolean, vendor: AgentVendor = AgentVendor.CLAUDE) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(listOf(row("a", "Fix the parser", vendor)), 0, emptyList(), null, DeckFixtures.NOW),
                        filter = FleetFilter(),
                        sort = FleetSort.RECENT,
                        refreshing = false,
                        snoozed = emptyMap(),
                        openKey = null,
                        onFilter = {},
                        onSort = {},
                        onRefresh = {},
                        onOpen = {},
                        onSnooze = {},
                        onStop = {},
                        canMcp = canMcp,
                        onMcp = { opened += it.key },
                    )
                }
            }
        }
    }

    private fun showDialog(sheet: SessionMcpSheet) {
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                SessionMcpDialog(sheet, onToggle = { toggled += it }, onUseAll = { all++ }, onDismiss = {})
            }
        }
    }

    @Test
    fun `the row sheet offers MCP servers only where the machine serves them`() {
        showRows(canMcp = false)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("MCP servers…").assertCountEquals(0)
    }

    @Test
    fun `a Codex row never offers MCP servers`() {
        showRows(canMcp = true, vendor = AgentVendor.CODEX)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onAllNodesWithText("MCP servers…").assertCountEquals(0)
    }

    @Test
    fun `the row sheet's MCP servers asks for that chat's list`() {
        showRows(canMcp = true)
        compose.onNodeWithText("Fix the parser").performTouchInput { longClick() }
        compose.onNodeWithText("MCP servers…").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test
    fun `the dialog lists each server with its tick, and a tap flips that one`() {
        showDialog(sheet(narrowed = true))

        compose.onNodeWithText("MCP servers").assertIsDisplayed()
        compose.onAllNodesWithTag("session-mcp-row").assertCountEquals(2)
        compose.onNodeWithText("user · stdio · npx").assertIsDisplayed()
        compose.onAllNodesWithTag("session-mcp-row")[0].assertIsOn().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("session-mcp-row")[1].assertIsOff().performClick()
        compose.runOnIdle { assertEquals(listOf("beta"), toggled) }
    }

    @Test
    fun `Use all servers is offered only while the chat is limited`() {
        showDialog(sheet(narrowed = false))
        compose.onAllNodesWithTag("session-mcp-all").assertCountEquals(0)
    }

    @Test
    fun `Use all servers asks for all`() {
        showDialog(sheet(narrowed = true))
        compose.onNodeWithTag("session-mcp-all").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, all) }
    }

    @Test
    fun `the machine's refusal shows under the list and a change in flight disables the ticks`() {
        showDialog(sheet(narrowed = true, refused = "gamma is not a configured MCP server for this chat's account.", busy = true))

        compose.onNodeWithText("gamma is not a configured MCP server for this chat's account.").assertIsDisplayed()
        compose.onAllNodesWithTag("session-mcp-row")[0].assertIsNotEnabled()
        compose.onNodeWithTag("session-mcp-all").assertIsNotEnabled()
    }

    @Test
    fun `an unread list says it is being read, and no servers says so`() {
        showDialog(SessionMcpSheet("a", "Fix the parser", busy = true))
        compose.onNodeWithText("Reading this conversation…").assertIsDisplayed()
    }

    @Test
    fun `a chat with no configured server says so`() {
        showDialog(SessionMcpSheet("a", "Fix the parser", servers = emptyList()))
        compose.onNodeWithText("No MCP servers are configured.").assertIsDisplayed()
    }

    @Test
    fun `a refusal to read shows the machine's own sentence and no list`() {
        showDialog(SessionMcpSheet("a", "Fix the parser", error = "This machine does not let this phone decide tool permissions or change working directories."))
        compose.onNodeWithText("This machine does not let this phone decide tool permissions or change working directories.").assertIsDisplayed()
        compose.onAllNodesWithTag("session-mcp-row").assertCountEquals(0)
    }

    private fun sheet(narrowed: Boolean, refused: String? = null, busy: Boolean = false) = SessionMcpSheet(
        "a", "Fix the parser",
        servers = listOf(
            MobileSessionMcpServer("alpha", "user", "stdio", "npx", on = true),
            MobileSessionMcpServer("beta", "project", "http", "https://mcp.example", on = !narrowed),
        ),
        narrowed = narrowed, refused = refused, busy = busy,
    )

    private fun row(key: String, title: String, vendor: AgentVendor = AgentVendor.CLAUDE) = MobileFleetRow(
        key = key, vendor = vendor, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = DeckFixtures.NOW - 60_000, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
