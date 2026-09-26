package dev.agentdeck.companion

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewHunk
import com.github.claudeagents.core.mobile.MobileReviewMark
import com.github.claudeagents.core.mobile.MobileReviewRevertRequest
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The M4 journeys of PLAN-MOBILE-REDESIGN.md: J05 (Review → task → file → diff → Mark reviewed,
 * with a newer desk edit taking the tick back) and J06 (preview and revert chosen files: nothing
 * claimed before the machine answers, a moved file fails closed, a lost answer is retried under
 * one operation id, unrelated files survive). Production Activity, ViewModel, SecureStore and
 * pinned HTTPS client against [StoryBridge]; the machine's own revert guards have their own
 * `MobileReview*WiringTest`s.
 */
@RunWith(AndroidJUnit4::class)
class M4JourneysE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val evidence = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            runCatching { screenshot("m4-failed-${description.methodName}") }
            bridges.forEach { b -> android.util.Log.i("M4Journeys", "commands: " + b.commands.map { it.first + " " + it.second }) }
        }
    }
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private val bridges = mutableListOf<StoryBridge>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
    }

    @After fun finish() {
        runCatching { scenario?.close() }
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridges.forEach { it.close() }
    }

    /**
     * J05: the queue opens on the task's files in two taps from Chats, a file opens its diff, and
     * Mark reviewed lands on the machine for that file alone. A desk edit made afterwards takes the
     * tick back on the machine, and the phone shows what the machine says rather than what it
     * remembers. Marking everything empties the queue.
     */
    @Test fun reviewQueueToDiffToTickAndANewerEditTakesTheTickBack() {
        val desk = deskWithChanges()
        cache(desk)
        launch()
        chats()

        openReview()
        awaitText("0 of 3 reviewed")
        screenshot("m4-j05-queue")
        // Two taps from Chats — the destination, then the row — and the changed files are up.
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("Login.kt")
        ui.onNodeWithText("Session.kt").assertExists()
        ui.onNodeWithText("notes.md").assertExists()
        screenshot("m4-j05-files")

        ui.onNodeWithText("Login.kt").performClick()
        awaitText("val token = refresh()")
        screenshot("m4-j05-diff")
        ui.onNodeWithText("Mark reviewed").performClick()
        await("the machine's tick") { desk.tick("src/Login.kt") }
        assertEquals("only the open file is ticked", listOf(false, false), listOf(desk.tick("src/Session.kt"), desk.tick("docs/notes.md")))
        assertEquals(listOf(listOf("src/Login.kt")), desk.marks())
        awaitText("Reviewed")
        ui.onNodeWithContentDescription("Back to the changed files").performClick()
        await("ticked row") { has("Reviewed: src/Login.kt. Tap to clear.") }

        // The desk edits the ticked file: the machine's tick is gone, and the phone reads it again.
        desk.editOnDesk("story-chat", "src/Login.kt", "retry(token)")
        ui.onNodeWithContentDescription("Back").performClick()
        openReview()
        awaitText("1 of 3 reviewed")
        ui.onNodeWithText("Review navigation").performClick()
        await("the tick the desk took back") { has("Mark reviewed: src/Login.kt") }
        assertFalse(has("Reviewed: src/Login.kt. Tap to clear."))
        ui.onNodeWithText("Login.kt").performClick()
        awaitText("retry(token)")
        ui.onNodeWithContentDescription("Back to the changed files").performClick()

        ui.onNodeWithText("Mark all reviewed").performClick()
        await("every tick on the machine") { listOf("src/Login.kt", "src/Session.kt", "docs/notes.md").all { desk.tick(it) } }
        ui.onNodeWithContentDescription("Back").performClick()
        openReview()
        awaitText("Nothing to review")
        screenshot("m4-j05-empty")
    }

    /**
     * J06: the sheet names the ticked files and only those go out; while the machine has not
     * answered the phone claims nothing and the machine has written nothing; the answer lands and
     * the file left unticked survives.
     */
    @Test fun revertNamesOnlyTheTickedFilesAndClaimsNothingBeforeTheMachineAnswers() {
        val desk = deskWithChanges()
        cache(desk)
        launch()
        chats()
        openChanges()

        openRevertSheet()
        ui.onNodeWithText("Revert 3 files").assertExists()
        ui.onNodeWithText("docs/notes.md").performClick()
        ui.onNodeWithText("Revert 2 files").assertExists()
        screenshot("m4-j06-preview")

        val held = desk.holdNextRevert()
        ui.onNodeWithTag("revert-confirm").performClick()
        assertTrue("the confirmed revert reached the machine", held.received.await(10, TimeUnit.SECONDS))
        ui.onNodeWithTag("revert-sheet").assertExists()
        ui.onNodeWithTag("revert-confirm").assertIsNotEnabled()
        assertFalse("no success before the machine answers", has("Reverted", substring = true))
        assertTrue("nothing written yet", desk.revertedPaths.isEmpty())
        screenshot("m4-j06-waiting")

        held.release()
        awaitText("Reverted 2 files.")
        assertEquals(listOf("src/Login.kt", "src/Session.kt"), desk.revertedPaths.toList())
        assertEquals("unrelated work survives", listOf("docs/notes.md"), desk.reviewFiles.getValue("story-chat").map { it.path })
        val sent = desk.reverts().single()
        assertEquals(listOf("src/Login.kt", "src/Session.kt"), sent.paths)
        assertEquals(MobileReviewRevertRequest.SESSION, sent.scope)
        await("the list without the reverted files") { !has("Login.kt", substring = true) }
        ui.onNodeWithText("notes.md").assertExists()
    }

    /**
     * J06: a file moved after the preview. The machine writes nothing and refuses; the phone says
     * so, reads the files again with the reader's ticks and reverts only after a second, informed
     * confirmation under the machine's new preview.
     */
    @Test fun aRevertOverAMovedFileWritesNothingAndAsksAgain() {
        val desk = deskWithChanges()
        desk.revertAnswer = StoryBridge.RevertAnswer.STALE
        cache(desk)
        launch()
        chats()
        openChanges()

        openRevertSheet()
        ui.onNodeWithTag("revert-confirm").performClick()
        awaitText(MobileRefusal.REVERT_PREVIEW_STALE.message)
        assertTrue("nothing was written", desk.revertedPaths.isEmpty())
        // The sheet re-reads under the message; the reader's three ticks come back with the preview.
        awaitText("Revert 3 files")
        ui.onNodeWithTag("revert-sheet").assertExists()
        screenshot("m4-j06-stale")

        desk.revertAnswer = StoryBridge.RevertAnswer.REVERTS
        ui.onNodeWithTag("revert-confirm").performClick()
        awaitText("Reverted 3 files.")
        assertEquals("the second confirmation carries the machine's new preview", listOf("preview-1", "preview-2"),
            desk.reverts().map { it.previewToken })
        assertEquals(3, desk.revertedPaths.size)
    }

    /**
     * J06: the write landed but the answer was lost. The phone says it cannot tell, offers the
     * revert again, and the retry carries the first attempt's operation id, so the machine writes
     * once.
     */
    @Test fun aLostRevertAnswerIsNotSuccessAndItsRetryWritesOnce() {
        val desk = deskWithChanges()
        desk.revertAnswer = StoryBridge.RevertAnswer.LOST_ACK
        cache(desk)
        launch()
        chats()
        openChanges()

        openRevertSheet()
        ui.onNodeWithTag("revert-confirm").performClick()
        await("the write on the machine") { desk.revertedPaths.size == 3 }
        await("the phone's own doubt") { has("revert-error", tag = true) }
        assertFalse("a lost answer is never reported as done", has("Reverted", substring = true))
        ui.onNodeWithTag("revert-confirm").assertIsEnabled()
        screenshot("m4-j06-lost-answer")

        desk.revertAnswer = StoryBridge.RevertAnswer.REVERTS
        ui.onNodeWithTag("revert-confirm").performClick()
        awaitText("Reverted 3 files.")
        val sends = desk.reverts()
        assertEquals(2, sends.size)
        assertEquals("the retry is the same operation", sends[0].operationId, sends[1].operationId)
        assertEquals("one write however many sends", 3, desk.revertedPaths.size)
    }

    private fun deskWithChanges(): StoryBridge {
        val desk = bridge()
        desk.reviews = true
        desk.rows = desk.rows.filter { it.key == "story-chat" }
        desk.reviewFiles["story-chat"] = listOf(
            MobileReviewFile("src/Login.kt", MobileReviewFile.MODIFIED, added = 2, removed = 1),
            MobileReviewFile("src/Session.kt", MobileReviewFile.MODIFIED, added = 1, removed = 1),
            MobileReviewFile("docs/notes.md", MobileReviewFile.ADDED, added = 4),
        )
        desk.reviewHunks["src/Login.kt"] = listOf(
            MobileReviewHunk(10, 10, listOf(" fun login() {", "-    val token = old()", "+    val token = refresh()", "+    audit(token)", " }")),
        )
        desk.reviewHunks["src/Session.kt"] = listOf(MobileReviewHunk(3, 3, listOf("-    ttl = 60", "+    ttl = 300")))
        desk.reviewHunks["docs/notes.md"] = listOf(MobileReviewHunk(0, 1, listOf("+# Notes")))
        desk.syncRow("story-chat")
        return desk
    }

    private fun StoryBridge.tick(path: String) = reviewFiles.getValue("story-chat").first { it.path == path }.reviewed

    private fun StoryBridge.marks() = commands.filter { it.first == "/v1/review/story-chat" }
        .map { MobileReviewMark.fromJson(it.second).paths }

    private fun StoryBridge.reverts() = commands.filter { it.first == "/v1/review/story-chat/revert" }
        .map { MobileReviewRevertRequest.fromJson(it.second) }

    private fun openChanges() {
        openReview()
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("Login.kt")
    }

    private fun openRevertSheet() {
        ui.onNodeWithTag("changes-revert").performClick()
        awaitText("Revert to session start")
        awaitText("src/Session.kt")
    }

    private fun openReview() = ui.onNode(destination("Review"), useUnmergedTree = true).performClick()

    private fun destination(label: String) = SemanticsMatcher("destination $label") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .any { it == label || it.startsWith("$label, ") }
    }

    private fun has(text: String, substring: Boolean = false, tag: Boolean = false): Boolean =
        if (tag) ui.onAllNodesWithTag(text).fetchSemanticsNodes().isNotEmpty()
        else ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun bridge() = StoryBridge().also { bridges += it }

    /** Saving activates, so the machine cached last is the one the app opens on. */
    private fun cache(bridge: StoryBridge) {
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        store.cacheTranscript(bridge.machine.id, bridge.page)
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = java.io.File(app.getExternalFilesDir(null), "story-evidence").apply { mkdirs() }
        java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP uninstalls the target after a connected run, including its external files.
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun chats() { awaitText("Search chats"); ui.onNodeWithText("Search chats").assertIsDisplayed() }
    private fun awaitText(text: String) = await("text $text") { has(text, substring = true) }

    /** A timed-out wait photographs the screen first: the watcher runs after the activity has closed. */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            ui.waitUntil(10_000, condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { screenshot("m4-timeout-" + what.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)) }
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }
}
