package dev.agentdeck.companion

import dev.agentdeck.companion.data.SendAttempt
import org.junit.Assert.*
import org.junit.Test

class SendAttemptTest {
    @Test fun `acknowledgement consumes only the submitted draft`() {
        assertEquals("", SendAttempt.remainingDraft("Run tests", "Run tests"))
        assertEquals("Run tests then explain", SendAttempt.remainingDraft("Run tests then explain", "Run tests"))
        assertEquals("Next question", SendAttempt.remainingDraft("Next question", "Run tests"))
    }

    @Test fun `duplicate identity includes machine conversation and exact prompt`() {
        val pending = setOf(SendAttempt("laptop", "chat", "Run tests"))
        assertTrue(SendAttempt("laptop", "chat", "Run tests") in pending)
        assertFalse(SendAttempt("laptop", "chat", "Explain") in pending)
        assertFalse(SendAttempt("desktop", "chat", "Run tests") in pending)
        assertFalse(SendAttempt("laptop", "other", "Run tests") in pending)
    }
}
