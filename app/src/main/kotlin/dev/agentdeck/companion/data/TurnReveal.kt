package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileTurn

/** A turn a Usage row asked to land on: the chat's fleet key and the instant the row names (`MobileInsightsOpen`). */
data class TurnReveal(val key: String, val atMs: Long)

/**
 * Where a [TurnReveal] lands in the turns loaded so far, decided as the desk's row is: the first
 * message written at or after the instant, and nothing older — a landing on the wrong turn is worse
 * than saying it could not find one.
 */
sealed interface TurnRevealPlan {
    data class Show(val index: Int) : TurnRevealPlan

    /** The match is the oldest turn held while earlier ones exist, so the true one may still be above it. */
    data object LoadEarlier : TurnRevealPlan

    /** No held turn was written at or after the instant, and nothing earlier is left to load. */
    data object Gone : TurnRevealPlan

    companion object {
        fun of(turns: List<MobileTurn>, atMs: Long, earlierAvailable: Boolean): TurnRevealPlan {
            val index = turns.indices.filter { turns[it].recordAtMs >= atMs && turns[it].recordAtMs > 0 }
                .minWithOrNull(compareBy({ turns[it].recordAtMs }, { it }))
                ?: return Gone
            return if (index == 0 && earlierAvailable) LoadEarlier else Show(index)
        }
    }
}
