package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRefusal
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.MarkdownImages
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** t3code #10322: what the phone remembers about a picture, and what it is allowed to forget. */
class MarkdownImagesTest {

    private val refusal = BridgeRefusal(
        MobileRefusal.IMAGE_UNAVAILABLE.status,
        MobileRefusal.IMAGE_UNAVAILABLE.code,
        MobileRefusal.IMAGE_UNAVAILABLE.message,
    )

    @Test
    fun `a picture already fetched is not fetched again`() = runBlocking {
        val images = MarkdownImages<String>()
        var fetches = 0

        repeat(3) { assertEquals("png", images.load("a") { fetches++; "png" }) }

        assertEquals(1, fetches)
    }

    @Test
    fun `a refusal is remembered so a scrolled bubble does not ask the machine again`() = runBlocking {
        val images = MarkdownImages<String>()
        var fetches = 0

        repeat(2) { assertNull(images.load("a") { fetches++; throw refusal }) }

        assertEquals(1, fetches)
    }

    @Test
    fun `a dropped link is not remembered, so the next look retries`() = runBlocking {
        val images = MarkdownImages<String>()
        var fetches = 0

        assertNull(runCatching { images.load("a") { fetches++; throw IOException("no route") } }.getOrNull())
        assertEquals("png", images.load("a") { fetches++; "png" })

        assertEquals(2, fetches)
    }

    @Test
    fun `the oldest picture goes first once the cache is full`() = runBlocking {
        val images = MarkdownImages<String>(maxEntries = 2)
        images.load("a") { "A" }
        images.load("b") { "B" }
        images.load("c") { "C" }

        var refetched = false
        images.load("a") { refetched = true; "A" }

        assertEquals(true, refetched)
    }
}
