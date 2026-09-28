package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.mobile.MobileCodexOutput
import com.github.claudeagents.core.mobile.MobileModelOption
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Machine's Codex answer length and reasoning summary are the desk's pair: drawn only once
 * the machine has answered with its lists (empty where there is no Codex), asked for on every visit,
 * and a pick names one cell, so flipping one cannot write back a stale copy of the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CodexOutputInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val picks = mutableListOf<MobileCodexOutput.Change>()
    private var reads = 0

    private fun options(vararg pairs: Pair<String, String>) = pairs.map { (slug, label) -> MobileModelOption(slug, label) }

    private val output = MobileCodexOutput(
        "default", "default",
        options("default" to "Codex default", "low" to "Low", "high" to "High"),
        options("default" to "Codex default", "concise" to "Concise", "none" to "None"),
    )

    private fun show(codexOutput: MobileCodexOutput?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(codexOutput = codexOutput),
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
                onRefreshCodexOutput = { reads++ },
                onCodexOutput = { picks += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `each picker names its own cell and sends only that cell`() {
        show(output)

        compose.onNodeWithText("Codex answer length").assertIsDisplayed()
        compose.onNodeWithText("Codex reasoning summary").assertIsDisplayed()

        compose.onAllNodesWithText("Codex default")[0].performClick()
        compose.onNodeWithText("High").performClick()
        compose.onAllNodesWithText("Codex default")[1].performClick()
        compose.onNodeWithText("Concise").performClick()

        assertEquals(
            listOf(MobileCodexOutput.Change(verbosity = "high"), MobileCodexOutput.Change(summary = "concise")),
            picks,
        )
    }

    @Test
    fun `a machine with no Codex answers empty lists and draws no row`() {
        show(MobileCodexOutput("default", "default"))

        assertEquals(0, count("Codex answer length"))
        assertEquals(0, count("Codex reasoning summary"))
    }

    @Test
    fun `a machine that has not answered draws no row`() {
        show(null)

        assertEquals(0, count("Codex answer length"))
    }

    @Test
    fun `opening the tab asks the machine for its pair`() {
        show(null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
