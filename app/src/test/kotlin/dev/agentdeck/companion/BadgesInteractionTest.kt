package dev.agentdeck.companion

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileBadge
import com.github.claudeagents.core.mobile.MobileBadges
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.badgeProgress
import dev.agentdeck.companion.ui.badgeStatus
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Resources › "Badges" is the desk's badge gallery, read-only, with Share on an earned
 * one: offered only by a machine that advertises `badges`, read again each time it opens, every
 * state a word, and a share that hands the desk's own line to the share sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BadgesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private val streak = MobileBadge(
        id = "streak", name = "Daily Driver", emoji = "🔥", caption = "Longest run of consecutive days with Claude",
        unit = "days", tier = "Silver", value = "25", nextTier = "Gold", nextTarget = "30",
        unlockedAtMs = 1_700_000_000_000, isNew = true,
        shareText = "🔥 I just unlocked \"Daily Driver · Silver\" in Agents Deck — 25-day streak with Claude Code!",
    )
    private val tokens = MobileBadge(
        id = "tokens", name = "Torrent", emoji = "🌊", caption = "Output tokens generated", unit = "tokens",
        value = "740 000", nextTier = "Bronze", nextTarget = "1M",
    )
    private val gallery = MobileBadges(listOf(streak, tokens))

    private fun show(capable: Boolean = true, answer: suspend () -> MobileBadges?) {
        val base = DeckFixtures.byName("settings")!!
        val state = if (capable) {
            base.copy(hello = base.hello!!.copy(capabilities = base.hello!!.capabilities + MobileProtocol.Capability.BADGES))
        } else {
            base
        }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    state = state, onSettings = {}, onSwitchMachine = {}, onAddMachine = {}, onUnpair = {},
                    onRefreshHello = {}, onRefreshPush = {}, onChoosePush = {}, onCheckUpdate = {},
                    onDownloadUpdate = {}, onInstallUpdate = {}, onReleasePage = {},
                    onLoadBadges = { loads++; answer() },
                )
            }
        }
    }

    private fun open() {
        compose.onNodeWithText("Badges").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        show(capable = false) { gallery }

        assertEquals(0, compose.onAllNodesWithText("Badges").fetchSemanticsNodes().size)
    }

    @Test
    fun `the sheet reads each badge's tier, progress and count in words`() {
        show { gallery }
        open()

        compose.onNodeWithText("1 of 2 badges earned").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p39/badges-sheet.png", RECORD)
        compose.onNodeWithText("🔥 Daily Driver").assertIsDisplayed()
        compose.onNodeWithText("25 of 30 days to Gold").assertIsDisplayed()
        compose.onNodeWithText("Not earned yet").assertIsDisplayed()
        compose.onNodeWithText("740 000 of 1M tokens to Bronze").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `sharing an earned badge hands the desk's line to the share sheet, and a locked one has no button`() {
        show { gallery }
        open()

        assertEquals(0, compose.onAllNodesWithContentDescription("Share Torrent").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Share Daily Driver").performClick()

        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(streak.shareText, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Daily Driver", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }
        open()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }

    @Test
    fun `a machine still counting gets its own sentence, not an empty gallery`() {
        show { MobileBadges(unavailable = "The machine is still counting your badges. Try again in a moment.") }
        open()

        compose.onNodeWithText("The machine is still counting your badges. Try again in a moment.").assertIsDisplayed()
    }

    @Test
    fun `status and progress are words, and Diamond has no next bar`() {
        assertEquals("Silver · earned Nov 14, 2023 · NEW", badgeStatus(streak, ZoneOffset.UTC))
        assertEquals("Not earned yet", badgeStatus(tokens))
        assertEquals("Diamond", badgeStatus(streak.copy(tier = "Diamond", unlockedAtMs = 0, isNew = false, nextTier = null, nextTarget = null)))
        assertNull(badgeProgress(streak.copy(nextTier = null, nextTarget = null)))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private companion object {
        val RECORD = RoborazziOptions(taskType = RoborazziTaskType.Record)
    }
}
