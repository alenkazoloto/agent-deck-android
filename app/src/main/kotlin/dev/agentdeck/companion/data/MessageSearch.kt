package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileSessionSearchHit
import com.github.claudeagents.core.mobile.MobileSessionSearchRequest
import com.github.claudeagents.core.mobile.MobileSessionSearchResult

/**
 * The Chats search's second half: chats whose *messages* hold the query, asked of the machine
 * (`/v1/session-search`) after the title search has answered on the phone.
 *
 * Keyed by [query] and [keys] together, so a result can only ever be shown for the search that
 * asked for it: a page that lands after the reader typed another letter, changed a filter or
 * switched machine is dropped, never painted under the newer query.
 */
data class MessageSearch(
    val query: String,
    /** The chats asked about, newest first — the rows the title search did not already show. */
    val keys: List<String>,
    val hits: List<MobileSessionSearchHit> = emptyList(),
    val scanned: Int = 0,
    /** Index into [keys] where the next page starts, or null once every key was looked at. */
    val nextCursor: Int? = 0,
    val searching: Boolean = false,
    val timedOut: Boolean = false,
    val error: String? = null,
) {
    val complete: Boolean get() = nextCursor == null && !searching && error == null

    /** The keys the next page asks about — one page, never the whole list (the body cap). */
    fun pageKeys(): List<String> =
        nextCursor?.let { keys.drop(it).take(MobileSessionSearchRequest.PAGE_FILES) }.orEmpty()

    /**
     * This search, still answering [keys] for [query] — or null when it is a different search.
     *
     * Fleet frames re-sort a running chat and move one between title hit and not with its
     * activity line, and restarting on each would keep a search on a busy machine from ever
     * finishing, so a dropped key changes nothing ([visibleHits] hides its hit). A chat never
     * asked about — a new session, a reopened one — joins the end of the list rather than
     * restarting the pages already read; a finished search becomes resumable to read it.
     */
    fun continuedWith(query: String, keys: List<String>): MessageSearch? {
        if (this.query != query) return null
        val known = this.keys.toHashSet()
        val added = keys.filterNot { it in known }
        if (added.isEmpty()) return this
        return copy(keys = this.keys + added, nextCursor = nextCursor ?: this.keys.size)
    }

    fun visibleHits(keys: List<String>): List<MobileSessionSearchHit> {
        val wanted = keys.toHashSet()
        return hits.filter { it.key in wanted }
    }

    /** The page asked from [start] folded in. A hit already listed stays where it is. */
    fun plus(start: Int, page: MobileSessionSearchResult): MessageSearch {
        val known = hits.mapTo(HashSet()) { it.key }
        val next = start + page.examined.coerceAtLeast(0)
        return copy(
            hits = hits + page.hits.filter { known.add(it.key) },
            scanned = scanned + page.scanned,
            nextCursor = next.takeIf { it < keys.size },
            searching = false,
            timedOut = page.timedOut,
            error = null,
        )
    }

    companion object {
        /**
         * Which chats a message search looks through: the rows the reader's filters and snoozes
         * leave, minus those the title search already listed — one chat is one row, and a title
         * hit is the better one. Newest first, the desk's own order for a bounded pass.
         */
        fun population(visible: List<MobileFleetRow>, filter: FleetFilter): List<String> {
            val unfiltered = filter.copy(query = "")
            return visible
                .filter { unfiltered.matches(it) && !filter.matches(it) }
                .sortedWith(compareByDescending<MobileFleetRow> { it.lastActivityMs }.thenBy { it.key })
                .map { it.key }
        }
    }
}
