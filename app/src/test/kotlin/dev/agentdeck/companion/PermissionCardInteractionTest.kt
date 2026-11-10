package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.PermissionCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PLAN-MOBILE-REDESIGN P18: a tool call the run is parked on is decided from the transcript.
 *
 * The card names what would run, offers the three answers the desk's dialog does, and carries the
 * id of the ask it was drawn for — the id is what lets the machine refuse a decision made on a
 * card the agent has since replaced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class PermissionCardInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val decided = mutableListOf<Pair<String, String>>()
    private var failures by mutableIntStateOf(0)

    private fun show(fixture: String = "convo-permission", canDecide: Boolean = true) {
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
                        canDecide = canDecide,
                        onDecide = { id, decision -> decided += id to decision },
                        decideFailures = failures,
                    )
                }
            }
        }
    }

    @Test
    fun `the card shows the command the run is waiting to run`() {
        show()
        compose.onNodeWithTag("permission-card").performScrollTo().assertExists()
        compose.onNodeWithText("rm -rf build && ./gradlew assembleDebug").assertExists()
    }

    @Test
    fun `Allow sends the ask's own id and locks the card`() {
        show()
        compose.onNodeWithText("Allow").performScrollTo().performClick()
        assertEquals(listOf("perm-1" to MobileDecisionRequest.ALLOW), decided)
        compose.onNodeWithText("Allow").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
    }

    @Test
    fun `Always allow names the rule it would write`() {
        show()
        compose.onNodeWithText("Always allow Bash(rm -rf build:*)").performScrollTo().performClick()
        assertEquals(listOf("perm-1" to MobileDecisionRequest.ALWAYS), decided)
    }

    @Test
    fun `Deny sends deny`() {
        show()
        compose.onNodeWithText("Deny").performScrollTo().performClick()
        assertEquals(listOf("perm-1" to MobileDecisionRequest.DENY), decided)
    }

    @Test
    fun `an ask the machine will not remember offers no Always allow`() {
        val bare = DeckFixtures.byName("convo-permission")!!.transcript!!.pendingPermission!!.copy(rememberScope = null)
        compose.setContent { AgentDeckTheme { PermissionCard(bare, 0) { _, _ -> } } }
        compose.onNode(hasText("Always allow", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Allow").assertIsEnabled()
    }

    /** A refused decision must not strand the run behind a card that will never unlock. */
    @Test
    fun `a decision the machine refused lifts the lock so the same card can be tried again`() {
        show()
        compose.onNodeWithText("Allow").performScrollTo().performClick()
        compose.onNodeWithText("Allow").assertIsNotEnabled()

        failures += 1
        compose.waitForIdle()

        compose.onNodeWithText("Allow").assertIsEnabled().performClick()
        assertEquals(2, decided.size)
    }

    @Test
    fun `no card is drawn when the owner has not switched phone decisions on`() {
        show(fixture = "convo-permission-off", canDecide = false)
        compose.onNodeWithTag("permission-card").assertDoesNotExist()
    }
}
