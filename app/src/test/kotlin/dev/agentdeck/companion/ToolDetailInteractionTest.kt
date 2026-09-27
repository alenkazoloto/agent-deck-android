package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileToolResult
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.ProvideOpenToolCalls
import dev.agentdeck.companion.ui.toolDetailSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading what a tool returned, from the collapsed group to the sheet.
 *
 * Asserted on the **output text** reaching the screen rather than on the sheet existing: a
 * sheet that opened empty would satisfy a presence check and still leave the reader with the
 * walk to the desk this milestone exists to save. The negative case has its own test, because
 * a row that opens an empty sheet is the failure worth pinning — a call with no result yet is
 * indistinguishable, from the phone, from an IDE too old to send one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ToolDetailInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String>()

    private val result = MobileToolResult(
        MobileToolResult.EXECUTE,
        input = "./gradlew testDebugUnitTest",
        output = "PairingTest > pairs once FAILED\nexpected:<1> but was:<2>",
        omittedBytes = 41_000,
    )

    /** A machine that serves `/v1/tool/{key}/{id}`; null is one too old to. */
    private val toolResultsHello = requireNotNull(DeckFixtures.byName("convo-tool-results")?.hello)

    private fun show(
        call: MobileToolCall,
        thought: String? = null,
        hello: com.github.claudeagents.core.mobile.MobileHello? = null,
        toolOutputError: String? = null,
        openToolCalls: Boolean = false,
    ) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(
                MobileTurn(
                    id = "t0", role = "assistant", text = "Running them.",
                    timestampMs = DeckFixtures.NOW, toolCalls = listOf(call), thought = thought,
                ),
            ),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ProvideOpenToolCalls(openToolCalls) {
                    ConversationScreen(
                        target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                        page = page,
                        loading = false,
                        cached = false,
                        draft = "",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        onLoadToolOutput = { asked.add(it) },
                        hello = hello,
                        toolOutputCallId = call.id.takeIf { toolOutputError != null },
                        toolOutputError = toolOutputError,
                    )
                }
            }
        }
    }

    private val finished = MobileToolCall(
        "c2", "Bash", "Bash — run the tests", "command: ./gradlew test",
        MobileToolCall.ERROR, result = result,
    )

    @Test
    fun `opening a tool call shows what it returned`() {
        show(finished)

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").performClick()

        compose.onNodeWithText("expected:<1> but was:<2>", substring = true).assertIsDisplayed()
        compose.onNodeWithText("./gradlew testDebugUnitTest", substring = true).assertIsDisplayed()
    }

    /** Settings › Reading › "Open tool calls": the rows are there without a tap, and the header still folds them. */
    @Test
    fun `with Open tool calls on a group starts unfolded and a tap folds it`() {
        show(finished, openToolCalls = true)

        compose.onNodeWithText("Bash — run the tests").assertIsDisplayed()
        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").assertDoesNotExist()
    }

    @Test
    fun `by default the same group stays folded`() {
        show(finished)

        compose.onNodeWithText("Bash — run the tests").assertDoesNotExist()
    }

    /** The larger copy is a second request, so it is asked for only when the reader asks. */
    @Test
    fun `the rest of a cut body is fetched only on request`() {
        show(finished)

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").performClick()
        assertTrue("opening the sheet must not spend a request", asked.isEmpty())

        compose.onNodeWithText("Load more output").performClick()
        assertEquals(listOf("c2"), asked)
    }

    /** A call the run has not finished carries no result, so its row is not a door to nothing. */
    @Test
    fun `a running call offers no sheet`() {
        show(MobileToolCall("c1", "Bash", "Bash — run the tests", "", MobileToolCall.RUNNING))

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").performClick()

        compose.onNodeWithText("Copy output").assertDoesNotExist()
    }

    /**
     * J04: a finished call whose output the page left out (the desk trims older outputs to fit)
     * used to read like one whose output was a tap away and then did nothing when tapped. On a
     * machine that serves outputs, the tap opens the sheet and fetches it.
     */
    @Test
    fun `a finished call without output on the page fetches it when opened`() {
        show(MobileToolCall("c3", "Bash", "Bash — run the tests", "", MobileToolCall.OK), hello = toolResultsHello)

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").performClick()
        assertEquals(listOf("c3"), asked)
        compose.onNodeWithText("Loading output…").assertIsDisplayed()
    }

    /** The machine's own sentence when the output is gone, so "gone" is not "still coming". */
    @Test
    fun `an output the machine no longer has says so in the sheet`() {
        val gone = "That tool call's output is no longer loaded. Refresh this conversation."
        show(MobileToolCall("c3", "Bash", "Bash — run the tests", "", MobileToolCall.OK),
            hello = toolResultsHello, toolOutputError = gone)

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").performClick()
        compose.onNodeWithText(gone).assertIsDisplayed()
        compose.onNodeWithText("Loading output…").assertDoesNotExist()
    }

    /** An older machine cannot serve it, so the row stays closed and asks for nothing. */
    @Test
    fun `without the capability an outputless call neither opens nor asks`() {
        show(MobileToolCall("c3", "Bash", "Bash — run the tests", "", MobileToolCall.OK))

        compose.onNodeWithText("Tool calls (1)").performClick()
        compose.onNodeWithText("Bash — run the tests").performClick()
        assertTrue(asked.isEmpty())
        compose.onNodeWithText("Loading output…").assertDoesNotExist()
    }

    /** Thinking is folded away: the answer the reader came for stays at the top of the bubble. */
    @Test
    fun `a thought is present but closed until it is opened`() {
        show(finished, thought = "The stamp carries no date, so yesterday reads as today.")

        compose.onNodeWithText("The stamp carries no date", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Thought").performClick()
        compose.onNodeWithText("The stamp carries no date", substring = true).assertIsDisplayed()
    }

    /** The one line under the sheet's title says how much of the output is missing. */
    @Test
    fun `the subtitle names the kind, the outcome and the omission`() {
        assertEquals("Command · failed · 40 KB not shown", toolDetailSubtitle(finished, result))
        assertEquals(
            "Command · done",
            toolDetailSubtitle(
                finished.copy(status = MobileToolCall.OK),
                result.copy(omittedBytes = 0),
            ),
        )
    }
}
