package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.EMOJI_POPUP_TAG
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P15: the composer's `:` emoji popup and `:name:` swap against the real `ConversationScreen`,
 * with the table the APK actually packages — and with the screen's `emojiCompletion` off, which
 * proves the screen honours it; `MainActivity` passing Settings › Writing's value is not covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerEmojiInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")

    @Test
    fun `a colon and two letters list emoji and a tap writes the glyph`() {
        show(emojiCompletion = true)

        compose.onNode(hasSetTextAction()).performTextReplacement("thanks :thu")
        compose.onNodeWithTag(EMOJI_POPUP_TAG).assertExists()
        compose.onNodeWithText(":thumbsup:").performClick()

        compose.runOnIdle { assertEquals("thanks 👍", draft.value) }
        compose.onNodeWithText(":thumbsup:").assertDoesNotExist()
    }

    @Test
    fun `typing the closing colon swaps the shortcode`() {
        show(emojiCompletion = true)

        compose.onNode(hasSetTextAction()).performTextReplacement("great :tada")
        compose.onNode(hasSetTextAction()).performTextReplacement("great :tada:")

        compose.runOnIdle { assertEquals("great 🎉", draft.value) }
    }

    @Test
    fun `a clock time opens nothing`() {
        show(emojiCompletion = true)

        compose.onNode(hasSetTextAction()).performTextReplacement("at 10:30")
        compose.waitForIdle()

        compose.onNodeWithTag(EMOJI_POPUP_TAG).assertDoesNotExist()
    }

    @Test
    fun `with the setting off neither the list nor the swap happens`() {
        show(emojiCompletion = false)

        compose.onNode(hasSetTextAction()).performTextReplacement("thanks :thu")
        compose.waitForIdle()
        compose.onNodeWithTag(EMOJI_POPUP_TAG).assertDoesNotExist()

        compose.onNode(hasSetTextAction()).performTextReplacement("great :tada")
        compose.onNode(hasSetTextAction()).performTextReplacement("great :tada:")
        compose.runOnIdle { assertEquals("great :tada:", draft.value) }
    }

    /** Not a golden: the rendered popup as evidence, written under `build/outputs/p15/`. */
    @Test
    fun `render the emoji popup`() {
        show(emojiCompletion = true)
        compose.onNode(hasSetTextAction()).performTextReplacement("nice :th")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/p15/emoji-popup.png", RECORD)
    }

    private fun show(emojiCompletion: Boolean) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests", turns = emptyList(), hasMore = false,
            costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { _, _ -> },
                    onStop = {},
                    onDismissNotice = {},
                    hello = MobileHello(
                        protocolVersion = MobileProtocol.VERSION,
                        machineName = "desk",
                        ideName = "IDEA",
                        pluginVersion = "1.0",
                        capabilities = emptyList(),
                    ),
                    emojiCompletion = emojiCompletion,
                )
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
