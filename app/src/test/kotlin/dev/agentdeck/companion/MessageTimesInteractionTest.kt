package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.Times
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's "Message times" is the desk's "Show message times": drawn only once the machine
 * has answered with its value, a tap asks for the opposite — and on, every finished message of a
 * quick exchange states its time instead of only the last one before a pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MessageTimesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()

    /** Three alternating turns a minute apart: well inside the phone's own five-minute run. */
    private val stamps = listOf(3, 2, 1).map { DeckFixtures.NOW - it * 60_000L }

    private fun showSettings(messageTimes: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, messageTimes = messageTimes),
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
                onMessageTimes = { flips += it },
            )
        }
    }

    private fun showExchange(everyMessageTime: Boolean) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests",
            turns = listOf(
                MobileTurn("t1", "user", "Why does it fail?", stamps[0]),
                MobileTurn("t2", "assistant", "At a day boundary.", stamps[1]),
                MobileTurn("t3", "user", "Fix it.", stamps[2]),
            ),
            hasMore = false, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
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
                    stopping = false,
                    onDismissNotice = {},
                    queued = emptyList(),
                    delivering = null,
                    everyMessageTime = everyMessageTime,
                )
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun shownTimes() = stamps.map { count(Times.clock(it, DeckFixtures.NOW)) }

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(messageTimes = false)

        compose.onNodeWithText("Message times").assertIsDisplayed().performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(messageTimes = null)

        assertEquals(0, count("Message times"))
    }

    @Test
    fun `off, a quick exchange states one time`() {
        showExchange(everyMessageTime = false)

        assertEquals(listOf(0, 0, 1), shownTimes())
    }

    @Test
    fun `on, every message states its time`() {
        showExchange(everyMessageTime = true)

        assertEquals(listOf(1, 1, 1), shownTimes())
    }
}
