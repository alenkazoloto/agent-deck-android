package dev.agentdeck.companion

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationBackE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private lateinit var bridge: StoryBridge
    private var scenario: ActivityScenario<MainActivity>? = null
    private var launchedActivity: MainActivity? = null
    private var originalIntent: Intent? = null
    private val gates = mutableListOf<StoryBridge.TranscriptGate>()
    private val first = Screen.Conversation("story-chat", "Review navigation", AgentVendor.CLAUDE, "/work/project")
    private val second = Screen.Conversation("second-chat", "Build account settings", AgentVendor.CLAUDE, "/work/project")
    private val firstBody = "Only conversation A: navigation review is ready."
    private val secondBody = "Only conversation B: account settings are ready."
    private val draft = "Unsent notes for conversation A"

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
        bridge = StoryBridge()
        bridge.pages[first.key] = bridge.page.copy(title = first.title, costKnown = true, costUsd = 1.25, running = false, liveLine = null, turns = listOf(turn("a", firstBody)))
        bridge.pages[second.key] = bridge.page.copy(key = second.key, title = second.title,
            costKnown = true, costUsd = 9.75, running = true, liveLine = "Working on conversation B", turns = listOf(turn("b", secondBody)))
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        bridge.pages.values.forEach { store.cacheTranscript(bridge.machine.id, it) }
        store.saveScreen(Navigation.toJson(first))
        store.saveDrafts(bridge.machine.id, mapOf(first.key to draft))
    }

    @After fun finish() {
        gates.forEach { it.release() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // ActivityScenario matches lifecycle events to its original launch intent.
            originalIntent?.let { launchedActivity?.intent = it }
        }
        scenario?.close()
        launchedActivity = null
        originalIntent = null
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridge.close()
    }

    @Test fun visibleBackFromNotificationRestoresPreviousTranscriptDraftAndRefreshesItsKey() {
        launchFirst()
        notificationToSecond()
        awaitText(secondBody)
        val readsBeforeBack = bridge.transcriptReads.count { it == first.key }
        val refresh = bridge.holdNextTranscript(first.key).also { gates += it }
        val refreshedBody = "Only conversation A: refreshed after returning."
        bridge.pages[first.key] = bridge.pages.getValue(first.key).copy(turns = listOf(turn("a-new", refreshedBody)))

        ui.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        awaitText(first.title)
        screenshot("conversation-back-restored")

        assertFirst(firstBody)
        ui.waitUntil(10_000) { bridge.transcriptReads.count { it == first.key } > readsBeforeBack }
        refresh.release()
        awaitText(refreshedBody)
        screenshot("conversation-back-refreshed")
        assertFirst(refreshedBody)
        assertEquals("Back must not move the draft to the notification chat", draft,
            store.drafts(bridge.machine.id)[first.key])
        ui.onNodeWithContentDescription("Back").performClick()
        awaitText("Search chats")
    }

    @Test fun visibleBackWhileOfflineRestoresCachedPreviousTranscriptAndDraft() {
        launchFirst()
        notificationToSecond()
        awaitText(secondBody)
        bridge.unavailable = true
        val readsBeforeBack = bridge.transcriptReads.count { it == first.key }

        ui.onNodeWithContentDescription("Back").performClick()
        awaitText(first.title)

        assertFirst(firstBody)
        ui.waitUntil(10_000) { bridge.transcriptReads.count { it == first.key } > readsBeforeBack }
        assertFirst(firstBody)
    }

    @Test fun visibleBackWhileOfflineWithoutCachedPageClearsOtherConversation() {
        launchFirst()
        notificationToSecond()
        awaitText(secondBody)
        val machineSlug = bridge.machine.id.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        val cachedFirst = java.io.File(app.filesDir, "transcripts-$machineSlug/${first.key}.json")
        assertTrue("Test must evict the previously read A transcript", cachedFirst.delete())
        assertNull(store.cachedTranscript(bridge.machine.id, first.key))
        bridge.unavailable = true
        val readsBeforeBack = bridge.transcriptReads.count { it == first.key }

        ui.onNodeWithContentDescription("Back").performClick()
        awaitText(first.title)
        ui.waitUntil(10_000) { bridge.transcriptReads.count { it == first.key } > readsBeforeBack }

        ui.onNodeWithText(firstBody).assertDoesNotExist()
        assertNoSecondConversation()
        ui.onNodeWithText("$1.25", substring = true).assertDoesNotExist()
        ui.onNode(hasSetTextAction()).assertTextContains(draft)
    }

    @Test fun responseForWrongConversationCannotReplaceRestoredPage() {
        launchFirst()
        notificationToSecond()
        awaitText(secondBody)
        val gate = bridge.holdNextTranscript(first.key).also { gates += it }
        bridge.pages[first.key] = bridge.pages.getValue(second.key)

        ui.onNodeWithContentDescription("Back").performClick()
        awaitText(first.title)
        ui.waitUntil(10_000) { gate.received.count == 0L }
        assertFirst(firstBody)
        gate.release()
        ui.waitUntil(10_000) { gate.released.count == 0L }
        val observeUntil = SystemClock.uptimeMillis() + 1_000
        ui.waitUntil(3_000) {
            assertFirst(firstBody)
            SystemClock.uptimeMillis() >= observeUntil
        }
        assertEquals(firstBody, store.cachedTranscript(bridge.machine.id, first.key)?.turns?.single()?.text)
    }

    @Test fun lateNotificationTranscriptCannotReplacePreviousConversationAfterBack() {
        launchFirst()
        val gate = bridge.holdNextTranscript(second.key).also { gates += it }
        val lateBody = "Only conversation B: late network response."
        bridge.pages[second.key] = bridge.pages.getValue(second.key).copy(turns = listOf(turn("b-late", lateBody)))
        notificationToSecond()
        awaitText(secondBody)
        ui.waitUntil(10_000) { gate.received.count == 0L }

        ui.onNodeWithContentDescription("Back").performClick()
        awaitText(first.title)
        assertFirst(firstBody)
        gate.release()
        ui.waitUntil(10_000) { gate.released.count == 0L }
        // Observe the UI after the held HTTPS response arrives, including the IO-to-main handoff.
        val observeUntil = SystemClock.uptimeMillis() + 1_000
        ui.waitUntil(3_000) {
            assertFirst(firstBody)
            ui.onNodeWithText(lateBody).assertDoesNotExist()
            SystemClock.uptimeMillis() >= observeUntil
        }
    }

    private fun launchFirst() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        scenario!!.onActivity { activity ->
            launchedActivity = activity
            originalIntent = Intent(activity.intent)
        }
        awaitText(firstBody)
        ui.waitUntil(10_000) { first.key in bridge.transcriptReads }
        assertFirst(firstBody)
    }

    private fun notificationToSecond() {
        scenario!!.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Navigation.link(second)), app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        awaitText(second.title)
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("$9.75", substring = true).assertIsDisplayed()
        ui.onNodeWithText(draft).assertDoesNotExist()
    }

    private fun assertFirst(body: String) {
        ui.onNodeWithText(first.title).assertIsDisplayed()
        ui.onNodeWithText(body).assertIsDisplayed()
        ui.onNodeWithText("$1.25", substring = true).assertIsDisplayed()
        assertNoSecondConversation()
        ui.onNode(hasSetTextAction()).assertTextContains(draft)
    }

    private fun assertNoSecondConversation() {
        ui.onNodeWithText(second.title).assertDoesNotExist()
        ui.onNodeWithText(secondBody).assertDoesNotExist()
        ui.onNodeWithText("$9.75", substring = true).assertDoesNotExist()
        ui.onNodeWithContentDescription("Stop").assertDoesNotExist()
        ui.onNodeWithContentDescription("Stop & send").assertDoesNotExist()
    }

    private fun awaitText(text: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        ui.waitForIdle()
        val folder = java.io.File(app.getExternalFilesDir(null), "story-evidence").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        // Connected test teardown uninstalls the target, including its external files.
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun turn(id: String, text: String) = MobileTurn(id, "assistant", text, System.currentTimeMillis())
}
