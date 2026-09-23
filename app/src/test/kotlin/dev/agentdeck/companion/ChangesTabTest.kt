package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewHunk
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.changesSummary
import dev.agentdeck.companion.ui.changesTabLabel
import dev.agentdeck.companion.ui.fileCounts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading and clearing an agent's changes from the phone.
 *
 * The questions are the ones a reviewer's thumb asks: is the tab there at all against a machine
 * that cannot serve it; does the checklist cost a request before somebody looks; does a tick say
 * what it did; and is the *reason* visible when the tick is withheld — a "Mark reviewed" that is
 * simply gone reads as a bug, so the running conversation's sentence has to be on the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChangesTabTest {

    @get:Rule
    val compose = createComposeRule()

    private val asked = mutableListOf<String>()
    private val marks = mutableListOf<Pair<List<String>, Boolean>>()
    private val opened = mutableListOf<String>()

    private val review = MobileReviewList(
        key = "conversation",
        files = listOf(
            MobileReviewFile("src/Clock.kt", MobileReviewFile.MODIFIED, added = 31, removed = 4),
            MobileReviewFile("docs/time.md", MobileReviewFile.MODIFIED, added = 6, removed = 2, reviewed = true),
        ),
        added = 37, removed = 6, reviewedFiles = 1,
    )

    private val diff = MobileReviewFileDiff(
        file = review.files.first(),
        hunks = listOf(
            MobileReviewHunk(17, 17, listOf(" import java.time.Instant", "-fun now() = 0L", "+fun now() = clock()")),
        ),
        omittedBytes = 41_000,
    )

    @Test
    fun `a machine that cannot serve changes offers no tab`() {
        show(canReview = false)
        compose.onNodeWithText("Messages").assertDoesNotExist()
        compose.onNodeWithText("Changes").assertDoesNotExist()
    }

    @Test
    fun `the checklist is fetched when the tab is opened and not before`() {
        show(review = null)

        assertEquals("opening a conversation must not cost a transcript scan", emptyList<String>(), asked)
        compose.onNodeWithText("Changes").performClick()
        assertEquals("the tab opens and asks in one gesture", listOf("open", "fetch"), asked)
    }

    @Test
    fun `the checklist names the files, their sizes and which are already reviewed`() {
        show(review = review, changesOpen = true)

        compose.onNodeWithText("2 files · +37 −6").assertIsDisplayed()
        compose.onNodeWithText("Clock.kt").assertIsDisplayed()
        compose.onNodeWithText("+31 −4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mark reviewed: src/Clock.kt").assertIsOff()
        compose.onNodeWithContentDescription("Reviewed: docs/time.md. Tap to clear.").assertIsOn()
    }

    @Test
    fun `ticking a file asks the machine for that file alone`() {
        show(review = review, changesOpen = true)

        compose.onNodeWithContentDescription("Mark reviewed: src/Clock.kt").performClick()
        assertEquals(listOf(listOf("src/Clock.kt") to true), marks)

        compose.onNodeWithText("Mark all reviewed").performClick()
        assertEquals("an empty list is the machine's own 'everything'", emptyList<String>(), marks.last().first)
    }

    @Test
    fun `tapping a file asks for that file's diff`() {
        show(review = review, changesOpen = true)
        compose.onNodeWithText("Clock.kt").performClick()
        assertEquals(listOf("src/Clock.kt"), opened)
    }

    @Test
    fun `an open file renders its hunks by line prefix and names what was cut`() {
        show(review = review, changesOpen = true, path = "src/Clock.kt", diff = diff)
        compose.onNodeWithText("-fun now() = 0L").assertIsDisplayed()
        compose.onNodeWithText("+fun now() = clock()").assertIsDisplayed()
        compose.onNodeWithText("… 40 KB of this diff omitted. Open it in the IDE to read the rest.")
            .assertIsDisplayed()
    }

    @Test
    fun `a running conversation shows why the tick is withheld instead of hiding it silently`() {
        show(review = review.copy(writerNotice = "This conversation is still running."), changesOpen = true)

        compose.onNodeWithText("This conversation is still running.").assertIsDisplayed()
        compose.onNodeWithText("Mark all reviewed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Mark reviewed: src/Clock.kt").assertDoesNotExist()
    }

    /** The device-free half: the two sentences, pinned without composing anything. */
    @Test
    fun `the summary drops sizes it could not measure, and the tab counts files`() {
        assertEquals("2 files · +37 −6", changesSummary(review))
        assertEquals("Changes (2)", changesTabLabel(review))
        assertEquals("Changes", changesTabLabel(null))

        val unmeasured = review.copy(files = review.files + MobileReviewFile("gen/X.kt", MobileReviewFile.UNKNOWN, note = "no before"))
        assertEquals("3 files", changesSummary(unmeasured))
        assertEquals("new file · +88", fileCounts(MobileReviewFile("a", MobileReviewFile.ADDED, added = 88)))
        assertEquals("deleted", fileCounts(MobileReviewFile("a", MobileReviewFile.REMOVED)))
    }

    private fun show(
        canReview: Boolean = true,
        review: MobileReviewList? = this.review,
        changesOpen: Boolean = false,
        path: String? = null,
        diff: MobileReviewFileDiff? = null,
    ) {
        val page = MobileTranscriptPage(
            key = "conversation", title = "Move time behind a clock",
            turns = listOf(MobileTurn(id = "t0", role = "assistant", text = "Done.", timestampMs = DeckFixtures.NOW)),
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
                    onDismissNotice = {},
                    hello = MobileHello(
                        MobileProtocol.VERSION, "mac-mini", "IntelliJ IDEA", "1.0",
                        if (canReview) listOf(MobileProtocol.Capability.REVIEW) else emptyList(),
                    ),
                    canReview = canReview,
                    changesOpen = changesOpen,
                    onChangesOpen = { open -> if (open) asked.add("open") },
                    review = review,
                    reviewPath = path,
                    reviewDiff = diff,
                    onOpenChanges = { asked.add("fetch") },
                    onOpenReviewFile = { opened.add(it) },
                    onMarkReviewed = { paths, reviewed -> marks.add(paths to reviewed) },
                )
            }
        }
    }
}
