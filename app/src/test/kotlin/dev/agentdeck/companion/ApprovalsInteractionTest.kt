package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.mobile.MobileApprovals
import com.github.claudeagents.core.mobile.MobileStatus
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ApprovalsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's Codex `/permissions` on the phone: the machine's rows in its own labels, its sentence when Codex
 * could not be asked, and a machine that does not answer said so rather than drawn as an empty page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val loads = ArrayList<Unit>()

    private val rows = listOf(
        MobileStatus.Row("In force for chats here", "Never ask", "Codex never asks; a failure goes straight back to the model."),
        MobileStatus.Row("Configured", "On request", "The model decides when to ask. Codex's own default — no config layer sets it."),
        MobileStatus.Row("Execpolicy rules · User config", "default.rules\nnpm.rules"),
    )

    private fun show(answer: suspend () -> MobileApprovals?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                ApprovalsSheet(onLoad = { loads += Unit; answer() }, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `each row reads as its label over its value, with the machine's detail`() {
        show { MobileApprovals("k", rows) }

        compose.onNodeWithText("Permissions").assertIsDisplayed()
        compose.onNodeWithText("In force for chats here").assertIsDisplayed()
        compose.onNodeWithText("Never ask").assertIsDisplayed()
        compose.onNodeWithText("Codex never asks; a failure goes straight back to the model.").assertIsDisplayed()
        compose.onNodeWithText("default.rules\nnpm.rules").assertIsDisplayed()
        assertEquals(1, loads.size)
    }

    @Test
    fun `a Codex that could not be asked is the machine's sentence and no rows`() {
        val sentence = "Codex did not answer with its approval policy"
        show { MobileApprovals("k", emptyList(), sentence) }

        compose.onNodeWithText(sentence).assertIsDisplayed()
        compose.onNodeWithText("In force for chats here").assertDoesNotExist()
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }
}
