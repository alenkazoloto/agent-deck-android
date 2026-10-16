package dev.agentdeck.companion.ambient

import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot

/**
 * The two lines the widget and the tile both paint, decided once.
 *
 * [headline] is the most urgent count the snapshot holds and [detail] is what is left over — the
 * waiting row's title when something is waiting, the remaining counts when nothing is. The widget
 * has two text views and the tile has a label and a subtitle, so both surfaces want exactly this
 * shape, and a second copy of the ranking would be a second answer to "what is my machine doing".
 */
data class AmbientSummary(val headline: String, val detail: String)

/**
 * Counts per [SessionAttentionState], as of the snapshot's own stamp.
 *
 * [running] is **already zeroed** when the snapshot is too old to vouch for it — see
 * [ambientCounts]. `failed` and `done` are not: a run that failed an hour ago still failed, while
 * "running" is a claim about *right now* that a stale file cannot make.
 */
data class AmbientCounts(
    val waiting: Int,
    val running: Int,
    val failed: Int,
    val done: Int,
)

/**
 * How old a cached snapshot may be before its "running" half is dropped.
 *
 * The cache is written only by a live link (`LiveLink.refresh` and the `FLEET` stream frame), so
 * its age measures how long since this phone last heard from the machine — and a phone that has
 * not heard for [RUNNING_TRUST_MS] cannot tell "still working" from "the IDE was closed twenty
 * minutes ago". Shorter than `MobileFleetRow.RECENT_WINDOW_MS` on purpose: run state is the most
 * perishable fact in the snapshot, and the cost of dropping it too early is only the pre-existing
 * "Nothing waiting", while the cost of keeping it too long is a widget that lies.
 */
const val RUNNING_TRUST_MS = 10 * 60_000L

/**
 * [nowMs] is the *reader's* clock and [MobileFleetSnapshot.generatedAtMs] the machine's, which is
 * the one place these surfaces cannot avoid mixing the two — nothing else answers "how long ago
 * was this made". A phone running behind the machine yields a negative age and counts as fresh
 * rather than as infinitely stale, and an unstamped snapshot (`generatedAtMs == 0`) is trusted,
 * because a build old enough to omit the stamp is not evidence that its runs ended.
 */
fun ambientCounts(snapshot: MobileFleetSnapshot?, nowMs: Long): AmbientCounts {
    val rows = snapshot?.rows.orEmpty()
    fun count(state: SessionAttentionState) = rows.count { it.attention == state }
    val stamp = snapshot?.generatedAtMs ?: 0L
    val trustsRunning = stamp <= 0L || nowMs - stamp <= RUNNING_TRUST_MS
    return AmbientCounts(
        // badgeCount is the machine's own answer to "what is waiting"; the rows are only asked
        // for the states it does not carry a count for.
        waiting = snapshot?.badgeCount ?: 0,
        running = if (trustsRunning) count(SessionAttentionState.RUNNING) else 0,
        failed = count(SessionAttentionState.FAILED),
        done = count(SessionAttentionState.DONE_UNREVIEWED),
    )
}

/** The newest row waiting on the reader — the one a tap should land on. */
fun topWaiting(snapshot: MobileFleetSnapshot?): MobileFleetRow? = snapshot?.rows
    ?.filter { it.attention == SessionAttentionState.WAITING_ON_YOU }
    ?.maxByOrNull { it.lastActivityMs }

/**
 * [machineName] is null when nothing is paired, which is its own pair of lines.
 *
 * The ranking is by what the reader would act on: something waiting on them beats a failure they
 * have not seen, which beats work in flight, which beats a finished run to review. Whichever count
 * the headline spends is not repeated in the detail.
 */
fun ambientSummary(
    snapshot: MobileFleetSnapshot?,
    nowMs: Long,
    machineName: String?,
): AmbientSummary {
    if (machineName == null) return AmbientSummary("Not paired", "Tap to pair")
    val counts = ambientCounts(snapshot, nowMs)
    val ranked = listOf(WAITING, FAILED, RUNNING, DONE).filter { counts.of(it) > 0 }
    val lead = ranked.firstOrNull()
    val headline = lead?.let { counts.phrase(it) } ?: "Nothing waiting"
    // A waiting row names itself; the leftover counts are only worth the line when it does not.
    val title = topWaiting(snapshot)?.title?.ifBlank { "(no title)" }
    val rest = ranked.drop(1).joinToString(" · ") { counts.phrase(it) }.ifBlank { null }
    return AmbientSummary(headline, title ?: rest ?: machineName.ifBlank { "Agent Deck" })
}

private fun AmbientCounts.of(key: String): Int = when (key) {
    WAITING -> waiting
    FAILED -> failed
    RUNNING -> running
    else -> done
}

private fun AmbientCounts.phrase(key: String): String = when (key) {
    WAITING -> "$waiting waiting on you"
    FAILED -> "$failed failed"
    RUNNING -> "$running running"
    else -> "$done to review"
}

private const val WAITING = "waiting"
private const val FAILED = "failed"
private const val RUNNING = "running"
private const val DONE = "done"
