package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.mobile.MobileCodexFeatures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.CodexFeaturesSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's Codex `/experimental` on the phone: each flag with its stage and resolved state, the machine's
 * count above the list, the desk dialog's filter, and the machine's sentence when Codex has none or did not
 * answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodexFeaturesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private fun show(answer: suspend () -> MobileCodexFeatures?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                CodexFeaturesSheet(onLoad = { loads++; answer() }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    private val features = MobileCodexFeatures(
        listOf(
            MobileCodexFeatures.Feature("Network proxy", "network_proxy", "Beta", "On · default off", "Apply network proxy restrictions to sandboxed sessions."),
            MobileCodexFeatures.Feature("shell_tool", "shell_tool", "Stable", "On"),
        ),
        summary = "2 features — 1 set away from its default.",
    )

    @Test
    fun `each flag reads with its stage and state, and the count leads`() {
        show { features }

        compose.onNodeWithText("Codex features").assertIsDisplayed()
        compose.onNodeWithText("2 features — 1 set away from its default.").assertIsDisplayed()
        compose.onNodeWithText("Network proxy").assertIsDisplayed()
        compose.onNodeWithText("Beta · On · default off").assertIsDisplayed()
        compose.onNodeWithText("Apply network proxy restrictions to sandboxed sessions.").assertIsDisplayed()
        compose.onNodeWithText("network_proxy").assertIsDisplayed()
        compose.onNodeWithText("Stable · On").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `the filter narrows the list by name, stage or prose`() {
        show { features }

        compose.onNodeWithTag("codex-features-filter").performTextInput("stable")
        compose.onNodeWithText("shell_tool").assertIsDisplayed()
        compose.onNodeWithText("Network proxy").assertDoesNotExist()
    }

    @Test
    fun `a filter nothing matches says so`() {
        show { features }

        compose.onNodeWithTag("codex-features-filter").performTextInput("zzz")
        compose.onNodeWithText("No feature matches this filter.").assertIsDisplayed()
    }

    @Test
    fun `the machine's sentence stands in for an empty list`() {
        show { MobileCodexFeatures(emptyList(), notice = "Codex reported no feature flags") }

        compose.onNodeWithText("Codex reported no feature flags").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer says so instead of showing no features`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
