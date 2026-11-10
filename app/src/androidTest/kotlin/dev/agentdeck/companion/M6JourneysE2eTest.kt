package dev.agentdeck.companion

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduledCommand
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.ui.Times
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The J09 journey of PLAN-MOBILE-REDESIGN.md (M9 runbook step 2): Pause, Resume, Run now and
 * Cancel each reach the machine with the row's own id and the list repaints from its answer, a
 * "Run now" outcome's "Recent runs" row opens the exact chat it wrote with Back returning to the
 * list, and a repeat's edit form names the host's own zone and its DST policy for the pick about
 * to be saved (`Times.dstNote`). The DST assertion computes its expectation from the same pure
 * function the UI calls rather than assuming a calendar phase, so the case holds on any run date —
 * `TimesTest` pins the exact six-months-out math against fixed instants.
 */
@RunWith(AndroidJUnit4::class)
class M6JourneysE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val evidence = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            runCatching { screenshot("m6-failed-${description.methodName}") }
            bridges.forEach { b -> android.util.Log.i("M6Journeys", "commands: " + b.commands.map { it.first + " " + it.second }) }
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
     * J09: a daily repeat's edit form names the machine's own time zone and states this pick's
     * DST policy — never left for the reader to discover that "09:00" moves with the clock in
     * `America/New_York` while a fixed interval does not. The expectation is computed from
     * [Times.dstNote] itself, so the assertion holds whatever day this runs.
     */
    @Test fun editingARepeatNamesTheHostZoneAndItsDstPolicy() {
        val zone = ZoneId.of("America/New_York")
        val desk = bridge()
        desk.timeZoneId = zone.id
        val detail = MobileScheduleEditDetail(
            id = "nightly", prompt = "Summarize today's commits", projectPath = "/work/project",
            sessionId = null, dueAtMs = System.currentTimeMillis() + 3_600_000L,
            repeatEveryMs = 0, repeatAtTime = "09:00", model = null, vendor = "CLAUDE",
            accountId = "default", afterSessionId = null, timeZoneId = zone.id,
            editable = true, canRepeat = true,
            accountOptions = listOf(MobileScheduleAccountOption("default", "Personal")),
        )
        desk.scheduleDetails["nightly"] = detail
        desk.scheduled = listOf(MobileScheduledRow(
            id = "nightly", prompt = detail.prompt, projectPath = detail.projectPath, sessionId = null,
            dueAtMs = detail.dueAtMs, state = MobileScheduledRow.QUEUED, repeating = true, cadence = "Daily at 09:00",
        ))
        cache(desk)
        launch()
        schedule()

        ui.onNodeWithText("Summarize today's commits").performClick()
        awaitText("schedule-edit-form", byTag = true)
        screenshot("m6-j09-edit-opened")

        // The form opens with the machine's saved daily repeat: the note the reader sees is
        // whatever the same function says for "09:00" right now, computed independently here
        // rather than assumed, so the case is not tied to today's place in the DST calendar.
        assertDstNoteMatches(Times.dstNote("09:00", 0L, zone, System.currentTimeMillis()))

        // Flipping the pick to a fixed interval swaps in the interval's own reading for the
        // value about to be saved, not the one on file — the edit form's own pick, never the
        // machine's stored one.
        ui.onNodeWithText("In").performClick()
        assertDstNoteMatches(Times.dstNote(null, 3_600_000L, zone, System.currentTimeMillis()))
    }

    /**
     * J09: Pause, Resume, Run now and Cancel each carry the row's own id to the machine and the
     * list repaints only from its answer, never a locally guessed state; a "Run now" outcome's
     * "Recent runs" row opens the exact chat it wrote and Back returns to the list.
     */
    @Test fun pauseResumeRunNowAndCancelReachTheMachineAndAnOutcomeOpensItsChat() {
        val desk = bridge()
        desk.scheduled = listOf(MobileScheduledRow(
            id = "nightly", prompt = "Summarize today's commits", projectPath = "/work/project",
            sessionId = null, dueAtMs = System.currentTimeMillis() + 3_600_000L,
            state = MobileScheduledRow.QUEUED, repeating = false,
        ))
        cache(desk)
        launch()
        schedule()
        awaitText("Summarize today's commits")

        ui.onNodeWithContentDescription("Pause").performClick()
        await("the row shows paused") { has("Status: paused", substring = true) }
        assertEquals(listOf("nightly"), desk.commandIds(MobileScheduledCommand.PAUSE))
        screenshot("m6-j09-paused")

        ui.onNodeWithContentDescription("Resume").performClick()
        await("the row shows queued") { has("Status: queued", substring = true) }
        assertEquals(listOf("nightly"), desk.commandIds(MobileScheduledCommand.RESUME))

        ui.onNodeWithContentDescription("Run now").performClick()
        awaitText("Recent runs")
        awaitText("Ran")
        assertEquals(listOf("nightly"), desk.commandIds(MobileScheduledCommand.RUN_NOW))
        screenshot("m6-j09-ran")

        // The outcome names the fixture's own conversation ("story-chat"): tapping it opens
        // that transcript, and Back returns to the list rather than to Chats.
        ui.onNodeWithTag("scheduled-outcome").performClick()
        awaitText("Navigation review is ready.")
        screenshot("m6-j09-outcome-opened")
        ui.onNodeWithContentDescription("Back").performClick()
        awaitText("Recent runs")

        ui.onNodeWithContentDescription("Cancel this prompt").performClick()
        awaitText("Cancel this prompt?")
        ui.onNodeWithText("Cancel them").performClick()
        // "Summarize today's commits" also names the outcome row Run now just wrote under
        // "Recent runs", which Cancel does not touch — the scheduled row itself is what
        // disappears, and only it carries this action.
        await("the row is gone") { !has("Cancel this prompt") }
        assertEquals(listOf("nightly"), desk.commandIds(MobileScheduledCommand.CANCEL))
        screenshot("m6-j09-cancelled")
    }

    private fun assertDstNoteMatches(expected: String?) {
        if (expected == null) {
            await("no DST note is shown") { !has("schedule-dst-note", byTag = true) }
        } else {
            await("the DST note appears") { has("schedule-dst-note", byTag = true) }
            ui.onNodeWithTag("schedule-dst-note").assertTextEquals(expected)
        }
    }

    private fun StoryBridge.commandIds(action: String) = commands
        .filter { it.first == "/v1/scheduled" && it.second["action"]?.asString == action }
        .flatMap { it.second["ids"].asJsonArray.map { id -> id.asString } }

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

    private fun has(text: String, substring: Boolean = false, byTag: Boolean = false): Boolean = if (byTag) {
        ui.onAllNodesWithTag(text).fetchSemanticsNodes().isNotEmpty()
    } else {
        ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun schedule() { awaitText("Schedule"); ui.onNodeWithText("Schedule").performClick() }
    private fun awaitText(text: String, byTag: Boolean = false) = await("${if (byTag) "tag" else "text"} $text") { has(text, substring = true, byTag = byTag) }

    /** A timed-out wait photographs the screen first: the watcher runs after the activity has closed. */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            ui.waitUntil(10_000, condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { screenshot("m6-timeout-" + what.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)) }
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }
}
