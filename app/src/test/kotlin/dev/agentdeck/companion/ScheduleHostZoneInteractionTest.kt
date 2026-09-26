package dev.agentdeck.companion

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.claudeagents.core.AgentVendor
import dev.agentdeck.companion.data.AtTime
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleWhen
import dev.agentdeck.companion.fixture.DeckFixtures
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalHostZone
import dev.agentdeck.companion.ui.LocalNow
import dev.agentdeck.companion.ui.ScheduleDuePick
import dev.agentdeck.companion.ui.ScheduleDueSelector
import dev.agentdeck.companion.ui.Times
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * J09: the machine's scheduler reads "At 09:00", "This evening" and a daily repeat in *its* zone, so a phone in another one
 * counts the first run there and names both readings. Driven through the production pill without the dialog window, as
 * [ScheduleCustomWhenInteractionTest] does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScheduleHostZoneInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val phone = ZoneId.of("UTC")
    private val host = ZoneId.of("Asia/Tokyo")
    private val now = DeckFixtures.NOW
    private val hello = DeckFixtures.byName("scheduled")!!.hello!!
    private val pick = ScheduleDuePick()
    private lateinit var saved: TimeZone

    @Before fun phoneInUtc() { saved = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone(phone)) }
    @After fun restore() = TimeZone.setDefault(saved)

    private fun hourIn(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).hour

    private fun show(hostZone: ZoneId?) = compose.setContent {
        CompositionLocalProvider(LocalNow provides { now }, LocalHostZone provides hostZone) {
            AgentDeckTheme {
                Column { ScheduleDueSelector(pick, hello, NewChatTarget("/Users/dev/Plugin", AgentVendor.CLAUDE)) }
            }
        }
    }

    @Test
    fun `wall-clock picks are counted in the machine's zone`() {
        val at = AtTime("09:05").dueAtMs(now, host)
        assertEquals(9, hourIn(at, host))
        assertEquals(5, Instant.ofEpochMilli(at).atZone(host).minute)
        assertNotEquals("the phone's zone would land it nine hours away", 9, hourIn(at, phone))
        assertEquals(9, hourIn(ScheduleWhen.TOMORROW_MORNING.dueAtMs(now, host), host))
        val evening = ScheduleWhen.THIS_EVENING.dueAtMs(now, host)
        assertEquals(if (hourIn(now, host) < 18) 18 else hourIn(now + 3_600_000L, host), hourIn(evening, host))
    }

    @Test
    fun `a moment reads in the machine's zone with the phone's beside it only when they disagree`() {
        val nineInTokyo = AtTime("09:00").dueAtMs(now, host)
        val text = Times.hostClock(nineInTokyo, now, host)
        assertTrue(text, "Tokyo (" in text && text.endsWith(" here)") && "09:00" in text)
        assertEquals(Times.clock(nineInTokyo, now), Times.hostClock(nineInTokyo, now, phone))
        assertEquals(Times.clock(nineInTokyo, now), Times.hostClock(nineInTokyo, now, null))
    }

    @Test
    fun `the create pill names where a clock pick lands on a machine in another zone`() {
        typeNineOhFive(host)
        compose.onNodeWithTag("schedule-host-time").assertTextContains("Tokyo (", substring = true)
        compose.onNodeWithTag("schedule-host-time").assertTextContains("here)", substring = true)
    }

    @Test
    fun `a schedule's confirmation words the time as the row will`() {
        val due = System.currentTimeMillis() + 3_600_000L
        val tokyo = hello.copy(timeZoneId = host.id)
        val text = Times.scheduledAt(due, tokyo)
        assertTrue(text, "Tokyo (" in text && text.endsWith(" here)"))
        assertEquals(Times.clock(due, System.currentTimeMillis()), Times.scheduledAt(due, hello.copy(timeZoneId = phone.id)))
        assertEquals(Times.clock(due, System.currentTimeMillis()), Times.scheduledAt(due, null))
    }

    private fun typeNineOhFive(zone: ZoneId?) {
        show(zone)
        compose.onNodeWithText("In an hour").performClick()
        compose.onNodeWithText("At a clock time").performClick()
        compose.onNodeWithTag("schedule-at-time").performTextInput("9:05")
    }

    @Test
    fun `no note when the machine shares the phone's zone`() {
        typeNineOhFive(phone)
        compose.onNodeWithTag("schedule-host-time").assertDoesNotExist()
    }

    @Test
    fun `no note when the machine named no zone`() {
        typeNineOhFive(null)
        compose.onNodeWithTag("schedule-host-time").assertDoesNotExist()
    }
}
