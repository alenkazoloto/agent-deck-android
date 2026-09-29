package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.mobile.MobileHooks
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.HooksSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's `/hooks` on the phone: hooks grouped under their event with matcher, origin and program name, the
 * machine's own notes and per-entry problem, its sentence when the project is not open, and a machine that does
 * not answer said so rather than drawn as an empty list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HooksInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val loads = ArrayList<Unit>()

    private val rows = listOf(
        MobileHooks.Row("PreToolUse", "Bash", "Project", "guard.sh", timeoutSeconds = 30),
        MobileHooks.Row("PreToolUse", "", "Plugin · lint", "lint"),
        MobileHooks.Row("SessionStart", "", "User", "notify", runs = false, problem = "No \"type\" — every hook needs \"type\": \"command\"."),
    )

    private fun show(answer: suspend () -> MobileHooks?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                HooksSheet(onLoad = { loads += Unit; answer() }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `hooks are grouped under their event with matcher, origin and timeout`() {
        show { MobileHooks("k", rows, notes = listOf("A managed policy allows only managed hooks.")) }

        compose.onNodeWithText("Hooks").assertIsDisplayed()
        compose.onNodeWithText("A managed policy allows only managed hooks.").assertIsDisplayed()
        compose.onNodeWithText("PreToolUse").assertIsDisplayed()
        compose.onNodeWithText("guard.sh").assertIsDisplayed()
        compose.onNodeWithText("Bash · Project · 30 s").assertIsDisplayed()
        compose.onNodeWithText("Every input · Plugin · lint").assertIsDisplayed()
        compose.onNodeWithText("Every input · User · Does not run").assertIsDisplayed()
        compose.onNodeWithText("No \"type\" — every hook needs \"type\": \"command\".").assertIsDisplayed()
        assertEquals("two PreToolUse hooks share one heading", 1, compose.onAllNodesWithTag("hooks-event-PreToolUse").fetchSemanticsNodes().size)
        assertEquals(1, loads.size)
    }

    @Test
    fun `a project with no hooks says so`() {
        show { MobileHooks("k", emptyList()) }

        compose.onNodeWithText("No hooks are configured for this project.").assertIsDisplayed()
    }

    @Test
    fun `a project that is not open is the machine's sentence and no rows`() {
        val sentence = "This chat's project is not open in the IDE on the machine."
        show { MobileHooks("k", emptyList(), notice = sentence) }

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithText("No hooks are configured for this project.").assertDoesNotExist()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty list`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
