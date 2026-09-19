package dev.agentdeck.companion

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.RunningIndicator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class RunningIndicatorInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val indeterminate = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
    )

    @Test
    fun `the production fleet follows a row from running to stopped without losing vendor identity`() {
        val fixture = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!
        val row = fixture.rows.first().copy(
            vendor = AgentVendor.CODEX, title = "Review the parser", attention = SessionAttentionState.RUNNING,
            liveLine = "Running tests", lastActivityMs = fixture.generatedAtMs,
        )
        val snapshot = mutableStateOf(fixture.copy(rows = listOf(row)))
        compose.setContent {
            AgentDeckTheme {
                FleetScreen(
                    snapshot = snapshot.value, filter = FleetFilter(), sort = FleetSort.RECENT,
                    refreshing = false, snoozed = emptyMap(), openKey = null,
                    onFilter = {}, onSort = {}, onRefresh = {}, onOpen = {}, onSnooze = {}, onStop = {},
                )
            }
        }

        compose.onNode(indeterminate, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Codex conversation, Review the parser", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("◆", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            snapshot.value = fixture.copy(rows = listOf(row.copy(attention = null, liveLine = null)))
        }
        compose.onNode(indeterminate, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Codex conversation, Review the parser", substring = true)
            .assertIsDisplayed()
    }

    @Test
    @OptIn(ExperimentalRoborazziApi::class)
    fun `the running spokes visibly advance with the composition clock`() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AgentDeckTheme { RunningIndicator() } }
        compose.mainClock.advanceTimeByFrame()
        val indicator = compose.onNode(indeterminate).assertIsDisplayed()
        val directory = File("build/outputs/running-indicator").apply { mkdirs() }
        val beforeFile = File(directory, "before.png")
        val afterFile = File(directory, "after.png")
        val options = RoborazziOptions(taskType = RoborazziTaskType.Record)
        indicator.captureRoboImage(beforeFile, options)
        compose.mainClock.advanceTimeBy(160)
        indicator.captureRoboImage(afterFile, options)
        val before = ImageIO.read(beforeFile)
        val after = ImageIO.read(afterFile)
        assertTrue("Running remained a static mark", (0 until before.height).any { y ->
            (0 until before.width).any { x -> before.getRGB(x, y) != after.getRGB(x, y) }
        })
    }
}
