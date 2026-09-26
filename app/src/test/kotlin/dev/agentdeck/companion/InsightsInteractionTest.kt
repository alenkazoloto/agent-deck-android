package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.github.claudeagents.core.mobile.MobileInsights
import com.github.claudeagents.core.mobile.MobileInsightsTable
import com.github.claudeagents.core.mobile.MobileInsightsTile
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.UsageScreen
import dev.agentdeck.companion.ui.insightsRowDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk Usage tab's Session insights on the phone (P25): a row after the figures it sums, offered
 * only where the machine serves it, read again each time the sheet opens, every figure the machine's
 * own sentence, and a table read as a name over its numbers in words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InsightsInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private var loads = 0

    private val base = DeckFixtures.byName("usage")!!
    private val capable = base.hello!!.copy(capabilities = base.hello!!.capabilities + MobileProtocol.Capability.SESSION_INSIGHTS)

    private val loops = MobileInsightsTable(
        "loops", "Loops — 1 ran 4 times, ~\$1.20 estimated",
        listOf("Loop", "Runs", "Tokens", "Per run", "Last run"),
        listOf(listOf("check the build", "4", "4.0K", "1.0K", "5 min ago")),
    )
    private val skills = MobileInsightsTable(
        "skills", "Skills and commands — who invoked them",
        listOf("Skill or command", "Uses", "You typed", "Claude ran"),
        listOf(listOf("review", "3", "2", "1")),
    )
    private val insights = MobileInsights(
        summary = "Last 30 days · 12 sessions across 3 projects",
        empty = false,
        tiles = listOf(
            MobileInsightsTile("12", "sessions"),
            MobileInsightsTile("340", "messages"),
            MobileInsightsTile("1.2M", "tokens", "Input 20K · Output 60K · Cache write 300K · Cache read 820K"),
        ),
        hours = List(24) { if (it in 9..17) it - 8 else 0 },
        hoursCaption = "Time of day — hours worked, busiest at 17:00",
        tables = listOf(loops, skills),
        footer = "2 sessions you interrupted, 1 that ended in an error",
        generatedAtMs = 1_700_000_000_000,
    )

    private fun show(hello: com.github.claudeagents.core.mobile.MobileHello = capable, answer: suspend () -> MobileInsights?) {
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(
                    report = base.usage, loading = false, error = null, onLoad = {}, hello = hello,
                    onLoadInsights = { loads++; answer() },
                )
            }
        }
    }

    private fun open() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("usage-insights"))
        compose.onNodeWithTag("usage-insights").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `a machine without the capability has no row`() {
        assert(MobileProtocol.Capability.SESSION_INSIGHTS !in base.hello!!.capabilities) { "the negative control must not advertise it" }
        show(hello = base.hello!!) { error("asked") }

        assertEquals(0, compose.onAllNodesWithTag("usage-insights").fetchSemanticsNodes().size)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun `the sheet reads the machine's summary, tiles, hours and tables as its own words`() {
        show { insights }
        open()

        compose.onNodeWithText("Last 30 days · 12 sessions across 3 projects").assertIsDisplayed()
        compose.onAllNodes(isRoot()).get(1).captureRoboImage("build/outputs/p25/session-insights.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithText("1.2M").assertIsDisplayed()
        compose.onNodeWithText("Time of day — hours worked, busiest at 17:00").assertIsDisplayed()
        compose.onNodeWithText("Loops — 1 ran 4 times, ~\$1.20 estimated").assertIsDisplayed()
        compose.onNodeWithText("Runs 4 · Tokens 4.0K · Per run 1.0K · Last run 5 min ago").assertIsDisplayed()
        assertEquals(1, loads)
    }

    @Test
    fun `closing and reopening the sheet reads the machine again`() {
        show { insights }
        open()
        compose.onNodeWithText("Last 30 days · 12 sessions across 3 projects").assertIsDisplayed()
        compose.onNodeWithTag("insights-sheet").performTouchInput { swipeDown(startY = top, endY = bottom + 600f) }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("insights-sheet").fetchSemanticsNodes().size)
        open()

        assertEquals(2, loads)
    }

    @Test
    fun `a window with nothing in it says so and paints no tiles`() {
        show { MobileInsights(summary = "No sessions in the last 30 days — nothing to report yet.") }
        open()

        compose.onNodeWithText("No sessions in the last 30 days — nothing to report yet.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("insights-tile").fetchSemanticsNodes().size)
    }

    @Test
    fun `a machine that did not answer says so instead of showing an empty page`() {
        show { null }
        open()

        compose.onNodeWithText("The machine did not answer. Close this and try again.").assertIsDisplayed()
    }

    @Test
    fun `a machine still indexing says its totals will grow`() {
        show { insights.copy(indexing = true) }
        open()

        compose.onNodeWithText("This machine is still reading its transcripts, so these totals will grow.").assertIsDisplayed()
    }

    @Test
    fun `a table row reads each cell under its column's heading`() {
        assertEquals("Runs 4 · Tokens 4.0K · Per run 1.0K · Last run 5 min ago", insightsRowDetail(loops, loops.rows.single()))
        assertEquals("", insightsRowDetail(loops, listOf("only a name")))
        assertEquals("Uses 3 · You typed 2 · Claude ran 1", insightsRowDetail(skills, skills.rows.single()))
    }
}
