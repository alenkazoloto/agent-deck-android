package dev.agentdeck.companion

import dev.agentdeck.companion.ui.Times
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /**
     * J09: a daily repeat holds its wall-clock hour across a daylight-saving change, so its note
     * differs from a fixed-interval repeat's, which is real time and can drift by an hour instead.
     * Deep in New York's DST season (six months from a June date is still DST) the two readings
     * agree and there is nothing to warn about — the "checked six months out" edge the KDoc names.
     */
    @Test
    fun `a daily repeat's DST note differs from a fixed interval's, six months out`() {
        val zone = ZoneId.of("America/New_York")
        val deepInStandardTime = at(2026, Calendar.JANUARY, 15, 9, 0)
        assertEquals(
            "Repeats keep 09:00 in America/New_York across daylight saving; the clock time here can shift by an hour.",
            Times.dstNote("09:00", 0L, zone, deepInStandardTime),
        )
        assertEquals(
            "This interval is real time — it can land an hour off in America/New_York on the day its clocks change.",
            Times.dstNote(null, 3_600_000L, zone, deepInStandardTime),
        )
    }

    @Test
    fun `a DST note is null when six months out lands in the same DST season`() {
        val zone = ZoneId.of("America/New_York")
        // Six months from mid-September 2026 lands in mid-March 2027 — DST both times, since
        // 2027's clocks move forward before that date arrives.
        val sixMonthsStillDst = at(2026, Calendar.SEPTEMBER, 15, 9, 0)
        assertNull(Times.dstNote("09:00", 0L, zone, sixMonthsStillDst))
    }

    @Test
    fun `a DST note is null for a one-shot, an unknown zone, or a zone with no seasonal offset`() {
        val now = at(2026, Calendar.JANUARY, 15, 9, 0)
        assertNull(Times.dstNote(null, 0L, ZoneId.of("America/New_York"), now))
        assertNull(Times.dstNote("09:00", 0L, null, now))
        assertNull(Times.dstNote("09:00", 0L, ZoneId.of("UTC"), now))
    }
}
