package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.ui.streamingTurnLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingTurnPresentationTest {

    @Test
    fun `only an unfinished assistant turn says it is still being written`() {
        assertEquals("Still writing…", streamingTurnLabel(turn("assistant", streaming = true)))
        assertNull(streamingTurnLabel(turn("assistant", streaming = false)))
        assertNull(streamingTurnLabel(turn("user", streaming = true)))
    }

    private fun turn(role: String, streaming: Boolean) = MobileTurn(
        id = "turn",
        role = role,
        text = "partial text",
        timestampMs = 1L,
        streaming = streaming,
    )
}
