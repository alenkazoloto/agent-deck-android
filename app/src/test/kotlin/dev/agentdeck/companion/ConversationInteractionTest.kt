package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the rendered composer and its callbacks, including the separate full-screen route. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ConversationInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val pending = mutableStateOf(emptyList<dev.agentdeck.companion.data.OutgoingSend>())
    private val notice = mutableStateOf<String?>(null)
    private val stopping = mutableStateOf(false)
    private var stops = 0
    private val sent = mutableListOf<Pair<String, Boolean>>()
    private val pageState = mutableStateOf<MobileTranscriptPage?>(null)

    /** A send this phone owes the machine — what the composer's chip and its Send button read. */
    private fun queued(prompt: String) = dev.agentdeck.companion.data.OutgoingSend(
        clientMessageId = prompt,
        key = "conversation",
        projectPath = "/project",
        vendor = AgentVendor.CLAUDE,
        label = "Fix the tests",
        prompt = prompt,
    )

    private fun show(running: Boolean = false, turns: List<MobileTurn> = emptyList(), agentName: String? = null) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests", turns = turns, hasMore = false,
            costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = running, generatedAtMs = DeckFixtures.NOW,
        )
        pageState.value = page
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    agentName = agentName,
                    page = pageState.value,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = notice.value,
                    onDraft = { draft.value = it },
                    onSend = { text, stopFirst ->
                        sent.add(text to stopFirst)
                        pending.value = pending.value + queued(text)
                    },
                    onStop = { stops++ },
                    stopping = stopping.value,
                    onDismissNotice = { notice.value = null },
                    queued = pending.value,
                    delivering = pending.value.firstOrNull()?.clientMessageId,
                )
            }
        }
    }

    @Test
    fun `the conversation running indicator disappears when the run finishes`() {
        show(running = true)
        val spinner = SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )
        compose.onNode(spinner, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { pageState.value = pageState.value!!.copy(running = false) }
        compose.onNode(spinner, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a group the machine titled reads as that title and an untitled one keeps its count`() {
        fun call(id: String) = MobileToolCall(id, "Bash", "Bash: ls", "ls", MobileToolCall.OK)
        show(
            turns = listOf(
                MobileTurn(
                    "t1", "assistant", "", DeckFixtures.NOW,
                    toolCalls = listOf(call("u1"), call("u2")), groupTitle = "Inspecting the build files",
                ),
                MobileTurn("t2", "assistant", "", DeckFixtures.NOW, toolCalls = listOf(call("u3"))),
            ),
        )

        compose.onNodeWithText("Inspecting the build files").assertIsDisplayed()
        compose.onNodeWithText("Tool calls (2)").assertDoesNotExist()
        compose.onNodeWithText("Tool calls (1)").assertIsDisplayed()
    }

    @Test
    fun `the working bubble names running subagents and folds the rest into a count`() {
        show(running = true)
        val names = listOf("Explore · Find retries", "Plan · Sequence rollout", "general-purpose · Draft migration")
        compose.runOnIdle { pageState.value = pageState.value!!.copy(subagents = 5, subagentNames = names) }
        compose.onNodeWithText("5 subagents running").assertIsDisplayed()
        names.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("+2 more").assertIsDisplayed()

        compose.runOnIdle { pageState.value = pageState.value!!.copy(subagents = 1, subagentNames = names.take(1)) }
        compose.onNodeWithText("Explore · Find retries").assertIsDisplayed()
        compose.onNodeWithText("+2 more").assertDoesNotExist()
    }

    @Test
    fun `the working bubble counts running subagents and drops the line when none run`() {
        show(running = true)
        compose.onNodeWithText("1 subagent running").assertDoesNotExist()

        compose.runOnIdle { pageState.value = pageState.value!!.copy(subagents = 1) }
        compose.onNodeWithText("1 subagent running").assertIsDisplayed()

        compose.runOnIdle { pageState.value = pageState.value!!.copy(subagents = 3) }
        compose.onNodeWithText("3 subagents running").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()

        compose.runOnIdle { pageState.value = pageState.value!!.copy(running = false) }
        compose.onNodeWithText("3 subagents running").assertDoesNotExist()
    }

    @Test
    fun `an ACP chat names its agent in the empty state, the bubble label and the working row`() {
        val answer = MobileTurn(id = "a1", role = "assistant", text = "Parsed the file.", timestampMs = DeckFixtures.NOW)
        show(agentName = "Gemini CLI")
        compose.onNodeWithText("Ask Gemini CLI to investigate, make a change, or explain the next step.").assertIsDisplayed()
        compose.onNodeWithText("Ask Claude", substring = true).assertDoesNotExist()

        compose.runOnIdle { pageState.value = pageState.value!!.copy(turns = listOf(answer), running = true) }
        compose.onNodeWithText("Gemini CLI").assertIsDisplayed()
        compose.onNodeWithText("Gemini CLI is working…").assertIsDisplayed()
        compose.onNodeWithText("Claude", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a Claude chat keeps Claude as its speaker`() {
        show(turns = listOf(MobileTurn(id = "a1", role = "assistant", text = "Done.", timestampMs = DeckFixtures.NOW)), running = true)
        compose.onNodeWithText("Claude").assertIsDisplayed()
        compose.onNodeWithText("Claude is working…").assertIsDisplayed()
    }

    @Test
    fun `a tapped Stop shows Stopping until the machine answers and cannot be tapped twice`() {
        show(running = true)

        compose.onNodeWithText("Claude is working…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops); stopping.value = true }

        compose.onNodeWithText("Stopping…").assertIsDisplayed()
        compose.onNodeWithText("Claude is working…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop").assertIsNotEnabled()

        compose.runOnIdle { stopping.value = false }
        compose.onNodeWithText("Claude is working…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
    }

    @Test
    fun `sending retains the draft and prevents its duplicate but permits a new instruction`() {
        draft.value = "Run the relevant tests."
        show()

        compose.onNodeWithContentDescription("Send").performClick()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertTextContains("Run the relevant tests.")
        compose.onNodeWithText("Sending…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("Run the relevant tests." to false), sent) }

        compose.onNode(hasSetTextAction()).performTextReplacement("Also check the Android tests.")
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Run the relevant tests." to false, "Also check the Android tests." to false),
                sent,
            )
        }
    }

    @Test
    fun `stop and send respects the pending draft while allowing an edited redirect`() {
        draft.value = "Investigate the failed test."
        pending.value = listOf(queued(draft.value))
        show(running = true)

        compose.onNodeWithContentDescription("Stop & send").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Explain the failure before editing.")
        compose.onNodeWithContentDescription("Stop & send").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Explain the failure before editing." to true), sent)
        }
    }

    @Test
    fun `quick reply composes an editable useful prompt without starting work`() {
        show()

        compose.onNodeWithText("Test changes").performClick()
        compose.onNode(hasSetTextAction())
            .assertTextContains("Run the relevant tests and report any failures.")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.runOnIdle { assertTrue("A suggestion must not send a command", sent.isEmpty()) }
    }

    @Test
    fun `failed delivery notice keeps the draft editable and dismissing it loses no text`() {
        draft.value = "Investigate the connection failure."
        show()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle {
            pending.value = emptyList()
            notice.value = "The machine could not be reached. Your message was not confirmed."
        }

        compose.onNodeWithText(notice.value!!).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Investigate the connection failure.")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
        compose.onNodeWithContentDescription("Dismiss this message").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Investigate the connection failure.")
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test
    fun `full screen editor cannot bypass a pending send but accepts an edited draft`() {
        draft.value = "Investigate the connection failure carefully. ".repeat(12)
        pending.value = listOf(queued(draft.value))
        show()

        compose.onNodeWithContentDescription("Write this in a full-screen editor").performClick()
        val inEditor = hasAnyAncestor(isDialog())
        compose.onNode(hasContentDescription("Send") and inEditor).assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and inEditor)
            .performTextReplacement("Start with the reconnect callback.")
        compose.onNode(hasContentDescription("Send") and inEditor).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Start with the reconnect callback." to false), sent)
        }
    }

    @Test
    fun `finished tall answer offers latest after scrolling up and jumps to its actual end`() {
        show(turns = listOf(tallAnswer()))
        compose.onNodeWithText("Final answer marker.").assertIsDisplayed()

        repeat(3) { compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() } }
        compose.onNodeWithText("Final answer marker.").assertIsNotDisplayed()
        compose.onNodeWithText("Latest message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Latest message").assertIsDisplayed().performClick()
        compose.onNodeWithText("Final answer marker.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Latest message").assertDoesNotExist()
    }

    @Test
    fun `streaming growth preserves the viewport when reading higher in the same tall turn`() {
        show(running = true, turns = listOf(tallAnswer().copy(streaming = true)))
        repeat(3) { compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() } }
        compose.onNodeWithContentDescription("Latest message").assertIsDisplayed()
        val before = transcriptScroll()

        compose.runOnIdle {
            val page = pageState.value!!
            val turn = page.turns.single()
            pageState.value = page.copy(
                turns = listOf(turn.copy(text = turn.text + "\n\nA new streaming update.")),
                generatedAtMs = page.generatedAtMs + 1_000,
            )
        }

        assertEquals("New text must not move a reader higher in the same turn", before, transcriptScroll(), 0.01f)
        compose.onNodeWithContentDescription("Latest message").assertIsDisplayed()
        compose.onNodeWithText("A new streaming update.").assertIsNotDisplayed()
    }

    private fun transcriptScroll(): Float = compose.onNode(hasScrollToIndexAction())
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun tallAnswer() = MobileTurn(
        id = "answer", role = "assistant", timestampMs = DeckFixtures.NOW,
        text = (1..30).joinToString("\n\n") { index ->
            "Paragraph $index explains the connection recovery and what the tests verified."
        } + "\n\nFinal answer marker.",
    )
}
