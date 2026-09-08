package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
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
    private val pending = mutableStateOf(emptySet<String>())
    private val notice = mutableStateOf<String?>(null)
    private val sent = mutableListOf<Pair<String, Boolean>>()
    private val pageState = mutableStateOf<MobileTranscriptPage?>(null)

    private fun show(running: Boolean = false, turns: List<MobileTurn> = emptyList()) {
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
                    page = pageState.value,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = notice.value,
                    onDraft = { draft.value = it },
                    onSend = { text, stopFirst ->
                        sent.add(text to stopFirst)
                        pending.value = pending.value + text
                    },
                    onStop = {},
                    onDismissNotice = { notice.value = null },
                    pendingPrompts = pending.value,
                )
            }
        }
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
        pending.value = setOf(draft.value)
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
            pending.value = emptySet()
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
        pending.value = setOf(draft.value)
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
        compose.onNodeWithText("Latest message").assertIsDisplayed().performClick()
        compose.onNodeWithText("Final answer marker.").assertIsDisplayed()
        compose.onNodeWithText("Latest message").assertDoesNotExist()
    }

    @Test
    fun `streaming growth preserves the viewport when reading higher in the same tall turn`() {
        show(running = true, turns = listOf(tallAnswer().copy(streaming = true)))
        repeat(3) { compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() } }
        compose.onNodeWithText("Latest message").assertIsDisplayed()
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
        compose.onNodeWithText("Latest message").assertIsDisplayed()
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
