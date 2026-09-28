package dev.agentdeck.companion

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileAnswerRequest
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import com.github.claudeagents.core.mobile.MobilePendingPermission
import com.github.claudeagents.core.mobile.MobileQuestion
import com.github.claudeagents.core.mobile.MobileQuestionOption
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The J01 journey of PLAN-MOBILE-REDESIGN.md (M9 runbook step 2): a "Waiting on you" row opens
 * straight to the ask it names, a pick or a decision settles it on the machine, and a stale card
 * — one the agent has since replaced with a newer ask — cannot settle the newer one. Both
 * `/v1/answer` and `/v1/decision` are already bound to the ask's own id in production
 * (`RemoteQuestionRelay.answer`, `MobileActions.decide`); this proves the phone's own card honours
 * it end to end against [StoryBridge], not merely that the host route refuses a mismatched id.
 */
@RunWith(AndroidJUnit4::class)
class M5JourneysE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val evidence = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            runCatching { screenshot("m5-failed-${description.methodName}") }
            bridges.forEach { b -> android.util.Log.i("M5Journeys", "commands: " + b.commands.map { it.first + " " + it.second }) }
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
     * J01: Chats groups a parked question under "Waiting for your answer"; the row opens straight
     * to the card, and the tapped option is accepted and clears the row on both screens.
     */
    @Test fun needsYouRowOpensToAQuestionAndAPickSettlesIt() {
        val desk = askQuestion("ask-1", "What next?", listOf("Proceed", "Hold off"))
        cache(desk)
        launch()
        chats()

        awaitText("Waiting for your answer")
        screenshot("m5-j01-needs-you-question")
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("What next?")
        screenshot("m5-j01-question-card")

        ui.onNodeWithText("Proceed").performClick()
        awaitText("Answer sent")
        assertEquals("the pick reached the machine bound to its own ask", listOf("ask-1" to "Proceed"), desk.answers())
        ui.onNodeWithContentDescription("Back").performClick()
        await("the row's waiting cleared") { !has("Waiting for your answer") }
        screenshot("m5-j01-question-settled")
    }

    /**
     * J01: while the reader was looking at the screen the agent asked a newer question. The stale
     * card's tap is refused as `question-superseded` — never answered as the new ask, and never
     * sent as a fresh prompt into the run either — and the transcript reloads to the ask that
     * actually stands.
     */
    @Test fun aStaleQuestionCannotAnswerTheNewerAskAndTheCardReloads() {
        val desk = askQuestion("ask-1", "What next?", listOf("Proceed", "Hold off"))
        cache(desk)
        launch()
        chats()
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("What next?")

        // The agent moves on to a second question before the reader taps the first one.
        desk.replaceQuestion("ask-2", "Pick a target", listOf("Staging", "Production"))

        ui.onNodeWithText("Proceed").performClick()
        awaitText(MobileRefusal.QUESTION_SUPERSEDED.message)
        assertEquals("the newer ask was never answered", MobileToolCall.RUNNING, desk.runningAsk()?.status)
        assertEquals("ask-2", desk.runningAsk()?.id)
        screenshot("m5-j01-stale-question")
        awaitText("Pick a target")
        ui.onNodeWithText("Staging").assertExists()
    }

    /**
     * J01: the permission twin of the question journey — the row names the ask, Allow lands on
     * the machine bound to the exact request, and settling it clears the row.
     */
    @Test fun needsYouRowOpensToAPermissionAndAllowSettlesIt() {
        val desk = askPermission("req-1", "Bash", "rm -rf build/")
        cache(desk)
        launch()
        chats()

        awaitText("Waiting for tool permission")
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("Allow Bash?")
        screenshot("m5-j01-permission-card")

        ui.onNodeWithText("Allow").performClick()
        awaitText("Allowed")
        assertEquals(listOf("req-1"), desk.decisions())
        ui.onNodeWithContentDescription("Back").performClick()
        await("the row's waiting cleared") { !has("Waiting for tool permission") }
    }

    /**
     * J01: a decision made on a permission card the agent has since replaced is refused as
     * `permission-gone` — it never allows the newer ask by accident — and the card reloads to it.
     */
    @Test fun aStalePermissionCannotSettleTheNewerAskAndTheCardReloads() {
        val desk = askPermission("req-1", "Bash", "rm -rf build/")
        cache(desk)
        launch()
        chats()
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("Allow Bash?")

        // The agent's run moved on to a second tool permission before the reader taps the first.
        desk.replacePermission("req-2", "Edit", "src/Login.kt")

        ui.onNodeWithText("Allow").performClick()
        awaitText(MobileRefusal.PERMISSION_GONE.message)
        assertEquals("the newer ask was never decided", "req-2", desk.page.pendingPermission?.requestId)
        screenshot("m5-j01-stale-permission")
        awaitText("Allow Edit?")
    }

    private fun askQuestion(askId: String, header: String, options: List<String>): StoryBridge {
        val desk = bridge()
        desk.rows = desk.rows.filter { it.key == "story-chat" }
            .map { it.copy(attention = SessionAttentionState.WAITING_ON_YOU, waitingReason = WaitingReason.QUESTION) }
        desk.page = desk.page.copy(
            running = true,
            turns = listOf(MobileTurn("opening", "assistant", "One more thing before I continue.", System.currentTimeMillis(),
                toolCalls = listOf(question(askId, header, options)))),
        )
        return desk
    }

    private fun askPermission(requestId: String, tool: String, detail: String): StoryBridge {
        val desk = bridge()
        desk.rows = desk.rows.filter { it.key == "story-chat" }
            .map { it.copy(attention = SessionAttentionState.WAITING_ON_YOU, waitingReason = WaitingReason.PERMISSION) }
        desk.page = desk.page.copy(
            running = true,
            turns = emptyList(),
            pendingPermission = MobilePendingPermission(requestId, tool, "$tool call", detail),
        )
        return desk
    }

    private fun question(id: String, header: String, options: List<String>) = MobileToolCall(
        id = id, name = "AskUserQuestion", title = header, summary = "", status = MobileToolCall.RUNNING,
        questions = listOf(MobileQuestion("q1", header, options.map { MobileQuestionOption(it) })),
    )

    /** The agent asked a newer question: the running call the machine answers for is the new one. */
    private fun StoryBridge.replaceQuestion(askId: String, header: String, options: List<String>) {
        page = page.copy(turns = listOf(MobileTurn("opening-2", "assistant", header, System.currentTimeMillis(),
            toolCalls = listOf(question(askId, header, options)))))
    }

    /** The agent's run moved past the first ask onto a second tool permission. */
    private fun StoryBridge.replacePermission(requestId: String, tool: String, detail: String) {
        page = page.copy(pendingPermission = MobilePendingPermission(requestId, tool, "$tool call", detail))
    }

    private fun StoryBridge.answers() = commands.filter { it.first == "/v1/answer" }
        .map { MobileAnswerRequest.fromJson(it.second) }
        .map { it.askId to it.answers["q1"] }

    private fun StoryBridge.runningAsk() = page.turns.flatMap { it.toolCalls }.firstOrNull { it.questions.isNotEmpty() }

    private fun StoryBridge.decisions() = commands.filter { it.first == "/v1/decision" }
        .map { MobileDecisionRequest.fromJson(it.second).requestId }

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

    private fun has(text: String, substring: Boolean = false): Boolean =
        ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun chats() { awaitText("Search chats"); ui.onNodeWithText("Search chats").assertIsDisplayed() }
    private fun awaitText(text: String) = await("text $text") { has(text, substring = true) }

    /** A timed-out wait photographs the screen first: the watcher runs after the activity has closed. */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            ui.waitUntil(10_000, condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { screenshot("m5-timeout-" + what.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)) }
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }
}
