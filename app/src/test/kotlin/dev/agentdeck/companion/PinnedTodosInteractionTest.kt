package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTodo
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's "Pinned todo list" is the desk's "Pin the agent's todo list": drawn only once the
 * machine has answered with its value, a tap asks for the opposite — and a conversation pins
 * "Todos 3/7 · Running tests…" above its message box while the agent's last list has work open, folded until
 * tapped, and pins nothing when the switch is off, unread, or every row is done.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinnedTodosInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()

    private fun showSettings(pinned: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, pinnedTodos = pinned),
                onSettings = {},
                onSwitchMachine = {},
                onAddMachine = {},
                onUnpair = {},
                onRefreshHello = {},
                onRefreshPush = {},
                onChoosePush = {},
                onCheckUpdate = {},
                onDownloadUpdate = {},
                onInstallUpdate = {},
                onReleasePage = {},
                onPinnedTodos = { flips += it },
            )
        }
    }

    private fun showConversation(pinned: Boolean?, transform: (MobileTranscriptPage) -> MobileTranscriptPage = { it }) {
        val page = transform(requireNotNull(DeckFixtures.byName("convo-tasks")?.transcript).copy(running = false))
        compose.setContent {
            AgentDeckTheme {
                val target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project")
                if (pinned == null) ConversationScreen(target, page, false, false, "", null, {}, { _, _ -> }, {}, {})
                else ConversationScreen(target, page, false, false, "", null, {}, { _, _ -> }, {}, {}, pinnedTodos = pinned)
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(pinned = true)

        compose.onNodeWithText("Pinned todo list").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(pinned = false)

        compose.onNodeWithText("Pinned todo list").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(pinned = null)

        assertEquals(0, count("Pinned todo list"))
    }

    @Test
    fun `an on switch pins the open list folded and unfolds it on a tap`() {
        showConversation(pinned = true)

        compose.onNodeWithText("Todos 2/5 · Running the suite").assertIsDisplayed()
        // The transcript's own checklist already lists every row once.
        assertEquals(1, count("Update the changelog"))

        compose.onNodeWithText("Todos 2/5 · Running the suite").performClick()

        assertEquals("the pinned list adds the rows a second time", 2, count("Update the changelog"))
    }

    @Test
    fun `an off or unread switch pins nothing`() {
        showConversation(pinned = false)
        compose.waitForIdle()
        assertEquals(0, count("Todos 2/5"))
    }

    @Test
    fun `a conversation opened without the machine's answer pins nothing as before`() {
        showConversation(pinned = null)
        compose.waitForIdle()
        assertEquals(0, count("Todos 2/5"))
    }

    @Test
    fun `a list with every row done is history and pins nothing`() {
        showConversation(pinned = true) { page ->
            page.copy(turns = page.turns.map { turn -> turn.copy(todos = turn.todos.map { MobileTodo(it.text, MobileTodo.COMPLETED) }) })
        }
        compose.waitForIdle()
        assertEquals(0, count("Todos 5/5"))
        assertEquals(0, count("Todos "))
    }
}
