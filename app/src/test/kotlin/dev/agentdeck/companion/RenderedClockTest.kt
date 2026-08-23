package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which clock a rendered time is measured against.
 *
 * Every timestamp on the wire — a row's `lastActivityMs`, a turn's `timestampMs`, a
 * snapshot's `generatedAtMs` — is minted by the *machine*. The phone has a second clock, and
 * for three weeks the fleet row's age badge read the phone's: `FleetGrouping.groupOf` sorted a
 * row into "Recent" against `generatedAtMs` while the badge beside the heading said "21d", and
 * the four `fleet-*` goldens failed by the calendar every day after the one they were recorded
 * on.
 *
 * This is the golden's pure counterpart. A screenshot proves the pixels; these assert the rule
 * the pixels depend on, so a re-record cannot quietly re-freeze today's date and call it fixed.
 *
 * The negative control is the *second* render in each test: the same rows against a different
 * stamp. Without it, "reads `generatedAtMs`" and "happens to agree with the phone today" are
 * the same result — which is exactly how the defect survived being screenshotted.
 */
@RunWith(RobolectricTestRunner::class)
// The same frame the `fleet-*` goldens are shot at. Robolectric's default window is short
// enough that the third waiting row is composed but never laid out, and `assertIsDisplayed`
// then fails for a reason that has nothing to do with the clock.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class RenderedClockTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE

        /** `DeckFixtures.WAITING_TITLES`, first and last — 2 and 6 minutes before the stamp. */
        const val WAITING_FIRST = "Approve the migration for accounts_v3"
        const val WAITING_LAST = "Plan ready — 6 files, 2 deletions"
    }

    /** The fixture the `fleet-*` goldens paint, with its stamp moved by [shiftMs]. */
    private fun fleetSnapshot(shiftMs: Long = 0L): MobileFleetSnapshot {
        val snapshot = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!
        return snapshot.copy(generatedAtMs = snapshot.generatedAtMs + shiftMs)
    }

    private fun showFleet(snapshot: MobileFleetSnapshot) {
        compose.setContent {
            FleetScreen(
                snapshot = snapshot,
                filter = FleetFilter(),
                sort = FleetSort.ATTENTION,
                refreshing = false,
                snoozed = emptyMap(),
                openKey = null,
                onFilter = {},
                onSort = {},
                onRefresh = {},
                onOpen = {},
                onSnooze = {},
                onStop = {},
            )
        }
    }

    /**
     * The rows are addressed by *title* rather than by counting badges: a fleet row merges its
     * project, age, title and live line into one semantics node, and several rows of this
     * fixture legitimately share an age once a shift coarsens them. A count would then be an
     * assertion about which rows the viewport composed.
     */
    private fun ageOf(title: String) = compose.onNode(hasText(title))

    /**
     * The three waiting rows are 2, 4 and 6 minutes older than the snapshot that carried them,
     * and they are the first section a fleet paints.
     *
     * Against the phone's clock these read as however many days old `DeckFixtures.NOW` is
     * today, which is what made this test's subject a bug rather than a preference.
     */
    @Test
    fun `a row's age is measured against the snapshot that carried it`() {
        showFleet(fleetSnapshot())
        ageOf(WAITING_FIRST).assertTextContains("2m")
        ageOf(WAITING_LAST).assertTextContains("6m")
    }

    /**
     * The control. The same rows under a snapshot generated three days later must age by
     * exactly three days — a badge that ignored `generatedAtMs` would be unmoved by this, and
     * one that read the phone would be unmoved by it too.
     */
    @Test
    fun `moving the snapshot's stamp moves every row's age with it`() {
        showFleet(fleetSnapshot(shiftMs = 3 * DAY))
        listOf(WAITING_FIRST, WAITING_LAST).forEach { title ->
            ageOf(title).assertTextContains("3d")
        }
        listOf("2m", "6m").forEach {
            assertEquals(
                "\"$it\" survived a snapshot generated three days later",
                0,
                compose.onAllNodesWithText(it).fetchSemanticsNodes().size,
            )
        }
    }

    /**
     * The other half of the fix, and the other clock. "Last snapshot" asks whether the stamp
     * falls on the *reader's* day, so it reads the phone — but a fixture has to be able to pin
     * it, or `settings-font-1_3` and the `settings-*` pair rot on the next New Year's Day the
     * way the fleet goldens rotted daily.
     */
    @Test
    fun `a pinned reader clock stamps the snapshot as today`() {
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                SettingsScreen(
                    state = DeckFixtures.byName("settings")!!,
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
                )
            }
        }
        // Same day as the pinned clock, so `Times.clock` drops the date and leaves the time
        // alone. Unpinned this carries "Jul 31, " in front of it — and gains a year in 2027.
        compose.onNodeWithText(dev.agentdeck.companion.ui.Times.clock(DeckFixtures.NOW, DeckFixtures.NOW))
            .assertIsDisplayed()
    }
}
