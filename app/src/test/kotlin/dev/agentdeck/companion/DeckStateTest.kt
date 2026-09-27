package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The unpair route's one race, isolated as a pure decision.
 *
 * `unpairFromMachine` wipes the phone first and tells the machine afterwards, so the revoke's
 * failure can arrive at any point in the future — including after the user has already paired
 * somewhere else. Everything about that is a coroutine, which is why the *rule* lives in
 * `withLateUnpairFailure`: a test that had to stand up a dispatcher and a fake client would be
 * the first thing a later edit deleted.
 */
class DeckStateTest {

    @Test
    fun `a late unpair failure lands on an idle pairing screen`() {
        val state = DeckState(screen = Screen.Pair).withLateUnpairFailure("could not reach it")
        assertEquals("could not reach it", state.pairError)
    }

    @Test
    fun `it is dropped once the user has paired somewhere else`() {
        // The whole point of wiping locally first is that the user can move on immediately;
        // an error about the machine they left must not follow them.
        listOf(
            Screen.Fleet,
            Screen.Scheduled,
            Screen.Conversation("k", "t", AgentVendor.CLAUDE, "/p"),
        ).forEach { screen ->
            assertNull(
                "a late failure must not surface on $screen",
                DeckState(screen = screen).withLateUnpairFailure("could not reach it").pairError,
            )
        }
    }

    /** `PairScreen` paints `pairError` and the "Pairing…" spinner in the same column. */
    @Test
    fun `it is dropped while the next pairing is in flight`() {
        val state = DeckState(screen = Screen.Pair, pairing = true)
        assertNull(state.withLateUnpairFailure("could not reach it").pairError)
    }
}
