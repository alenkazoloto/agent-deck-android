package dev.agentdeck.companion

import dev.agentdeck.companion.ui.Times
import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `Times.clock` used to format only `HH:mm`, so a snapshot from yesterday and a prompt due
 * tomorrow both read as if they were today (MU-05). Every case here injects a fixed `nowMs`
 * rather than reading the real clock, so the midnight-boundary assertions cannot flake.
 *
 * The month name is locale-sensitive (`%tb`), so the default locale is pinned for the
 * duration of this suite and restored after — the production code still asks for the
 * device's own locale, as `formatCost` and the rest of the file already do.
 */
class TimesTest {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `a timestamp from today shows only the clock`() {
        val now = at(2026, Calendar.JULY, 31, 22, 0)
        val today = at(2026, Calendar.JULY, 31, 21, 14)
        assertEquals("21:14", Times.clock(today, now))
    }

    /**
     * The exact boundary the bug lived on: two timestamps 2 minutes apart, either side of
     * midnight, must not render identically.
     */
    @Test
    fun `a timestamp from just before midnight carries its day once now has crossed into the next one`() {
        val justBeforeMidnight = at(2026, Calendar.JULY, 30, 23, 59)
        val justAfterMidnight = at(2026, Calendar.JULY, 31, 0, 1)
        assertEquals("Jul 30, 23:59", Times.clock(justBeforeMidnight, justAfterMidnight))

        // Negative control: the identical wall-clock reading, but "now" has not crossed
        // midnight yet — the bare time is still correct and must not gain a day.
        val sameEvening = at(2026, Calendar.JULY, 30, 23, 59)
        assertEquals("23:59", Times.clock(justBeforeMidnight, sameEvening))
    }

    @Test
    fun `a scheduled row due tomorrow carries its day, not just its time`() {
        val now = at(2026, Calendar.JULY, 31, 21, 0)
        val dueTomorrow = at(2026, Calendar.AUGUST, 1, 9, 0)
        assertEquals("Aug 1, 09:00", Times.clock(dueTomorrow, now))
    }

    @Test
    fun `a timestamp from a different year also carries its year`() {
        val now = at(2026, Calendar.JULY, 31, 12, 0)
        val nextYear = at(2027, Calendar.JULY, 31, 12, 0)
        assertEquals("Jul 31, 2027, 12:00", Times.clock(nextYear, now))
    }

    @Test
    fun `zero or negative timestamps stay blank regardless of now`() {
        val now = at(2026, Calendar.JULY, 31, 12, 0)
        assertEquals("", Times.clock(0L, now))
        assertEquals("", Times.clock(-5L, now))
    }
}
