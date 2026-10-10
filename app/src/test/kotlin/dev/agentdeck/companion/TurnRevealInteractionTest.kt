package dev.agentdeck.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Prompt cache row's "Open in chat": the conversation lands on the message at the row's instant, loading
 * earlier history a page at a time while the match is the oldest turn held, and says so when it is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class TurnRevealInteractionTest {

    @get:Rule val ui = createComposeRule()

    private var page by mutableStateOf(pageOf(30..59, earlier = true))
    private var reveal by mutableStateOf<Long?>(null)
    private var revealed = 0
    private var earlierAsks = 0
    private val notices = mutableListOf<String>()

    /** Turn n was written at n * 1000, and says "message n". */
    private fun pageOf(range: IntRange, earlier: Boolean) = MobileTranscriptPage(
        key = "conversation", title = "Long chat",
        turns = range.map { MobileTurn("t$it", if (it % 2 == 0) "user" else "assistant", "message $it", DeckFixtures.NOW, recordAtMs = it * 1000L) },
        hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
        liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        previousCursor = if (earlier) "cursor" else null,
    )

    private fun show() = ui.setContent {
        AgentDeckTheme {
            ConversationScreen(
                target = Screen.Conversation("conversation", "Long chat", AgentVendor.CLAUDE, "/project"),
                page = page, loading = false, cached = false, draft = "", notice = null,
                onDraft = {}, onSend = { _, _ -> }, onStop = {}, onDismissNotice = {},
                canPage = true,
                onEarlier = { earlierAsks++ },
                revealAtMs = reveal,
                onRevealed = { revealed++; reveal = null },
                onCommandNotice = { notices += it },
            )
        }
    }

    @Test
    fun `lands on the first message at or after the instant, well above the tail`() {
        page = pageOf(0..59, earlier = false)
        reveal = 12_500L
        show()
        ui.waitForIdle()

        ui.onNodeWithText("message 13").assertIsDisplayed()
        assertEquals(1, revealed)
        assertEquals(0, earlierAsks)
    }

    @Test
    fun `a match that is the oldest turn held asks for earlier history, then lands once it is loaded`() {
        reveal = 12_500L
        show()
        ui.waitForIdle()

        assertEquals("the true turn may be above the oldest one held", 1, earlierAsks)
        assertEquals(0, revealed)

        page = pageOf(0..59, earlier = false)
        ui.waitForIdle()

        ui.onNodeWithText("message 13").assertIsDisplayed()
        assertEquals(1, revealed)
    }

    @Test
    fun `an instant newer than anything held says the message is gone and stays where it is`() {
        reveal = 99_000L
        show()
        ui.waitForIdle()

        assertEquals(1, revealed)
        assertEquals(listOf("That message is no longer in the history this chat can load."), notices)
    }

    @Test
    fun `a history that failed to load ends the request with a reason`() {
        reveal = 12_500L
        ui.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation("conversation", "Long chat", AgentVendor.CLAUDE, "/project"),
                    page = page, loading = false, cached = false, draft = "", notice = null,
                    onDraft = {}, onSend = { _, _ -> }, onStop = {}, onDismissNotice = {},
                    canPage = true, earlierError = "The machine did not answer.",
                    revealAtMs = reveal, onRevealed = { revealed++; reveal = null },
                    onCommandNotice = { notices += it },
                )
            }
        }
        ui.waitForIdle()

        assertEquals(1, revealed)
        assertEquals(listOf("That message could not be reached: earlier history did not load."), notices)
    }

    @Test
    fun `no request leaves the chat at its tail`() {
        show()
        ui.waitForIdle()

        ui.onNodeWithText("message 59").assertIsDisplayed()
        assertEquals(0, revealed)
    }
}
