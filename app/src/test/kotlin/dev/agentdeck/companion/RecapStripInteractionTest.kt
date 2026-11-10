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

/** The desk's "where you left off" strip on the phone: named over the transcript, dismissible, gone once the reader types. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class RecapStripInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun show(fixture: String, draft: String = "") {
        val state = DeckFixtures.byName(fixture)!!
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = state.screen as Screen.Conversation,
                        page = state.transcript!!,
                        loading = false,
                        cached = false,
                        draft = draft,
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        recap = state.recap?.recap,
                        onDismissRecap = { dismissed += 1 },
                    )
                }
            }
        }
    }

    @Test
    fun `a returning reader is told where they left off, with the stamp in their own zone`() {
        show("convo-recap")
        compose.onNodeWithText("Where you left off").assertExists()
        compose.onNodeWithText("Last active ", substring = true).assertExists()
        compose.onNodeWithText("add the retry to the upload client", substring = true).assertExists()
    }

    @Test
    fun `the close button hides it and names how to turn recaps off`() {
        show("convo-recap")
        compose.onNodeWithContentDescription("Hide this recap", substring = true).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a message already being typed retires it`() {
        show("convo-recap", draft = "and then")
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a conversation with no offer draws no strip`() {
        show("convo-claude")
        compose.onNodeWithText("Where you left off").assertDoesNotExist()
    }
}
