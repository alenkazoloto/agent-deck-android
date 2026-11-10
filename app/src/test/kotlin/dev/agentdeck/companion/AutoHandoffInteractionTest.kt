package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.mobile.MobileAutoHandoff
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
 * Settings › Machine's "Continue on another account near the usage limit" is the desk's switch and two
 * percentages: drawn only once a phone holding the owner's grant has had the machine's answer and only where
 * the build may hold a second account, the percentages only while the switch is on, each edit naming just its
 * own field, and a percentage sent only when the machine would accept it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AutoHandoffInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<MobileAutoHandoff.Change>()
    private var reads = 0

    private fun show(handoff: MobileAutoHandoff?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(autoHandoff = handoff),
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
                onKeepAwake = {},
                onRefreshAutoHandoff = { reads++ },
                onAutoHandoff = { changes += it },
            )
        }
    }

    private val title = "Continue on another account near the usage limit"

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performTextClearance()
        compose.onNodeWithTag(tag).performTextInput(text)
        compose.onNodeWithTag(tag).performImeAction()
    }

    @Test
    fun `the switch shows the desk's value and a tap sends only the switch`() {
        show(MobileAutoHandoff(enabled = false, sessionPercent = 95, weeklyPercent = 90, multiAccount = true))

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(title).performClick()

        assertEquals(listOf(MobileAutoHandoff.Change(enabled = true)), changes)
    }

    @Test
    fun `the percentages are drawn only while the switch is on`() {
        show(MobileAutoHandoff(enabled = false, sessionPercent = 95, weeklyPercent = 90, multiAccount = true))

        assertEquals(0, count("5-hour window"))
        assertEquals(0, count("Weekly window"))
    }

    @Test
    fun `with the switch on each percentage shows the desk's number and sends only itself`() {
        show(MobileAutoHandoff(enabled = true, sessionPercent = 95, weeklyPercent = 90, multiAccount = true))

        compose.onNodeWithTag("auto-handoff-session").assertTextContains("95")
        compose.onNodeWithTag("auto-handoff-weekly").assertTextContains("90")

        type("auto-handoff-session", "80")
        type("auto-handoff-weekly", "60")

        assertEquals(listOf(MobileAutoHandoff.Change(sessionPercent = 80), MobileAutoHandoff.Change(weeklyPercent = 60)), changes)
    }

    @Test
    fun `the same number, an empty field and a number outside the desk's range send nothing`() {
        show(MobileAutoHandoff(enabled = true, sessionPercent = 95, weeklyPercent = 90, multiAccount = true))

        type("auto-handoff-session", "95")
        type("auto-handoff-session", "")
        type("auto-handoff-session", "0")
        type("auto-handoff-session", "100")
        type("auto-handoff-weekly", "999")

        assertEquals(emptyList<MobileAutoHandoff.Change>(), changes)
        compose.onNodeWithTag("auto-handoff-session").assertTextContains("95")
        compose.onNodeWithTag("auto-handoff-weekly").assertTextContains("90")
    }

    @Test
    fun `a build that may not hold a second account draws no row, as the desk hides it`() {
        show(MobileAutoHandoff(enabled = false, sessionPercent = 95, weeklyPercent = 95, multiAccount = false))

        assertEquals(0, count(title))
    }

    @Test
    fun `a phone the machine has not answered, for want of the grant, draws no row`() {
        show(null)

        assertEquals(0, count(title))
    }

    @Test
    fun `opening the tab asks the machine for the values`() {
        show(null)
        compose.waitForIdle()

        assertEquals(1, reads)
    }
}
