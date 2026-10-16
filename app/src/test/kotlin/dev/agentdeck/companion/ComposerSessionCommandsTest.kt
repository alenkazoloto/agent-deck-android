package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileCommand
import com.github.claudeagents.core.mobile.MobileDeskCommands
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk composer's `/rename`, `/branch`, Codex's `/fork` and `/export`, typed on the phone, run
 * the row sheet's Rename, Branch chat and Share on this chat instead of reaching the agent as prose
 * (`PhoneDeskCommandParityTest` listed them as gaps after M3 shipped the controls).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSessionCommandsTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val sent = mutableListOf<String>()
    private val notices = mutableListOf<String>()
    private val navigations = mutableListOf<MobileDeskCommands.Action>()
    private val renamed = mutableListOf<String>()

    @Test
    fun `rename with a title opens the dialog on it and renames the chat`() {
        show()

        send("/rename \"Parser fix\"")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()) and hasText("Parser fix")).assertExists()
        compose.onNodeWithText("Rename").performClick()

        compose.runOnIdle {
            assertEquals(listOf("Parser fix"), renamed)
            assertEquals("", draft.value)
            assertTrue("sent as prose: $sent", sent.isEmpty())
        }
    }

    @Test
    fun `a cancelled rename keeps the typed line`() {
        show()

        send("/rename Parser fix")
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle {
            assertTrue(renamed.isEmpty())
            assertEquals("/rename Parser fix", draft.value)
        }
    }

    @Test
    fun `a bare rename opens on the chat's current name`() {
        show()

        send("/rename")

        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()) and hasText("Renamed at the desk")).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(renamed.isEmpty()) }
    }

    @Test
    fun `branch and export run the row sheet's actions`() {
        show()

        send("/branch")
        send("/export")
        send("/export chat.md")

        compose.runOnIdle {
            val expected = listOf(MobileDeskCommands.Action.BRANCH, MobileDeskCommands.Action.EXPORT, MobileDeskCommands.Action.EXPORT)
            assertEquals(expected, navigations)
            assertEquals("", draft.value)
            assertTrue(sent.isEmpty())
        }
    }

    @Test
    fun `branch mid-turn says why and keeps the line`() {
        show(running = true)

        send("/branch")

        compose.runOnIdle {
            assertTrue(navigations.isEmpty())
            assertEquals("/branch", draft.value)
            assertEquals(listOf("Wait for this turn to finish before branching."), notices)
        }
    }

    @Test
    fun `Codex's fork is its branch, and Claude's fork is still the CLI's`() {
        show(vendor = AgentVendor.CODEX)
        send("/fork")
        compose.runOnIdle { assertEquals(listOf(MobileDeskCommands.Action.BRANCH), navigations) }
    }

    @Test
    fun `a machine without the routes gets the lines as messages`() {
        show(capabilities = emptySet(), canRename = false)

        send("/rename x")
        send("/branch")
        send("/fork")

        compose.runOnIdle { assertEquals(listOf("/rename x", "/branch", "/fork"), sent) }
    }

    /** Not a golden: the popup's new rows and the dialog `/rename` opens, under `build/outputs/p15/`. */
    @Test
    fun `render the session command rows and the rename dialog`() {
        show()
        compose.onNode(hasSetTextAction()).performTextReplacement("/re")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Rename this chat").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(isRoot())[0].captureRoboImage("build/outputs/p15/session-command-rows.png", RECORD)
        send("/rename Parser fix")
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.last().let { last ->
            compose.onAllNodes(isRoot())[last].captureRoboImage("build/outputs/p15/rename-command-dialog.png", RECORD)
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

    private fun show(
        running: Boolean = false,
        vendor: AgentVendor = AgentVendor.CLAUDE,
        capabilities: Set<String> = setOf(
            MobileProtocol.Capability.SESSION_BRANCH,
            MobileProtocol.Capability.SESSION_FORK,
            MobileProtocol.Capability.SESSION_EXPORT,
        ),
        canRename: Boolean = true,
    ) {
        val pills = DeckFixtures.byName("convo-composer-pills")!!
        val hello = pills.hello!!.let {
            it.copy(capabilities = it.capabilities - SESSION_CAPS + MobileProtocol.Capability.COMMANDS + capabilities)
        }
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(MobileTurn("t1", "user", "Run the tests", DeckFixtures.NOW - 60_000)),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = running, generatedAtMs = DeckFixtures.NOW,
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
                    onDeskNavigation = { navigations += it },
                    onCommandNotice = { notices += it },
                    onRename = if (canRename) ({ renamed += it }) else null,
                    renameTitle = "Renamed at the desk",
                )
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
        val SESSION_CAPS = setOf(
            MobileProtocol.Capability.SESSION_BRANCH,
            MobileProtocol.Capability.SESSION_FORK,
            MobileProtocol.Capability.SESSION_EXPORT,
        )
    }
}
