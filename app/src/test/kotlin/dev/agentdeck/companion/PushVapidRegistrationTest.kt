package dev.agentdeck.companion

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobilePush
import com.google.gson.JsonParser
import dev.agentdeck.companion.push.UnifiedPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * What this phone tells a distributor about the machine behind it.
 *
 * The `vapid` extra is the only value in a `REGISTER` this app neither mints nor understands: it
 * travels from the plugin's `/v1/hello` straight into a third-party app's broadcast. That makes
 * two failures possible and neither is visible from here — sending nothing, which a UnifiedPush
 * 3.x distributor answers with `REGISTRATION_FAILED reason=VAPID_REQUIRED`, and sending
 * something malformed, which it refuses as *this app's* registration. Both end as a reader who
 * gets no push (`TODO.md`), so the broadcast itself is what is asserted rather than the store
 * that fed it.
 */
@RunWith(RobolectricTestRunner::class)
class PushVapidRegistrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `a register broadcast names the machine's application server`() {
        val key = machineKey()

        UnifiedPush.register(context, DISTRIBUTOR, "token-1", key)

        val intent = lastRegister()
        assertEquals(DISTRIBUTOR, intent.`package`)
        assertEquals("token-1", intent.getStringExtra(UnifiedPush.EXTRA_TOKEN))
        assertEquals(key, intent.getStringExtra(UnifiedPush.EXTRA_VAPID))
    }

    /**
     * And the negative control. A key that is the wrong width, the wrong point form or simply
     * not base64url must be left out entirely rather than forwarded: an absent `vapid` is a
     * registration a 2.x distributor accepts and a 3.x one refuses *by name*, while a malformed
     * one is a refusal that names this app.
     */
    @Test
    fun `a key the distributor would refuse is not sent at all`() {
        UnifiedPush.register(context, DISTRIBUTOR, "token-1", "not-a-key")
        assertNull(lastRegister().getStringExtra(UnifiedPush.EXTRA_VAPID))

        UnifiedPush.register(context, DISTRIBUTOR, "token-1", null)
        assertNull(lastRegister().getStringExtra(UnifiedPush.EXTRA_VAPID))

        // 87 characters, so a length check alone would pass it — but it decodes to a
        // compressed point, which is not what RFC 8292 or the distributor accepts.
        val compressed = MobilePush.encodeBase64Url(ByteArray(MobilePush.PUBLIC_KEY_LEN) { if (it == 0) 0x03 else 0x7f })
        assertEquals(MobilePush.VAPID_PUBLIC_KEY_B64_LEN, compressed.length)
        UnifiedPush.register(context, DISTRIBUTOR, "token-1", compressed)
        assertNull(lastRegister().getStringExtra(UnifiedPush.EXTRA_VAPID))
    }

    /**
     * The wire in between. `/v1/hello` is where the key comes from, so a phone that could not
     * read it out of that body would register without one however well the plugin signs.
     */
    @Test
    fun `the key survives the hello it arrives in`() {
        val key = machineKey()
        val hello = MobileHello.fromJson(
            JsonParser.parseString(
                """{"v":1,"machine":"m","ide":"IDEA","pluginVersion":"1","capabilities":["push"],"vapid":"$key"}""",
            ).asJsonObject,
        )

        assertEquals(key, hello.vapidPublicKey)
        assertTrue(MobilePush.isVapidPublicKey(hello.vapidPublicKey))
    }

    /** A machine that named a malformed key named none: the phone must not carry it onward. */
    @Test
    fun `a malformed key in hello reads as no key`() {
        val hello = MobileHello.fromJson(
            JsonParser.parseString(
                """{"v":1,"machine":"m","ide":"IDEA","pluginVersion":"1","capabilities":[],"vapid":"AAAA"}""",
            ).asJsonObject,
        )

        assertNull(hello.vapidPublicKey)
        assertFalse(MobilePush.isVapidPublicKey("AAAA"))
    }

    /** The last `REGISTER` this app broadcast, as the distributor's receiver would see it. */
    private fun lastRegister(): Intent =
        Shadows.shadowOf(context).broadcastIntents.last { it.action == UnifiedPush.ACTION_REGISTER }

    /** A real P-256 public key in the 65-byte uncompressed form the plugin advertises. */
    private fun machineKey(): String {
        val keys = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val point = keys.public as ECPublicKey
        val width = (point.params.curve.field.fieldSize + 7) / 8
        fun coordinate(value: java.math.BigInteger): ByteArray {
            val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(width - raw.size) + raw
        }
        return MobilePush.encodeBase64Url(
            byteArrayOf(0x04) + coordinate(point.w.affineX) + coordinate(point.w.affineY),
        )
    }

    private companion object {
        const val DISTRIBUTOR = "io.heckel.ntfy"
    }
}
