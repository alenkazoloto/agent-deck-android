package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.mobile.MobileStatus
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.StatusSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's `/status` on the phone: the machine's rows in its own labels, "Check API connectivity" asking
 * once and replacing the rows with the answer, the machine's sentence when the chat's project is closed,
 * and a machine that does not answer said so rather than drawn as an empty status.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val loads = ArrayList<Boolean>()

    private val rows = listOf(
        MobileStatus.Row("Working directory", "/repo"),
        MobileStatus.Row("Account", "me@example.com · Max"),
        MobileStatus.Row("MCP servers", "2 configured", "Connection state for each server is in Settings › MCP."),
    )

    private fun show(answer: suspend (Boolean) -> MobileStatus?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                StatusSheet(onLoad = { check -> loads += check; answer(check) }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `each row reads as its label over its value, with the machine's detail`() {
        show { MobileStatus("k", rows) }

        compose.onNodeWithText("Status").assertIsDisplayed()
        compose.onNodeWithText("Working directory").assertIsDisplayed()
        compose.onNodeWithText("me@example.com · Max").assertIsDisplayed()
        compose.onNodeWithText("Connection state for each server is in Settings › MCP.").assertIsDisplayed()
        assertEquals(listOf(false), loads)
    }

    @Test
    fun `checking asks once more with check and shows the connectivity row it brings`() {
        show { check ->
            MobileStatus("k", if (check) rows + MobileStatus.Row("API connectivity", "Reachable · checked just now") else rows)
        }

        compose.onNodeWithTag("status-check").assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Reachable · checked just now").assertIsDisplayed()
        assertEquals(listOf(false, true), loads)
    }

    @Test
    fun `a project that is not open is the machine's sentence and no check`() {
        val sentence = "This chat's project is not open in the IDE on the machine, so there is nothing to read its status from."
        show { MobileStatus("k", emptyList(), sentence) }

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithTag("status-check").assertDoesNotExist()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty status`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
