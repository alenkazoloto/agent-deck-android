package dev.agentdeck.companion.ui

import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn

/**
 * The two decisions the transcript list makes about its own tail, kept outside the Composable
 * because both were previously wrong in ways nothing could observe.
 *
 * The list scrolled to the last turn on *every* arriving turn, so reading back through a live
 * run yanked the viewport away mid-sentence; and `hasMore` — a field the bridge has always
 * sent — had no reader at all, so a conversation longer than one page showed its tail as if
 * that were the whole thing.
 */
object TranscriptTail {

    /** How near the end still counts as reading the tail: the last row, or the one above it. */
    const val SLACK = 1

    /**
     * Whether new output may move the viewport. True before anything is laid out (the first
     * paint of a conversation belongs at its newest turn) and true while the reader is within
     * [SLACK] rows of the end; false anywhere above that, which is a reader who scrolled there.
     */
    fun followsTail(lastVisibleIndex: Int, totalItems: Int): Boolean =
        totalItems <= 0 || lastVisibleIndex < 0 || lastVisibleIndex >= totalItems - 1 - SLACK

    /** Turns that arrived since the reader last saw the end — the count on the jump pill. */
    fun unreadBelow(turnCount: Int, readThrough: Int): Int = (turnCount - readThrough).coerceAtLeast(0)

    /**
     * Whether a `run` frame is about the conversation on screen.
     *
     * [frameKeys] empty means the frame named none — an older plugin pinging, which still has
     * to move a reader off a stale page — so it reloads. A frame that *does* name keys and
     * omits this one is some other agent on the same machine ticking, and re-fetching a
     * transcript for it is a cost every paired phone would pay for every unrelated run.
     */
    fun runFrameConcerns(openKey: String, frameKeys: Set<String>): Boolean =
        frameKeys.isEmpty() || openKey in frameKeys

    /**
     * What a page that is not the whole conversation says about itself.
     *
     * `/v1/session/{key}` takes no offset, so there is no older page to ask for yet; until
     * there is, the honest reading of `hasMore` is a marker naming what *is* shown rather
     * than a "Load earlier" button with nothing behind it.
     */
    fun truncationNotice(page: MobileTranscriptPage?): String? {
        if (page == null || !page.hasMore) return null
        val shown = page.turns.size
        if (shown <= 0) return null
        return "Showing the last $shown turns. Earlier ones are on the desktop."
    }
}

/**
 * Where one speaker's run of turns starts and stops.
 *
 * A chat reads as a conversation only when consecutive turns by the same speaker are drawn as
 * one block — one avatar, one name, one timestamp, a tail on the last bubble. Drawn per-turn
 * instead, a five-message agent answer repeats "Claude" and a clock five times and the column
 * stops having any shape. The rule is here rather than in the Composable because it is
 * arithmetic over the list, and the Composable that had it inline could only be checked by
 * looking at a screenshot.
 */
object TurnGrouping {

    /** Longer than this between two turns by the same speaker and the block restarts. */
    const val GAP_MS = 5 * 60_000L

    /**
     * Whether [index] opens a block: a new speaker, the same one after a [GAP_MS] pause, or a
     * turn still being written — that one wears the live outline and has not merged with
     * anything yet, and the finished turn above it gets to close and to state the run's time.
     */
    fun startsBlock(turns: List<MobileTurn>, index: Int): Boolean {
        val turn = turns.getOrNull(index) ?: return false
        val previous = turns.getOrNull(index - 1) ?: return true
        if (turn.streaming) return true
        if (previous.role != turn.role) return true
        // A backwards or missing stamp must not silently merge two blocks: an unknown gap is
        // not a short one.
        if (turn.timestampMs <= 0L || previous.timestampMs <= 0L) return true
        return turn.timestampMs - previous.timestampMs > GAP_MS
    }

    /** Whether [index] closes a block — the bubble that gets the tail corner. */
    fun endsBlock(turns: List<MobileTurn>, index: Int): Boolean =
        index == turns.lastIndex || startsBlock(turns, index + 1)

    /**
     * Whether [index] is the turn that writes the time.
     *
     * Every block end used to, and a block ends on a change of *speaker* — so six alternating
     * turns sent inside six minutes stacked six clocks down the column, none of which told the
     * reader anything the one above it hadn't. The time belongs to the conversation's own run:
     * the last turn before a [GAP_MS] pause, and the newest turn of all. Nothing new is
     * grouped here — it is [startsBlock]'s own pause, asked of the pair of turns rather than
     * of one speaker, so every stamped turn is also a block end and the time still lands
     * beside the tail corner.
     */
    fun carriesTime(turns: List<MobileTurn>, index: Int): Boolean {
        val turn = turns.getOrNull(index) ?: return false
        // A turn the wire says is mid-write has no finished time to state; its bubble says so.
        if (turn.streaming || turn.timestampMs <= 0L) return false
        val next = turns.getOrNull(index + 1) ?: return true
        // The live turn states no time of its own, so the run would lose its clock entirely
        // if this one deferred to it. A turn still being written closes nothing.
        if (next.streaming || next.timestampMs <= 0L) return true
        return next.timestampMs - turn.timestampMs > GAP_MS
    }
}
