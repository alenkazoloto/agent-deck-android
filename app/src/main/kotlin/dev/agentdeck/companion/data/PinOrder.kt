package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * The desk's "Move pin up/down" on the phone's list (M3, P05).
 *
 * A move steps among the pins the list *paints* around the row — one section of the grouped
 * list, or the whole flat one — as the desk's move steps among the pins its own list paints,
 * so "up" never swaps with a pin the reader cannot see and reports a success that changed nothing.
 * The machine is sent the whole wanted order of those pins, not a step, so a retried request
 * cannot move a pin twice.
 */
object PinOrder {

    /** The desk's two end sentences (`ConversationActions.movePinRefusal`). */
    fun endMessage(delta: Int): String =
        if (delta < 0) "This chat is already the first of your pinned chats."
        else "This chat is already the last of your pinned chats."

    /** The keys of the pinned rows in [painted], top first. */
    fun painted(painted: List<MobileFleetRow>): List<String> = painted.filter { it.pinned }.map { it.key }

    /** [pins] with [key] moved [delta] places, or null when it is already at that end or not among them. */
    fun moved(pins: List<String>, key: String, delta: Int): List<String>? {
        val from = pins.indexOf(key)
        val to = from + delta
        if (from < 0 || delta == 0 || to !in pins.indices) return null
        return pins.toMutableList().apply {
            set(from, pins[to])
            set(to, pins[from])
        }
    }

    /**
     * [rows] after the machine accepted [wanted]: those pins keep the rank slots they held, in the
     * new order, which is what the machine wrote. Unchanged when a rank is missing — the next
     * fleet frame carries the machine's own.
     */
    fun reranked(rows: List<MobileFleetRow>, wanted: List<String>): List<MobileFleetRow> {
        val slots = rows.filter { it.key in wanted }.mapNotNull { it.pinRank }.sorted()
        if (slots.size != wanted.size) return rows
        val rank = wanted.withIndex().associate { (i, key) -> key to slots[i] }
        return rows.map { row -> rank[row.key]?.let { row.copy(pinRank = it) } ?: row }
    }
}
