package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** The desk's suggested-reply row on the phone: offered on a finished turn, taken into the box, hidden, retired by typing it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class PromptSuggestionInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val taken = ArrayList<String>()

    private fun show(fixture: String, draft: String = "", cached: Boolean = false, running: Boolean? = null) {
        val state = DeckFixtures.byName(fixture)!!
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = state.screen as Screen.Conversation,
                        page = state.transcript!!.let { if (running == null) it else it.copy(running = running) },
                        loading = false,
                        cached = cached,
                        draft = draft,
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        onTakeSuggestion = { taken += it },
                    )
                }
            }
        }
    }

    @Test
    fun `a finished turn offers the prediction and tapping it hands over the whole text`() {
        show("convo-suggestion")
        compose.onNodeWithContentDescription("Suggested reply: Run the upload tests and fix whatever fails").performClick()
        assertEquals(listOf("Run the upload tests and fix whatever fails"), taken)
    }

    @Test
    fun `the close button hides it and names how to turn suggestions off`() {
        show("convo-suggestion")
        compose.onNodeWithContentDescription("Hide this suggestion", substring = true).performClick()
        compose.onNodeWithContentDescription("Suggested reply", substring = true).assertDoesNotExist()
        assertEquals(emptyList<String>(), taken)
    }

    @Test
    fun `words typed that the prediction already starts with retire the row, others keep it`() {
        show("convo-suggestion", draft = "run the up")
        compose.onNodeWithContentDescription("Suggested reply", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a different draft keeps it`() {
        show("convo-suggestion", draft = "actually, stop")
        compose.onNodeWithContentDescription("Suggested reply", substring = true).assertExists()
    }

    @Test
    fun `a saved copy and a running chat draw none`() {
        show("convo-suggestion", cached = true)
        compose.onNodeWithContentDescription("Suggested reply", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a running chat draws none`() {
        show("convo-suggestion", running = true)
        compose.onNodeWithContentDescription("Suggested reply", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a conversation with none draws no strip`() {
        show("convo-claude")
        compose.onNodeWithContentDescription("Suggested reply", substring = true).assertDoesNotExist()
    }
}
