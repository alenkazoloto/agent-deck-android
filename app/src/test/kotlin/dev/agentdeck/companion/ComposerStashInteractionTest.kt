package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.data.ComposerStashes
import dev.agentdeck.companion.data.PendingPhoto
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ComposerStashActions
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * P15: the stash's two controls on the real `ConversationScreen` — the tray beside the run line
 * while something is typed, and the "stashed" chip on the empty composer that lists every entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerStashInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val drafts = mutableStateOf(mapOf<String, String>())

    @Test
    fun `stash parks the typed message and the chip puts it back`() {
        show()

        compose.onNode(hasSetTextAction()).performTextReplacement("check the flaky test tomorrow")
        compose.onNodeWithContentDescription("Stash this message for later").performClick()
        compose.runOnIdle { assertEquals("", drafts.value[KEY]) }

        compose.onNodeWithText("1 message stashed").performClick()
        compose.onNodeWithText("Stashed messages").assertExists()
        compose.onNodeWithText("check the flaky test tomorrow").performClick()

        compose.runOnIdle { assertEquals("check the flaky test tomorrow", drafts.value[KEY]) }
        compose.onNodeWithText("1 message stashed").assertDoesNotExist()
    }

    @Test
    fun `discarding the last entry closes the list and removes the chip`() {
        drafts.value = ComposerStashes.stash(mapOf(KEY to "never mind"), KEY, DeckFixtures.NOW)!!
        show()

        compose.onNodeWithText("1 message stashed").performClick()
        compose.onNodeWithContentDescription("Discard “never mind”").performClick()

        compose.onNodeWithText("Stashed messages").assertDoesNotExist()
        compose.onNodeWithText("1 message stashed").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", drafts.value[KEY].orEmpty()) }
    }

    @Test
    fun `no stash while a photo waits, since the photo would stay behind for the next send`() {
        show(photos = listOf(PendingPhoto("att-1", "240 KB")))

        compose.onNode(hasSetTextAction()).performTextReplacement("what is wrong here?")

        compose.onNodeWithContentDescription("Stash this message for later").assertDoesNotExist()
    }

    @Test
    fun `an empty stash shows no chip`() {
        show()

        compose.onNodeWithText("message stashed", substring = true).assertDoesNotExist()
    }

    /** Not a golden: the rendered controls as evidence, written under `build/outputs/p15/`. */
    @Test
    fun `render the stash button and list`() {
        var d = ComposerStashes.stash(mapOf(KEY to "rename the parser after the tests pass"), KEY, DeckFixtures.NOW - 60 * 60_000)!!
        d = ComposerStashes.stash(d + (KEY to "check the flaky test tomorrow"), KEY, DeckFixtures.NOW - 3 * 60_000)!!
        drafts.value = d + (KEY to "ask for a summary of the diff")
        show(hello = MobileHello(
            protocolVersion = MobileProtocol.VERSION, machineName = "desk", ideName = "IDEA", pluginVersion = "1.0",
            capabilities = listOf(MobileProtocol.Capability.MODELS, MobileProtocol.Capability.EFFORT),
            effort = mapOf(AgentVendor.CLAUDE to listOf(MobileModelOption("high", "High"))),
        ))
        compose.onRoot().captureRoboImage("build/outputs/p15/stash-button.png", RECORD)

        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onRoot().captureRoboImage("build/outputs/p15/stash-chip.png", RECORD)
        compose.onNodeWithText("2 messages stashed").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p15/stash-sheet.png", RECORD)
    }

    private fun show(hello: MobileHello? = null, photos: List<PendingPhoto> = emptyList()) {
        val page = MobileTranscriptPage(
            key = KEY, title = "Fix the tests", turns = emptyList(), hasMore = false,
            costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ConversationScreen(
                        target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                        page = page,
                        loading = false,
                        cached = false,
                        draft = drafts.value[KEY].orEmpty(),
                        notice = null,
                        onDraft = { drafts.value = drafts.value + (KEY to it) },
                        onSend = { _, _ -> },
                        onStop = {},
                        onDismissNotice = {},
                        hello = hello,
                        photos = photos,
                        stash = ComposerStashActions(
                            entries = ComposerStashes.entries(drafts.value, KEY),
                            onStash = { ComposerStashes.stash(drafts.value, KEY, DeckFixtures.NOW)?.let { drafts.value = it } },
                            onRestore = { id -> ComposerStashes.restore(drafts.value, KEY, id, DeckFixtures.NOW)?.let { drafts.value = it } },
                            onDiscard = { id -> ComposerStashes.discard(drafts.value, KEY, id)?.let { drafts.value = it } },
                        ),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        const val KEY = "conversation"
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
