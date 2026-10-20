package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.UsageScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desk's Export › CSV on the phone (P25): a row after the figures it exports, offered only where
 * the machine serves the file. `usage` is the negative control of `usage-export`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageExportInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = DeckFixtures.byName("usage-export")!!

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun `Export usage appears at the foot of the screen on a machine that advertises it, and asks for the file`() {
        var exported = 0
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage, loading = false, error = null, onLoad = {}, hello = state.hello, onExport = { exported++ })
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("usage-export"))
        compose.onNodeWithTag("usage-screen").captureRoboImage("build/outputs/p25/usage-export.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        compose.onNodeWithTag("usage-export").assertIsDisplayed().performClick()
        assertEquals(1, exported)
    }

    @Test
    fun `no Export usage row on a machine that does not advertise it`() {
        val older = DeckFixtures.byName("usage")!!.hello!!
        assert(MobileProtocol.Capability.USAGE_EXPORT !in older.capabilities) { "the negative control must not advertise it" }
        compose.setContent {
            AgentDeckTheme(dark = false, dynamic = false) {
                UsageScreen(report = state.usage, loading = false, error = null, onLoad = {}, hello = older, onExport = { error("offered") })
            }
        }
        assertEquals(0, compose.onAllNodesWithTag("usage-export").fetchSemanticsNodes().size)
    }
}
