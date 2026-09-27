package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobilePromptLibrary.Entry
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.PromptLibraryActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's `/prompts` and `/saveprompt` on the phone: the first lists the desk's saved prompts and a tap
 * puts one in the composer, the second keeps a new one. Both are consumed by the composer rather than reaching
 * the agent as prose, and a machine that does not advertise `prompt-library` gets the lines as messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptLibraryInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val sent = mutableListOf<String>()
    private val saved = mutableListOf<Pair<String, String>>()
    private var saveError: String? = null
    private var rows: List<Entry>? = listOf(
        Entry("Code review", "Review my diff.\nBe blunt about risks."),
        Entry("Release notes", "Summarise the diff for the changelog."),
    )

    @Test
    fun `slash prompts lists the saved prompts and a tap fills the composer without sending`() {
        show()

        send("/prompts")
        compose.onNodeWithText("Saved prompts").assertExists()
        compose.onNodeWithText("Release notes").assertExists()
        compose.onNodeWithText("Review my diff.").assertExists()
        compose.onNodeWithText("Code review").performClick()

        compose.runOnIdle {
            assertEquals("Review my diff.\nBe blunt about risks.", draft.value)
            assertTrue("sent as prose: $sent", sent.isEmpty())
        }
        compose.onAllNodesWithText("Saved prompts").assertCountEquals(0)
        // The field itself, not just the hoisted draft: what the reader sees is what a Send would send.
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(isDialog()) and hasText("Be blunt about risks.", substring = true)).assertExists()
    }

    @Test
    fun `picking the rows from the popup opens the same list and form`() {
        show()

        compose.onNode(hasSetTextAction()).performTextReplacement("/promp")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Put a saved prompt into the message box").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Put a saved prompt into the message box").performClick()
        compose.onNodeWithText("Saved prompts").assertExists()
        compose.onNodeWithText("Release notes").performClick()
        compose.runOnIdle { assertEquals("Summarise the diff for the changelog.", draft.value) }

        compose.onNode(hasSetTextAction()).performTextReplacement("/savepr")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Save a prompt under a name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save a prompt under a name").performClick()
        compose.onNodeWithText("Save prompt").assertExists()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextReplacement("Terse")
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[1].performTextReplacement("Be brief.")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("Terse" to "Be brief."), saved)
            assertTrue("sent as prose: $sent", sent.isEmpty())
        }
    }

    @Test
    fun `a typed prompts line keeps its words until a pick, and stays in the composer while the list is open`() {
        show()

        send("/prompts release\nfor the auth module")
        compose.onNodeWithText("Release notes").assertExists()
        compose.onAllNodesWithText("Code review").assertCountEquals(0)
        compose.onNodeWithText("Release notes").performClick()

        compose.runOnIdle { assertEquals("Summarise the diff for the changelog.\n\nfor the auth module", draft.value) }

        draft.value = ""
        send("/prompts release")
        compose.onNodeWithText("Saved prompts").assertExists()
        // Nothing is consumed by opening the list: the line is still there to edit, as a refused rename's is.
        compose.runOnIdle { assertEquals("/prompts release", draft.value) }
    }

    @Test
    fun `a name typed after the command opens the list on it`() {
        show()

        send("/prompts release")

        compose.onNodeWithText("Release notes").assertExists()
        compose.onAllNodesWithText("Code review").assertCountEquals(0)
        compose.onNode(hasSetTextAction() and hasText("release")).assertExists()
    }

    @Test
    fun `an empty library says so and so does a machine that did not answer`() {
        rows = emptyList()
        show()
        send("/prompts")
        compose.onNodeWithText("No saved prompts yet.", substring = true).assertExists()
    }

    @Test
    fun `a machine that did not answer is not shown as an empty library`() {
        rows = null
        show()
        send("/prompts")
        compose.onNodeWithText("The machine did not answer.", substring = true).assertExists()
    }

    @Test
    fun `saveprompt with a name and body opens the dialog on them and Save keeps them and clears the line`() {
        show()

        send("/saveprompt Terse\nBe brief.")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()) and hasText("Terse")).assertExists()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()) and hasText("Be brief.")).assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(listOf("Terse" to "Be brief."), saved)
            assertEquals("", draft.value)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `a refused save keeps the dialog and the typed line and shows the machine's sentence`() {
        saveError = "The IDE is shutting down or restarting. Try again in a moment."
        show()

        send("/saveprompt Terse\nBe brief.")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(saveError!!).assertExists()
        compose.runOnIdle { assertEquals("/saveprompt Terse\nBe brief.", draft.value) }
    }

    @Test
    fun `a bare saveprompt opens an empty form whose Save waits for a name and a prompt`() {
        show()

        send("/saveprompt")
        compose.onNodeWithText("Save prompt").assertExists()
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog()) and isNotEnabled()).assertExists()
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextReplacement("Terse")
        compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[1].performTextReplacement("Be brief.")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog()) and isEnabled()).assertExists()
    }

    @Test
    fun `saving under a name already kept warns that it replaces it`() {
        show()

        send("/saveprompt code REVIEW\nNew text.")

        compose.onNodeWithText("Replaces the saved prompt of that name.").assertExists()
    }

    @Test
    fun `a machine without the library gets both lines as messages`() {
        show(offered = false)

        send("/prompts")
        send("/saveprompt Terse\nBe brief.")

        compose.runOnIdle { assertEquals(listOf("/prompts", "/saveprompt Terse\nBe brief."), sent) }
    }

    /** Not a golden: the list and the dialog, under `build/outputs/prompt-library/`. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun `render the saved prompts and the save dialog`() {
        show()
        send("/prompts")
        compose.onNodeWithText("Saved prompts").assertExists()
        compose.onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().indices.last()] }
            .captureRoboImage("build/outputs/prompt-library/saved-prompts.png", RECORD)
        compose.onNodeWithText("Release notes").performClick()
        send("/saveprompt Terse\nBe brief.")
        compose.onNodeWithText("Save prompt").assertExists()
        compose.onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().indices.last()] }
            .captureRoboImage("build/outputs/prompt-library/save-prompt.png", RECORD)
    }

    /** Types the name first, as a thumb does: the `/` popup is what reads the catalogue. */
    private fun send(text: String) {
        val composer = hasSetTextAction() and !hasAnyAncestor(isDialog())
        compose.onNode(composer).performTextReplacement(text.substringBefore(' ').substringBefore('\n'))
        compose.waitForIdle()
        compose.onNode(composer).performTextReplacement(text)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
    }

    private fun show(offered: Boolean = true) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let { it.copy(capabilities = it.capabilities + MobileProtocol.Capability.COMMANDS) }
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000)),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, com.github.claudeagents.core.AgentVendor.CLAUDE, "/project"),
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
                    promptLibrary = PromptLibraryActions(
                        load = { rows },
                        save = { name, text ->
                            saveError ?: null.also { saved += name to text }
                        },
                    ).takeIf { offered },
                )
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
