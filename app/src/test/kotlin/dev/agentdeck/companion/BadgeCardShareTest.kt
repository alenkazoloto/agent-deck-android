package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileBadgeCard
import dev.agentdeck.companion.data.BadgeShare
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * A badge card leaves the app as a file the machine drew: kept under its own plain name in the private
 * exports directory, refused when it is not a PNG, and never an empty file for a badge with no card.
 */
class BadgeCardShareTest {
    @get:Rule val dir = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private fun card(bytes: ByteArray = png, name: String = "claude-badge-streak-silver.png") =
        MobileBadgeCard(name, Base64.getEncoder().encodeToString(bytes))

    @Test
    fun `the machine's picture is written byte for byte under its own name`() {
        val file = BadgeShare.writeCard(dir.root, card(), 1_000L)

        assertNotNull(file)
        assertEquals("claude-badge-streak-silver.png", file!!.name)
        assertArrayEquals(png, file.readBytes())
    }

    @Test
    fun `a badge with no card writes nothing`() {
        assertNull(BadgeShare.writeCard(dir.root, MobileBadgeCard(unavailable = "That badge has no card yet."), 1_000L))
        assertEquals(0, dir.root.listFiles()!!.size)
    }

    @Test
    fun `an answer that is not a PNG is not handed to other apps`() {
        assertNull(BadgeShare.writeCard(dir.root, card(bytes = "<html>".toByteArray()), 1_000L))
        assertNull(BadgeShare.writeCard(dir.root, MobileBadgeCard("x.png", "%%% not base64 %%%"), 1_000L))
        assertNull(BadgeShare.writeCard(dir.root, MobileBadgeCard("x.png", ""), 1_000L))
        assertEquals(0, dir.root.listFiles()!!.size)
    }

    @Test
    fun `a name that climbs out of the directory is reduced to a plain file name`() {
        val file = BadgeShare.writeCard(dir.root, card(name = "../../etc/card.png"), 1_000L)!!

        assertEquals("card.png", file.name)
        assertFalse(file.canonicalPath.removePrefix(dir.root.canonicalPath).contains(".."))
        assertEquals(dir.root.canonicalPath, file.parentFile!!.parentFile!!.canonicalPath)
    }

    @Test
    fun `a card older than a day is swept with the other shares`() {
        val old = BadgeShare.writeCard(dir.root, card(), 1_000L)!!
        old.parentFile!!.setLastModified(1_000L)

        BadgeShare.writeCard(dir.root, card(name = "next.png"), 1_000L + 25L * 60 * 60 * 1000)

        assertFalse(old.exists())
    }
}
