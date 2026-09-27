package dev.agentdeck.companion

import dev.agentdeck.companion.ui.PreviewFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering between the polling refresh and the reader's own intents. A refresh that started before an
 * intent answers with the page as it was, so applying it would put the old picture back and wipe the
 * intent's sentence; these are the rules that keep it out.
 */
class PreviewFeedTest {

    @Test
    fun `an idle screen refreshes and keeps the answer`() {
        val feed = PreviewFeed()
        val started = feed.beginRefresh()!!

        assertTrue(feed.keepsRefresh(started))
    }

    @Test
    fun `no refresh starts while an intent is in flight`() {
        val feed = PreviewFeed()
        feed.beginIntent()

        assertNull(feed.beginRefresh())
        feed.endIntent()
        assertEquals(1, feed.beginRefresh())
    }

    @Test
    fun `a refresh that started before an intent is dropped even after the intent has finished`() {
        val feed = PreviewFeed()
        val started = feed.beginRefresh()!!
        feed.beginIntent()
        feed.endIntent()

        assertFalse(feed.keepsRefresh(started))
    }

    @Test
    fun `a refresh answering while an intent is in flight is dropped`() {
        val feed = PreviewFeed()
        val started = feed.beginRefresh()!!
        feed.beginIntent()

        assertFalse(feed.keepsRefresh(started))
    }

    @Test
    fun `two overlapping intents keep the refresh away until both have finished`() {
        val feed = PreviewFeed()
        feed.beginIntent()
        feed.beginIntent()
        feed.endIntent()

        assertNull("the first intent finishing must not free the loop while the second runs", feed.beginRefresh())
        feed.endIntent()
        assertEquals(2, feed.beginRefresh())
    }
}
