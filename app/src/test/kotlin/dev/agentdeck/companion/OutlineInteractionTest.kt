package dev.agentdeck.companion

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * The outline sheet, the two end-of-transcript jumps and the pull, on the real screen.
 *
 * A `ModalBottomSheet` is its own window, so a Roborazzi capture of the conversation cannot
 * photograph it — the same reason M3's tool sheet is pinned here rather than as a golden. What
 * this asserts is the content that reached the reader and the callback each gesture fired, not
 * that a sheet exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OutlineInteractionTest {

    @get:Rule val ui = createComposeRule()

    private val started = mutableListOf<String>()
    private var refreshes = 0
    private var outlineOpen = true

    private val longPrompt = "Refactor the loader\n\nKeep the streaming path; never load a whole file."

    private fun turns(): List<MobileTurn> = listOf(
        MobileTurn("u1", "user", longPrompt, DeckFixtures.NOW),
        MobileTurn(
            "a1", "assistant", "Split it in two.", DeckFixtures.NOW,
            toolCalls = listOf(
                MobileToolCall("c1", "Read", "Read TranscriptLoader.kt", "", "ok"),
                MobileToolCall("c2", "Edit", "Edit TranscriptLoader.kt", "", "ok"),
            ),
        ),
        MobileTurn("u2", "user", "now run the tests", DeckFixtures.NOW),
        MobileTurn("a2", "assistant", "All green.", DeckFixtures.NOW),
    )

    private fun show(open: Boolean = true, body: List<MobileTurn> = turns()) {
        outlineOpen = open
        val page = MobileTranscriptPage(
            key = "conversation", title = "Refactor the loader", turns = body,
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        ui.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page = page, loading = false, cached = false, draft = "", notice = null,
                    onDraft = {}, onSend = { _, _ -> }, onStop = {}, onDismissNotice = {},
                    outlineOpen = outlineOpen,
                    onOutline = { outlineOpen = it },
                    onRefresh = { refreshes++ },
                    onStartFromPrompt = { started.add(it) },
                )
            }
        }
    }

    @Test
    fun `the outline lists the prompts, the work and the answers`() {
        show()

        // A row, not the bubble behind the sheet: the transcript holds the same words, and an
        // assertion that cannot tell the two apart would pass with no sheet at all.
        row("Refactor the loader").assertExists()
        row("now run the tests").assertExists()
        row("Read TranscriptLoader.kt").assertExists()
        ui.onNodeWithText("+1 more").assertExists()
        row("All green.").assertExists()
    }

    /** The header says what it counted, because the page is a window over the conversation. */
    @Test
    fun `the header counts the prompts and the tool calls in view`() {
        show()

        ui.onNodeWithText("2 prompts · 2 tool calls in view").assertExists()
    }

    /**
     * The row hands back the turn's *whole* text. Starting from the headline would silently
     * drop everything after the first line of the prompt that is worth re-running.
     */
    @Test
    fun `starting a new chat from a prompt carries the whole prompt`() {
        show()

        ui.onAllNodesWithContentDescription("Start a new chat from this prompt")
            .onFirst().performClick()

        ui.runOnIdle {
            assertEquals(listOf(longPrompt), started)
            assertTrue("the sheet closes behind the navigation", !outlineOpen)
        }
    }

    /** Only prompts are chapters, so only prompts carry the action. */
    @Test
    fun `an answer row offers no new chat`() {
        show()

        assertEquals(
            2,
            ui.onAllNodesWithContentDescription("Start a new chat from this prompt")
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a closed outline paints nothing`() {
        show(open = false)

        ui.onNodeWithTag("conversation-outline").assertDoesNotExist()
    }

    /** Two ends, two controls: a conversation that fits on one screen has no "above". */
    @Test
    fun `a transcript with nothing above it offers no jump`() {
        show(open = false, body = listOf(MobileTurn("only", "user", "hello", DeckFixtures.NOW)))

        ui.onNodeWithContentDescription("Oldest loaded message").assertDoesNotExist()
    }

    /**
     * A conversation opens at its tail, so the jump is there from the first frame — and it is
     * gone once it would do nothing, which is the state the *other* test above cannot reach.
     */
    @Test
    fun `the jump to the oldest message lands there and then withdraws`() {
        show(open = false, body = (0..40).map { filler(it) })

        ui.waitUntil(5_000) {
            ui.onAllNodesWithContentDescription("Oldest loaded message").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithContentDescription("Oldest loaded message").performClick()
        ui.waitUntil(5_000) {
            ui.onAllNodesWithContentDescription("Oldest loaded message").fetchSemanticsNodes().isEmpty()
        }
        ui.onNodeWithText("Passage 0", substring = true).assertIsDisplayed()
    }

    /** The pull is the only gesture that re-asks the machine for this conversation. */
    @Test
    fun `pulling the transcript down asks the machine again`() {
        show(open = false, body = (0..40).map { filler(it) })

        val list = ui.onNodeWithTag("conversation-transcript")
        // The pull only reaches the refresh from the top: anywhere else the list consumes it,
        // which is the whole point of a nested-scroll gesture.
        list.performScrollToIndex(0)
        ui.waitForIdle()
        list.performTouchInput { swipeDown(startY = centerY, endY = bottom) }

        ui.waitUntil(5_000) { refreshes > 0 }
        assertEquals(1, refreshes)
    }

    private fun row(text: String) = ui.onNode(hasText(text) and hasClickAction())

    private fun filler(index: Int) = MobileTurn(
        "turn-$index", "assistant", "Passage $index\n\n" + "Synthetic reading text. ".repeat(10),
        DeckFixtures.NOW,
    )
}
