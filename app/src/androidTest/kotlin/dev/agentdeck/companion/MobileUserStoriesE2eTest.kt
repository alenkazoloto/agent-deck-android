package dev.agentdeck.companion

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.mobile.MobileTurn
import org.junit.Assert.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.fixture.DeckFixtures
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileUserStoriesE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private var bridge: StoryBridge? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private val conversation = Screen.Conversation("story-chat", "Review navigation", AgentVendor.CLAUDE, "/work/project")

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
    }

    @After fun finish() {
        scenario?.close()
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridge?.close()
    }

    @Test fun restoredConversationCanReturnToChatsAndEveryMainDestination() {
        val fixture = requireNotNull(DeckFixtures.byName("convo-idle"))
        val machine = requireNotNull(fixture.machine).copy(hosts = listOf("127.0.0.1"), port = 1,
            preferredHost = "127.0.0.1", deviceId = "navigation-e2e")
        store.save(machine)
        store.cacheSnapshot(machine.id, requireNotNull(DeckFixtures.byName("fleet-uncapped")!!.snapshot))
        store.cacheTranscript(machine.id, requireNotNull(fixture.transcript).copy(key = conversation.key))
        store.saveScreen(Navigation.toJson(conversation))
        launch()
        ui.onNodeWithContentDescription("Back").assertIsDisplayed()
        screenshot("restored-chat")
        ui.onNodeWithContentDescription("Back").performClick()
        ui.onNodeWithContentDescription("Back").assertDoesNotExist()
        ui.onNodeWithText("Search chats").assertIsDisplayed()
        screenshot("restored-chat-back-to-chats")
        ui.onNodeWithContentDescription("Scheduled", useUnmergedTree = true).performClick()
        awaitText("Nothing is scheduled on this machine.")
        ui.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Machine").assertIsDisplayed()
        ui.onNodeWithContentDescription("Chats", substring = true, useUnmergedTree = true).performClick()
        ui.onNodeWithText("Search chats").assertIsDisplayed()
    }

    @Test fun restoredConversationSystemBackAndRecreationKeepExitAndDraft() {
        connected(conversation)
        store.saveDrafts(requireNotNull(bridge).machine.id, mapOf(conversation.key to "Unsent review notes"))
        launch()
        ui.onNodeWithText("Unsent review notes").assertIsDisplayed()
        scenario!!.recreate()
        ui.onNodeWithText("Unsent review notes").assertIsDisplayed()
        ui.onNodeWithContentDescription("Back").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        chats()
        ui.onNodeWithText(conversation.title).performClick()
        ui.onNodeWithText("Unsent review notes").assertIsDisplayed()
    }

    @Test fun searchOpenAndReplyUsesRealBridgeAndClearsOnlyAcceptedDraft() {
        val server = connected()
        launch()
        ui.onNodeWithText("Search chats").performTextInput("Review navigation")
        ui.onNodeWithText("Build account settings").assertDoesNotExist()
        ui.onNodeWithContentDescription("Clear search").performClick()
        ui.onNodeWithText("Build account settings").assertExists()
        ui.onNodeWithText(conversation.title).performClick()
        awaitText("Navigation review is ready.")
        ui.onNode(hasSetTextAction()).performTextInput("Please check the back button")
        ui.onNodeWithContentDescription("Send").performClick()
        awaitCommand("/v1/send")
        awaitText("Please check the back button")
        ui.waitUntil(5_000) { store.drafts(server.machine.id)[conversation.key].isNullOrEmpty() }
        assertEquals(conversation.key, server.commands.last { it.first == "/v1/send" }.second["key"].asString)
        ui.onNodeWithContentDescription("Back").performClick()
        chats()
    }

    @Test fun refusedReplyPreservesDraftAndCanBeRetried() {
        val server = connected(conversation)
        server.refuseSend = true
        launch()
        awaitText("Navigation review is ready.")
        ui.onNode(hasSetTextAction()).performTextInput("Keep this task")
        ui.onNodeWithContentDescription("Send").performClick()
        awaitText("This project is not open in the IDE.")
        ui.onNode(hasSetTextAction()).assertTextContains("Keep this task")
        assertEquals("Keep this task", store.drafts(server.machine.id)[conversation.key])
        server.refuseSend = false
        ui.onNodeWithContentDescription("Send").performClick()
        ui.waitUntil(5_000) { store.drafts(server.machine.id)[conversation.key].isNullOrEmpty() }
        awaitText("Keep this task")
    }

    @Test fun newChatCanBeCancelledResumedAndStarted() {
        val server = connected()
        server.projects = listOf("/work/project", "/work/other")
        server.rows = server.rows.mapIndexed { index, row -> if (index == 1) row.copy(vendor = AgentVendor.CODEX) else row }
        store.cacheSnapshot(server.machine.id, server.snapshot)
        launch()
        ui.onNodeWithContentDescription("New chat").performClick()
        ui.onNodeWithText("Your task").performTextInput("Investigate mobile navigation")
        ui.onNodeWithContentDescription("Back").performClick()
        chats()
        ui.onNodeWithContentDescription("New chat").performClick()
        ui.onNodeWithText("Investigate mobile navigation").assertIsDisplayed()
        ui.onNodeWithText("Project: project").performScrollTo().performClick()
        ui.onNodeWithText("other", substring = false).performClick()
        ui.onNodeWithText("Agent: Claude").performScrollTo().performClick()
        ui.onNodeWithText("Codex", substring = false).performClick()
        awaitDescription("Model: Default")
        ui.onNodeWithContentDescription("Model: Default").performScrollTo().performClick()
        ui.onNodeWithText("gpt-5.1-codex", substring = false).performClick()
        ui.onNodeWithText("Start chat").performClick()
        awaitCommand("/v1/send")
        chats()
        awaitText("Investigate mobile navigation")
        val sent = server.commands.last { it.first == "/v1/send" }.second
        assertTrue(sent["newChat"].asBoolean)
        assertEquals("/work/other", sent["projectPath"].asString)
        assertEquals(AgentVendor.CODEX.name, sent["vendor"].asString)
        assertEquals("gpt-5.1-codex", sent["model"].asString)
    }

    @Test fun scheduledPromptCanBeCreatedPausedResumedRunAndCancelled() {
        val server = connected()
        launch()
        ui.onNodeWithContentDescription("Scheduled", useUnmergedTree = true).performClick()
        awaitText("Nothing is scheduled on this machine.")
        ui.onNodeWithContentDescription("Schedule a prompt").performClick()
        ui.onNodeWithText("What should the agent do?").performTextInput("Review tomorrow")
        ui.onNodeWithText("Schedule", substring = false).performClick()
        awaitText("Review tomorrow")
        val sent = server.commands.last { it.first == "/v1/send" }.second
        assertTrue(sent["dueAtMs"].asLong > System.currentTimeMillis())
        ui.onNodeWithContentDescription("Pause").performClick()
        ui.waitUntil(5_000) { server.scheduled.single().state == "paused" }
        awaitDescription("Resume")
        ui.onNodeWithContentDescription("Resume").performClick()
        awaitDescription("Pause")
        ui.onNodeWithContentDescription("Run now").performClick()
        ui.waitUntil(5_000) { server.commands.any { it.second["action"]?.asString == "run-now" } }
        val runNow = server.commands.last { it.first == "/v1/scheduled" && it.second["action"]?.asString == "run-now" }.second
        assertEquals(listOf("queued-story"), runNow["ids"].asJsonArray.map { it.asString })
        ui.onNodeWithContentDescription("Cancel this prompt").performClick()
        ui.onNodeWithText("Keep them").performClick()
        ui.onNodeWithText("Review tomorrow").assertIsDisplayed()
        ui.onNodeWithContentDescription("Cancel this prompt").performClick()
        ui.onNodeWithText("Cancel them").performClick()
        awaitText("Nothing is scheduled on this machine.")
        assertTrue(server.scheduled.isEmpty())
    }

    @Test fun settingsAndAddMachineCanAlwaysReturnToChats() {
        connected()
        launch()
        ui.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Pair another machine").performScrollTo().performClick()
        ui.onNodeWithText("Pair with another machine").assertIsDisplayed()
        ui.onNodeWithText("Back to Settings").performClick()
        ui.onNodeWithText("Machine").assertIsDisplayed()
        ui.onNodeWithContentDescription("Chats", useUnmergedTree = true).performClick()
        chats()
    }

    @Test fun notificationDeepLinkHasVisibleExitAndSharedTextRemainsUnsent() {
        val server = connected()
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(Navigation.link(conversation)), app, MainActivity::class.java))
        awaitText("Navigation review is ready.")
        ui.onNodeWithContentDescription("Back").performClick()
        chats()
        scenario!!.close()
        launch(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Inspect https://example.test/change"))
        ui.onNodeWithText(conversation.title).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("Inspect https://example.test/change")
        assertFalse(server.commands.any { it.first == "/v1/send" })
    }

    @Test fun latestMessageArrowReturnsFromHistoryToNewestTurn() {
        val server = connected(conversation)
        server.page = server.page.copy(turns = (1..40).map {
            MobileTurn("turn-$it", "assistant", "Review step $it. " + "Details of the navigation check. ".repeat(8),
                System.currentTimeMillis())
        })
        launch()
        val transcript = ui.onAllNodes(hasScrollAction()).filterToOne(hasAnyDescendant(hasText("Review step 40.", substring = true)))
        transcript.performScrollToIndex(0)
        awaitDescription("Latest message", substring = true)
        ui.onNodeWithContentDescription("Latest message", substring = true).assertIsDisplayed().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("Latest message", substring = true).fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText("Review step 40.", substring = true).assertIsDisplayed()
    }

    @Test fun firstPairingManualFormReachesChatsAndUnpairReturnsToPairing() {
        val server = StoryBridge().also { bridge = it }
        launch()
        ui.onNodeWithText("Host or IP").performScrollTo().performTextInput("127.0.0.1")
        ui.onNodeWithText("Port").performScrollTo().performTextReplacement(server.machine.port.toString())
        ui.onNodeWithText("8-digit code").performScrollTo().performTextInput("12345678")
        ui.onNodeWithText("Certificate fingerprint").performScrollTo().performTextInput(server.machine.spkiFingerprint)
        ui.onNodeWithText("Pair", substring = false).performScrollTo().performClick()
        awaitText("Not now")
        ui.onNodeWithText("Not now").performClick()
        chats()
        awaitCommand("/v1/pair")
        ui.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Unpair Story workstation…").performScrollTo().performClick()
        ui.onNodeWithText("Cancel", substring = false).performClick()
        ui.onNodeWithText("Unpair Story workstation…").performClick()
        ui.onNodeWithText("Unpair", substring = false).performClick()
        awaitText("Pair with a machine")
        assertNull(store.paired())
    }

    @Test fun stopAndStopThenSendReachTheRunningConversationInOrder() {
        val server = connected(conversation)
        server.page = server.page.copy(running = true, liveLine = "Checking navigation")
        launch()
        awaitDescription("Stop")
        ui.onNodeWithContentDescription("Stop").performClick()
        awaitCommand("/v1/stop")
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().isEmpty() }
        scenario!!.close()
        server.page = server.page.copy(running = true, liveLine = "Checking navigation again")
        launch()
        awaitDescription("Stop")
        ui.onNode(hasSetTextAction()).performTextInput("Change direction")
        ui.onNodeWithContentDescription("Stop & send").performClick()
        awaitCommand("/v1/send")
        val commands = server.commands.filter { it.first == "/v1/stop" || it.first == "/v1/send" }
        assertEquals(listOf("/v1/stop", "/v1/stop", "/v1/send"), commands.map { it.first })
        assertEquals("Change direction", commands.last().second["prompt"].asString)
    }

    @Test fun quickReplyAndFullscreenEditorKeepTextUntilExplicitSend() {
        val server = connected(conversation)
        launch()
        awaitText("Navigation review is ready.")
        ui.onNodeWithText("Summarize", substring = false).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("Summarize what changed", substring = true)
        assertFalse(server.commands.any { it.first == "/v1/send" })
        val longDraft = "Review these details carefully. ".repeat(25)
        ui.onNode(hasSetTextAction()).performTextReplacement(longDraft)
        ui.onNodeWithContentDescription("Write this in a full-screen editor").performClick()
        ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertTextContains(longDraft)
        ui.onNodeWithContentDescription("Back to the conversation").performClick()
        ui.onNode(hasSetTextAction()).assertTextContains(longDraft)
        ui.onNodeWithContentDescription("Back").performClick()
        chats()
        ui.onNodeWithText(conversation.title).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains(longDraft)
    }

    @Test fun answerAgentQuestionUsesTheAnswerRoute() {
        val server = connected(conversation)
        server.page = requireNotNull(DeckFixtures.byName("convo-question")?.transcript).copy(key = conversation.key)
        launch()
        awaitText("Pin the clock only")
        ui.waitUntil(10_000) { !ui.onNodeWithText("Pin the clock only").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        ui.onNodeWithText("Pin the clock only").assertIsEnabled().performScrollTo().performClick()
        awaitCommand("/v1/answer")
        val answered = server.commands.last { it.first == "/v1/answer" }.second
        assertEquals(conversation.key, answered["key"].asString)
        assertEquals(server.page.turns.flatMap { it.toolCalls }.flatMap { it.questions }.single().key,
            answered["answers"].asJsonObject.entrySet().single().key)
        assertEquals("Pin the clock only", answered["answers"].asJsonObject.entrySet().single().value.asString)
        assertFalse(server.commands.any { it.first == "/v1/send" })
    }

    @Test fun settingsPersistAndSwitchingMachinesKeepsEachDraft() {
        val server = connected()
        val laptop = server.machine.copy(machineName = "Story laptop", deviceId = "laptop-device")
        store.save(laptop)
        store.cacheSnapshot(laptop.id, server.snapshot)
        store.activate(server.machine.id)
        store.saveDrafts(server.machine.id, mapOf(conversation.key to "Desktop draft"))
        store.saveDrafts(laptop.id, mapOf(conversation.key to "Laptop draft"))
        launch()
        ui.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        ui.onNodeWithText("Match the system").performScrollTo().performClick()
        ui.onNodeWithText("Dark", substring = false).performClick()
        ui.waitUntil(5_000) { store.settings().theme == dev.agentdeck.companion.data.ThemeChoice.DARK }
        ui.onNodeWithText("Tell me about new versions").performScrollTo().performClick()
        assertTrue(store.settings().updateNotices)
        ui.onNodeWithText("Needs you", substring = false).performScrollTo().performClick()
        assertTrue(dev.agentdeck.companion.data.NotifyTrigger.NEEDS_YOU in store.settings().triggers)
        scenario!!.recreate()
        assertTrue(store.settings().updateNotices)
        assertTrue(dev.agentdeck.companion.data.NotifyTrigger.NEEDS_YOU in store.settings().triggers)
        ui.onNodeWithText("Dark", substring = false).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Story laptop", substring = false).performScrollTo().performClick()
        ui.waitUntil(5_000) { store.paired()?.id == laptop.id }
        ui.onNodeWithContentDescription("Chats", useUnmergedTree = true).performClick()
        chats()
        ui.onNodeWithText(conversation.title).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("Laptop draft")
        assertEquals("Desktop draft", store.drafts(server.machine.id)[conversation.key])
    }

    @Test fun unreachableMachineKeepsCachedChatAndDraftNavigable() {
        val server = connected(conversation)
        store.saveDrafts(server.machine.id, mapOf(conversation.key to "Offline draft"))
        server.unavailable = true
        launch()
        awaitText("Navigation review is ready.")
        ui.onNode(hasSetTextAction()).assertTextContains("Offline draft")
        ui.onNodeWithContentDescription("Back").performClick()
        chats()
        ui.onNodeWithText(conversation.title).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("Offline draft")
        server.unavailable = false
        ui.onNodeWithContentDescription("Send").performClick()
        awaitCommand("/v1/send")
        ui.waitUntil(5_000) { store.drafts(server.machine.id)[conversation.key].isNullOrEmpty() }
    }

    @Test fun sessionScopesAndSnoozeUndoKeepTheListUsable() {
        val server = connected()
        server.rows = server.rows.mapIndexed { index, row -> row.copy(attention = if (index == 0)
            com.github.claudeagents.core.SessionAttentionState.RUNNING else com.github.claudeagents.core.SessionAttentionState.DONE_UNREVIEWED) }
        store.cacheSnapshot(server.machine.id, server.snapshot)
        launch()
        ui.onNodeWithText("Running", substring = false).performClick()
        ui.onNodeWithText("Build account settings").assertDoesNotExist()
        ui.onNodeWithText("All", substring = false).performClick()
        ui.onNodeWithText("Build account settings").assertExists()
        ui.onNodeWithText(conversation.title).performTouchInput { longClick() }
        ui.onNodeWithText("Snooze until this agent moves").performClick()
        awaitText("Undo")
        ui.onNodeWithText(conversation.title).assertDoesNotExist()
        ui.onNodeWithText("Undo").performClick()
        ui.onNodeWithText(conversation.title).assertExists()
    }

    @Test fun keyboardBackDismissesTypingThenReturnsToChats() {
        connected(conversation)
        launch()
        awaitText("Navigation review is ready.")
        ui.onNode(hasSetTextAction()).performClick().performTextInput("Keep the keyboard draft")
        ui.waitUntil(5_000) {
            var visible = false
            scenario!!.onActivity { activity ->
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNode(hasSetTextAction()).assertTextContains("Keep the keyboard draft")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        chats()
        ui.onNodeWithText(conversation.title).performClick()
        ui.onNode(hasSetTextAction()).assertTextContains("Keep the keyboard draft")
    }

    private fun screenshot(name: String) {
        val folder = java.io.File(app.getExternalFilesDir(null), "story-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP uninstalls the target after a connected run, including its external files.
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    @Test fun wideLayoutsKeepNavigationAvailableInsideChatsAndComposers() {
        connected(conversation)
        val originalSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = app.resources.displayMetrics.density
        try {
            for (widthDp in listOf(700, 1000)) {
                shell("wm size ${(widthDp * density).toInt()}x${(900 * density).toInt()}")
                store.saveScreen(Navigation.toJson(conversation))
                launch()
                scenario!!.onActivity { assertTrue(it.resources.configuration.screenWidthDp >= widthDp - 2) }
                ui.onNodeWithContentDescription("Back").assertIsDisplayed()
                for (label in listOf("Chats", "Scheduled", "Settings")) {
                    ui.onNodeWithContentDescription(label, useUnmergedTree = true).assertIsDisplayed()
                }
                screenshot("wide-$widthDp-chat")
                ui.onNodeWithContentDescription("Back").performClick()
                ui.onNodeWithContentDescription("Back").assertDoesNotExist()
                ui.onNodeWithText(conversation.title).performClick()
                ui.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
                ui.onNodeWithText("Machine").assertIsDisplayed()
                ui.onNodeWithContentDescription("Chats", useUnmergedTree = true).performClick()
                ui.onNodeWithContentDescription("New chat").performClick()
                ui.onNodeWithText("Your task").performTextReplacement("Wide layout draft")
                ui.onNodeWithContentDescription("Scheduled", useUnmergedTree = true).performClick()
                awaitText("Nothing is scheduled on this machine.")
                ui.onNodeWithText("Your task").assertDoesNotExist()
                ui.onNodeWithContentDescription("Chats", useUnmergedTree = true).performClick()
                ui.onNodeWithContentDescription("New chat").performClick()
                ui.onNodeWithText("Wide layout draft").assertIsDisplayed()
                ui.onNodeWithContentDescription("Back").performClick()
                ui.onNodeWithContentDescription("Back").assertDoesNotExist()
                scenario!!.close()
                scenario = null
            }
        } finally {
            scenario?.close()
            scenario = null
            shell("wm size ${originalSize ?: "reset"}")
        }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun connected(screen: Screen = Screen.Fleet): StoryBridge {
        val server = StoryBridge().also { bridge = it }
        store.save(server.machine)
        store.cacheSnapshot(server.machine.id, server.snapshot)
        store.cacheTranscript(server.machine.id, server.page)
        store.saveScreen(Navigation.toJson(screen))
        return server
    }

    private fun chats() { awaitText("Search chats"); ui.onNodeWithText("Search chats").assertIsDisplayed() }
    private fun awaitText(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitDescription(text: String, substring: Boolean = false) {
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitCommand(path: String) {
        ui.waitUntil(10_000) { requireNotNull(bridge).commands.any { it.first == path } }
    }

    private fun launch(intent: Intent = Intent(app, MainActivity::class.java)) {
        scenario = ActivityScenario.launch(intent)
        ui.waitForIdle()
    }
}
