package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import com.github.claudeagents.core.mobile.MobileDiffView
import com.github.claudeagents.core.mobile.MobileReviewSort
import com.github.claudeagents.core.mobile.MobileReviewSortOption
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import dev.agentdeck.companion.data.BridgeClient
import java.io.InputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewHunk
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileReviewScope
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.fixture.DeckFixtures
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ReviewNotesUi
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.changesSummary
import dev.agentdeck.companion.ui.changesTabLabel
import dev.agentdeck.companion.ui.fileCounts
import dev.agentdeck.companion.ui.steppedPath
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private val opened = mutableListOf<Pair<String, String?>>()
    private val bases = mutableListOf<String?>()
    private val whitespace = mutableListOf<Boolean>()
    private val sorts = mutableListOf<String>()

    /** The open file, followed from what the tab asks to open, as the view model does. */
    private var openPath by mutableStateOf<String?>(null)

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
        assertEquals(listOf("src/Clock.kt" to null), opened)
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

    private val scoped = review.copy(
        files = review.files + MobileReviewFile("gen/Loose.kt", MobileReviewFile.UNKNOWN, note = "no before"),
        requests = listOf(
            MobileReviewScope("p1", 1, "Move time\n behind a clock", "Request 1 · 1 file · +31 −4", listOf("src/Clock.kt")),
            MobileReviewScope("p2", 2, "Document it", "Request 2 · 2 files · +37 −6", listOf("src/Clock.kt", "docs/time.md")),
            MobileReviewScope(null, 0, "Other changes", "Other changes · 1 file", listOf("gen/Loose.kt")),
        ),
    )

    @Test
    fun `a review without request groups offers no scope`() {
        show(review = review, changesOpen = true)
        compose.onNodeWithText("Whole session").assertDoesNotExist()
    }

    @Test
    fun `choosing a request lists its files alone, under the desk's label`() {
        show(review = scoped, changesOpen = true)
        compose.onNodeWithText("3 files").assertIsDisplayed()

        compose.onNodeWithText("Whole session").performClick()
        compose.onNodeWithText("1. Move time behind a clock").performClick()

        compose.onNodeWithText("Request 1 · 1 file · +31 −4").assertIsDisplayed()
        compose.onNodeWithText("Clock.kt").assertIsDisplayed()
        compose.onNodeWithText("time.md").assertDoesNotExist()
        compose.onNodeWithText("Loose.kt").assertDoesNotExist()
    }

    @Test
    fun `a file opened under a request asks for that request's diff, and mark all stays in scope`() {
        show(review = scoped, changesOpen = true)
        compose.onNodeWithText("Whole session").performClick()
        compose.onNodeWithText("2. Document it").performClick()

        compose.onNodeWithText("Mark all reviewed").performClick()
        assertEquals(listOf("src/Clock.kt", "docs/time.md") to true, marks.last())

        compose.onNodeWithText("Clock.kt").performClick()
        assertEquals("src/Clock.kt" to "p2", opened.last())
    }

    @Test
    fun `the other-changes bucket opens whole-session diffs`() {
        show(review = scoped, changesOpen = true)
        compose.onNodeWithText("Whole session").performClick()
        compose.onNodeWithText("Other changes").performClick()

        compose.onNodeWithText("Other changes · 1 file").assertIsDisplayed()
        compose.onNodeWithText("Loose.kt").performClick()
        assertEquals("gen/Loose.kt" to null, opened.last())
    }

    @Test
    fun `a request's diff names its sides, and a fallback says why it is the whole session`() {
        show(review = scoped, changesOpen = true, path = "src/Clock.kt", diff = diff.copy(beforeTitle = "Before request 1", afterTitle = "After request 1"))
        compose.onNodeWithText("Before request 1 → After request 1").assertIsDisplayed()
    }

    @Test
    fun `a request's diff offers no review notes, whose lines are the current file's`() {
        val notes = ReviewNotesUi(emptyList(), {}, { _, _, _, _ -> }, {})
        show(review = scoped, changesOpen = true, path = "src/Clock.kt", diff = diff.copy(beforeTitle = "Before request 1", afterTitle = "After request 1"), notes = notes)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertCountEquals(0)
    }

    @Test
    fun `a whole-session diff offers a note on every line`() {
        val notes = ReviewNotesUi(emptyList(), {}, { _, _, _, _ -> }, {})
        show(review = scoped, changesOpen = true, path = "src/Clock.kt", diff = diff, notes = notes)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertCountEquals(3)
    }

    @Test
    fun `a request diff that fell back to the whole session says so`() {
        val note = "This request left no recorded before and after for this file, so this is the whole session's diff."
        show(review = scoped, changesOpen = true, path = "src/Clock.kt", diff = diff.copy(scopeNote = note))
        compose.onNodeWithText(note).assertIsDisplayed()
    }

    @Test
    fun `a picked Base reaches the file it opens, as the desk side's Base holds its diff`() {
        show(changesOpen = true, diffBases = true)
        compose.onNodeWithText("Clock.kt").performClick()
        assertEquals("session start is the default and sends no base", null, bases.last())

        compose.onNodeWithText("Base: Session start").performClick()
        compose.onNodeWithText("HEAD~1").performClick()
        compose.onNodeWithText("Clock.kt").performClick()

        assertEquals("src/Clock.kt" to null, opened.last())
        assertEquals("HEAD~1", bases.last())
    }

    @Test
    fun `any revision can be typed, and the button then names it`() {
        show(changesOpen = true, diffBases = true)
        compose.onNodeWithText("Base: Session start").performClick()
        compose.onNodeWithText("Other revision…").performClick()
        compose.onNodeWithTag("changes-base-field").performTextInput(" release/2.0 ")
        compose.onNodeWithText("Compare").performClick()

        compose.onNodeWithText("Base: release/2.0").assertIsDisplayed()
        compose.onNodeWithText("time.md").performClick()
        assertEquals("release/2.0", bases.last())
    }

    @Test
    fun `typing the default's own name is the session's start, not a second row`() {
        show(changesOpen = true, diffBases = true)
        compose.onNodeWithText("Base: Session start").performClick()
        compose.onNodeWithText("Other revision…").performClick()
        compose.onNodeWithTag("changes-base-field").performTextInput("session start")
        compose.onNodeWithText("Compare").performClick()

        compose.onNodeWithText("Clock.kt").performClick()
        assertEquals(null, bases.last())
    }

    @Test
    fun `no Base against a machine that cannot take one`() {
        show(changesOpen = true)
        compose.onNodeWithText("Base: Session start").assertDoesNotExist()
    }

    @Test
    fun `a request's own diff neither shows nor sends the Base`() {
        show(review = scoped, changesOpen = true, diffBases = true)
        compose.onNodeWithText("Base: Session start").performClick()
        compose.onNodeWithText("HEAD").performClick()
        compose.onNodeWithText("Whole session").performClick()
        compose.onNodeWithText("Other changes").performClick()
        compose.onNodeWithText("Base: HEAD").assertDoesNotExist()
        compose.onNodeWithText("Loose.kt").performClick()
        assertEquals("a request scope has its own sides, so the base is not sent", null, bases.last())
    }

    @Test
    fun `a revision's diff names its sides`() {
        show(changesOpen = true, path = "src/Clock.kt", diff = diff.copy(beforeTitle = "main", afterTitle = "Current"), diffBases = true)
        compose.onNodeWithText("main → Current").assertIsDisplayed()
    }

    @Test
    fun `a missing commit says so where the diff would be`() {
        val missing = "No commit named nope in this repository."
        show(
            changesOpen = true, path = "src/Clock.kt", diffBases = true,
            diff = MobileReviewFileDiff(review.files.first().copy(note = missing), beforeTitle = "nope", afterTitle = "Current"),
        )
        compose.onNodeWithText(missing).assertIsDisplayed()
    }

    @Test
    fun `the client sends the base beside the path`() {
        val urls = mutableListOf<String>()
        val client = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
            urls += url.toString()
            object : HttpsURLConnection(url) {
                private val answer = diff.toJson().toString()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getCipherSuite() = "test"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
                override fun getResponseCode(): Int = 200
                override fun getInputStream(): InputStream = answer.byteInputStream()
                override fun getErrorStream(): InputStream = answer.byteInputStream()
            }
        }
        client.reviewFile("conversation", "src/Clock.kt", base = "HEAD~1")
        client.reviewFile("conversation", "src/Clock.kt")
        assertEquals(true, urls.first().endsWith("/file?path=src%2FClock.kt&base=HEAD%7E1"))
        assertEquals(true, urls.last().endsWith("/file?path=src%2FClock.kt"))
    }

    @Test
    fun `the diff's whitespace switch shows the desk's value and asks for the other`() {
        show(changesOpen = true, path = "src/Clock.kt", diff = diff, whitespaceSwitch = true)
        compose.onNodeWithTag("changes-ignore-whitespace").assertIsDisplayed().assertIsNotSelected().performClick()
        assertEquals(listOf(true), whitespace)
    }

    @Test
    fun `a diff cut without whitespace says so, and switching back asks to show it`() {
        show(
            changesOpen = true, path = "src/Clock.kt", whitespaceSwitch = true,
            diff = MobileReviewFileDiff(review.files.first(), ignoreWhitespace = true),
        )
        compose.onNodeWithText("No changes to show with whitespace ignored.").assertIsDisplayed()
        compose.onNodeWithTag("changes-ignore-whitespace").assertIsSelected().performClick()
        assertEquals(listOf(false), whitespace)
    }

    @Test
    fun `no whitespace switch against a machine that cannot write it`() {
        show(changesOpen = true, path = "src/Clock.kt", diff = diff)
        compose.onNodeWithTag("changes-ignore-whitespace").assertDoesNotExist()
    }

    @Test
    fun `the client writes the setting and reads back the machine's value`() {
        val sent = mutableListOf<String>()
        val client = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
            object : HttpsURLConnection(url) {
                private val body = java.io.ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getCipherSuite() = "test"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
                override fun getOutputStream(): java.io.OutputStream = body
                override fun getResponseCode(): Int = 200.also { sent += url.path + " " + body.toString() }
                override fun getInputStream(): InputStream = MobileDiffView(true).toJson().toString().byteInputStream()
                override fun getErrorStream(): InputStream = getInputStream()
            }
        }
        assertEquals(true, client.setDiffView(true).ignoreWhitespace)
        assertEquals(true, sent.single().startsWith(MobileDiffView.ROUTE + " "))
        assertEquals(true, sent.single().contains("\"ignoreWhitespace\":true"))
    }

    private val sorted = review.copy(
        sortOrder = "SIZE",
        sortOptions = listOf(
            MobileReviewSortOption("DEFAULT", "Session order"), MobileReviewSortOption("RISK", "Risk"),
            MobileReviewSortOption("NAME", "Name"), MobileReviewSortOption("SIZE", "Lines changed"),
        ),
    )

    @Test
    fun `the sort shows the desk's order under its label and asks for another`() {
        show(changesOpen = true, review = sorted, sortable = true)
        compose.onNodeWithTag("changes-sort").assertIsDisplayed()
        compose.onNodeWithText("Sort: Lines changed").assertIsDisplayed().performClick()
        compose.onNodeWithText("Risk").performClick()
        assertEquals(listOf("RISK"), sorts)
    }

    @Test
    fun `picking the order already in force writes nothing`() {
        show(changesOpen = true, review = sorted, sortable = true)
        compose.onNodeWithText("Sort: Lines changed").performClick()
        compose.onAllNodesWithText("Lines changed").onLast().performClick()
        assertEquals(emptyList<String>(), sorts)
    }

    @Test
    fun `no sort against a machine that cannot write it`() {
        show(changesOpen = true, review = sorted)
        compose.onNodeWithTag("changes-sort").assertDoesNotExist()
    }

    @Test
    fun `no sort over a list the machine did not order`() {
        show(changesOpen = true, review = review, sortable = true)
        compose.onNodeWithTag("changes-sort").assertDoesNotExist()
    }

    @Test
    fun `the client writes the sort and reads back the machine's order`() {
        val sent = mutableListOf<String>()
        val client = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
            object : HttpsURLConnection(url) {
                private val body = java.io.ByteArrayOutputStream()
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getCipherSuite() = "test"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
                override fun getOutputStream(): java.io.OutputStream = body
                override fun getResponseCode(): Int = 200.also { sent += url.path + " " + body.toString() }
                override fun getInputStream(): InputStream = MobileReviewSort("NAME").toJson().toString().byteInputStream()
                override fun getErrorStream(): InputStream = getInputStream()
            }
        }
        assertEquals("NAME", client.setReviewSort("NAME").order)
        assertEquals(true, sent.single().startsWith(MobileReviewSort.ROUTE + " "))
        assertEquals(true, sent.single().contains("\"order\":\"NAME\""))
    }

    @Test
    fun `the list's order and choices survive the wire`() {
        assertEquals(sorted, MobileReviewList.fromJson(sorted.toJson()))
        assertEquals(null, MobileReviewList.fromJson(review.toJson()).sortOrder)
    }

    @Test
    fun `an open diff steps to the next and previous file and wraps at both ends`() {
        show(review = scoped, changesOpen = true, diff = diff, follow = true)
        compose.onNodeWithText("Clock.kt").performClick()
        compose.onNodeWithText("File 1 of 3").assertIsDisplayed()

        compose.onNodeWithContentDescription("Next file").performClick()
        compose.onNodeWithText("File 2 of 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next file").performClick()
        compose.onNodeWithContentDescription("Next file").performClick()
        compose.onNodeWithContentDescription("Previous file").performClick()

        assertEquals(
            "the list's own order, wrapping past the last file and back",
            listOf("src/Clock.kt", "docs/time.md", "gen/Loose.kt", "src/Clock.kt", "gen/Loose.kt"),
            opened.map { it.first },
        )
    }

    @Test
    fun `stepping stays inside the chosen request and keeps its diff`() {
        show(review = scoped, changesOpen = true, diff = diff, follow = true)
        compose.onNodeWithText("Whole session").performClick()
        compose.onNodeWithText("2. Document it").performClick()
        compose.onNodeWithText("time.md").performClick()

        compose.onNodeWithText("File 2 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next file").performClick()
        assertEquals("src/Clock.kt" to "p2", opened.last())
    }

    @Test
    fun `a single changed file offers no stepping`() {
        show(review = scoped.copy(files = review.files.take(1), requests = emptyList()), changesOpen = true, path = "src/Clock.kt", diff = diff)
        compose.onNodeWithTag("changes-file-stepper").assertDoesNotExist()
    }

    @Test
    fun `a file the list no longer holds steps to its first or last file`() {
        val paths = listOf("a", "b", "c")
        assertEquals("b", steppedPath(paths, "a", forward = true))
        assertEquals("c", steppedPath(paths, "a", forward = false))
        assertEquals("a", steppedPath(paths, "gone", forward = true))
        assertEquals("c", steppedPath(paths, "gone", forward = false))
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
        notes: ReviewNotesUi? = null,
        diffBases: Boolean = false,
        whitespaceSwitch: Boolean = false,
        sortable: Boolean = false,
        follow: Boolean = false,
    ) {
        openPath = path
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
                    reviewPath = if (follow) openPath else path,
                    reviewDiff = diff,
                    onOpenChanges = { asked.add("fetch") },
                    onOpenReviewFile = { path, request, base -> opened.add(path to request); bases.add(base); openPath = path },
                    diffBases = diffBases,
                    onIgnoreWhitespace = if (whitespaceSwitch) { ignore -> whitespace.add(ignore) } else null,
                    onReviewSort = if (sortable) { order -> sorts.add(order) } else null,
                    onMarkReviewed = { paths, reviewed -> marks.add(paths to reviewed) },
                    reviewNotes = notes,
                )
            }
        }
    }
}
