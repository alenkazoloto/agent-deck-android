package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileMentionRow
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.MentionMatches
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
import org.robolectric.annotation.GraphicsMode

/**
 * The composer's two M6 surfaces, against the real `ConversationScreen`.
 *
 * Both are capability-gated, so each case is asked twice — with the machine advertising and
 * without. A test that only ever showed the advertising machine would pass identically if the
 * gate were deleted, which is the failure `memory/evidence.md` keeps a whole index about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerAttachmentInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = mutableStateOf("")
    private val photos = mutableStateOf(emptyList<PendingPhoto>())
    private val removed = mutableListOf<String>()
    private val pasted = mutableListOf<String>()
    private val queries = mutableListOf<String>()
    private var context = emptyList<MobileMentionRow>()
    private var indexing = false

    @Test
    fun `the attach button appears only while the machine accepts photos`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS))
        compose.onNodeWithContentDescription("Attach a photo").assertExists()
    }

    /** An older machine keeps the one-tap photo; `attachment-text` turns the paperclip into a two-item menu. */
    @Test
    fun `text files are offered only by a machine that advertises them`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS))
        compose.onNodeWithContentDescription("Attach a photo or file").assertDoesNotExist()
        compose.onNodeWithContentDescription("Attach a photo").assertExists()
    }

    @Test
    fun `the paperclip of a machine that takes text files asks photo or text file`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT))
        compose.onNodeWithContentDescription("Attach a photo").assertDoesNotExist()

        compose.onNodeWithContentDescription("Attach a photo or file").performClick()

        compose.onNodeWithText("Photo").assertExists()
        compose.onNodeWithText("Text file").assertExists()
    }

    @Test
    fun `a machine that also takes PDFs calls the file item File`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT, MobileProtocol.Capability.ATTACHMENT_PDF))

        compose.onNodeWithContentDescription("Attach a photo or file").performClick()

        compose.onNodeWithText("File").assertExists()
        compose.onNodeWithText("Text file").assertDoesNotExist()
    }

    @Test
    fun `a machine that takes archives but not PDFs also calls the file item File`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT, MobileProtocol.Capability.ATTACHMENT_ZIP))

        compose.onNodeWithContentDescription("Attach a photo or file").performClick()

        compose.onNodeWithText("File").assertExists()
        compose.onNodeWithText("Text file").assertDoesNotExist()
    }

    @Test
    fun `a machine that takes gzip files but not PDFs or archives also calls the file item File`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT, MobileProtocol.Capability.ATTACHMENT_GZIP))

        compose.onNodeWithContentDescription("Attach a photo or file").performClick()

        compose.onNodeWithText("File").assertExists()
        compose.onNodeWithText("Text file").assertDoesNotExist()
    }

    @Test
    fun `a machine that takes tar archives but not PDFs, archives or gzip also calls the file item File`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT, MobileProtocol.Capability.ATTACHMENT_TAR))

        compose.onNodeWithContentDescription("Attach a photo or file").performClick()

        compose.onNodeWithText("File").assertExists()
        compose.onNodeWithText("Text file").assertDoesNotExist()
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

    /**
     * The machine's git and past-chat rows follow the files, as the desk merges them, and a pick
     * inserts the desk's own token — the text the machine expands into the diff or the chat.
     */
    @Test
    fun `git and past-chat rows follow the files and insert the desk's tokens`() {
        context = listOf(
            MobileMentionRow("diff", "diff", "uncommitted changes", MobileMentionRow.Kind.WORKING_DIFF),
            MobileMentionRow("chat:1a2b3c4d", "chat:1a2b3c4d", "Auth rework · 2h", MobileMentionRow.Kind.CHAT),
        )
        indexing = true
        show(capabilities = listOf(MobileProtocol.Capability.FILES))

        compose.onNode(hasSetTextAction()).performTextReplacement("see @d")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.onNodeWithContentDescription("Past chat chat:1a2b3c4d, Auth rework · 2h").assertExists()
        // Git and chats need no index, so an indexing machine still offers them — and says why
        // the files are missing rather than hiding the rows it could answer.
        compose.onNodeWithText("The IDE is still indexing this project.").assertExists()
        val diffRow = compose.onNodeWithContentDescription("Uncommitted changes diff, uncommitted changes")
        val gitTop = diffRow.fetchSemanticsNode().boundsInRoot.top
        val fileTop = compose.onNodeWithText("ui/Conversation.kt").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the files do not lead the git row: $fileTop vs $gitTop", fileTop < gitTop)

        diffRow.performClick()
        compose.runOnIdle {
            assertEquals("see @diff ", draft.value)
        }
    }

    /** The desk's `@symbol:` row: labelled as a symbol, and a pick inserts the token the machine expands. */
    @Test
    fun `a code-symbol row names its declaration and inserts the desk's symbol token`() {
        context = listOf(
            MobileMentionRow("symbol:MentionSession", "symbol:MentionSession", "class · MentionSession.kt:120", MobileMentionRow.Kind.SYMBOL),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))

        compose.onNode(hasSetTextAction()).performTextReplacement("explain @Mention")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        val row = compose.onNodeWithContentDescription("Symbol symbol:MentionSession, class · MentionSession.kt:120")
        row.assertExists()

        row.performClick()
        compose.runOnIdle {
            assertEquals("explain @symbol:MentionSession ", draft.value)
        }
    }

    /** The desk's peer and extension rows: each says what it is, and a pick inserts its bare token. */
    @Test
    fun `a running-session row and an extension row insert the desk's tokens`() {
        context = listOf(
            MobileMentionRow("reviewer", "reviewer", "send message", MobileMentionRow.Kind.PEER),
            MobileMentionRow("notes:standup", "Stand-up notes", "09:30", MobileMentionRow.Kind.EXTENSION),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))

        compose.onNode(hasSetTextAction()).performTextReplacement("ask @re")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.onNodeWithContentDescription("Running session reviewer, send message").performClick()
        compose.runOnIdle { assertEquals("ask @reviewer ", draft.value) }

        compose.onNode(hasSetTextAction()).performTextReplacement("sum @no")
        compose.waitUntil(5_000) { queries.size >= 2 }
        compose.onNodeWithContentDescription("Extension Stand-up notes, 09:30").performClick()
        compose.runOnIdle { assertEquals("sum @notes:standup ", draft.value) }
    }

    /** A Codex chat's plugin row names itself as one, and a pick inserts the mention Codex resolves. */
    @Test
    fun `a Codex plugin row inserts its mention`() {
        context = listOf(
            MobileMentionRow("Template-Creator", "Template Creator", "Create or update reusable templates", MobileMentionRow.Kind.CODEX_PLUGIN),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))

        compose.onNode(hasSetTextAction()).performTextReplacement("use @temp")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.onNodeWithContentDescription("Codex plugin Template Creator, Create or update reusable templates").performClick()
        compose.runOnIdle { assertEquals("use @Template-Creator ", draft.value) }
    }

    /** Not a golden: a Codex plugin row as evidence, under `build/outputs/p15/`. */
    @Test
    fun `render the mention popup with Codex plugin rows`() {
        context = listOf(
            MobileMentionRow("PDF", "PDF", "Read, create, and verify PDF files", MobileMentionRow.Kind.CODEX_PLUGIN),
            MobileMentionRow("Template-Creator", "Template Creator", "Create or update reusable templates", MobileMentionRow.Kind.CODEX_PLUGIN),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))
        compose.onNode(hasSetTextAction()).performTextReplacement("use @t")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/p15/mention-codex-plugin-popup.png", RECORD)
    }

    /** Not a golden: peer and extension rows as evidence, under `build/outputs/p15/`. */
    @Test
    fun `render the mention popup with peer and extension rows`() {
        context = listOf(
            MobileMentionRow("reviewer", "reviewer", "send message", MobileMentionRow.Kind.PEER),
            MobileMentionRow("notes:standup", "Stand-up notes", "09:30", MobileMentionRow.Kind.EXTENSION),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))
        compose.onNode(hasSetTextAction()).performTextReplacement("ask @re")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/p15/mention-peer-extension-popup.png", RECORD)
    }

    /** Not a golden: the rendered popup as evidence, written under `build/outputs/p15/`. */
    @Test
    fun `render the mention popup with git and chat rows`() {
        context = listOf(
            MobileMentionRow("diff", "diff", "uncommitted changes", MobileMentionRow.Kind.WORKING_DIFF),
            MobileMentionRow("branch-diff", "branch-diff", "this branch vs main", MobileMentionRow.Kind.BRANCH_DIFF),
            MobileMentionRow("commit:7233791", "commit:7233791", "Fix the reconnect loop · 2 days ago", MobileMentionRow.Kind.COMMIT),
            MobileMentionRow("chat:1a2b3c4d", "chat:1a2b3c4d", "Auth rework · 2h", MobileMentionRow.Kind.CHAT),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))
        compose.onNode(hasSetTextAction()).performTextReplacement("look at @d")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/p15/mention-context-popup.png", RECORD)
    }

    /** Not a golden: the symbol rows as evidence, under `build/outputs/p15/`. */
    @Test
    fun `render the mention popup with symbol rows`() {
        context = listOf(
            MobileMentionRow("symbol:MentionSession", "symbol:MentionSession", "class · MentionSession.kt:120", MobileMentionRow.Kind.SYMBOL),
            MobileMentionRow("symbol:mentionSession", "symbol:mentionSession", "property · ChatInput.kt:88", MobileMentionRow.Kind.SYMBOL),
        )
        show(capabilities = listOf(MobileProtocol.Capability.FILES))
        compose.onNode(hasSetTextAction()).performTextReplacement("explain @MentionS")
        compose.waitUntil(5_000) { queries.isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/p15/mention-symbol-popup.png", RECORD)
    }

    @Test
    fun `a machine that does not serve files never opens the popup`() {
        show(capabilities = emptyList())

        compose.onNode(hasSetTextAction()).performTextReplacement("fix @Conv")
        compose.waitForIdle()

        assertTrue("the popup queried a machine that never advertised files", queries.isEmpty())
        compose.onNodeWithText("ui/Conversation.kt").assertDoesNotExist()
    }

    private val longPaste = (1..6).joinToString("\n") { "line $it of a stack trace" }

    /** A paste past the desk's rule leaves the field for an upload, only where an upload can take it. */
    @Test
    fun `a long paste leaves the field for an upload on a machine that takes text files`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT))

        compose.onNode(hasSetTextAction()).performTextReplacement(longPaste)
        compose.waitForIdle()

        assertEquals(listOf(longPaste), pasted)
        assertEquals("the field keeps nothing of it", "", draft.value)
    }

    @Test
    fun `a long paste stays in the field where the machine takes no text files`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS))

        compose.onNode(hasSetTextAction()).performTextReplacement(longPaste)
        compose.waitForIdle()

        assertTrue(pasted.isEmpty())
        assertEquals(longPaste, draft.value)
    }

    @Test
    fun `a short line stays in the field even where pastes are diverted`() {
        show(capabilities = listOf(MobileProtocol.Capability.ATTACHMENTS, MobileProtocol.Capability.ATTACHMENT_TEXT))

        compose.onNode(hasSetTextAction()).performTextReplacement("fix the test")
        compose.waitForIdle()

        assertTrue(pasted.isEmpty())
        assertEquals("fix the test", draft.value)
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
                    onPastedText = { pasted += it },
                    onSearchFiles = { query ->
                        queries += query
                        MentionMatches(listOf("ui/Conversation.kt"), context, indexing)
                    },
                )
            }
        }
    }

    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
