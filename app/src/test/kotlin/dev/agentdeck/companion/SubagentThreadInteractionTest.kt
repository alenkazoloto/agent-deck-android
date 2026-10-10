package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSubagentThread
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SubagentThreadFrame
import dev.agentdeck.companion.ui.SubagentThreads
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Task/Agent row opens the thread it ran — only where the machine advertises `subagent-threads` — as a read-only
 * sheet of the machine's own turns; a delegation inside it opens the next thread naming its owner, Back returns,
 * and a failed re-read keeps what was already read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SubagentThreadInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Pair<String, String?>>()
    private var refreshed = 0
    private var backed = 0
    private var closed = 0
    private val frames = mutableStateOf(emptyList<SubagentThreadFrame>())

    private val task = MobileToolCall("toolu_task", "Task", "Task", "look around", MobileToolCall.OK, subagent = true)
    private val bash = MobileToolCall("toolu_bash", "Bash", "Bash: ls", "ls", MobileToolCall.OK)

    private fun show(capable: Boolean = true) {
        val base = DeckFixtures.byName("settings")!!.hello!!
        val hello = if (capable) base.copy(capabilities = base.capabilities + MobileProtocol.Capability.SUBAGENT_THREADS) else base
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(MobileTurn("t1", "assistant", "Delegating.", DeckFixtures.NOW, toolCalls = listOf(task, bash))),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page = page, loading = false, cached = false, draft = "", notice = null,
                    onDraft = {}, onSend = { _, _ -> }, onStop = {}, onDismissNotice = {},
                    hello = hello,
                    subagentThreads = SubagentThreads(
                        frames = frames.value,
                        onOpen = { call, owner -> opened += call.id to owner },
                        onBack = { backed++ },
                        onRefresh = { refreshed++ },
                        onClose = { closed++ },
                    ),
                )
            }
        }
        compose.onNodeWithText("Tool calls (2)").performClick()
    }

    private val nestedCall = MobileToolCall("toolu_nested", "Agent", "Agent", "dig", MobileToolCall.OK, subagent = true)

    private val thread = MobileSubagentThread(
        callId = "toolu_task", agentId = "a1", agentType = "Explore", description = "look around",
        turns = listOf(
            MobileTurn("s0", "user", "look", DeckFixtures.NOW),
            MobileTurn("s1", "assistant", "Found the seam.", DeckFixtures.NOW, toolCalls = listOf(nestedCall)),
        ),
    )

    @Test
    fun `a delegating row offers its thread and an ordinary row does not`() {
        show()
        compose.onNodeWithText("Open thread ›").assertIsDisplayed()
        compose.onNodeWithText("Task").performClick()
        assertEquals(listOf("toolu_task" to null), opened)
        compose.onAllNodesWithText("Open thread ›").assertCountEquals(1)
    }

    @Test
    fun `a machine that does not advertise threads gets an ordinary row`() {
        show(capable = false)
        compose.onAllNodesWithText("Open thread ›").assertCountEquals(0)
    }

    @Test
    fun `the sheet reads the thread, opens a nested delegation through its owner and goes back`() {
        show()
        frames.value = listOf(SubagentThreadFrame("toolu_task", null, thread, loading = false))
        compose.onNodeWithText("Explore · look around").assertIsDisplayed()
        compose.onNodeWithText("Found the seam.").assertIsDisplayed()

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onAllNodesWithText("Open thread ›").assertCountEquals(2)
        compose.onAllNodesWithText("Agent")[0].performClick()
        assertEquals("the call sits in agent a1's own file, so a1 owns it", listOf("toolu_nested" to "a1"), opened)

        frames.value = frames.value + SubagentThreadFrame("toolu_nested", "a1")
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backed)
        compose.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun `a failed re-read keeps the thread and says why`() {
        show()
        frames.value = listOf(SubagentThreadFrame("toolu_task", null, thread, loading = false, error = "The machine is not answering."))
        compose.onNodeWithText("Found the seam.").assertIsDisplayed()
        compose.onNodeWithText("The machine is not answering.").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshed)
    }

    @Test
    fun `a first read that failed says why and can be tried again`() {
        show()
        frames.value = listOf(SubagentThreadFrame("toolu_task", null, null, loading = false, error = "This subagent left no thread on the machine."))
        compose.onNodeWithText("Subagent thread").assertIsDisplayed()
        compose.onNodeWithText("This subagent left no thread on the machine.").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshed)
    }
}
