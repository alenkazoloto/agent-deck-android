package dev.agentdeck.companion

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.DeckIconButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real conversation surface with disposable in-memory state and no paired machine. */
@RunWith(AndroidJUnit4::class)
class ConversationFindE2eTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val draft = mutableStateOf("Unsent draft survives finding")
    private val open = mutableStateOf(false)
    private val text = (1..150).joinToString("\n") { line ->
        when (line) {
            111 -> "Привет needle() first exact code passage"
            137 -> "Привет needle() second exact code passage"
            else -> "Line $line: a long downloaded conversation for reading."
        }
    }
    private val page = mutableStateOf(MobileTranscriptPage(
        key = "find-chat", title = "Find passages", turns = listOf(MobileTurn("long", "assistant", text, 1)),
        hasMore = true, costUsd = 0.0, costKnown = false, contextPct = null, model = null,
        liveLine = null, running = false, generatedAtMs = 1,
    ))

    private fun render() {
        ui.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                AgentDeckTheme(dark = true) {
                    Scaffold(modifier = Modifier.imePadding()) { padding ->
                      Column(Modifier.fillMaxSize().padding(padding)) {
                        DeckIconButton("Find in conversation", Icons.Default.Search, { open.value = true })
                        ConversationScreen(
                            target = Screen.Conversation("find-chat", "Find passages", AgentVendor.CLAUDE, "/fixture"),
                            page = page.value, loading = false, cached = false, draft = draft.value,
                            notice = null, onDraft = { draft.value = it }, onSend = { _, _ -> },
                            onStop = {}, onDismissNotice = {}, findOpen = open.value,
                            onCloseFind = { open.value = false },
                        )
                      }
                    }
                }
            }
        }
        ui.onNodeWithContentDescription("Find in conversation").performClick()
        ui.onNodeWithText("Find in conversation").assertIsFocused()
    }

    @Test fun longPassageStepsToExactVisibleOccurrenceWithLargeTextAndKeepsDraftOnClose() {
        render()
        ui.onNodeWithText("Find in conversation").performTextInput("needle()")
        awaitCount("1 of 2")
        assertExactPassageVisible(text.indexOf("needle()"))
        screenshot("find-first-large-dark-keyboard")
        ui.onNodeWithContentDescription("Next match").performClick()
        awaitCount("2 of 2")
        assertExactPassageVisible(text.lastIndexOf("needle()"))
        screenshot("find-second-exact-passage")
        ui.onNodeWithContentDescription("Previous match").performClick()
        awaitCount("1 of 2")
        assertExactPassageVisible(text.indexOf("needle()"))
        ui.onNodeWithContentDescription("Close find").performClick()
        ui.onNodeWithTag("find-passage").assertDoesNotExist()
        ui.onNodeWithText("Downloaded message source text · excludes tools").assertDoesNotExist()
        assertEquals("Unsent draft survives finding", draft.value)
        ui.onNodeWithText(draft.value).assertIsDisplayed()
        screenshot("find-closed-draft-preserved")
        ui.onNodeWithContentDescription("Find in conversation").performClick()
        ui.onNodeWithText("Enter text to find").assertIsDisplayed()
    }

    @Test fun replacingQueryAndPrependingDownloadedHistoryUpdatesScopeAndSelection() {
        render()
        ui.onNodeWithText("Find in conversation").performTextInput("not present")
        awaitCount("No matches")
        ui.onNodeWithText("Find in conversation").performTextReplacement("привет")
        awaitCount("1 of 2")
        assertExactPassageVisible(text.indexOf("Привет"))
        ui.runOnIdle {
            page.value = page.value.copy(turns = listOf(MobileTurn("earlier", "user", "Привет from earlier", 0)) + page.value.turns)
        }
        awaitCount("2 of 3")
        assertExactPassageVisible(text.indexOf("Привет"))
        ui.onNodeWithText("Find in conversation").performTextReplacement("needle() second")
        awaitCount("1 of 1")
        assertExactPassageVisible(text.lastIndexOf("needle()"))
        assertEquals("Unsent draft survives finding", draft.value)
    }

    private fun awaitCount(label: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }

    private fun assertExactPassageVisible(offset: Int) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("find-passage").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        ui.onNodeWithText("Find in conversation").assertIsDisplayed()
        ui.onNodeWithText("Downloaded message source text · excludes tools").assertIsDisplayed()
        ui.onNodeWithContentDescription("Previous match").assertIsDisplayed()
        ui.onNodeWithText(draft.value).assertIsDisplayed()
        val node = ui.onNodeWithTag("find-passage")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("The selected source occurrence must be highlighted", layout.layoutInput.text.spanStyles.any { it.start == offset })
        val glyph = layout.getBoundingBox(offset)
        val origin = node.fetchSemanticsNode().positionInRoot
        val composer = ui.onNodeWithText(draft.value).fetchSemanticsNode().boundsInRoot
        val previous = ui.onNodeWithContentDescription("Previous match").fetchSemanticsNode().boundsInRoot
        assertTrue("Exact match must appear below Find controls", origin.y + glyph.top >= previous.bottom - 1)
        assertTrue("Exact match must appear above the composer", origin.y + glyph.bottom <= composer.top + 1)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The IME window animates independently of Compose's test clock.
        instrumentation.uiAutomation.waitForIdle(350, 3_000)
        val folder = instrumentation.targetContext.getExternalFilesDir("screenshots")!!
        folder.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        shell("mkdir -p /data/local/tmp/agent-deck-story-evidence")
        shell("cp ${folder.absolutePath}/$name.png /data/local/tmp/agent-deck-story-evidence/$name.png")
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
