package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A recurrence for a queued prompt: every [everyMs], or daily at [atTime] (`HH:mm`, machine-local — the zone `MobileHello.timeZoneId` names). */
data class ScheduleRepeat(val everyMs: Long = 0, val atTime: String? = null)

/** A pick of the create dialog's When pill: a relative [ScheduleWhen], or [AfterReset]. */
sealed interface ScheduleDue {
    val label: String
    /**
     * When it runs. A wall-clock pick ("At 09:00", "This evening") is read in [zone], which the create dialogs give as the machine's:
     * the scheduler repeats "daily at 09:00" in its own zone, so a first run counted in the phone's would land hours from the repeats.
     */
    fun dueAtMs(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long
    fun repeat(): ScheduleRepeat
    fun repeatLabel(): String

    /** False while a typed pick (an empty amount, half a clock time) names no moment, so the dialog cannot Schedule it. */
    val valid: Boolean get() = true
}

/**
 * "After the usage limit resets", as the desk's dialog offers it: two minutes into the account's
 * next window, so the run lands inside it, and a repeat once per window — weekly when the
 * drained window is the week. The desk stores exactly this due time and interval, nothing more.
 */
data class AfterReset(val resetAtMs: Long, val weekly: Boolean) : ScheduleDue {
    override val label: String get() = "after the limit resets"
    override fun dueAtMs(nowMs: Long, zone: ZoneId): Long = resetAtMs + MARGIN_MS
    override fun repeat(): ScheduleRepeat = ScheduleRepeat(everyMs = if (weekly) WEEK_MS else WINDOW_MS)
    override fun repeatLabel(): String = if (weekly) "Repeat each week" else "Repeat each usage window (~5 h)"

    companion object {
        const val MARGIN_MS = 2 * 60_000L
        private const val WINDOW_MS = 5 * 60 * 60_000L
        private const val WEEK_MS = 7 * 24 * 60 * 60_000L

        /** Null when the machine knows no future reset for [option], which is when the desk greys the choice out. */
        fun of(option: MobileScheduleAccountOption?, nowMs: Long): AfterReset? =
            option?.resetAtMs?.takeIf { it > nowMs }?.let { AfterReset(it, option.weeklyLimit) }
    }
}

/**
 * "In N minutes/hours", the desk dialog's `In` radio: [amount] of the chosen unit from now, repeating
 * every same interval. Bounded to the desk's 1..999, so a typed pick is never longer than the host keeps.
 */
data class AfterDelay(val amount: Int, val minutes: Boolean) : ScheduleDue {
    override val label: String get() = "In a set time"
    override val valid: Boolean get() = amount in 1..MAX_AMOUNT
    private val intervalMs: Long get() = amount.coerceIn(1, MAX_AMOUNT) * if (minutes) MINUTE_MS else HOUR_MS
    override fun dueAtMs(nowMs: Long, zone: ZoneId): Long = nowMs + intervalMs
    override fun repeat(): ScheduleRepeat = ScheduleRepeat(everyMs = intervalMs)
    override fun repeatLabel(): String {
        val unit = if (minutes) "minute" else "hour"
        return if (amount == 1) "Repeat every $unit" else "Repeat every $amount ${unit}s"
    }

    companion object {
        const val MAX_AMOUNT = 999
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
    }
}

/**
 * "At HH:mm", the desk dialog's `At` radio: the next such 24-hour time (a past one runs tomorrow), and
 * a repeat is daily at it. Read in the machine's zone (the dialog passes it), as the scheduler reads the repeat.
 */
data class AtTime(val text: String) : ScheduleDue {
    private val hourMinute: Pair<Int, Int>? get() = TIME.matchEntire(text.trim())?.destructured
        ?.let { (h, m) -> h.toInt() to m.toInt() }?.takeIf { (h, m) -> h <= 23 && m <= 59 }
    override val label: String get() = "At a clock time"
    override val valid: Boolean get() = hourMinute != null
    override fun dueAtMs(nowMs: Long, zone: ZoneId): Long {
        val (hour, minute) = hourMinute ?: return nowMs + HOUR_MS
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return (if (today.toInstant().toEpochMilli() <= nowMs) today.plusDays(1) else today).toInstant().toEpochMilli()
    }

    override fun repeat(): ScheduleRepeat = ScheduleRepeat(atTime = clock())
    override fun repeatLabel(): String = "Repeat daily at ${clock() ?: "that time"}"
    private fun clock(): String? = hourMinute?.let { (h, m) -> "%02d:%02d".format(h, m) }

    private companion object {
        val TIME = Regex("(\\d{1,2}):(\\d{2})")
        const val HOUR_MS = 60 * 60_000L
    }
}

/**
 * "When the current run in this chat finishes", the desk dialog's third radio: no moment of its own
 * (the machine holds the prompt until the run's end) and nothing to repeat on. Offered only while the
 * open chat is running, under `SCHEDULE_AFTER_RUN`.
 */
data object AfterRun : ScheduleDue {
    override val label: String get() = "when this run finishes"
    override fun dueAtMs(nowMs: Long, zone: ZoneId): Long = nowMs
    override fun repeat(): ScheduleRepeat = ScheduleRepeat()
    override fun repeatLabel(): String = "A prompt that waits for the run can't repeat"
}

/**
 * "Schedule after sessions…", the desk dialog's fourth radio: the prompt has no moment of its own
 * (the machine holds it until the chosen sessions and schedules finish) and nothing to repeat on.
 * Offered under `SCHEDULE_DEPENDENCIES`; the sessions themselves are the dialog's picks, not this pick's.
 */
data object AfterSessions : ScheduleDue {
    override val label: String get() = "after other sessions finish"
    override fun dueAtMs(nowMs: Long, zone: ZoneId): Long = nowMs
    override fun repeat(): ScheduleRepeat = ScheduleRepeat()
    override fun repeatLabel(): String = "A prompt that waits for sessions can't repeat"
}

/**
 * When a prompt queued from the phone should run.
 *
 * Relative, not absolute: what a user schedules from a couch is "when I'm back at it", and the
 * arithmetic that turns that into a timestamp is the part worth testing — [TOMORROW_MORNING]
 * and [THIS_EVENING] both cross a boundary the naive `now + N hours` gets wrong, and
 * [THIS_EVENING] has to notice that it is already evening.
 */
enum class ScheduleWhen(override val label: String) : ScheduleDue {
    IN_AN_HOUR("In an hour"),
    IN_FOUR_HOURS("In 4 hours"),
    THIS_EVENING("This evening"),
    TOMORROW_MORNING("Tomorrow morning"),
    ;

    /**
     * What "repeat" means for this pick: the two relative ones re-run at their own interval, the
     * two clock ones daily at their hour — the same recurrences the desk's dialog offers.
     */
    override fun repeat(): ScheduleRepeat = when (this) {
        IN_AN_HOUR -> ScheduleRepeat(everyMs = HOUR)
        IN_FOUR_HOURS -> ScheduleRepeat(everyMs = 4 * HOUR)
        THIS_EVENING -> ScheduleRepeat(atTime = "%02d:00".format(EVENING_HOUR))
        TOMORROW_MORNING -> ScheduleRepeat(atTime = "%02d:00".format(MORNING_HOUR))
    }

    override fun repeatLabel(): String = when (this) {
        IN_AN_HOUR -> "Repeat every hour"
        IN_FOUR_HOURS -> "Repeat every 4 hours"
        THIS_EVENING -> "Repeat daily at 18:00"
        TOMORROW_MORNING -> "Repeat daily at 09:00"
    }

    override fun dueAtMs(nowMs: Long, zone: ZoneId): Long = when (this) {
        IN_AN_HOUR -> nowMs + HOUR
        IN_FOUR_HOURS -> nowMs + 4 * HOUR
        // Already past 18:00 means the user means *this* evening, which has started — an hour
        // out is the honest reading, not 18:00 yesterday and not tomorrow.
        THIS_EVENING -> atHour(nowMs, EVENING_HOUR, zone).takeIf { it > nowMs } ?: (nowMs + HOUR)
        TOMORROW_MORNING -> atHour(Instant.ofEpochMilli(nowMs).atZone(zone).plusDays(1).toInstant().toEpochMilli(), MORNING_HOUR, zone)
    }

    private fun atHour(anchorMs: Long, hour: Int, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(anchorMs), zone).withHour(hour).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()

    private companion object {
        const val HOUR = 60 * 60_000L
        const val EVENING_HOUR = 18
        const val MORNING_HOUR = 9
    }
}
