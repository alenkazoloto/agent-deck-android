package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileRunSelection
import dev.agentdeck.companion.data.conversationWaiting
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.RunChoiceSummary
import dev.agentdeck.companion.ui.workingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PLAN-MOBILE-REDESIGN M2: the conversation names what it waits on, and the run line its values. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ConversationWaitingTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("convo-composer-pills")!!
    private val hello = fixture.hello!!

    private fun show(running: Boolean, waiting: String?) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = fixture.screen as Screen.Conversation,
                        page = fixture.transcript!!.copy(running = running),
                        loading = false,
                        cached = false,
                        draft = "",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        waiting = waiting,
                    )
                }
            }
        }
    }

    @Test
    fun `a blocked run says what it waits on instead of working`() {
        show(running = true, waiting = "Waiting for tool permission")
        compose.onNodeWithText("Waiting for tool permission").assertIsDisplayed()
        compose.onNodeWithText(AgentVendor.CLAUDE.workingText()).assertDoesNotExist()
    }

    @Test
    fun `a waiting chat whose page is not running still says so`() {
        show(running = false, waiting = "Waiting for your answer")
        compose.onNodeWithText("Waiting for your answer").assertExists()
    }

    @Test
    fun `an unblocked run keeps its working line`() {
        show(running = true, waiting = null)
        compose.onNodeWithText(AgentVendor.CLAUDE.workingText()).assertIsDisplayed()
    }

    @Test
    fun `only a row held waiting on you names a wait`() {
        val row = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!.rows.first()
        assertNull(conversationWaiting(row.copy(attention = SessionAttentionState.RUNNING, waitingReason = WaitingReason.PERMISSION)))
        assertEquals(
            "Waiting for plan approval",
            conversationWaiting(row.copy(attention = SessionAttentionState.WAITING_ON_YOU, waitingReason = WaitingReason.PLAN_APPROVAL)),
        )
        assertEquals("Waiting on you", conversationWaiting(row.copy(attention = SessionAttentionState.WAITING_ON_YOU, waitingReason = null)))
    }

    @Test
    fun `the run line spells each offered value and only chosen toggles`() {
        assertEquals(
            "Default model · Default effort · Default mode",
            RunChoiceSummary.of(hello, AgentVendor.CLAUDE, MobileRunSelection(null, null, null)),
        )
        val chosen = RunChoiceSummary.of(hello, AgentVendor.CLAUDE, MobileRunSelection("opus", "max", null, fastMode = true, thinking = false))
        assertEquals(true, chosen.startsWith("Opus 5 · Max effort · Default mode"))
    }
}
