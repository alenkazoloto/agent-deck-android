package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The desk's goal bar on the phone: a Claude chat with an armed `/goal` says so above the composer,
 * and Clear hands the reader the command rather than sending it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class GoalStripInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private var cleared = 0

    private fun show(fixture: String) {
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
                        onClearGoal = { cleared += 1 },
                    )
                }
            }
        }
    }

    @Test
    fun `an armed goal is named above the composer`() {
        show("convo-goal")
        compose.onNodeWithText("Goal").assertExists()
        compose.onNodeWithText("every test in the payments module passes", substring = true).assertExists()
    }

    @Test
    fun `Clear asks for the command and sends nothing itself`() {
        show("convo-goal")
        compose.onNodeWithContentDescription("Clear goal", substring = true).performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `a chat with no goal draws no strip`() {
        show("convo-claude")
        compose.onNodeWithText("Goal").assertDoesNotExist()
    }
}
