package dev.agentdeck.companion

import dev.agentdeck.companion.push.UnifiedPush
import dev.agentdeck.companion.ui.PushCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five things Settings can say about being told while the app is closed.
 *
 * A screenshot cannot be asked about these. The branch is decided by four independent facts, a
 * golden fixture holds one arrangement of them, and the arrangement that matters most — push is
 * working — is unreachable from a Robolectric device with no distributor installed. The
 * `settings-current` golden therefore only ever photographs "nothing installed".
 *
 * What is asserted is the *repair* each state names, not the wording: the states exist because a
 * silent phone is fixed in four different places, and the failure this guards against is the
 * collapse back into one "Push: off" sentence that is true in all five and actionable in none.
 */
class PushCopyTest {

    private val ntfy = UnifiedPush.Distributor("io.heckel.ntfy", "ntfy")
    private val other = UnifiedPush.Distributor("org.example.push", "Example Push")

    private fun live() = PushState(
        distributors = listOf(ntfy),
        chosen = ntfy.packageName,
        endpoint = "https://ntfy.sh/abc",
        machineSupports = true,
    )

    private val states = mapOf(
        "live" to live(),
        "machine off" to live().copy(machineSupports = false),
        "waiting" to live().copy(endpoint = null),
        "none installed" to PushState(),
        "none chosen" to PushState(distributors = listOf(ntfy, other)),
    )

    /**
     * The only state in which the phone can actually be reached while closed — so it is the only
     * one allowed to make the privacy promise, and the payload is what has to keep it: the push
     * service forwards ciphertext it has no key for (`MobilePushSenderTest`).
     */
    @Test
    fun `a working push names its carrier and promises the service cannot read it`() {
        val copy = PushCopy.of(live())
        assertTrue(copy.body, copy.body.contains("ntfy"))
        assertTrue(copy.body, copy.body.contains("encrypted end to end"))
        assertTrue("nothing left to choose", copy.offer.isEmpty())
        assertTrue("there is something to turn off", copy.offerStop)
    }

    /**
     * The one repair this screen cannot perform. Sending the reader to watch a phone that can
     * never change is the specific harm of sharing a sentence with `waiting` — so this state has
     * to name the IDE, and must not name a wait.
     */
    @Test
    fun `a machine that was never asked sends the reader to the desk, not to a wait`() {
        val copy = PushCopy.of(live().copy(machineSupports = false))
        assertTrue(copy.body, copy.body.contains("Settings › Connections › Mobile"))
        assertFalse(copy.title + copy.body, copy.title.contains("Waiting"))
    }

    /**
     * Distributor chosen, machine willing, no address back yet. The reader has already done
     * everything asked of them, so this must not repeat an instruction — it names who is late.
     */
    @Test
    fun `no endpoint yet names the relay being waited on`() {
        val copy = PushCopy.of(live().copy(endpoint = null))
        assertTrue(copy.title, copy.title.contains("Waiting for ntfy"))
        assertFalse(copy.body, copy.body.contains("Settings › Connections › Mobile"))
    }

    /** `SharedPreferences.getString` hands back `""`, not `null`, for a cleared endpoint. */
    @Test
    fun `a blank endpoint is not a working push`() {
        assertEquals(
            PushCopy.of(live().copy(endpoint = null)),
            PushCopy.of(live().copy(endpoint = "")),
        )
    }

    /**
     * A phone with no distributor is the ordinary case, not a fault. It has nothing to offer —
     * offering an empty list is how "install ntfy" becomes a row that does nothing when tapped.
     */
    @Test
    fun `no distributor installed asks for one to be installed and offers nothing`() {
        val copy = PushCopy.of(PushState())
        assertTrue(copy.body, copy.body.contains("ntfy"))
        assertTrue("nothing is installed to offer", copy.offer.isEmpty())
        assertFalse("nothing has been started", copy.offerStop)
    }

    /** The only state whose repair is a tap on this screen — so the only one with rows. */
    @Test
    fun `an unchosen distributor is offered, and it is the only state that offers one`() {
        assertEquals(listOf(ntfy, other), PushCopy.of(PushState(distributors = listOf(ntfy, other))).offer)
        val offering = states.filterValues { PushCopy.of(it).offer.isNotEmpty() }
        assertEquals(setOf("none chosen"), offering.keys)
    }

    /**
     * The regression that would make the other six tests pass while the screen stopped helping:
     * two branches folded into one sentence. Five distinct repairs owe five distinct sentences.
     */
    @Test
    fun `the five states say five different things`() {
        val bodies = states.mapValues { PushCopy.of(it.value).body }
        assertEquals(bodies.toString(), states.size, bodies.values.toSet().size)
    }

    /** Offering to stop what was never started is the mirror of the same collapse. */
    @Test
    fun `stopping is offered exactly where a distributor was chosen`() {
        val stoppable = states.filterValues { PushCopy.of(it).offerStop }
        assertEquals(setOf("live", "machine off", "waiting"), stoppable.keys)
    }

    /**
     * `chosenLabel` falls back to the package name for a distributor that has been uninstalled
     * since it was picked — a real sequence, and the one that puts the word "null" on screen if
     * the fallback is ever dropped.
     */
    @Test
    fun `an uninstalled but still-chosen distributor renders its package, never null`() {
        val copy = PushCopy.of(live().copy(distributors = emptyList()))
        assertTrue(copy.body, copy.body.contains("io.heckel.ntfy"))
        states.values.forEach { assertFalse(it.toString(), PushCopy.of(it).body.contains("null")) }
    }
}
