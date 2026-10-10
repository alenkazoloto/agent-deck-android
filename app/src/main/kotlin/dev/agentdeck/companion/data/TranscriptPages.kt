package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileTranscriptPage

object TranscriptPages {
    fun prepend(current: MobileTranscriptPage, earlier: MobileTranscriptPage): MobileTranscriptPage {
        require(current.key == earlier.key && current.revision != null && current.revision == earlier.revision)
        require(earlier.previousCursor == null || earlier.previousCursor != current.previousCursor)
        val latest = current.turns.associateBy { it.id }
        val turns = (earlier.turns.map { latest[it.id] ?: it } + current.turns).distinctBy { it.id }
        return current.copy(turns = turns, previousCursor = earlier.previousCursor, hasMore = earlier.hasMore, historyPending = earlier.historyPending, historyPaused = earlier.historyPaused)
    }

    /** A changed snapshot is offered through Latest while the reader remains in an earlier passage. */
    fun refresh(current: MobileTranscriptPage?, fresh: MobileTranscriptPage, following: Boolean): MobileTranscriptPage? {
        if (current == null || current.key != fresh.key || current.turns.isEmpty()) return fresh
        if (current.revision != null && current.revision == fresh.revision) {
            val byId = fresh.turns.associateBy { it.id }
            return fresh.copy(turns = (current.turns.map { byId[it.id] ?: it } + fresh.turns).distinctBy { it.id },
                previousCursor = current.previousCursor, hasMore = current.hasMore)
        }
        return if (!following && current.turns != fresh.turns) null else fresh
    }
}
