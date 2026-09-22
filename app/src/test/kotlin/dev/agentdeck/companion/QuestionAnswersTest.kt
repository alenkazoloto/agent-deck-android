package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
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
 * PLAN-MOBILE-REDESIGN P16: every question of an ask is answered, in one `/v1/answer`.
 *
 * Before, the first tap on any question sent that question alone and locked the card, so a
 * two-question ask resumed with its second question unanswered and a multi-select question
 * could carry only one label.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class QuestionAnswersTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<Map<String, String>>()

    private fun show(fixture: String, answerFailures: Int = 0) {
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
                        canAnswer = true,
                        onAnswer = { sent += it },
                        answerFailures = answerFailures,
                    )
                }
            }
        }
    }

    private fun tap(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test
    fun `a lone single-choice question still answers on its tap`() {
        show("convo-question")
        tap("Pin the clock only")
        assertEquals(listOf(mapOf("What should the flaky-test fix cover?" to "Pin the clock only")), sent)
    }

    @Test
    fun `several questions collect every pick and send them together`() {
        show("convo-questions-multi")
        tap("Pin the clock and the pairing window")
        assertTrue("a pick on one question must not send the ask", sent.isEmpty())
        compose.onNodeWithText("Send answers").performScrollTo().assertIsNotEnabled()
        tap("Goldens")
        tap("Pairing")
        tap("Outgoing queue")
        tap("Outgoing queue")
        compose.onNodeWithText("Send answers").performScrollTo().assertIsEnabled().performClick()
        assertEquals(
            listOf(
                mapOf(
                    "What should the flaky-test fix cover?" to "Pin the clock and the pairing window",
                    // The ask's own option order, whatever order they were tapped in.
                    "Which suites should it re-run?" to "Pairing, Goldens",
                ),
            ),
            sent,
        )
        compose.onNodeWithText("Send answers").assertDoesNotExist()
    }

    @Test
    fun `a refused answer lifts the lock and keeps the picks`() {
        var failures = 0
        val state = DeckFixtures.byName("convo-questions-multi")!!
        val failuresState = androidx.compose.runtime.mutableIntStateOf(0)
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
                        canAnswer = true,
                        onAnswer = { sent += it },
                        answerFailures = failuresState.intValue,
                    )
                }
            }
        }
        tap("Pin the clock only")
        tap("Pairing")
        tap("Send answers")
        compose.onNodeWithText("Send answers").assertDoesNotExist()
        failures++
        failuresState.intValue = failures
        compose.onNodeWithText("Send answers").performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
    }
}
