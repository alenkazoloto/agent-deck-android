package dev.agentdeck.companion

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingContinuityE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private lateinit var bridge: StoryBridge
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalIntent: Intent? = null
    private val first = Screen.Conversation("story-chat", "Review navigation", AgentVendor.CLAUDE, "/work/project")
    private val second = Screen.Conversation("second-chat", "Build account settings", AgentVendor.CLAUDE, "/work/project")

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
        bridge = StoryBridge()
        bridge.pages[first.key] = bridge.page.copy(turns = (0..59).map {
            MobileTurn("turn-$it", "assistant", "Reading passage $it. " + "A quiet synthetic paragraph for retaining position. ".repeat(8), System.currentTimeMillis())
        })
        bridge.pages[second.key] = bridge.page.copy(key = second.key, title = second.title,
            turns = listOf(MobileTurn("second-turn", "assistant", "Separate conversation body", System.currentTimeMillis())))
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        bridge.pages.values.forEach { store.cacheTranscript(bridge.machine.id, it) }
        store.saveScreen(Navigation.toJson(first))
    }

    @After fun finish() {
        scenario?.onActivity { originalIntent?.let { original -> it.intent = Intent(original) } }
        scenario?.close()
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridge.close()
    }

    @Test fun olderPassageSurvivesAnotherConversationBackAndNewViewModel() {
        launch()
        olderPassage()
        val anchor = storedAnchor()
        scenario!!.onActivity {
            it.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Navigation.link(second)), app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        awaitText("Separate conversation body")
        ui.onNodeWithContentDescription("Back").performClick()
        awaitText("Reading passage 12.", true)
        assertEquals(anchor.itemKey, storedAnchor().itemKey)
        screenshot("conversation-reading-restored")
        assertFalse(storedAnchor().followingLatest)
        scenario!!.onActivity { originalIntent?.let { original -> it.intent = Intent(original) } }
        scenario!!.close()
        scenario = null
        launch()
        awaitText("Reading passage 12.", true)
        assertEquals(anchor.itemKey, storedAnchor().itemKey)
        ui.onNode(hasContentDescription("Latest message", substring = true)).performClick()
        awaitText("Reading passage 59.", true)
        ui.waitUntil(5_000) { store.readingPositions(bridge.machine.id).conversations[first.key]?.followingLatest == true }
    }

    @Test fun olderPassageSurvivesActivityRecreation() {
        launch()
        olderPassage()
        val anchor = storedAnchor()
        scenario!!.recreate()
        awaitText("Reading passage 12.", true)
        assertEquals(anchor.itemKey, storedAnchor().itemKey)
        assertFalse(storedAnchor().followingLatest)
    }

    @Test fun freshOutputDoesNotMoveReaderAboveTheTail() {
        launch()
        olderPassage()
        bridge.pages[first.key] = bridge.pages.getValue(first.key).copy(turns = bridge.pages.getValue(first.key).turns +
            MobileTurn("new-output", "assistant", "Newest output after refresh", System.currentTimeMillis()))
        scenario!!.onActivity { ViewModelProvider(it)[DeckViewModel::class.java].refreshHistory(first.key) }
        ui.waitUntil(10_000) { bridge.transcriptReads.count { it == first.key } >= 2 }
        awaitText("Reading passage 12.", true)
        assertFalse(storedAnchor().followingLatest)
        ui.onNode(hasContentDescription("Latest message", substring = true)).performClick()
        awaitText("Newest output after refresh")
    }

    @Test fun manuallyReachingNewTailMarksOutputReadWhenScrollingAway() {
        launch()
        olderPassage()
        bridge.pages[first.key] = bridge.pages.getValue(first.key).copy(turns = bridge.pages.getValue(first.key).turns +
            MobileTurn("new-output", "assistant", "Newest output after refresh", System.currentTimeMillis()))
        scenario!!.onActivity { ViewModelProvider(it)[DeckViewModel::class.java].refreshHistory(first.key) }
        ui.waitUntil(10_000) {
            ui.onAllNodesWithContentDescription("Latest message, 1 new turn").fetchSemanticsNodes().isNotEmpty()
        }
        val list = ui.onNodeWithTag("conversation-transcript")
        list.performScrollToIndex(59)
        repeat(3) { list.performTouchInput { swipeUp() } }
        awaitText("Newest output after refresh")
        list.performTouchInput { swipeDown() }
        ui.onNodeWithContentDescription("Latest message").assertIsDisplayed()
        screenshot("conversation-manual-tail-read")
    }

    @Test fun expandedBacklogAndVisibleRowSurviveChatAndRecreation() {
        val row = bridge.rows.first()
        bridge.rows = (0..39).map { row.copy(key = "row-$it", title = "Backlog row $it", lastActivityMs = 100_000L - it) } +
            row.copy(key = "waiting", title = "Backlog waiting elsewhere", attention = SessionAttentionState.WAITING_ON_YOU) +
            row.copy(key = "excluded", title = "Unrelated conversation")
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        store.saveScreen(Navigation.toJson(Screen.Fleet))
        store.saveReadingPositions(bridge.machine.id, ReadingPositions(fleet = FleetBrowsing(filter = FleetFilter(query = "Backlog"), sort = FleetSort.ATTENTION)))
        launch()
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show all 40"))
        ui.onNodeWithText("Show all 40").performClick()
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Backlog row 25"))
        ui.onNodeWithText("Backlog row 25").performClick()
        awaitText("Backlog row 25")
        ui.onNodeWithContentDescription("Back").performClick()
        awaitText("Backlog row 25")
        ui.onNodeWithText("Backlog row 25").assertIsDisplayed()
        ui.onNodeWithText("Backlog").assertIsDisplayed()
        screenshot("conversation-backlog-restored")
        scenario!!.recreate()
        awaitText("Backlog row 25")
        assertTrue(store.readingPositions(bridge.machine.id).fleet.expandedGroups.contains("DONE_UNREVIEWED"))
        assertEquals("Backlog", store.readingPositions(bridge.machine.id).fleet.filter.query)
    }

    @Test fun sameConversationKeyOnAnotherMachineHasIndependentAnchorAndUnpairWipesIt() {
        val other = bridge.machine.copy(deviceId = "other-story-device", machineName = "Second workstation")
        store.save(other)
        store.activate(bridge.machine.id)
        launch()
        olderPassage()
        val anchor = storedAnchor()
        scenario!!.onActivity { ViewModelProvider(it)[DeckViewModel::class.java].switchMachine(other.id) }
        awaitText("Search chats")
        scenario!!.onActivity { ViewModelProvider(it)[DeckViewModel::class.java].openConversation(bridge.rows.first()) }
        awaitText("Reading passage 59.", true)
        assertTrue(store.readingPositions(other.id).conversations[first.key]?.followingLatest != false)
        assertEquals(anchor.itemKey, storedAnchor().itemKey)
        scenario!!.onActivity { ViewModelProvider(it)[DeckViewModel::class.java].switchMachine(bridge.machine.id) }
        awaitText("Search chats")
        scenario!!.onActivity { ViewModelProvider(it)[DeckViewModel::class.java].openConversation(bridge.rows.first()) }
        awaitText("Reading passage 12.", true)
        store.forget(other.id)
        assertEquals(ReadingPositions(), store.readingPositions(other.id))
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val folder = java.io.File(app.getExternalFilesDir(null), "story-evidence").apply { mkdirs() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = automation.takeScreenshot()
        java.io.File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        listOf(
            "mkdir -p /data/local/tmp/agent-deck-story-evidence",
            "cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png",
        ).forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        scenario!!.onActivity { originalIntent = Intent(it.intent) }
    }
    private fun olderPassage() {
        awaitText("Reading passage 59.", true)
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Reading passage 12.", substring = true))
        awaitText("Reading passage 12.", true)
        ui.waitUntil(5_000) { store.readingPositions(bridge.machine.id).conversations[first.key]?.followingLatest == false }
    }
    private fun storedAnchor() = requireNotNull(store.readingPositions(bridge.machine.id).conversations[first.key])
    private fun awaitText(text: String, substring: Boolean = false) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(text, substring = substring).assertIsDisplayed()
    }
}
