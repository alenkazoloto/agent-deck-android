package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.PendingPhoto
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

/**
 * The composer's two M6 surfaces, against the real `ConversationScreen`.
 *
 * Both are capability-gated, so each case is asked twice — with the machine advertising and
 * without. A test that only ever showed the advertising machine would pass identically if the
 * gate were deleted, which is the failure `memory/evidence.md` keeps a whole index about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ComposerAttachmentInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val photos = mutableStateOf(emptyList<PendingPhoto>())
    private val removed = mutableListOf<String>()
    private val queries = mutableListOf<String>()

    @Test
    fun `the attach button appears only while the machine accepts photos`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS))
        compose.onNodeWithContentDescription("Attach a photo").assertExists()
    }

    @Test
    fun `a machine that does not accept photos shows no attach button`() {
        show(capabilities = emptyList())
        compose.onNodeWithContentDescription("Attach a photo").assertDoesNotExist()
    }

    @Test
    fun `an uploaded photo is a chip the reader can take back, and may be sent alone`() {
        photos.value = listOf(PendingPhoto("id-1", "Photo · 1.2 MB"))
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS))

        compose.onNodeWithText("Photo · 1.2 MB").assertExists()
        // A photo with no typed text is still a message — the desktop's rule, and the send
        // button must not sit disabled over it.
        compose.onNodeWithContentDescription("Send").assertIsEnabled()

        compose.onNodeWithText("Photo · 1.2 MB").performClick()
        assertEquals(listOf("id-1"), removed)
    }

    @Test
    fun `typing an at queries the machine and accepting a row rewrites the draft`() {
        show(capabilities = listOf(MobileProtocol.Capability.FILES))

        compose.onNode(hasSetTextAction()).performTextReplacement("fix @Conv")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.onNodeWithText("ui/Conversation.kt").assertExists()

        compose.onNodeWithText("ui/Conversation.kt").performClick()
        compose.runOnIdle {
            assertEquals("fix @ui/Conversation.kt ", draft.value)
        }
    }

    @Test
    fun `a machine that does not serve files never opens the popup`() {
        show(capabilities = emptyList())

        compose.onNode(hasSetTextAction()).performTextReplacement("fix @Conv")
        compose.waitForIdle()

        assertTrue("the popup queried a machine that never advertised files", queries.isEmpty())
        compose.onNodeWithText("ui/Conversation.kt").assertDoesNotExist()
    }

    private fun show(capabilities: List<String>) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Fix the tests", turns = emptyList(), hasMore = false,
            costUsd = 0.0, costKnown = false, contextPct = null, model = null,
            liveLine = null, running = false, generatedAtMs = DeckFixtures.NOW,
        )
        compose.setContent {
            AgentDeckTheme {
                ConversationScreen(
                    target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project"),
                    page = page,
                    loading = false,
                    cached = false,
                    draft = draft.value,
                    notice = null,
                    onDraft = { draft.value = it },
                    onSend = { _, _ -> },
                    onStop = {},
                    onDismissNotice = {},
                    hello = MobileHello(
                        protocolVersion = MobileProtocol.VERSION,
                        machineName = "desk",
                        ideName = "IDEA",
                        pluginVersion = "1.0",
                        capabilities = capabilities,
                    ),
                    photos = photos.value,
                    onRemovePhoto = { removed += it },
                    onSearchFiles = { query ->
                        queries += query
                        listOf("ui/Conversation.kt")
                    },
                )
            }
        }
    }
}
