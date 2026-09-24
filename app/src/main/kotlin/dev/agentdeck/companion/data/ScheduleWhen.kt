package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import java.util.Calendar

/** A recurrence for a queued prompt: every [everyMs], or daily at [atTime] (`HH:mm`, machine-local). */
data class ScheduleRepeat(val everyMs: Long = 0, val atTime: String? = null)

/** A pick of the create dialog's When pill: a relative [ScheduleWhen], or [AfterReset]. */
sealed interface ScheduleDue {
    val label: String
    fun dueAtMs(nowMs: Long = System.currentTimeMillis()): Long
    fun repeat(): ScheduleRepeat
    fun repeatLabel(): String
}

/**
 * "After the usage limit resets", as the desk's dialog offers it: two minutes into the account's
 * next window, so the run lands inside it, and a repeat once per window — weekly when the
 * drained window is the week. The desk stores exactly this due time and interval, nothing more.
 */
data class AfterReset(val resetAtMs: Long, val weekly: Boolean) : ScheduleDue {
    override val label: String get() = "after the limit resets"
    override fun dueAtMs(nowMs: Long): Long = resetAtMs + MARGIN_MS
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
 * "When the current run in this chat finishes", the desk dialog's third radio: no moment of its own
 * (the machine holds the prompt until the run's end) and nothing to repeat on. Offered only while the
 * open chat is running, under `SCHEDULE_AFTER_RUN`.
 */
data object AfterRun : ScheduleDue {
    override val label: String get() = "when this run finishes"
    override fun dueAtMs(nowMs: Long): Long = nowMs
    override fun repeat(): ScheduleRepeat = ScheduleRepeat()
    override fun repeatLabel(): String = "A prompt that waits for the run can't repeat"
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

    override fun dueAtMs(nowMs: Long): Long = when (this) {
        IN_AN_HOUR -> nowMs + HOUR
        IN_FOUR_HOURS -> nowMs + 4 * HOUR
        // Already past 18:00 means the user means *this* evening, which has started — an hour
        // out is the honest reading, not 18:00 yesterday and not tomorrow.
        THIS_EVENING -> atHour(nowMs, EVENING_HOUR).takeIf { it > nowMs } ?: (nowMs + HOUR)
        TOMORROW_MORNING -> atHour(nowMs + DAY, MORNING_HOUR)
    }

    private fun atHour(anchorMs: Long, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = anchorMs
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAY = 24 * HOUR
        const val EVENING_HOUR = 18
        const val MORNING_HOUR = 9
    }
}
