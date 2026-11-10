package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFollowUpSource
import com.github.claudeagents.core.mobile.MobileScheduledFollowUp
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduledScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A follow-up opens onto the sessions it waited on; only a chat the machine lists is tappable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduledFollowUpsInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("scheduled")!!
    private val opened = mutableListOf<List<String?>>()

    private val followUp = MobileScheduledFollowUp(
        id = "f1", status = "Finished", projectPath = "/work/project",
        resultKey = "v2:result", resultVendor = AgentVendor.CODEX,
        sources = listOf(
            MobileFollowUpSource("Implementation session", "finished", true, "v2:a", AgentVendor.CODEX),
            MobileFollowUpSource("Test session", "finished", false, null, null),
        ),
    )

    private fun show(followUps: List<MobileScheduledFollowUp>) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = emptyList(), loading = false, canCreate = true,
                        projects = listOf("/work/project"), hello = fixture.hello,
                        draft = "", onDraft = {}, onRefresh = {}, onCreate = { _, _, _, _ -> },
                        onCommand = { _, _, _ -> },
                        followUps = followUps,
                        onOpenFollowUpChat = { key, vendor, project, title -> opened += listOf(key, vendor?.name, project, title) },
                    )
                }
            }
        }
    }

    @Test
    fun `a follow-up is closed on what it came to and opens onto its sources`() {
        show(listOf(followUp))

        compose.onNodeWithText("Follow-ups").assertIsDisplayed()
        compose.onNodeWithText("Follow-up · Finished · 2 sources").assertIsDisplayed()
        compose.onAllNodesWithTag("scheduled-follow-up-line").assertCountEquals(0)

        compose.onNodeWithText("Follow-up · Finished · 2 sources").performClick()
        compose.onAllNodesWithTag("scheduled-follow-up-line").assertCountEquals(3)
        compose.onNodeWithText("Implementation session · finished · context included").assertIsDisplayed()
        compose.onNodeWithText("Test session · finished").assertIsDisplayed()
    }

    @Test
    fun `a listed source and the result open their chats and an unlisted one offers nothing`() {
        show(listOf(followUp))
        compose.onNodeWithText("Follow-up · Finished · 2 sources").performClick()

        compose.onNodeWithText("Open result").performClick()
        compose.onNodeWithText("Implementation session · finished · context included").performClick()
        compose.onNodeWithText("Test session · finished").performClick()

        assertEquals(
            listOf(
                listOf("v2:result", "CODEX", "/work/project", "Follow-up"),
                listOf("v2:a", "CODEX", "/work/project", "Implementation session"),
            ),
            opened,
        )
    }

    @Test
    fun `nothing admitted draws no heading`() {
        show(emptyList())

        compose.onAllNodesWithText("Follow-ups").assertCountEquals(0)
        compose.onAllNodesWithTag("scheduled-follow-up").assertCountEquals(0)
    }
}
