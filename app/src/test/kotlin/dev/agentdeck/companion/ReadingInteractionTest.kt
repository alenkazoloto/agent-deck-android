package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.ReadingAnchor
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ReadingInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun outgoingEmptyPageCannotOverwriteSavedPassageAndPrependKeepsTurn() {
        val initial = requireNotNull(DeckFixtures.byName("convo-idle")?.transcript).copy(
            running = false, hasMore = false, turns = (10..40).map { turn(it) },
        )
        val page = mutableStateOf<MobileTranscriptPage?>(initial)
        var saved: ReadingAnchor? = ReadingAnchor("turn-15", 5, 24, false)
        ui.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    Screen.Conversation(initial.key, initial.title, AgentVendor.CLAUDE, "/project"),
                    page.value, false, false, "", null, {}, { _, _ -> }, {}, {},
                    readingAnchor = saved, onReadingAnchor = { saved = it },
                )
            }
        }
        ui.onNodeWithText("Passage 15", substring = true).assertIsDisplayed()
        val anchor = ui.runOnIdle { requireNotNull(saved) }
        ui.runOnIdle { page.value = null }
        ui.runOnIdle { assertEquals(anchor, saved) }
        ui.runOnIdle { page.value = initial.copy(turns = (0..40).map(::turn)) }
        ui.onNodeWithText("Passage 15", substring = true).assertIsDisplayed()
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("Latest message").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle {
            assertEquals("turn-15", saved?.itemKey)
            assertFalse(requireNotNull(saved).followingLatest)
        }
    }

    @Test fun manuallyReadingNewTailClearsUnreadWhenScrollingAwayAgain() {
        val initial = requireNotNull(DeckFixtures.byName("convo-idle")?.transcript).copy(
            running = false, hasMore = false, turns = (10..40).map(::turn),
        )
        val page = mutableStateOf(initial)
        ui.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    Screen.Conversation(initial.key, initial.title, AgentVendor.CLAUDE, "/project"),
                    page.value, false, false, "", null, {}, { _, _ -> }, {}, {},
                    readingAnchor = ReadingAnchor("turn-15", 5, 24, false),
                )
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("Latest message").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { page.value = initial.copy(turns = (10..41).map(::turn)) }
        ui.onNodeWithContentDescription("Latest message, 1 new turn").assertIsDisplayed()
        val list = ui.onNodeWithTag("conversation-transcript")
        list.performScrollToIndex(30)
        repeat(3) { list.performTouchInput { swipeUp() } }
        ui.onNodeWithContentDescription("Latest message", substring = true).assertDoesNotExist()
        list.performTouchInput { swipeDown() }
        ui.onNodeWithContentDescription("Latest message").assertIsDisplayed()
    }

    private fun turn(index: Int) = MobileTurn("turn-$index", "assistant",
        "Passage $index\n\n" + "Synthetic reading text. ".repeat(10), DeckFixtures.NOW)
}
