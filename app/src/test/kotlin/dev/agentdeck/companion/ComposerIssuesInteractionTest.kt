package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileIssue
import com.github.claudeagents.core.mobile.MobileIssueList
import com.github.claudeagents.core.mobile.MobileIssueQuery
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * P15: the composer's `#` issue/PR popup against the real `ConversationScreen`, with and without
 * the `issues` capability, and the caret rule it shares with the machine. `MainActivity` passing
 * `DeckViewModel.searchIssues` into the screen is not covered: no test drives `ScreenHost`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerIssuesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val queries = mutableListOf<String>()
    private var unavailable = false
    private var rows = listOf(
        MobileIssue("123", "Crash when the tunnel drops", state = "open", provider = "github"),
        MobileIssue("98", "Retry the upload", pullRequest = true, state = "merged", resolved = true, provider = "github"),
        MobileIssue("DECK-7", "Phone composer parity", state = "In Progress", provider = "youtrack"),
    )

    @Test
    fun `a hash lists the machine's issues and accepting one writes its id after the hash`() {
        show(listOf(MobileProtocol.Capability.ISSUES))

        compose.onNode(hasSetTextAction()).performTextReplacement("fix #cra")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        // One announcement per row: kind, id, summary, state (TalkBack reads it once).
        compose.onNodeWithContentDescription("Issue #123, Crash when the tunnel drops, open").assertExists()
        compose.onNodeWithContentDescription("Pull request #98, Retry the upload, merged").assertExists()
        assertEquals("cra", queries.last())

        compose.onNodeWithContentDescription("Issue #123", substring = true).performClick()
        // `#123`, never `##123`: the typed `#` stays and the id follows it.
        compose.runOnIdle { assertEquals("fix #123 ", draft.value) }
        compose.onNodeWithContentDescription("Issue #123", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a tracker id is written as the tracker spells it`() {
        show(listOf(MobileProtocol.Capability.ISSUES))

        compose.onNode(hasSetTextAction()).performTextReplacement("#DECK")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.onNodeWithContentDescription("Issue DECK-7", substring = true).performClick()
        compose.runOnIdle { assertEquals("#DECK-7 ", draft.value) }
    }

    @Test
    fun `no rows means no popup, as on the desk`() {
        rows = emptyList()
        show(listOf(MobileProtocol.Capability.ISSUES))

        compose.onNode(hasSetTextAction()).performTextReplacement("#12")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("did not answer", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a tracker that did not answer says so`() {
        rows = emptyList()
        unavailable = true
        show(listOf(MobileProtocol.Capability.ISSUES))

        compose.onNode(hasSetTextAction()).performTextReplacement("#12")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.onNodeWithText("The issue tracker did not answer. Keep typing to ask again.").assertExists()
    }

    @Test
    fun `a hash inside a word or before a space asks nothing`() {
        show(listOf(MobileProtocol.Capability.ISSUES))

        compose.onNode(hasSetTextAction()).performTextReplacement("see foo#12")
        compose.onNode(hasSetTextAction()).performTextReplacement("# heading")
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), queries)
    }

    @Test
    fun `a machine that does not serve issues never asks`() {
        show(capabilities = emptyList())

        compose.onNode(hasSetTextAction()).performTextReplacement("#cra")
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), queries)
        compose.onNodeWithContentDescription("Issue #123", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the caret rule matches the desk's`() {
        assertEquals(MobileIssueQuery.Active(0, ""), MobileIssueQuery.activeAt("#", 1))
        assertEquals(MobileIssueQuery.Active(4, "12"), MobileIssueQuery.activeAt("fix #12", 7))
        assertNull(MobileIssueQuery.activeAt("foo#12", 6))
        assertNull(MobileIssueQuery.activeAt("##1", 3))
        assertNull(MobileIssueQuery.activeAt("#-x", 3))
        assertNull(MobileIssueQuery.activeAt("#12 done", 8))
        val (text, caret) = MobileIssueQuery.accept("a #1 b", MobileIssueQuery.Active(2, "1"), MobileIssue("123", "x"))
        assertEquals("a #123  b", text)
        assertEquals(7, caret)
        assertEquals("#123", MobileIssue("123", "").display)
        assertEquals("DECK-7", MobileIssue("DECK-7", "").display)
    }

    /** Not a golden: the rendered popup as evidence, written under `build/outputs/p15/`. */
    @Test
    fun `render the issue popup`() {
        show(listOf(MobileProtocol.Capability.ISSUES))
        compose.onNode(hasSetTextAction()).performTextReplacement("fix #")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/p15/issue-popup.png", RECORD)
    }

    private fun show(capabilities: List<String>) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests", turns = emptyList(), hasMore = false,
            costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { _, _ -> },
                    onStop = {},
                    onDismissNotice = {},
                    hello = MobileHello(
                        protocolVersion = MobileProtocol.VERSION,
                        machineName = "desk",
                        ideName = "IDEA",
                        pluginVersion = "1.0",
                        capabilities = capabilities,
                    ),
                    onSearchIssues = { query ->
                        queries += query
                        MobileIssueList("/project", query, rows, unavailable)
                    },
                )
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
