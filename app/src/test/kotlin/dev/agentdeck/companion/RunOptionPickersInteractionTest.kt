package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import dev.agentdeck.companion.data.ComposerPicks
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleTargetPickers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PLAN-MOBILE-PARITY M2: the effort and mode pills, driven through the production composables. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class RunOptionPickersInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val pills = DeckFixtures.byName("convo-composer-pills")!!
    private val hello = pills.hello!!

    /** The milestone's bar: a Codex prompt can be scheduled on Codex's own rungs and sandboxes. */
    @Test
    fun `a scheduled Codex prompt picks its effort and sandbox, and a switch back clears them`() {
        val target = mutableStateOf(NewChatTarget("/Users/dev/Plugin"))
        compose.setContent {
            AgentDeckTheme {
                Column {
                    ScheduleTargetPickers(
                        target = target.value,
                        projects = listOf("/Users/dev/Plugin"),
                        vendors = NewChat.vendorOptions(emptyList(), hello),
                        hello = hello,
                        onTarget = { target.value = it },
                    )
                }
            }
        }
        compose.onNodeWithText("Agent Claude").performClick()
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("Default effort").performClick()
        compose.onNodeWithText("High effort").performClick()
        compose.onNodeWithText("Mode Default").performClick()
        compose.onNodeWithText("Supervised").performClick()
        compose.onNodeWithText("Mode Supervised").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(AgentVendor.CODEX, target.value.vendor)
            assertEquals(MobileRunSelection(null, "high", "read-only"), NewChat.forWire(hello, target.value.selection))
        }

        compose.onNodeWithText("Agent Codex").performClick()
        compose.onNodeWithText("Claude").performClick()
        compose.runOnIdle {
            assertEquals("a Codex rung survived the switch to Claude", null, target.value.effort)
            assertEquals("a Codex sandbox survived the switch to Claude", null, target.value.permissionMode)
        }
    }

    @Test
    fun `the composer summarises its run settings only with a draft, and the sheet edits them`() {
        val draft = mutableStateOf("")
        val state = mutableStateOf(pills)
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = pills.screen as Screen.Conversation,
                        page = pills.transcript,
                        loading = false,
                        cached = false,
                        draft = draft.value,
                        notice = null,
                        onDraft = { draft.value = it },
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        hello = hello,
                        selection = state.value.composerSelection("convo-1"),
                        onPick = { field, value ->
                            state.value = state.value.copy(
                                composerPicks = mapOf("convo-1" to ComposerPicks().with(field, value)),
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("composer-run-pills").assertDoesNotExist()

        draft.value = "run the whole suite"
        // One line spelling every value the send names; the sheet behind it holds the pickers.
        compose.onNodeWithText("Opus 5 · Extra-high effort · Auto-accept edits").assertIsDisplayed()
        compose.onNodeWithText("Model Opus 5").assertDoesNotExist()
        compose.onNodeWithTag("composer-run-pills").performClick()
        compose.onNodeWithTag("run-settings-sheet").assertIsDisplayed()
        compose.onNodeWithText("Model Opus 5").assertIsDisplayed()
        compose.onNodeWithText("Mode Auto-accept edits").assertIsDisplayed()
        compose.onNodeWithText("Extra-high effort").performClick()
        compose.onNodeWithText("Max effort").performClick()
        compose.onNodeWithText("Max effort").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(MobileRunSelection("opus", "max", "acceptEdits"), state.value.composerSelection("convo-1"))
        }
        compose.onNodeWithText("Opus 5 · Max effort · Auto-accept edits").assertExists()
    }

    @Test
    fun `an older machine gets no composer pills even with a draft`() {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = pills.screen as Screen.Conversation,
                        page = pills.transcript,
                        loading = false,
                        cached = false,
                        draft = "run the whole suite",
                        notice = null,
                        onDraft = {},
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        hello = hello.copy(capabilities = hello.capabilities - MobileProtocol.Capability.EFFORT),
                        selection = pills.composerSelection("convo-1"),
                    )
                }
            }
        }
        compose.onNodeWithText("run the whole suite").assertIsDisplayed()
        compose.onNodeWithTag("composer-run-pills").assertDoesNotExist()
    }
}
