package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.ui.TranscriptTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript list's two decisions about its own tail.
 *
 * Both are asserted here rather than on a device because both are *decisions*, and both were
 * previously invisible: the list scrolled to the newest turn unconditionally, and `hasMore`
 * had no reader anywhere in the app. A screenshot of a conversation that happens to fit on one
 * page looks identical either way, which is exactly why this is a unit test.
 */
class TranscriptTailTest {

    private fun page(turns: Int, hasMore: Boolean): MobileTranscriptPage {
        val rows = (1..turns).joinToString(",") {
            """{"id":"t$it","role":"assistant","text":"turn $it","timestampMs":$it}"""
        }
        return MobileTranscriptPage.fromJson(
            MobileProtocol.parseObject(
                """{"v":1,"key":"k","title":"t","turns":[$rows],"hasMore":$hasMore,""" +
                    """"costUsd":0.0,"costKnown":false,"running":false,"generatedAtMs":1}""",
            ) ?: error("fixture did not parse"),
        )
    }

    // ---- MU-07: new output may only move a viewport that is already at the tail -----------

    @Test
    fun `a reader at the last row follows new output`() {
        assertTrue(TranscriptTail.followsTail(lastVisibleIndex = 9, totalItems = 10))
    }

    @Test
    fun `one row of slack still counts as the tail`() {
        // The last turn half off the bottom edge is a reader watching the run, not one who
        // deliberately scrolled away.
        assertTrue(TranscriptTail.followsTail(lastVisibleIndex = 8, totalItems = 10))
    }

    @Test
    fun `a reader scrolled up is left where they are`() {
        // The whole point: at row 4 of 10 a turn arriving must not yank the list to row 9.
        assertFalse(TranscriptTail.followsTail(lastVisibleIndex = 4, totalItems = 10))
        assertFalse(TranscriptTail.followsTail(lastVisibleIndex = 0, totalItems = 200))
    }

    @Test
    fun `nothing laid out yet opens at the newest turn`() {
        assertTrue(TranscriptTail.followsTail(lastVisibleIndex = -1, totalItems = 10))
        assertTrue(TranscriptTail.followsTail(lastVisibleIndex = -1, totalItems = 0))
    }

    @Test
    fun `the pill counts only what arrived after the reader left the end`() {
        assertEquals(3, TranscriptTail.unreadBelow(turnCount = 12, readThrough = 9))
        assertEquals(0, TranscriptTail.unreadBelow(turnCount = 9, readThrough = 9))
        // A shorter page than last seen (a reload, a switched conversation) is not -3 new.
        assertEquals(0, TranscriptTail.unreadBelow(turnCount = 6, readThrough = 9))
    }

    // ---- MU-08: a truncated transcript says so -------------------------------------------

    @Test
    fun `a truncated page names how much of it is showing`() {
        val notice = TranscriptTail.truncationNotice(page(turns = 120, hasMore = true))
        assertEquals("Showing the last 120 turns. Earlier ones are on the desktop.", notice)
    }

    @Test
    fun `a complete page says nothing`() {
        // The regression this guards: `hasMore` had no reader at all, so *every* page — whole
        // or truncated — painted the same silence.
        assertNull(TranscriptTail.truncationNotice(page(turns = 4, hasMore = false)))
    }

    @Test
    fun `no page and an empty page carry no marker`() {
        assertNull(TranscriptTail.truncationNotice(null))
        assertNull(TranscriptTail.truncationNotice(page(turns = 0, hasMore = true)))
    }

    // ---- which `run` frames concern the open conversation -----------------------------

    @Test
    fun `a frame naming this conversation reloads it`() {
        assertTrue(TranscriptTail.runFrameConcerns("k1", setOf("k1", "k2")))
    }

    @Test
    fun `a frame naming only other conversations is ignored`() {
        // Otherwise every unrelated agent on the machine costs this phone a transcript fetch,
        // every tick, for a conversation nobody is reading.
        assertFalse(TranscriptTail.runFrameConcerns("k1", setOf("k2", "k3")))
    }

    @Test
    fun `a frame that names nothing still reloads`() {
        // An older plugin pings without naming keys. Treating that as "nothing changed" would
        // reintroduce the exact bug — a reader parked on a page that never refreshes — against
        // a machine that is trying to tell them otherwise.
        assertTrue(TranscriptTail.runFrameConcerns("k1", emptySet()))
    }
}
