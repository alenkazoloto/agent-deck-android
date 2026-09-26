package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.claudeagents.core.mobile.MobileScheduledRow
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduledScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The desk's overlapping-runs switch shows where a repeat could be held back, and a tap flips it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduledOverlapInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = DeckFixtures.byName("scheduled")!!
    private val flips = mutableListOf<Boolean>()
    private val counts = mutableListOf<Int>()

    private fun row(repeating: Boolean) = MobileScheduledRow(
        id = "r", prompt = "Fix the next item", projectPath = "/work/project", sessionId = null,
        dueAtMs = DeckFixtures.NOW, state = MobileScheduledRow.QUEUED, repeating = repeating,
    )

    private fun show(rows: List<MobileScheduledRow>, allowOverlap: Boolean?, backgroundRuns: Int? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme {
                    ScheduledScreen(
                        rows = rows, loading = false, canCreate = true,
                        projects = listOf("/work/project"), hello = fixture.hello,
                        draft = "", onDraft = {}, onRefresh = {}, onCreate = { _, _, _, _ -> },
                        onCommand = { _, _, _ -> },
                        allowOverlap = allowOverlap, onAllowOverlap = { flips += it },
                        backgroundRuns = backgroundRuns, onBackgroundRuns = { counts += it },
                    )
                }
            }
        }
    }

    @Test
    fun `a repeating row offers the switch and a tap asks for the opposite`() {
        show(listOf(row(repeating = true)), allowOverlap = true)

        compose.onNodeWithTag("schedule-overlap").assertIsDisplayed()
        compose.onNodeWithText("A repeat shorter than its run fires on time.").assertIsDisplayed()
        compose.onNodeWithTag("schedule-overlap").performClick()

        assertEquals(listOf(false), flips)
    }

    @Test
    fun `a switch already off stays reachable without a repeat, and a tap turns it on`() {
        show(listOf(row(repeating = false)), allowOverlap = false)

        compose.onNodeWithText("A repeat waits for its previous run to finish.").assertIsDisplayed()
        compose.onNodeWithTag("schedule-overlap").performClick()

        assertEquals(listOf(true), flips)
    }

    @Test
    fun `no repeat and the default value draws nothing`() {
        show(listOf(row(repeating = false)), allowOverlap = true)

        compose.onAllNodesWithTag("schedule-overlap").assertCountEquals(0)
    }

    @Test
    fun `a machine without the switch draws nothing`() {
        show(listOf(row(repeating = true)), allowOverlap = null)

        compose.onAllNodesWithTag("schedule-overlap").assertCountEquals(0)
    }

    @Test
    fun `two prompts offer the count and its buttons step it by one`() {
        show(listOf(row(repeating = false), row(repeating = false).copy(id = "s")), allowOverlap = true, backgroundRuns = 3)

        compose.onNodeWithTag("schedule-background-runs").assertIsDisplayed()
        compose.onNodeWithText("Up to 3 due prompts run together.").assertIsDisplayed()
        compose.onNodeWithContentDescription("More background runs").performClick()
        compose.onNodeWithContentDescription("Fewer background runs").performClick()

        assertEquals(listOf(4, 2), counts)
    }

    @Test
    fun `one prompt at the default count draws nothing`() {
        show(listOf(row(repeating = false)), allowOverlap = true, backgroundRuns = 3)

        compose.onAllNodesWithTag("schedule-background-runs").assertCountEquals(0)
    }

    @Test
    fun `a count off its default stays reachable and one reads as serial`() {
        show(listOf(row(repeating = false)), allowOverlap = true, backgroundRuns = 1)

        compose.onNodeWithText("Due prompts run one after another.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fewer background runs").assertIsNotEnabled()
        compose.onNodeWithContentDescription("More background runs").assertIsEnabled()
    }

    @Test
    fun `the top of the range cannot go higher`() {
        show(listOf(row(repeating = false)), allowOverlap = true, backgroundRuns = 10)

        compose.onNodeWithContentDescription("More background runs").assertIsNotEnabled()
    }

    @Test
    fun `a machine without the count draws nothing`() {
        show(listOf(row(repeating = false), row(repeating = false).copy(id = "s")), allowOverlap = true, backgroundRuns = null)

        compose.onAllNodesWithTag("schedule-background-runs").assertCountEquals(0)
    }
}
