package dev.agentdeck.companion

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFolder
import com.github.claudeagents.core.mobile.MobilePush
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionActionRequest
import com.github.claudeagents.core.mobile.MobileSessionSearchRequest
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.notify.DeckNotifications
import dev.agentdeck.companion.push.PushRegistration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M3 journeys of PLAN-MOBILE-REDESIGN.md: J08 (find, filter and organize chats, with each
 * change landing in the machine's stores) and J13 (two machines, a notification and a revoked
 * pairing, with nothing crossing between machines). Production Activity, ViewModel, SecureStore,
 * notifications and pinned HTTPS client against [StoryBridge]; no real agent runs.
 */
@RunWith(AndroidJUnit4::class)
class M3JourneysE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    @get:Rule val evidence = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable, description: org.junit.runner.Description) {
            runCatching { screenshot("m3-failed-${description.methodName}") }
            bridges.forEach { b -> android.util.Log.i("M3Journeys", "commands: " + b.commands.map { it.first + " " + it.second }) }
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
        // A notification's Open may leave the scenario's own instance paused under the one it raised.
        runCatching { scenario?.close() }
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        bridges.forEach { it.close() }
    }

    /**
     * J08: the message search names what it read and offers the rest; agent and folder filters
     * narrow the list; pin, Done with Undo, rename and move-to-folder each reach the machine and
     * the list shows the machine's answer; snooze stays a phone-side timer, apart from Done.
     */
    @Test fun searchFilterAndOrganizeReachTheMachine() {
        val desk = bridge()
        desk.organizes = true
        desk.folders = listOf(MobileFolder("f-release", "Release 1.4"))
        val base = desk.rows.first()
        val now = System.currentTimeMillis()
        desk.rows = listOf(
            base.copy(key = "codex-chat", title = "Tune the tokenizer", vendor = AgentVendor.CODEX,
                accountId = "codex-default", lastActivityMs = now - 60_000),
            base.copy(key = "second-chat", title = "Build account settings", lastActivityMs = now - 120_000),
            base.copy(key = "story-chat", title = "Review navigation", lastActivityMs = now - 180_000),
        )
        desk.bodies["codex-chat"] = "the login token refresh"
        desk.bodies["second-chat"] = "the login redirect loops"
        desk.searchPage = 1
        cache(desk)
        launch()
        chats()

        ui.onNodeWithText("Search chats").performTextInput("login")
        awaitText("…the login token refresh…")
        awaitText("The machine stopped after reading messages in 1 chat")
        ui.onNodeWithText("…the login redirect loops…").assertDoesNotExist()
        desk.searchPage = Int.MAX_VALUE
        // To the list's end, past the New-chat FAB that floats over its last row.
        ui.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        ui.onNodeWithText("Continue").performClick()
        awaitText("Messages searched in 3 chats")
        ui.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        awaitText("…the login redirect loops…")
        val pages = desk.commands.filter { it.first == MobileSessionSearchRequest.ROUTE }
            .map { MobileSessionSearchRequest.fromJson(it.second).keys }
        assertEquals("the second page resumes after the first, it does not re-read it", "codex-chat", pages.first().first())
        assertFalse(pages.last().contains("codex-chat"))
        ui.onNodeWithContentDescription("Clear search").performClick()

        ui.onNodeWithText("All agents").performClick()
        ui.onAllNodesWithText("Codex").onLast().performClick()
        awaitGone("Review navigation")
        ui.onNodeWithText("Tune the tokenizer").assertIsDisplayed()
        ui.onAllNodesWithText("Codex").onFirst().performClick()
        ui.onAllNodesWithText("All agents").onLast().performClick()
        awaitText("Review navigation")

        sheet("Build account settings", "Pin to the top")
        await("desk rows") { desk.rows.first { it.key == "second-chat" }.pinned }
        // The phone has taken the machine's answer once its sheet offers Unpin.
        ui.onNodeWithText("Build account settings").performTouchInput { longClick() }
        awaitText("Unpin")
        ui.onNodeWithText("Mark done").performClick()
        awaitGone("Build account settings")
        assertTrue(desk.rows.first { it.key == "second-chat" }.done)
        ui.onNodeWithText("Undo").performClick()
        awaitText("Build account settings")
        desk.rows.first { it.key == "second-chat" }.let {
            assertFalse("Undo reopens on the machine", it.done)
            assertTrue("Undo puts back the pin Done took", it.pinned)
        }

        sheet("Review navigation", "Rename")
        ui.onNode(hasSetTextAction() and hasText("Review navigation")).performTextReplacement("Navigation audit")
        ui.onAllNodesWithText("Rename").onLast().performClick()
        awaitText("Navigation audit")
        assertEquals("Navigation audit", desk.rows.first { it.key == "story-chat" }.title)

        sheet("Navigation audit", "Move to folder…")
        ui.onAllNodesWithText("Release 1.4").onLast().performClick()
        await("desk rows") { desk.rows.first { it.key == "story-chat" }.folderId == "f-release" }
        ui.onNodeWithText("All folders").performClick()
        ui.onAllNodesWithText("Release 1.4").onLast().performClick()
        awaitGone("Build account settings")
        ui.onNodeWithText("Navigation audit").assertIsDisplayed()
        ui.onAllNodesWithText("Release 1.4").onFirst().performClick()
        ui.onAllNodesWithText("All folders").onLast().performClick()
        awaitText("Build account settings")

        val organized = desk.commands.count { it.first == MobileSessionActionRequest.ROUTE }
        sheet("Tune the tokenizer", "Snooze until this agent moves")
        awaitGone("Tune the tokenizer")
        awaitText("1 conversation snoozed until its agent moves")
        assertEquals("a snooze is not the desk's Done", organized, desk.commands.count { it.first == MobileSessionActionRequest.ROUTE })
        assertFalse(desk.rows.first { it.key == "codex-chat" }.done)
        screenshot("m3-j08-organized")
    }

    /**
     * J13: two machines holding a chat under the same key. The draft typed on one never appears
     * on the other, each list is its own, and a reply sent after switching goes to the machine
     * on screen only.
     */
    @Test fun switchingMachinesKeepsDraftsListsAndSendsApart() {
        val (desk, laptop) = twoMachines()
        store.saveScreen(Navigation.toJson(Screen.Conversation("story-chat", "Review navigation", AgentVendor.CLAUDE, "/work/project")))
        launch()
        awaitText("Navigation review is ready.")
        ui.onNode(hasSetTextAction()).performTextInput("Desk only draft")
        ui.onNodeWithContentDescription("Back").performClick()
        chats()

        switchTo("Desk", "Laptop")
        awaitText("Laptop release notes")
        ui.onNodeWithText("Review navigation").assertDoesNotExist()
        ui.onNodeWithText("Laptop release notes").performClick()
        awaitText("Laptop answer is ready.")
        ui.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        ui.onNode(hasSetTextAction()).performTextInput("Laptop reply")
        // The switch's "Showing Laptop" is still up; it used to sit on the composer and take this tap.
        assertSnackbarAboveComposer("Showing Laptop", "m3-j13-snackbar-above-composer")
        ui.onNodeWithContentDescription("Send").performClick()
        await("laptop send") { laptop.commands.any { it.first == "/v1/send" } }
        ui.onNodeWithContentDescription("Back").performClick()

        switchTo("Laptop", "Desk")
        ui.onNodeWithText("Review navigation").performClick()
        awaitText("Navigation review is ready.")
        awaitText("Desk only draft")
        assertEquals(listOf("Laptop reply"), laptop.commands.filter { it.first == "/v1/send" }.map { it.second["prompt"].asString })
        assertTrue("the desk was sent nothing", desk.commands.none { it.first == "/v1/send" })
    }

    /**
     * J13: a notification posted for the desk still answers and opens the desk's chat after the
     * reader switched to the laptop, whose list holds a chat under the same key.
     */
    @Test fun aNotificationRepliesToAndOpensTheMachineThatPostedIt() {
        val (desk, laptop) = twoMachines()
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        store.saveSettings(store.settings().copy(triggers = setOf(NotifyTrigger.NEEDS_YOU)))
        launch()
        chats()
        PushRegistration.show(app, MobilePush.Payload(MobilePush.Trigger.NEEDS_YOU, listOf("story-chat"), 1))
        val notification = awaitNotification(DeckNotifications.idOf("story-chat"))
        switchTo("Desk", "Laptop")
        awaitText("Laptop release notes")

        val reply = notification.actions.first { it.title.toString() == "Reply" }
        val filled = Intent()
        RemoteInput.addResultsToIntent(reply.remoteInputs, filled,
            Bundle().apply { putCharSequence(DeckNotifications.REPLY_KEY, "From the shade") })
        reply.actionIntent.send(app, 0, filled)
        await("desk send") { desk.commands.any { it.first == "/v1/send" } }
        assertEquals("From the shade", desk.commands.first { it.first == "/v1/send" }.second["prompt"].asString)
        Thread.sleep(1_000)
        assertTrue("the laptop was sent the desk's reply", laptop.commands.none { it.first == "/v1/send" })

        // Opened from the laptop's own chat: a conversation replacing a conversation, whose outgoing
        // composer must not take the snackbar's lift with it.
        ui.onNodeWithText("Laptop release notes").performClick()
        awaitText("Laptop answer is ready.")
        notification.contentIntent.send()
        awaitText("Navigation review is ready.")
        ui.onNodeWithText("Laptop answer is ready.").assertDoesNotExist()
        assertEquals(desk.machine.id, store.paired()?.id)
        ui.onNode(hasSetTextAction()).performTextInput("Reply on the desk")
        assertSnackbarAboveComposer("Showing Desk")

        // A rotation keeps the used notification intent; it must not switch back after the reader left.
        ui.onNodeWithContentDescription("Back").performClick()
        switchTo("Desk", "Laptop")
        // The Open raised its own instance, so the scenario's is not the one on screen.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single().recreate()
        }
        instrumentation.waitForIdleSync()
        chats()
        Thread.sleep(1_000)
        ui.onNodeWithContentDescription("Machine: Laptop. Switch or pair a machine").assertExists()
        assertEquals(laptop.machine.id, store.paired()?.id)
    }

    private fun assertSnackbarAboveComposer(message: String, evidence: String? = null) {
        awaitText(message)
        val snack = ui.onNodeWithText(message).fetchSemanticsNode().boundsInWindow
        val send = ui.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInWindow
        val field = ui.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInWindow
        assertTrue("the snackbar covers the composer", snack.bottom <= minOf(send.top, field.top) + 1f)
        evidence?.let(::screenshot)
    }

    /**
     * J13: a machine that removed this phone says so, and "Pair again" opens the pairing form —
     * with a way back — rather than quietly showing the other paired machine's list.
     */
    @Test fun aRevokedPairingOpensThePairingFormAndKeepsTheOtherMachine() {
        val (desk, laptop) = twoMachines()
        store.activate(laptop.machine.id)
        laptop.revoked = true
        launch()
        awaitText(MobileRefusal.DEVICE_REVOKED.message)
        Thread.sleep(1_000) // the screen's fade-in, for the evidence frame only
        screenshot("m3-j13-revoked")
        ui.onNodeWithText("Pair again").performClick()
        awaitText("Pair with another machine")
        Thread.sleep(1_000)
        screenshot("m3-j13-pair-again")
        ui.onNodeWithText("Back to Workspace").assertExists()
        assertEquals(listOf(desk.machine.id), store.machines().map { it.id })
    }

    private fun twoMachines(): Pair<StoryBridge, StoryBridge> {
        val laptop = bridge("Laptop", "laptop-device")
        laptop.rows = laptop.rows.map { if (it.key == "story-chat") it.copy(title = "Laptop release notes") else it }
            .filter { it.key == "story-chat" }
        laptop.page = laptop.page.copy(title = "Laptop release notes",
            turns = listOf(MobileTurn("laptop-opening", "assistant", "Laptop answer is ready.", System.currentTimeMillis())))
        cache(laptop)
        val desk = bridge("Desk", "desk-device")
        cache(desk)
        return desk to laptop
    }

    private fun bridge(name: String = "Story workstation", device: String = "story-device") =
        StoryBridge(name, device).also { bridges += it }

    /** Saving activates, so the machine cached last is the one the app opens on. */
    private fun cache(bridge: StoryBridge) {
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        store.cacheTranscript(bridge.machine.id, bridge.page)
    }

    private fun switchTo(from: String, to: String) {
        ui.onNodeWithContentDescription("Machine: $from. Switch or pair a machine").performClick()
        ui.onAllNodesWithText(to).onLast().performClick()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithContentDescription("Machine: $to. Switch or pair a machine").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun sheet(row: String, action: String) {
        ui.onNodeWithText(row).performTouchInput { longClick() }
        ui.onNodeWithText(action).performClick()
    }

    private fun awaitNotification(id: Int): android.app.Notification {
        val manager = app.getSystemService(NotificationManager::class.java)
        ui.waitUntil(10_000) { manager.activeNotifications.any { it.id == id } }
        return manager.activeNotifications.first { it.id == id }.notification
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
    private fun awaitText(text: String) = await("text $text") {
        ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitGone(text: String) = await("gone $text") { ui.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    /** A timed-out wait photographs the screen first: the watcher runs after the activity has closed. */
    private fun await(what: String, condition: () -> Boolean) {
        try {
            ui.waitUntil(10_000, condition)
        } catch (e: ComposeTimeoutException) {
            runCatching { screenshot("m3-timeout-" + what.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)) }
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }
}
