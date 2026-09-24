package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileSessionSearchHit
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.MessageSearch
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Chats search lists chats found in their messages below the title hits, with the words that
 * matched, and never says "No chats match" before the messages were read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MessageSearchInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val search = mutableStateOf<MessageSearch?>(null)
    private val asked = mutableListOf<Pair<String, List<String>>>()
    private val opened = mutableListOf<String>()
    private var more = 0

    private val rows = listOf(
        row("a", "Fix the parser", DeckFixtures.NOW - 60_000),
        row("b", "Login page polish", DeckFixtures.NOW - 120_000),
        row("c", "Write the docs", DeckFixtures.NOW - 30_000),
    )

    private fun show(query: String, canSearch: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = MobileFleetSnapshot(rows, 0, emptyList(), null, DeckFixtures.NOW),
                        filter = FleetFilter(query = query),
                        sort = FleetSort.RECENT,
                        refreshing = false,
                        snoozed = emptyMap(),
                        openKey = null,
                        onFilter = {},
                        onSort = {},
                        onRefresh = {},
                        onOpen = { opened += it.key },
                        onSnooze = {},
                        onStop = {},
                        canSearchMessages = canSearch,
                        messageSearch = search.value,
                        onSearchMessages = { q, keys -> asked += q to keys },
                        onSearchMoreMessages = { more++ },
                    )
                }
            }
        }
    }

    @Test
    fun `message hits follow the title hits, quote the match and open the chat`() {
        show("login")
        compose.waitUntil(2_000) { asked.isNotEmpty() }
        assertEquals("the rows the title search did not list, newest first", "login" to listOf("c", "a"), asked.last())

        search.value = MessageSearch(
            "login", listOf("c", "a"),
            hits = listOf(MobileSessionSearchHit("a", "…the login redirect loops on Safari…")),
            scanned = 2, nextCursor = null,
        )
        compose.onNodeWithText("Login page polish").assertIsDisplayed()
        compose.onNodeWithText("In messages").assertIsDisplayed()
        compose.onNodeWithText("…the login redirect loops on Safari…").assertIsDisplayed()
        compose.onNodeWithText("Messages searched in 2 chats").assertIsDisplayed()
        compose.onNodeWithText("Fix the parser").performClick()
        compose.runOnIdle { assertEquals(listOf("a"), opened) }
    }

    @Test
    fun `no match is said only after the messages were read`() {
        show("nothing like this")
        // The typing pause: nothing asked yet, and that is not "no match".
        compose.onNodeWithText("Searching messages\u2026").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("No chats match \u201Cnothing like this\u201D") == 0)
        search.value = MessageSearch("nothing like this", listOf("c", "a", "b"), searching = true)
        compose.onNodeWithText("Searching messages…").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("No chats match “nothing like this”") == 0)

        search.value = MessageSearch("nothing like this", listOf("c", "a", "b"), scanned = 3, nextCursor = null)
        compose.onNodeWithText("No chats match “nothing like this”").assertIsDisplayed()
    }

    @Test
    fun `a bounded pass offers the older chats`() {
        show("parser")
        search.value = MessageSearch("parser", listOf("c", "b"), scanned = 1, nextCursor = 1)
        compose.onNodeWithText("Search older chats").performClick()
        compose.runOnIdle { assertEquals(1, more) }
    }

    @Test
    fun `a machine without message search is not asked`() {
        show("login", canSearch = false)
        compose.waitForIdle()
        assertTrue(asked.all { it.second.isEmpty() })
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private fun row(key: String, title: String, activityMs: Long) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = activityMs, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
    )
}
