package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.mobile.MobileDecisionRequest
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
 * PLAN-MOBILE-REDESIGN P18: a finished plan the run is parked on is approved or sent back from
 * the transcript.
 *
 * The card offers exactly the modes the machine named, carries the id of the ask it was drawn for,
 * and sends the reader's words with whichever button is tapped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class PlanCardInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private data class Decided(val requestId: String, val decision: String, val mode: String, val feedback: String)

    private val decided = mutableListOf<Decided>()
    private var failures by mutableIntStateOf(0)

    private fun show(fixture: String = "convo-plan", canDecide: Boolean = true) {
        val state = DeckFixtures.byName(fixture)!!
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = state.screen as Screen.Conversation,
                        page = state.transcript!!,
                        loading = false,
                        cached = false,
                        draft = "",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        canDecide = canDecide,
                        onDecidePlan = { id, decision, mode, feedback -> decided += Decided(id, decision, mode, feedback) },
                        decideFailures = failures,
                    )
                }
            }
        }
    }

    @Test
    fun `the card shows the plan the run is waiting on`() {
        show()
        compose.onNodeWithTag("plan-card").performScrollTo().assertExists()
        compose.onNodeWithText("Cover the flaky handshake with a test.", substring = true).assertExists()
    }

    @Test
    fun `each approval names a mode the machine offered and carries the ask's id`() {
        show()
        compose.onNodeWithText("Approve · Auto-accept edits").performScrollTo().performClick()
        assertEquals(listOf(Decided("plan-1", MobileDecisionRequest.APPROVE, "acceptEdits", "")), decided)
    }

    @Test
    fun `Supervised is a second approval, not a default`() {
        show()
        compose.onNodeWithText("Approve · Supervised").performScrollTo().performClick()
        assertEquals(listOf(Decided("plan-1", MobileDecisionRequest.APPROVE, "supervised", "")), decided)
    }

    @Test
    fun `Keep planning sends the words typed for the agent`() {
        show()
        compose.onNodeWithTag("plan-feedback").performScrollTo().performTextInput("split step 2")
        compose.onNodeWithText("Keep planning").performScrollTo().performClick()
        assertEquals(listOf(Decided("plan-1", MobileDecisionRequest.KEEP_PLANNING, "", "split step 2")), decided)
    }

    @Test
    fun `a tap locks every button until the machine answers`() {
        show()
        compose.onNodeWithText("Approve · Supervised").performScrollTo().performClick()
        compose.onNodeWithText("Approve · Auto-accept edits").assertIsNotEnabled()
        compose.onNodeWithText("Keep planning").assertIsNotEnabled()
    }

    /** A refused decision must not strand the run, nor lose what the reader wrote. */
    @Test
    fun `a refused decision lifts the lock and keeps the words`() {
        show()
        compose.onNodeWithTag("plan-feedback").performScrollTo().performTextInput("cover deletions")
        compose.onNodeWithText("Approve · Supervised").performScrollTo().performClick()

        failures += 1
        compose.waitForIdle()

        compose.onNodeWithText("Approve · Supervised").assertIsEnabled().performClick()
        assertEquals(listOf("cover deletions", "cover deletions"), decided.map { it.feedback })
    }

    @Test
    fun `no card is drawn when the owner has not switched phone decisions on`() {
        show(fixture = "convo-plan-off", canDecide = false)
        compose.onNodeWithTag("plan-card").assertDoesNotExist()
    }
}
