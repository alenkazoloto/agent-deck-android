package dev.agentdeck.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.NewChatScreen
import dev.agentdeck.companion.ui.STARTER_PROMPTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MobileChatWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `attention scope includes waiting and failed without mixing in finished backlog`() {
        val row = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!.rows.first()
        assertTrue(FleetFilter(scope = FleetScope.ATTENTION).matches(row.copy(attention = SessionAttentionState.WAITING_ON_YOU)))
        assertTrue(FleetFilter(scope = FleetScope.ATTENTION).matches(row.copy(attention = SessionAttentionState.FAILED)))
        assertFalse(FleetFilter(scope = FleetScope.ATTENTION).matches(row.copy(attention = SessionAttentionState.DONE_UNREVIEWED)))
        assertFalse(FleetFilter(scope = FleetScope.ATTENTION).matches(row.copy(attention = SessionAttentionState.RUNNING)))
        assertTrue(FleetFilter(scope = FleetScope.RUNNING).matches(row.copy(attention = SessionAttentionState.RUNNING)))
    }

    @Test fun `search matches current activity and respects project scope`() {
        val row = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!.rows.first().copy(liveLine = "Compiling the mobile release")
        assertTrue(FleetFilter(query = " MOBILE RELEASE ").matches(row))
        assertFalse(FleetFilter(query = "mobile release", projectPath = "/another-project").matches(row))
    }

    @Test fun `running tab and search compose before clearing filters restores chats`() {
        val snapshot = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!
        val running = snapshot.rows.first { it.attention == SessionAttentionState.RUNNING }
        var opened: String? = null
        compose.setContent {
            var filter by remember { mutableStateOf(FleetFilter()) }
            AgentDeckTheme(dynamic = false) {
                FleetScreen(
                    snapshot, filter, FleetSort.RECENT, false, emptyMap(), null,
                    onFilter = { filter = it }, onSort = {}, onRefresh = {},
                    onOpen = { opened = it.key }, onSnooze = {}, onStop = {},
                )
            }
        }
        compose.onNodeWithText("Running", substring = false).performClick()
        compose.onNodeWithText("Search chats").performTextInput("does not exist")
        compose.onNodeWithText("Nothing matches").assertIsDisplayed()
        compose.onNodeWithText("Clear filters").performClick()
        compose.onNodeWithText("Running", substring = false).performClick()
        compose.onNodeWithText(running.title).performClick()
        assertEquals(running.key, opened)
    }

    @Test fun `starter populates editable draft and does not send until the user starts`() {
        var draft = ""
        var sent = ""
        compose.setContent {
            var text by remember { mutableStateOf("") }
            AgentDeckTheme(dynamic = false) {
                NewChatScreen(
                    target = NewChatTarget("/project", AgentVendor.CLAUDE),
                    openProjects = listOf("/project"), vendors = listOf(AgentVendor.CLAUDE),
                    hello = null, draft = text, sending = false, notice = null,
                    onTarget = {}, onDraft = { text = it; draft = it },
                    onSend = { sent = text }, onDismissNotice = {},
                )
            }
        }
        compose.onNodeWithText("Review changes").performClick()
        assertEquals(STARTER_PROMPTS.first().second, draft)
        assertEquals("", sent)
        compose.onNodeWithText("Your task").performTextInput(" Focus on connectivity.")
        compose.onNodeWithText("Start chat").assertIsEnabled().performClick()
        assertEquals(draft, sent)
        assertTrue(sent.contains("Focus on connectivity."))
    }
}
