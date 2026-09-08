package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.FleetScreen
import dev.agentdeck.companion.ui.LocalNow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w280dp-h891dp")
class FleetFiltersInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val filter = mutableStateOf(FleetFilter())
    private val sort = mutableStateOf(FleetSort.RECENT)
    private val selectedSorts = mutableListOf<FleetSort>()

    private fun show() {
        val original = DeckFixtures.byName("fleet-uncapped")!!.snapshot!!
        val snapshot = original.copy(rows = original.rows.mapIndexed { index, row ->
            if (index % 2 == 0) row.copy(accountId = "work", model = "Opus 5") else row
        })
        compose.setContent {
            CompositionLocalProvider(LocalNow provides { DeckFixtures.NOW }) {
                AgentDeckTheme(dynamic = false) {
                    FleetScreen(
                        snapshot = snapshot,
                        filter = filter.value,
                        sort = sort.value,
                        refreshing = false,
                        snoozed = emptyMap(),
                        openKey = null,
                        onFilter = { filter.value = it },
                        onSort = { selectedSorts.add(it); sort.value = it },
                        onRefresh = {},
                        onOpen = {},
                        onSnooze = {},
                        onStop = {},
                    )
                }
            }
        }
    }

    @Test
    fun `narrow fleet keeps every selector on one horizontal line and scrolls to a working sort`() {
        show()
        val first = compose.onNodeWithText("All projects").assertIsDisplayed()
        val rowTop = first.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("Sort: Last message").assertIsNotDisplayed()

        listOf("All projects", "All agents", "All accounts", "All models", "Sort: Last message")
            .forEach { label ->
                val selector = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                assertEquals("$label wrapped below the selector row", rowTop,
                    selector.fetchSemanticsNode().boundsInRoot.top, 1f)
            }

        compose.onNodeWithText("Sort: Last message").performClick()
        compose.onNodeWithText("Title").assertIsDisplayed().performClick()
        compose.onNodeWithText("Sort: Title").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(FleetSort.TITLE), selectedSorts) }
        compose.onNodeWithText("All projects").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `narrow scope tabs stay on one line and remain selectable after scrolling`() {
        show()
        val rowTop = compose.onNodeWithText("All").fetchSemanticsNode().boundsInRoot.top
        FleetScope.entries.forEach { scope ->
            val tab = compose.onNodeWithText(scope.label).performScrollTo().assertIsDisplayed()
            assertEquals("${scope.label} wrapped below the scope row", rowTop,
                tab.fetchSemanticsNode().boundsInRoot.top, 1f)
            tab.performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(scope, filter.value.scope) }
        }
    }
}
