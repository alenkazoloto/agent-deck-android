package dev.agentdeck.companion

import dev.agentdeck.companion.data.TranscriptRequests
import org.junit.Assert.*
import org.junit.Test

class TranscriptRequestsTest {
    @Test fun `rapid run ticks allow slow response to paint then fetch once more`() {
        val requests = TranscriptRequests()
        val slow = requests.begin("laptop", "chat")!!
        repeat(20) { assertNull(requests.begin("laptop", "chat")) }
        assertTrue(requests.owns(slow))
        assertTrue(requests.finish(slow))
        val followup = requests.begin("laptop", "chat")!!
        assertFalse(requests.finish(followup))
    }

    @Test fun `conversation and machine changes reject old completions`() {
        val requests = TranscriptRequests()
        val old = requests.begin("laptop", "chat")!!
        val current = requests.begin("desktop", "chat")!!
        assertFalse(requests.owns(old))
        assertFalse(requests.finish(old))
        assertTrue(requests.owns(current))
        requests.reset()
        assertFalse(requests.owns(current))
    }
}
