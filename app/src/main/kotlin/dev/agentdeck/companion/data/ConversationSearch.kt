package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** UTF-16 source offsets, matching the offsets Compose uses to lay out the message. */
data class ConversationMatch(val turnId: String, val start: Int, val end: Int)

internal object ConversationSearch {
    const val MAX_MATCHES = 10_000
    suspend fun find(turns: List<MobileTurn>, query: String): List<ConversationMatch> =
        withContext(Dispatchers.Default) {
            if (query.isEmpty()) return@withContext emptyList()
            buildList {
                for (turn in turns) {
                    coroutineContext.ensureActive()
                    var offset = 0
                    var checks = 0
                    val last = turn.text.length - query.length
                    while (offset <= last) {
                        // Even an enormous message without matches must release obsolete queries.
                        if (checks++ and 1023 == 0) coroutineContext.ensureActive()
                        if (turn.text.regionMatches(offset, query, 0, query.length, ignoreCase = true)) {
                            add(ConversationMatch(turn.id, offset, offset + query.length))
                            if (size >= MAX_MATCHES) return@buildList
                            offset += query.length
                        } else offset++
                    }
                }
            }
        }
}
