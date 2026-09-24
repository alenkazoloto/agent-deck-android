package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileUsageBucket
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.SettingsScreen
import dev.agentdeck.companion.ui.UsageScreen
import dev.agentdeck.companion.ui.usageCost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading this machine's spend and its plan windows from the phone.
 *
 * The questions are the reader's, in order: is the page reachable at all from a machine that
 * cannot serve it; does opening it cost exactly one request; does a cost the machine could only
 * *bound* say so rather than read as a total; and is a drained window visible beside a window
 * that still has room — which is the one fact on the page anybody acts on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class UsageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = DeckFixtures.byName("usage")!!
    private var loads = 0

    private fun screen() = compose.setContent {
        AgentDeckTheme(dark = false, dynamic = false) {
            UsageScreen(
                report = state.usage,
                loading = false,
                error = null,
                onLoad = { loads++ },
            )
        }
    }

    /**
     * The machine walks every indexed conversation to answer, so a fetch driven by
     * recomposition would bill that walk to a scroll. Recomposed deliberately here: the screen
     * asking twice for one visit is the failure, and `LaunchedEffect(Unit)` is the guard.
     */
    @Test
    fun `opening the page asks the machine exactly once`() {
        screen()
        compose.waitForIdle()
        compose.onNodeWithText("Today").performClick()
        compose.waitForIdle()
        assertEquals(1, loads)
    }

    /** A floor is not a total: `≥$` is the machine's statement, and it has to survive to the eye. */
    @Test
    fun `a cost the machine could only bound is painted as a floor`() {
        assertEquals("≥\$912.80", usageCost(MobileUsageBucket(costUsd = 912.80, costKnown = false)))
        assertEquals("~\$186.44", usageCost(MobileUsageBucket(costUsd = 186.44, costEstimated = true)))
        assertEquals("\$3.17", usageCost(MobileUsageBucket(costUsd = 3.17)))
        // The rounding that used to print nothing over a day that had spend.
        assertEquals("<\$0.01", usageCost(MobileUsageBucket(costUsd = 0.004)))

        screen()
        compose.onNodeWithText("≥\$912.80").assertIsDisplayed()
    }

    /**
     * The whole reason the page lists windows. The fixture's week is drained while its 5-hour
     * window is at 12%; a page that showed the leader alone would report capacity that is not
     * there, and one that showed the 5-hour window alone would report the opposite.
     */
    @Test
    fun `a drained window is shown beside one that still has room`() {
        screen()
        compose.onNodeWithText("weekly", substring = false).assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithText("5-hour", substring = false).assertIsDisplayed()
        compose.onNodeWithText("12%").assertIsDisplayed()
    }

    /**
     * The desk's "By project" table: the name, its conversation count (singular when it is one)
     * and the same honest spelling of the cost the period cards use.
     */
    @Test
    fun `each project row names its sessions and paints its cost as the machine spelled it`() {
        screen()
        // A lazy list composes only what fits, and the table is the page's last section.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("scratch"))
        compose.onNodeWithText("By project").assertIsDisplayed()
        compose.onNodeWithText("Plugin").assertIsDisplayed()
        compose.onNodeWithText("41 sessions · 1.1M in · 240.0K out", substring = true).assertIsDisplayed()
        compose.onNodeWithText("\$121.60").assertIsDisplayed()
        compose.onNodeWithText("1 session · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("~\$9.05").assertIsDisplayed()
        compose.onNodeWithText("≥\$0.42").assertIsDisplayed()
    }

    /** A host that predates the table sends no rows; the page shows no empty heading for it. */
    @Test
    fun `a machine that sends no projects gets no By project heading`() {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage!!.copy(projects = emptyList()), loading = false, error = null, onLoad = {})
            }
        }
        assertEquals(0, compose.onAllNodesWithText("By project").fetchSemanticsNodes().size)
    }

    /** The desk's two cap rows: who set each, in the desk's words, printed because a phone has no tooltip. */
    @Test
    fun `each spend cap names who set it and prints the desk's sentence`() {
        screen()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Your custom chat budget"))
        compose.onNodeWithText("Spend limits").assertIsDisplayed()
        compose.onNodeWithText("hands off at \$5.00 · stops at \$10.00").assertIsDisplayed()
        compose.onNodeWithText("This is the plugin's own stop", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Weekly"))
        compose.onNodeWithText("Gateway spend limit — your organization set this").assertIsDisplayed()
        compose.onNodeWithText("the amount is not known here", substring = true).assertIsDisplayed()
    }

    /** With no budget and no refusal the page shows no empty heading for them. */
    @Test
    fun `a machine with no caps gets no Spend limits heading`() {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage!!.copy(caps = emptyList()), loading = false, error = null, onLoad = {})
            }
        }
        assertEquals(0, compose.onAllNodesWithText("Spend limits").fetchSemanticsNodes().size)
    }

    /** An account with nothing measured is not an account at 0%, and must say which it is. */
    @Test
    fun `an unmeasured account carries its sentence instead of a percentage`() {
        val unmeasured = DeckFixtures.byName("usage-unmeasured")!!
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = unmeasured.usage, loading = false, error = null, onLoad = {})
            }
        }
        // The two Claude accounts share the Claude sentence; the Codex one is its own, and the
        // pair is the point — an empty card must say which kind of nothing it is reporting.
        assertEquals(
            2,
            compose.onAllNodesWithText("No plan usage has been read for this account yet.")
                .fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Codex reports its limits only while it runs", substring = true)
            .assertIsDisplayed()
    }

    /**
     * A client hides a surface it does not see. The pair differs in the capability alone, so a
     * row wired to the build rather than to the hello would pass the first half and fail here.
     */
    @Test
    fun `the Settings row appears only on a machine that advertises usage`() {
        val advertised = DeckFixtures.byName("settings-usage")!!
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) { Settings(advertised.hello) }
        }
        compose.onNodeWithText("Spend and plan windows").assertIsDisplayed()
    }

    @Test
    fun `the Settings row is absent from a machine that does not`() {
        val older = DeckFixtures.byName("settings")!!
        assert(MobileProtocol.Capability.USAGE !in older.hello?.capabilities.orEmpty()) {
            "the negative control must not advertise usage, or it proves nothing"
        }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) { Settings(older.hello) }
        }
        assertEquals(
            "a surface the machine did not advertise must not be on the page",
            0,
            compose.onAllNodesWithText("Spend and plan windows").fetchSemanticsNodes().size,
        )
    }

    @androidx.compose.runtime.Composable
    private fun Settings(hello: MobileHello?) = SettingsScreen(
        state = DeckFixtures.byName("settings")!!.copy(hello = hello),
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
        onOpenUsage = {},
    )
}
