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
 * Settings › Machine's "Prompt cache clock" is the desk's "Show prompt cache clock": drawn only once the machine has
 * answered with its value, a tap asks for the opposite — and the open conversation's subtitle carries the words the
 * machine put on the page ("cache 12m", "cache cold"), and nothing when it sent none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CacheClockInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val flips = mutableListOf<Boolean>()

    private fun showSettings(cacheClock: Boolean?) = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = DeckFixtures.byName("settings")!!.copy(keepAwake = true, cacheClock = cacheClock),
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
                onCacheClock = { flips += it },
            )
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun `the switch asks for the opposite`() {
        showSettings(cacheClock = true)

        compose.onNodeWithText("Prompt cache clock").assertIsDisplayed().performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `an off switch asks for on`() {
        showSettings(cacheClock = false)

        compose.onNodeWithText("Prompt cache clock").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `a machine that has not answered draws no switch`() {
        showSettings(cacheClock = null)

        assertEquals(0, count("Prompt cache clock"))
    }

    @Test
    fun `the subtitle carries the machine's words after the cost and context`() {
        val page = requireNotNull(DeckFixtures.byName("convo-cache-clock")?.transcript).copy(contextPct = 41)

        assertEquals("cache 12m", page.cacheClock)
        assertEquals(true, conversationSubtitle(page)!!.contains(" · 41% context · cache 12m"))
        assertEquals(true, conversationSubtitle(page.copy(cacheClock = "cache cold"))!!.contains("cache cold"))
    }

    @Test
    fun `a page with no words leaves the subtitle as it was`() {
        val page = requireNotNull(DeckFixtures.byName("convo-idle")?.transcript)

        assertEquals(false, conversationSubtitle(page)!!.contains("cache"))
    }
}
