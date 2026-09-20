package dev.agentdeck.companion.data

import java.util.Calendar

/**
 * When a prompt queued from the phone should run.
 *
 * Relative, not absolute: what a user schedules from a couch is "when I'm back at it", and the
 * arithmetic that turns that into a timestamp is the part worth testing — [TOMORROW_MORNING]
 * and [THIS_EVENING] both cross a boundary the naive `now + N hours` gets wrong, and
 * [THIS_EVENING] has to notice that it is already evening.
 */
enum class ScheduleWhen(val label: String) {
    IN_AN_HOUR("In an hour"),
    IN_FOUR_HOURS("In 4 hours"),
    THIS_EVENING("This evening"),
    TOMORROW_MORNING("Tomorrow morning"),
    ;

    fun dueAtMs(nowMs: Long = System.currentTimeMillis()): Long = when (this) {
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
