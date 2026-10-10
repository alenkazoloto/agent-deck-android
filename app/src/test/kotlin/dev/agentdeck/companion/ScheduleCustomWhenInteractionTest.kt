package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.AfterDelay
import dev.agentdeck.companion.data.AtTime
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleRepeat
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleDuePick
import dev.agentdeck.companion.ui.ScheduleDueSelector
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * mobile-todo Scheduling #1: the create dialogs' When pill offers the desk's free `In N minutes/hours`
 * and `At HH:mm`, and a repeat follows the typed value (every N, or daily at that time). Driven through
 * the production pill and its fields without the dialog window, as [ScheduleAfterResetInteractionTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduleCustomWhenInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = DeckFixtures.NOW
    private val hello = DeckFixtures.byName("scheduled")!!.hello!!
    private val pick = ScheduleDuePick()

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalNow provides { now }) {
            AgentDeckTheme {
                Column { ScheduleDueSelector(pick, hello, NewChatTarget("/Users/dev/Plugin", AgentVendor.CLAUDE)) }
            }
        }
    }

    @Test
    fun `In a set time posts the typed amount and repeats at that interval`() {
        show()
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("In a set time").performClick()
        compose.onNodeWithTag("schedule-delay-amount").performTextClearance()
        compose.onNodeWithTag("schedule-delay-amount").performTextInput("30")
        compose.onNodeWithText("hours").performClick()
        compose.onNodeWithText("minutes").performClick()
        compose.runOnIdle {
            val picked = pick.picked(null)
            assertTrue(picked.valid)
            assertEquals(now + 30 * 60_000L, picked.dueAtMs(now))
            assertEquals(ScheduleRepeat(everyMs = 30 * 60_000L), picked.repeat())
            assertEquals("Repeat every 30 minutes", picked.repeatLabel())
        }
        // An emptied amount names no moment: Schedule must not post "now".
        compose.onNodeWithTag("schedule-delay-amount").performTextClearance()
        compose.runOnIdle { assertFalse(pick.picked(null).valid) }
    }

    @Test
    fun `At a clock time posts the next such time and repeats daily at it`() {
        show()
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("At a clock time").performClick()
        compose.onNodeWithTag("schedule-at-time").performTextInput("9:05")
        compose.runOnIdle {
            val picked = pick.picked(null)
            assertTrue(picked.valid)
            assertEquals(ScheduleRepeat(atTime = "09:05"), picked.repeat())
            assertEquals("Repeat daily at 09:05", picked.repeatLabel())
            val due = Calendar.getInstance().apply { timeInMillis = picked.dueAtMs(now) }
            assertEquals(9, due.get(Calendar.HOUR_OF_DAY))
            assertEquals(5, due.get(Calendar.MINUTE))
            assertTrue(picked.dueAtMs(now) > now && picked.dueAtMs(now) <= now + 24 * 3_600_000L)
        }
        // Picking a preset again drops the typed pick.
        compose.onNodeWithText("At a clock time").performClick()
        compose.onNodeWithText("Tomorrow morning").performClick()
        compose.runOnIdle { assertEquals(dev.agentdeck.companion.data.ScheduleWhen.TOMORROW_MORNING, pick.picked(null)) }
    }

    @Test
    fun `a time already past today runs tomorrow and a malformed one cannot be scheduled`() {
        val afternoon = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 24, 15, 0, 30); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val due = Calendar.getInstance().apply { timeInMillis = AtTime("14:30").dueAtMs(afternoon) }
        assertEquals(25, due.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, due.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, due.get(Calendar.MINUTE))
        assertEquals(0, due.get(Calendar.SECOND))
        val later = Calendar.getInstance().apply { timeInMillis = AtTime("16:00").dueAtMs(afternoon) }
        assertEquals(24, later.get(Calendar.DAY_OF_MONTH))

        listOf("", "24:00", "12:60", "1200", "12:5", "ab:cd").forEach { assertFalse("'$it'", AtTime(it).valid) }
        listOf("0:00", "23:59", " 7:30 ").forEach { assertTrue("'$it'", AtTime(it).valid) }
        assertFalse(AfterDelay(0, false).valid)
        assertFalse(AfterDelay(1000, true).valid)
        assertTrue(AfterDelay(999, false).valid)
        assertEquals("Repeat every hour", AfterDelay(1, false).repeatLabel())
    }
}
