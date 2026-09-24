package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileUsageBucket
import com.github.claudeagents.core.mobile.MobileUsageDay
import com.github.claudeagents.core.mobile.MobileUsageFilter
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
        // The filter chips sit above Spend, so Plans is a scroll away on the test's small screen.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("weekly"))
        compose.onNodeWithText("weekly", substring = false).assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        // The next account's card has a 5-hour row of its own, now on the same screen.
        compose.onAllNodesWithText("5-hour").onFirst().assertIsDisplayed()
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

    /**
     * The desk's daily cost chart: it opens on the newest day, a tap on another bar (a button of
     * its own for TalkBack) moves the readout to that day in the machine's own spelling, and a
     * day the machine could only bound reads as a floor rather than a total.
     */
    @Test
    fun `the daily chart opens on today and a tapped bar reads its own day as the machine spelled it`() {
        screen()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("usage-daily-chart"))
        compose.onNodeWithText("Daily cost — last 14 days").assertIsDisplayed()
        compose.onNode(hasTestTag("usage-daily-readout")).assertTextEquals("Thu, Sep 24 · \$0.31 · 12.0K in · 2.4K out")
        compose.onNodeWithContentDescription("Sat, Sep 19", substring = true).performClick()
        compose.onNode(hasTestTag("usage-daily-readout")).assertTextEquals("Sat, Sep 19 · ≥\$15.90 · 380.0K in · 76.0K out")
        compose.onNodeWithContentDescription("Tue, Sep 22", substring = true).performClick()
        compose.onNode(hasTestTag("usage-daily-readout")).assertTextEquals("Tue, Sep 22 · ~\$8.40 · 190.0K in · 38.0K out")
    }

    /** A day with nothing recorded is a bar slot that says so, not a missing one; a window of them is one sentence. */
    @Test
    fun `a free day says no usage and a window of free days says so once`() {
        val days = (11..24).map { MobileUsageDay("2026-09-$it") }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage!!.copy(days = days), loading = false, error = null, onLoad = {})
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("usage-daily-chart"))
        compose.onNodeWithText("No cost recorded in the last 14 days.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("usage-daily-readout")).fetchSemanticsNodes().size)
    }

    /** A host that predates the chart sends no days; the page shows no empty heading for it. */
    @Test
    fun `a machine that sends no days gets no daily cost heading`() {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage!!.copy(days = emptyList()), loading = false, error = null, onLoad = {})
            }
        }
        assertEquals(0, compose.onAllNodesWithText("Daily cost", substring = true).fetchSemanticsNodes().size)
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

    /**
     * The desk's Agent and Account filters, inline chips: a tick — not colour alone — says which is on,
     * a tap names the pick and keeps the other axis, and each row appears only when the machine lists
     * something to tell apart.
     */
    @Test
    fun `the agent and account chips name the pick and keep the other axis`() {
        val picks = mutableListOf<Pair<AgentVendor?, String?>>()
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(
                    report = state.usage!!.let { it.copy(filter = it.filter.copy(agent = AgentVendor.CLAUDE, account = "work")) },
                    loading = false, error = null, onLoad = {},
                    onFilter = { agent, account -> picks += agent to account },
                )
            }
        }
        compose.onNodeWithTag("usage-filter-agent-CLAUDE").assertIsSelected()
        compose.onNodeWithTag("usage-filter-agent-all").assertIsNotSelected()
        compose.onNodeWithTag("usage-filter-account-work").assertIsSelected()
        compose.onNodeWithTag("usage-filter-agent-CODEX").performClick()
        compose.onNodeWithTag("usage-filter-account-all").performClick()
        compose.onNodeWithTag("usage-filter-agent-all").performClick()
        assertEquals(
            listOf<Pair<AgentVendor?, String?>>(AgentVendor.CODEX to "work", AgentVendor.CLAUDE to null, null to "work"),
            picks,
        )
    }

    /** One agent and one account have nothing to tell apart, and a host that predates the filter says nothing. */
    @Test
    fun `no chips when the machine offers nothing to choose between`() {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage!!.copy(filter = MobileUsageFilter()), loading = false, error = null, onLoad = {})
            }
        }
        assertEquals(0, compose.onAllNodes(hasTestTag("usage-filter")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Spend").assertIsDisplayed()
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
