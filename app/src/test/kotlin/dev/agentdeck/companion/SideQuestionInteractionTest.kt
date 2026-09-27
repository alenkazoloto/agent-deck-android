package dev.agentdeck.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSideAsk
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.SideQuestionSheet
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SideQuestionDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's `/btw` on the phone: typed in a Claude chat it opens the side-question sheet — a
 * question after the name is asked at once, a bare name opens it empty — and never reaches the agent
 * as prose; the sheet shows each ask as the machine states it, and the typed question stays in the
 * field until the machine takes it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SideQuestionInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val sent = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val typed = mutableListOf<String>()
    private var askPressed = 0

    @Test
    fun `a question typed after btw is asked at once and never sent as a message`() {
        showConversation()

        send("/btw Which file sets the redirect?")

        compose.runOnIdle {
            assertEquals(listOf("Which file sets the redirect?"), asked)
            assertEquals("", draft.value)
            assertTrue("sent as prose: $sent", sent.isEmpty())
        }
    }

    @Test
    fun `a bare btw opens the sheet with no question`() {
        showConversation()

        send("/btw")

        compose.runOnIdle {
            assertEquals(listOf(""), asked)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `a machine without side questions, and a Codex chat, get the line as a message`() {
        showConversation(capabilities = emptySet())
        send("/btw why")
        compose.runOnIdle { assertEquals(listOf("/btw why"), sent) }
    }

    @Test
    fun `Codex's chat has no forked copy to ask, so btw is a message there`() {
        showConversation(vendor = AgentVendor.CODEX)
        send("/btw why")
        compose.runOnIdle {
            assertEquals(listOf("/btw why"), sent)
            assertTrue(asked.isEmpty())
        }
    }

    @Test
    fun `the sheet shows an answered ask, a running one and a failed one as the machine states them`() {
        showDialog(
            SideQuestionSheet(
                "a", "Fix the parser",
                asks = listOf(
                    MobileSideAsk("1", "Which file?", running = false, answer = "LoginRedirect.kt"),
                    MobileSideAsk("2", "Would a test catch it?", running = true),
                    MobileSideAsk("3", "Why the retry?", running = false, failure = "Claude Code did not answer in time."),
                ),
            ),
        )

        compose.onNodeWithText("Side question").assertIsDisplayed()
        compose.onNodeWithText("Fix the parser").assertIsDisplayed()
        compose.onAllNodesWithTag("side-question-ask-row").assertCountEquals(3)
        compose.onNodeWithText("LoginRedirect.kt").assertIsDisplayed()
        compose.onNodeWithText("Thinking…").assertIsDisplayed()
        compose.onNodeWithText("Claude Code did not answer in time.").assertIsDisplayed()
    }

    @Test
    fun `an empty sheet says what it is for and Ask waits for a question`() {
        var sheet by mutableStateOf(SideQuestionSheet("a", "Fix the parser"))
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                SideQuestionDialog(sheet, onDraft = { typed += it; sheet = sheet.copy(draft = it) }, onAsk = { askPressed++ }, onDismiss = {})
            }
        }

        compose.onNodeWithTag("side-question-notice").assertIsDisplayed()
        compose.onNodeWithTag("side-question-ask").assertIsNotEnabled()
        compose.onNodeWithTag("side-question-field").performTextInput("Why?")
        compose.onNodeWithTag("side-question-ask").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(listOf("Why?"), typed)
            assertEquals(1, askPressed)
        }
    }

    @Test
    fun `a refused question shows the machine's sentence and keeps the text in the field`() {
        showDialog(
            SideQuestionSheet(
                "a", "Fix the parser",
                draft = "Why so slow?",
                refused = "Other side questions are still being answered on this machine. Ask again when they finish.",
            ),
        )

        compose.onNodeWithText("Other side questions are still being answered on this machine. Ask again when they finish.").assertIsDisplayed()
        compose.onNodeWithText("Why so slow?").assertIsDisplayed()
        compose.onNodeWithTag("side-question-ask").assertIsEnabled()
    }

    @Test
    fun `Ask stays off while the question is on its way`() {
        showDialog(SideQuestionSheet("a", "Fix the parser", draft = "Why?", sending = true))

        compose.onNodeWithTag("side-question-ask").assertIsNotEnabled()
    }

    private fun showDialog(sheet: SideQuestionSheet) {
        compose.setContent {
            AgentDeckTheme(dynamic = false) {
                SideQuestionDialog(sheet, onDraft = { typed += it }, onAsk = { askPressed++ }, onDismiss = {})
            }
        }
    }

    /** Types the name first, as a thumb does: the `/` popup is what reads the catalogue. */
    private fun send(text: String) {
        val composer = hasSetTextAction() and !hasAnyAncestor(isDialog())
        compose.onNode(composer).performTextReplacement(text.substringBefore(' '))
        compose.waitForIdle()
        compose.onNode(composer).performTextReplacement(text)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
    }

    private fun showConversation(
        vendor: AgentVendor = AgentVendor.CLAUDE,
        capabilities: Set<String> = setOf(MobileProtocol.Capability.SIDE_QUESTION),
    ) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let {
            it.copy(capabilities = it.capabilities - MobileProtocol.Capability.SIDE_QUESTION + MobileProtocol.Capability.COMMANDS + capabilities)
        }
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000)),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, vendor, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { text, _ -> sent += text },
                    onStop = {},
                    onDismissNotice = {},
                    hello = hello,
                    onLoadCommands = { emptyList<MobileCommand>() },
                    onSideQuestion = { asked += it },
                )
            }
        }
    }
}
