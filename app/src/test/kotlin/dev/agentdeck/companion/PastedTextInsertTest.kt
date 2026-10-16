package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileAttachment
import dev.agentdeck.companion.data.PastedTextInsert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PastedTextInsertTest {

    private val trace = (1..5).joinToString("\n") { "at frame $it" }

    @Test fun `typing a character is not a paste`() {
        assertNull(PastedTextInsert.split("fix th", "fix the"))
    }

    @Test fun `three lines are still typing and four are a paste`() {
        assertNull(PastedTextInsert.split("", "a\nb\nc"))
        assertNull("a trailing newline is not a line", PastedTextInsert.split("", "a\nb\nc\n"))
        assertEquals(PastedTextInsert.Split("", "a\nb\nc\nd"), PastedTextInsert.split("", "a\nb\nc\nd"))
    }

    @Test fun `one long line is a paste past 512 characters`() {
        assertNull(PastedTextInsert.split("", "x".repeat(512)))
        assertEquals("x".repeat(513), PastedTextInsert.split("", "x".repeat(513))!!.pasted)
    }

    @Test fun `a paste in the middle leaves the words around it`() {
        val split = PastedTextInsert.split("see : please", "see $trace: please")!!
        assertEquals("see : please", split.kept)
        assertEquals(trace, split.pasted)
    }

    @Test fun `a paste over a selection takes the selection with it`() {
        val split = PastedTextInsert.split("read old words now", "read $trace now")!!
        assertEquals("read  now", split.kept)
        assertEquals(trace, split.pasted)
    }

    @Test fun `repeated characters around the paste do not shift what is kept`() {
        val split = PastedTextInsert.split("aaaa", "aa" + "a\na\na\na" + "aa")!!
        assertEquals("aaaa", split.kept)
    }

    @Test fun `a paste too big for a text file stays in the field`() {
        assertNull(PastedTextInsert.split("", "x".repeat(MobileAttachment.MAX_TEXT_BYTES + 1)))
    }

    @Test fun `the chip counts lines as the desk does`() {
        assertEquals("Pasted text (1 line) · 1 KB", PastedTextInsert.label("x".repeat(600), 600))
        assertEquals("Pasted text (5 lines) · 1 KB", PastedTextInsert.label(trace, 90))
    }
}
