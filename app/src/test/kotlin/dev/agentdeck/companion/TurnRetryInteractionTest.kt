package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.OutgoingQueue
import dev.agentdeck.companion.data.OutgoingSend
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M59: a chat that ended on a server error or a dropped stream offers the desk's Retry under that
 * failure, and only there. It sends the words the machine put on the turn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class TurnRetryInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val retried = mutableListOf<String>()
    private val state = DeckFixtures.byName("convo-retry")!!
    private val target = state.screen as Screen.Conversation

    private fun show(
        page: MobileTranscriptPage = state.transcript!!,
        queued: List<OutgoingSend> = emptyList(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = target,
                        page = page,
                        loading = false,
                        cached = false,
                        draft = "",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        queued = queued,
                        onRetryTurn = { retried += it },
                    )
                }
            }
        }
    }

    @Test fun `Retry under the failure sends the machine's words`() {
        show()
        compose.onNodeWithTag("turn-retry").performScrollTo().performClick()

        assertEquals(listOf(state.transcript!!.turns.last().retryPrompt), retried)
    }

    @Test fun `a running chat offers no Retry`() {
        show(state.transcript!!.copy(running = true))
        compose.onNodeWithTag("turn-retry").assertDoesNotExist()
    }

    /** A second tap would send the sentence twice: once it is owed to the machine, the button is gone. */
    @Test fun `a resend already owed offers no second Retry`() {
        show(
            queued = listOf(
                OutgoingSend(
                    clientMessageId = "retry-1", key = target.key, projectPath = target.projectPath,
                    vendor = AgentVendor.CLAUDE, label = target.title, prompt = "Continue where you left off.",
                ),
            ),
        )
        compose.onNodeWithTag("turn-retry").assertDoesNotExist()
    }

    @Test fun `a failure something answered offers no Retry`() {
        val answered = state.transcript!!.turns + MobileTurn("t3", "assistant", "Picked it back up.", DeckFixtures.NOW)
        show(state.transcript!!.copy(turns = answered))
        compose.onNodeWithText("Picked it back up.", substring = true).assertExists()
        compose.onNodeWithTag("turn-retry").assertDoesNotExist()
    }

    @Test fun `a failure the machine did not mark retryable offers no Retry`() {
        val turns = state.transcript!!.turns
        show(state.transcript!!.copy(turns = turns.dropLast(1) + turns.last().copy(retryPrompt = null)))
        compose.onNodeWithTag("turn-retry").assertDoesNotExist()
    }

    /** An older plugin sends no `retryPrompt`; the turn decodes without one and nothing is offered. */
    @Test fun `the wire carries the prompt and an older machine's turn has none`() {
        val turn = state.transcript!!.turns.last()
        assertEquals(turn.retryPrompt, MobileTurn.fromJson(turn.toJson()).retryPrompt)
        assertEquals(null, MobileTurn.fromJson(turn.copy(retryPrompt = null).toJson()).retryPrompt)
    }
}
