package dev.agentdeck.companion

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityFindE2eTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var store: SecureStore
    private lateinit var bridge: StoryBridge
    private var scenario: ActivityScenario<MainActivity>? = null
    private val target = Screen.Conversation("story-chat", "Review navigation", AgentVendor.CLAUDE, "/work/project")

    @Before fun prepare() {
        LiveLink.of(app).bind(null)
        store = SecureStore(app)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        store.saveSettings(AppSettings(triggers = emptySet(), dynamicColor = false, updateNotices = false))
        store.saveUpdateCheckedAt(System.currentTimeMillis())
        bridge = StoryBridge()
        bridge.page = bridge.page.copy(turns = listOf(MobileTurn("find", "assistant", "Needle one.\n\nNeedle two.", 1)))
        store.save(bridge.machine)
        store.cacheSnapshot(bridge.machine.id, bridge.snapshot)
        store.cacheTranscript(bridge.machine.id, bridge.page)
        store.saveScreen(Navigation.toJson(target))
        store.saveDrafts(bridge.machine.id, mapOf(target.key to "Saved unsent reply"))
    }

    @After fun finish() {
        scenario?.close()
        LiveLink.of(app).bind(null)
        store.machines().forEach { store.forget(it.id) }
        store.saveScreen(null)
        bridge.close()
    }

    @Test fun topBarFindOpensRealFieldStepsAndClosesWithoutConsumingDraft() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        ui.waitUntil(10_000) { ui.onAllNodesWithContentDescription("Find in this chat").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Find in this chat").assertIsDisplayed().performClick()
        ui.onNodeWithText("Find in conversation").assertIsDisplayed().performTextInput("needle")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("1 of 2").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Downloaded message source text · excludes tools").assertIsDisplayed()
        ui.onNodeWithTag("find-passage").assertIsDisplayed()
        ui.onNodeWithContentDescription("Next match").performClick()
        ui.onNodeWithText("2 of 2").assertIsDisplayed()
        ui.onNodeWithContentDescription("Close find").performClick()
        ui.onNodeWithText("Find in conversation").assertDoesNotExist()
        ui.onNodeWithTag("find-passage").assertDoesNotExist()
        ui.onNodeWithText("Saved unsent reply").assertIsDisplayed()
        assertEquals("Saved unsent reply", store.drafts(bridge.machine.id)[target.key])
        assertEquals("Find must never send or stop a run", emptyList<Any>(), bridge.commands.toList())
    }
}
