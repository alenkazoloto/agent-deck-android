package dev.agentdeck.companion

import android.graphics.Bitmap
import com.github.claudeagents.core.mobile.MobileAttachment
import dev.agentdeck.companion.data.PhotoEncoder
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a picked photo becomes before it is offered to the machine.
 *
 * The cap is enforced on both ends, so the question here is only whether the *client* gets under
 * it on its own — a re-encode that quietly hands the machine four and a bit megabytes turns every
 * ordinary photo into a refusal the reader cannot act on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoEncoderTest {

    @Test
    fun `a photo larger than the cap comes back under it`() {
        val encoded = PhotoEncoder.encode(jpeg(4000, 3000))

        assertNotNull("a phone-camera-sized photo was refused rather than shrunk", encoded)
        assertTrue(
            "the re-encode handed the machine something it will refuse: ${encoded!!.size}",
            encoded.size <= MobileAttachment.MAX_ATTACH_BYTES,
        )
        assertTrue("the result is not a JPEG", MobileAttachment.looksLikeJpeg(encoded))
    }

    // There is deliberately no "bytes that are not an image" case here. Robolectric's
    // BitmapFactory shadow fabricates a bitmap for any input, so such a test would pass whether
    // or not the guard existed — the artifact would look identical if the code did nothing.
    // The authority that actually decides is the machine, and `MobileAttachmentWiringTest`
    // asserts it against a real ImageIO with forged and garbage bytes.

    @Test
    fun `an impossible cap is a null and never a silent oversize`() {
        assertNull("the ladder ran out and still returned bytes", PhotoEncoder.encode(jpeg(2048, 2048), cap = 8))
    }

    @Test
    fun `the chip names a size the reader can compare against the limit`() {
        assertEquals("Photo · 512 KB", PhotoEncoder.label(512 * 1024))
        assertEquals("Photo · 1.5 MB", PhotoEncoder.label((1.5 * 1024 * 1024).toInt()))
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
    }
}
