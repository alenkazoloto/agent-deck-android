package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.mobile.MobileCodexApps
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.CodexAppsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's Codex `/apps` on the phone: each app with Codex's own state words, its description and plugins
 * and an https manage link, the machine's sentence when Codex has none or did not answer, and the desk
 * dialog's filter once the list is long.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodexAppsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private fun show(answer: suspend () -> MobileCodexApps?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                CodexAppsSheet(onLoad = { loads++; answer() }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    private val apps = MobileCodexApps(
        listOf(
            MobileCodexApps.App("Hotline", "Installed", "Look up local hotline information", listOf("Support"), "https://chatgpt.com/apps/hotline"),
            MobileCodexApps.App("Notes", "Installed · Disabled"),
        ),
    )

    @Test
    fun `each app reads with its state, description, plugins and a manage link`() {
        show { apps }

        compose.onNodeWithText("Codex apps").assertIsDisplayed()
        compose.onNodeWithText("Hotline").assertIsDisplayed()
        compose.onNodeWithText("Installed").assertIsDisplayed()
        compose.onNodeWithText("Look up local hotline information").assertIsDisplayed()
        compose.onNodeWithText("Used by Support").assertIsDisplayed()
        compose.onNodeWithText("Manage Hotline on ChatGPT").assertIsDisplayed()
        compose.onNodeWithText("Installed · Disabled").assertIsDisplayed()
        compose.onNodeWithText("Manage Notes on ChatGPT").assertDoesNotExist()
        compose.onNodeWithTag("codex-apps-filter").assertDoesNotExist()
        assertEquals(1, loads)
    }

    @Test
    fun `a long list gains the desk dialog's filter`() {
        val many = MobileCodexApps((1..12).map { MobileCodexApps.App("App $it", "Installed", "About $it") })
        show { many }

        compose.onNodeWithTag("codex-apps-filter").assertIsDisplayed()
        compose.onNodeWithTag("codex-apps-filter").performTextInput("app 7")
        compose.onNodeWithText("App 7").assertIsDisplayed()
        compose.onNodeWithText("App 8").assertDoesNotExist()
    }

    @Test
    fun `the machine's sentence stands in for an empty list`() {
        show { MobileCodexApps(emptyList(), notice = "No ChatGPT apps are installed for Codex") }

        compose.onNodeWithText("No ChatGPT apps are installed for Codex").assertIsDisplayed()
    }

    @Test
    fun `a machine that did not answer says so instead of showing no apps`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
