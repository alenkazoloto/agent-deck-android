package dev.agentdeck.companion

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.ui.STARTER_PROMPTS
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M2 journeys of PLAN-MOBILE-REDESIGN.md that the story suite did not already walk: J02's
 * prompt across recreation, J03/J07's lost acknowledgement across an app restart, and J14's
 * 200 % text with the keyboard open. Production Activity, ViewModel, SecureStore and pinned
 * HTTPS client against [StoryBridge]; no real agent runs.
 */
@RunWith(AndroidJUnit4::class)
class M2JourneysE2eTest {
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

    /** J02: one tap from Chats to typing, and the typed task outlives an Activity recreation. */
    @Test fun newChatPromptSurvivesRecreationAndStartsOnce() {
        val server = connected()
        launch()
        chats()
        ui.onNodeWithContentDescription("New chat").performClick()
        ui.onNodeWithText("Your task").performTextInput("Survive a rotation")
        scenario!!.recreate()
        awaitText("Survive a rotation")
        ui.onNodeWithText("Start chat").performClick()
        awaitCommand("/v1/send")
        chats()
        val sends = server.commands.filter { it.first == "/v1/send" }
        assertEquals(listOf("Survive a rotation"), sends.map { it.second["prompt"].asString })
    }

    /**
     * J03/J07: a send whose acknowledgement is lost is never shown as accepted, has one visible
     * owner (the parked item, not a second copy in the composer), is not repeated on its own
     * after the app restarts, and goes out exactly once more only when the reader says Retry.
     */
    @Test fun lostAcknowledgementParksAsUncertainAndIsNotRetriedAcrossRestart() {
        val server = connected(conversation)
        server.loseSendAck = true
        launch()
        awaitText("Navigation review is ready.")
        ui.onNode(hasSetTextAction()).performTextInput("Ship the fix")
        ui.onNodeWithContentDescription("Send").performClick()
        awaitText("This machine may already have received it — check the conversation first.")
        ui.onNodeWithText("“Ship the fix”").assertIsDisplayed()
        ui.onAllNodesWithText("Sent to Claude").assertCountEquals(0)
        // One owner: the parked item holds the words, the composer and its saved draft do not.
        ui.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertTrue(store.drafts(server.machine.id)[conversation.key].isNullOrEmpty())
        assertEquals(1, sends(server))

        // A new process-lifetime ViewModel reads the queue back from SecureStore.
        scenario!!.close()
        scenario = null
        launch()
        awaitText("“Ship the fix”")
        awaitText("This machine may already have received it — check the conversation first.")
        Thread.sleep(4_000)
        assertEquals("an uncertain send must not be repeated without the reader", 1, sends(server))

        server.loseSendAck = false
        ui.onNodeWithText("Retry", substring = false).performClick()
        ui.waitUntil(10_000) { sends(server) == 2 }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("“Ship the fix”").fetchSemanticsNodes().isEmpty() }
        awaitText("Ship the fix")
    }

    /**
     * J14: at 200 % text on a 360 dp phone, portrait and landscape, with the keyboard open, the
     * conversation's Send and Back and New chat's Start stay above the keyboard and ≥ 48 dp.
     */
    @Test fun twoHundredPercentTextKeepsPrimaryActionsAboveTheKeyboard() {
        connected(conversation)
        val originalSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val originalScale = shell("settings get system font_scale").trim()
        val density = app.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            for ((w, h) in listOf(360 to 760, 760 to 360)) {
                shell("wm size ${(w * density).toInt()}x${(h * density).toInt()}")
                store.saveScreen(Navigation.toJson(conversation))
                launch()
                scenario!!.onActivity { assertEquals(2.0f, it.resources.configuration.fontScale, 0.01f) }
                awaitText("Navigation review is ready.")
                ui.onNode(hasSetTextAction()).performClick().performTextReplacement("Large text reply")
                awaitKeyboard()
                screenshot("j14-${w}x$h-conversation-ime")
                assertAboveKeyboardAndTouchable(ui.onNodeWithContentDescription("Send"), "Send ${w}x$h")
                // Back closes the keyboard first; the bar and its visible Back are there after it,
                // including on a short window where the bar gave way to the composer.
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.onNode(hasSetTextAction()).assertTextContains("Large text reply")
                // The keyboard's inset animates out before the bar returns.
                ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
                screenshot("j14-${w}x$h-conversation-keyboard-closed")
                ui.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
                chats()
                ui.onNodeWithContentDescription("New chat").performClick()
                ui.onNode(hasSetTextAction()).performClick().performTextReplacement("Large text task")
                awaitKeyboard()
                // A short window moves Start into the field (an icon named "Start chat").
                val start = ui.onNode(hasText("Start chat") or hasContentDescription("Start chat"))
                screenshot("j14-${w}x$h-new-chat-ime")
                assertAboveKeyboardAndTouchable(start, "Start chat ${w}x$h")
                // The words being typed stay wholly in sight, not just the button that sends them —
                // also for a starter-length draft several lines long at 200 %.
                assertAboveKeyboard(ui.onNode(hasSetTextAction()), "typed task ${w}x$h", whole = true)
                ui.onNode(hasSetTextAction()).performTextReplacement(STARTER_PROMPTS.first().second)
                ui.waitForIdle()
                assertAboveKeyboard(ui.onNode(hasSetTextAction()), "starter draft ${w}x$h", whole = true)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                if (w >= 600) {
                    // Every destination stays reachable: the rail scrolls where four no longer fit.
                    for (label in listOf("Workspace", "Chats")) {
                        ui.onNode(destination(label), useUnmergedTree = true).performScrollTo().assertIsDisplayed().performClick()
                    }
                    screenshot("j14-${w}x$h-rail")
                    chats()
                }
                scenario!!.close()
                scenario = null
                store.saveScreen(null)
            }
        } finally {
            scenario?.close()
            scenario = null
            shell("settings put system font_scale ${originalScale.takeIf { it.toFloatOrNull() != null } ?: "1.0"}")
            shell("wm size ${originalSize ?: "reset"}")
        }
    }

    /**
     * The negative control: where the keyboard leaves room (a 412×915 phone upright, default
     * text), the bar, its Back and Find stay while typing. On its side the same phone's keyboard
     * leaves about a hundred dp even at default text, so there the bar rightly gives way.
     */
    @Test fun roomyWindowKeepsTheBarWhileTyping() {
        connected(conversation)
        val originalSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val originalScale = shell("settings get system font_scale").trim()
        val density = app.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 1.0")
            shell("wm size ${(412 * density).toInt()}x${(915 * density).toInt()}")
            launch()
            awaitText("Navigation review is ready.")
            ui.onNode(hasSetTextAction()).performClick().performTextReplacement("Default text reply")
            awaitKeyboard()
            screenshot("j14-412x915-default-text-ime")
            ui.onNodeWithContentDescription("Find in this chat").assertIsDisplayed()
            ui.onNodeWithContentDescription("Back").assertIsDisplayed()
            assertAboveKeyboardAndTouchable(ui.onNodeWithContentDescription("Send"), "Send at default text")
        } finally {
            scenario?.close()
            scenario = null
            shell("settings put system font_scale ${originalScale.takeIf { it.toFloatOrNull() != null } ?: "1.0"}")
            shell("wm size ${originalSize ?: "reset"}")
        }
    }

    private fun assertAboveKeyboardAndTouchable(node: SemanticsNodeInteraction, what: String) {
        assertAboveKeyboard(node, what)
        val touch = node.fetchSemanticsNode().touchBoundsInRoot
        val min = 48 * app.resources.displayMetrics.density - 1f
        assertTrue("$what touch target ${touch.width}x${touch.height} is under 48 dp",
            touch.width >= min && touch.height >= min)
    }

    private fun assertAboveKeyboard(node: SemanticsNodeInteraction, what: String, whole: Boolean = false) {
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        var keyboardTop = 0f
        var statusBottom = 0f
        scenario!!.onActivity { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            keyboardTop = (activity.window.decorView.height - ime).toFloat()
            statusBottom = (insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0).toFloat()
        }
        assertTrue("$what bottom ${bounds.bottom} is under the keyboard top $keyboardTop", bounds.bottom <= keyboardTop + 1f)
        if (whole) assertTrue("$what top ${bounds.top} is under the status bar $statusBottom", bounds.top >= statusBottom - 1f)
    }

    /** A bar/rail destination, whose icon reads "<label>, N …" once its badge counts something. */
    private fun destination(label: String) = SemanticsMatcher("destination $label") { node ->
        node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .any { it == label || it.startsWith("$label, ") }
    }

    private fun awaitKeyboard() = ui.waitUntil(5_000) {
        var visible = false
        scenario!!.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        visible
    }

    private fun sends(server: StoryBridge) = server.commands.count { it.first == "/v1/send" }

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
    private fun awaitCommand(path: String) {
        ui.waitUntil(10_000) { requireNotNull(bridge).commands.any { it.first == path } }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(android.content.Intent(app, MainActivity::class.java))
        ui.waitForIdle()
    }
}
