package dev.agentdeck.companion

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.claudeagents.core.reach.Route
import dev.agentdeck.companion.data.PairedRoute
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
 * Settings › Machine lists the machine's other addresses. One from the pairing code has a remove
 * button, because only the user can drop it; one the machine named has none, because the
 * machine's next answer replaces it anyway. Removing asks first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class MachineRoutesInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val removed = mutableListOf<Route>()

    private val answered = Route(Route.LAN, "192.168.1.24", 63350)
    private val paired = Route(Route.TAILNET, "100.71.4.9", 63350)
    private val learned = Route(Route.RELAY, "k3x.relay.example", 443)

    private fun show() = compose.setContent {
        val base = DeckFixtures.byName("settings")!!
        val machine = base.machine!!.copy(
            explicitRoutes = listOf(
                PairedRoute(answered, PairedRoute.Source.PAIRED),
                PairedRoute(paired, PairedRoute.Source.PAIRED),
                PairedRoute(learned, PairedRoute.Source.LEARNED),
            ),
            preferredRoute = answered,
        )
        AgentDeckTheme(dark = false, dynamic = false) {
            SettingsScreen(
                state = base.copy(machine = machine, machines = listOf(machine)),
                onSettings = {},
                onSwitchMachine = {},
                onAddMachine = {},
                onUnpair = {},
                onRemoveRoute = { removed += it },
                onRefreshHello = {},
                onRefreshPush = {},
                onChoosePush = {},
                onCheckUpdate = {},
                onDownloadUpdate = {},
                onInstallUpdate = {},
                onReleasePage = {},
            )
        }
    }

    @Test
    fun `only the pairing code's other address offers removal, and removing asks first`() {
        show()

        compose.onNodeWithText("k3x.relay.example:443").performScrollTo()
        compose.onAllNodesWithContentDescription("Remove k3x.relay.example:443").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Remove 192.168.1.24:63350").assertCountEquals(0)

        compose.onNodeWithContentDescription("Remove 100.71.4.9:63350").performScrollTo().performClick()
        assertEquals("removed before the user confirmed", emptyList<Route>(), removed)
        compose.onNodeWithText("Remove").performClick()

        assertEquals(listOf(paired), removed)
    }

    @Test
    fun `cancelling keeps the address`() {
        show()

        compose.onNodeWithContentDescription("Remove 100.71.4.9:63350").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<Route>(), removed)
    }
}
