package dev.agentdeck.companion

import dev.agentdeck.companion.data.*
import org.junit.Assert.*
import org.junit.Test

class ReadingPositionsTest {
    @Test fun stableTurnWinsAfterEarlierHistoryIsPrepended() {
        val anchor = ReadingAnchor("turn-15", 5, 217, false)
        assertEquals(15, anchor.resolvedIndex((0..30).map { "turn-$it" }))
        assertEquals(217, anchor.offset)
    }

    @Test fun missingTurnFallsBackToNearestAvailableIndex() {
        assertEquals(2, ReadingAnchor("evicted", 91, 20, false).resolvedIndex(listOf("a", "b", "c")))
        assertEquals(1, ReadingAnchor("changed", 1).resolvedIndex(listOf("a", "b", "c")))
        assertEquals(0, ReadingAnchor(index = -10).resolvedIndex(emptyList()))
    }

    @Test fun retentionIsBoundedAndRevisitedConversationBecomesMostRecent() {
        var positions = ReadingPositions()
        repeat(64) { positions = positions.remembering("chat-$it", ReadingAnchor("turn-$it")) }
        positions = positions.remembering("chat-0", ReadingAnchor("revisited", followingLatest = false))
            .remembering("new-chat", ReadingAnchor())
        assertEquals(64, positions.conversations.size)
        assertFalse(positions.conversations.containsKey("chat-1"))
        assertEquals("revisited", positions.conversations.getValue("chat-0").itemKey)
    }

    @Test fun durableRoundTripKeepsFiltersExpansionOffsetAndFollowingChoice() {
        val positions = ReadingPositions(
            mapOf("a" to ReadingAnchor("turn", 41, 76, false), "b" to ReadingAnchor(followingLatest = true)),
            FleetBrowsing(ReadingAnchor("chat-40", 42, 19, false), setOf("DONE_UNREVIEWED"),
                FleetFilter(query = "мобильный", projectPath = "/project"), FleetSort.ATTENTION),
        )
        assertEquals(positions, ReadingPositions.fromJson(positions.toJson()))
    }

    @Test fun malformedPositionsFallBackWithoutAffectingOtherStorage() {
        assertEquals(ReadingPositions(), ReadingPositions.fromJson(com.google.gson.JsonObject().apply {
            addProperty("chats", "not-an-array")
        }))
    }
}
