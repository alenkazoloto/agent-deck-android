package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Settings › Machine's "Skills in the / menu" is the desk's switch: drawn only once the machine
 * has answered with its value, asked for on every visit, and a tap asks for the opposite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SkillsInSlashInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()
    private var reads = 0
    private val keepAwakeFlips = mutableListOf<Boolean>()

    private fun show(skillsInSlash: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, skillsInSlash = skillsInSlash),
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
                onKeepAwake = { keepAwakeFlips += it },
                onRefreshSkillsInSlash = { reads++ },
                onSkillsInSlash = { flips += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite and leaves keep awake alone`() {
        show(skillsInSlash = true)

        compose.onNodeWithText("Skills in the / menu").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
        assertEquals(emptyList<Boolean>(), keepAwakeFlips)
    }

    @Test
    fun `an off switch asks for on`() {
        show(skillsInSlash = false)

        compose.onNodeWithText("Skills in the / menu").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        show(skillsInSlash = null)

        assertEquals(0, count("Skills in the / menu"))
    }

    @Test
    fun `opening the tab asks the machine for the value`() {
        show(skillsInSlash = null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
