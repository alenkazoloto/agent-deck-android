package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.data.ComposerStashes
import dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PendingPhoto
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ComposerStashActions
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.NewChatScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P15: the stash on the new-chat composer — the tray beside "Start chat" while a task is typed,
 * and the "stashed" chip on the empty prompt. Its queue is the `new-chat` draft's own, so a task
 * parked here never surfaces in a conversation's stash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NewChatStashInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val drafts = mutableStateOf(mapOf<String, String>())

    @Test
    fun `stash parks the typed task and the chip puts it back`() {
        show()

        compose.onNodeWithText("Your task").performTextReplacement("add retries to the uploader")
        compose.onNodeWithContentDescription("Stash this task for later").performClick()
        compose.runOnIdle {
            assertEquals("", drafts.value[NEW_CHAT_DRAFT_KEY])
            assertEquals(listOf("add retries to the uploader"), ComposerStashes.entries(drafts.value, NEW_CHAT_DRAFT_KEY).map { it.text })
        }

        compose.onNodeWithText("1 message stashed").performClick()
        compose.onNodeWithText("add retries to the uploader").performClick()

        compose.runOnIdle { assertEquals("add retries to the uploader", drafts.value[NEW_CHAT_DRAFT_KEY]) }
        compose.onNodeWithText("1 message stashed").assertDoesNotExist()
    }

    @Test
    fun `the new-chat stash is its own queue, not a conversation's`() {
        drafts.value = ComposerStashes.stash(mapOf("conversation" to "a conversation's reply"), "conversation", DeckFixtures.NOW)!!
        show()

        compose.onNodeWithText("message stashed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `no stash while a photo waits, since the photo would start the next chat`() {
        show(photos = listOf(PendingPhoto("att-1", "240 KB")))

        compose.onNodeWithText("Your task").performTextReplacement("what is wrong here?")

        compose.onNodeWithContentDescription("Stash this task for later").assertDoesNotExist()
    }

    @Test
    fun `no stash button with nothing typed`() {
        show()

        compose.onNodeWithContentDescription("Stash this task for later").assertDoesNotExist()
    }

    /** Not a golden: the rendered controls as evidence, written under `build/outputs/p15/`. */
    @Test
    fun `render the new-chat stash button and chip`() {
        drafts.value = ComposerStashes.stash(
            mapOf(NEW_CHAT_DRAFT_KEY to "rename the parser after the tests pass"), NEW_CHAT_DRAFT_KEY, DeckFixtures.NOW - 60 * 60_000,
        )!! + (NEW_CHAT_DRAFT_KEY to "add retries to the uploader")
        show()
        compose.onRoot().captureRoboImage("build/outputs/p15/new-chat-stash-button.png", RECORD)

        compose.onNodeWithText("Your task").performTextReplacement("")
        compose.onRoot().captureRoboImage("build/outputs/p15/new-chat-stash-chip.png", RECORD)
        compose.onNodeWithText("1 message stashed").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p15/new-chat-stash-sheet.png", RECORD)
    }

    private fun show(photos: List<PendingPhoto> = emptyList()) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    NewChatScreen(
                        target = NewChatTarget("/project", AgentVendor.CLAUDE),
                        openProjects = listOf("/project"), vendors = listOf(AgentVendor.CLAUDE),
                        hello = null, draft = drafts.value[NEW_CHAT_DRAFT_KEY].orEmpty(), sending = false, notice = null,
                        onTarget = {}, onDraft = { drafts.value = drafts.value + (NEW_CHAT_DRAFT_KEY to it) },
                        onSend = {}, onDismissNotice = {},
                        photos = photos,
                        stash = ComposerStashActions(
                            entries = ComposerStashes.entries(drafts.value, NEW_CHAT_DRAFT_KEY),
                            onStash = { ComposerStashes.stash(drafts.value, NEW_CHAT_DRAFT_KEY, DeckFixtures.NOW)?.let { drafts.value = it } },
                            onRestore = { id -> ComposerStashes.restore(drafts.value, NEW_CHAT_DRAFT_KEY, id, DeckFixtures.NOW)?.let { drafts.value = it } },
                            onDiscard = { id -> ComposerStashes.discard(drafts.value, NEW_CHAT_DRAFT_KEY, id)?.let { drafts.value = it } },
                        ),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
