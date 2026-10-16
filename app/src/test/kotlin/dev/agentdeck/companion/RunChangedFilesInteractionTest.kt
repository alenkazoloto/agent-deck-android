package dev.agentdeck.companion

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileRunChangedFile
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.ConversationScreen
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's "Changed files while a run works" is the desk's own switch: drawn only once the
 * machine has answered with its value, a tap asks for the opposite — and a running conversation's page
 * carries the files its run has changed so far, newest first, named above the message box with the
 * desk's own tally, three of them and "+N more" past that. A finished run's page carries none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class RunChangedFilesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()

    private fun showSettings(on: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, runChangedFiles = on),
                onSettings = {},
                onSwitchMachine = {},
                onAddMachine = {},
                onUnpair = {},
                onRefreshHello = {},
                onRefreshPush = {},
                onChoosePush = {},
                onCheckUpdate = {},
                onDownloadUpdate = {},
                onInstallUpdate = {},
                onReleasePage = {},
                onRunChangedFiles = { flips += it },
            )
        }
    }

    private fun showConversation(running: Boolean, files: List<MobileRunChangedFile>) {
        val state = DeckFixtures.byName("convo-live-runs")!!
        val page = requireNotNull(state.transcript).copy(running = running, runChangedFiles = files)
        compose.setContent {
            AgentDeckTheme {
                val target = Screen.Conversation(page.key, page.title, AgentVendor.CLAUDE, "/project")
                ConversationScreen(
                    target = target, page = page, loading = false, cached = false, draft = "", notice = null,
                    onDraft = {}, onSend = { _, _ -> }, onStop = {}, onDismissNotice = {},
                )
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(on = true)

        compose.onNodeWithText("Changed files while a run works").performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(on = false)

        compose.onNodeWithText("Changed files while a run works").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(on = null)

        assertEquals(0, count("Changed files while a run works"))
    }

    @Test
    fun `a live run names three files and folds the rest with the desk's tally`() {
        showConversation(
            running = true,
            files = listOf(
                MobileRunChangedFile("src/app/InvoiceTable.tsx", "+34 −12"),
                MobileRunChangedFile("src/app/useInvoices.ts", "+7 −1"),
                MobileRunChangedFile("src/api/invoices.ts", "+58"),
                MobileRunChangedFile("README.md", null),
            ),
        )

        compose.onNodeWithText("Changed so far:").assertExists()
        compose.onNodeWithText("InvoiceTable.tsx +34 −12", substring = true).assertExists()
        compose.onNodeWithText("useInvoices.ts +7 −1", substring = true).assertExists()
        compose.onNodeWithText("invoices.ts +58", substring = true).assertExists()
        compose.onNodeWithText("+1 more").assertExists()
        assertEquals("a fourth name would outgrow the strip", 0, count("README.md"))
    }

    @Test
    fun `a run that has changed nothing draws no strip`() {
        showConversation(running = true, files = emptyList())

        assertEquals(0, count("Changed so far:"))
    }
}
