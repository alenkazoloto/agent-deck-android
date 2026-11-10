package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.mobile.MobileDebugConfig
import com.github.claudeagents.core.mobile.MobileStatus
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.DebugConfigSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's Codex `/debug-config` on the phone: the machine's rows in its own labels, its sentence when Codex
 * could not be asked, and a machine that does not answer said so rather than drawn as an empty page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DebugConfigInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val loads = ArrayList<Unit>()

    private val rows = listOf(
        MobileStatus.Row("This project", "This folder is not trusted", "Project config, hooks and execpolicy rules in this folder do not load; skills still do."),
        MobileStatus.Row("Config layer · User config", "2 settings in force", "config.toml"),
        MobileStatus.Row("Settings from User config", "model\nweb_search"),
    )

    private fun show(answer: suspend () -> MobileDebugConfig?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                DebugConfigSheet(onLoad = { loads += Unit; answer() }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `each row reads as its label over its value, with the machine's detail`() {
        show { MobileDebugConfig("k", rows) }

        compose.onNodeWithText("Config layers").assertIsDisplayed()
        compose.onNodeWithText("This project").assertIsDisplayed()
        compose.onNodeWithText("This folder is not trusted").assertIsDisplayed()
        compose.onNodeWithText("config.toml").assertIsDisplayed()
        compose.onNodeWithText("model\nweb_search").assertIsDisplayed()
        assertEquals(1, loads.size)
    }

    @Test
    fun `a Codex that could not be asked is the machine's sentence and no rows`() {
        val sentence = "Codex did not answer with its config layers"
        show { MobileDebugConfig("k", emptyList(), sentence) }

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithText("This project").assertDoesNotExist()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
