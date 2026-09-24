package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.claudeagents.core.mobile.MobileAcpSession
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
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
 * A chat on an ACP agent (P07): the machine's `acpSessions` array is a Chats row that opens and can
 * be stopped, and its sheet offers none of the desk-store gestures that have nothing to write for it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AcpSessionRowInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val stopped = mutableListOf<String>()

    private val wire = MobileFleetSnapshot(
        rows = emptyList(), badgeCount = 0, openProjects = emptyList(), usageLine = null, generatedAtMs = DeckFixtures.NOW,
        acpSessions = listOf(
            MobileAcpSession(
                key = "acp:gemini/sess-1", agentName = "Gemini CLI", projectPath = "/work/repo", title = "Port the parser",
                running = true, lastActivityMs = DeckFixtures.NOW - 30_000,
            ),
        ),
    ).toJson()

    private fun show() {
        // What the phone actually holds: the snapshot its client parsed, not the machine's own object.
        val snapshot = MobileFleetSnapshot.fromJson(wire)
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = snapshot, filter = FleetFilter(), sort = FleetSort.RECENT, refreshing = false,
                        snoozed = emptyMap(), openKey = null, onFilter = {}, onSort = {}, onRefresh = {},
                        onOpen = { opened += it.key }, onSnooze = {}, onStop = { stopped += it.key },
                        canOrganize = true, canShare = true, canDelete = true, canFolder = true, canFork = true,
                        canBranch = true, canPinOrder = true, canRetitle = true, canRewind = true, canContext = true,
                    )
                }
            }
        }
    }

    @Test
    fun `an ACP session is a row that opens by its key`() {
        show()
        compose.onNodeWithText("Port the parser").assertIsDisplayed()
        compose.onNodeWithText("Port the parser").performClick()
        compose.runOnIdle { assertEquals(listOf("acp:gemini/sess-1"), opened) }
    }

    @Test
    fun `its TalkBack actions offer Stop but not the organizing gestures`() {
        show()
        val labels = compose.onNodeWithText("Port the parser").fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertTrue(labels.toString(), "Stop this run" in labels)
        assertTrue(labels.toString(), labels.none { it == "Pin to the top" || it == "Unpin" || it == "Mark done" })
    }

    @Test
    fun `its sheet stops the run and offers no desk-store gesture`() {
        show()
        compose.onNodeWithText("Port the parser").performTouchInput { longClick() }
        compose.onNodeWithText("Stop this run").assertIsDisplayed()
        compose.onNodeWithText("Copy the title").assertIsDisplayed()
        for (label in listOf("Delete the conversation…", "Rename", "Pin to the top")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }
}
