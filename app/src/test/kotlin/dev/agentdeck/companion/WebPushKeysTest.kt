package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobilePush
import dev.agentdeck.companion.push.WebPushKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * The phone's half of RFC 8291, pinned against the RFC's own published example.
 *
 * **A round trip would prove nothing here.** An implementation with a wrong info string, a
 * missing NUL, or two HKDF stages swapped decrypts its own output perfectly — and the plugin's
 * encoder is in a different Gradle build, so "it agrees with ours" is not available as evidence
 * on this side either. §5's vector is: a fixed record, the receiver's private key, and the
 * sentence that must come out. Nothing but the right derivation produces it.
 *
 * The second case covers what the *app* actually does between two launches: the keypair is
 * stored as PKCS#8/X.509 and rebuilt from prefs, and a phone whose stored halves no longer make
 * a pair would register a public key it cannot decrypt for — the failure whose only symptom is
 * silence.
 */
class WebPushKeysTest {

    // https://www.rfc-editor.org/rfc/rfc8291.html#section-5
    private val plaintext = "When I grow up, I want to be a watermelon"
    private val uaPrivate = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
    private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val authSecret = "BTBZMqHH6r4Tts7J_aSIgg"
    private val record =
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vC" +
            "YLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXL" +
            "WyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    @Test
    fun `reads the RFC 8291 published example`() {
        val decrypted = WebPushKeys.decrypt(rfcIdentity(), decode(record))

        assertEquals(plaintext, decrypted?.decodeToString())
    }

    /** The identity the RFC describes is the one this app would have registered. */
    @Test
    fun `the public key the machine is given is the one the record was encrypted to`() {
        assertEquals(uaPublic, rfcIdentity().publicKeyB64)
        assertEquals(authSecret, rfcIdentity().authSecretB64)
    }

    /** A record for someone else's subscription is a dropped frame, never a crash. */
    @Test
    fun `a record this phone has no key for is refused quietly`() {
        val stranger = WebPushKeys.generate()

        assertNull(WebPushKeys.decrypt(stranger, decode(record)))
    }

    @Test
    fun `truncated and empty bodies are refused quietly`() {
        val identity = rfcIdentity()

        assertNull(WebPushKeys.decrypt(identity, ByteArray(0)))
        assertNull(WebPushKeys.decrypt(identity, decode(record).copyOfRange(0, 30)))
        assertNull(WebPushKeys.decrypt(identity, ByteArray(200)))
    }

    /**
     * What a cold start does. The stored form is PKCS#8 and X.509 because those are what a JCE
     * `KeyFactory` can hand back on any host; a raw scalar would need the public point derived,
     * which Android's provider will not do.
     */
    @Test
    fun `a stored identity comes back able to decrypt`() {
        val minted = WebPushKeys.generate()

        val restored = WebPushKeys.restore(minted.privateB64, minted.publicSpkiB64, minted.authSecretB64)

        assertNotNull(restored)
        assertEquals(minted.publicKeyB64, restored!!.publicKeyB64)
        assertEquals(minted.authSecretB64, restored.authSecretB64)
        // And the RFC record still reads through a *restored* identity, not merely a fresh one.
        val storedRfc = requireNotNull(
            WebPushKeys.restore(rfcIdentity().privateB64, rfcIdentity().publicSpkiB64, authSecret),
        )
        assertEquals(plaintext, WebPushKeys.decrypt(storedRfc, decode(record))?.decodeToString())
    }

    /**
     * All-or-nothing. A half-written identity registers a public key whose private half is
     * gone, and the only symptom is a phone that never buzzes — indistinguishable from having
     * no push configured at all.
     */
    @Test
    fun `a partial stored identity is refused`() {
        val minted = WebPushKeys.generate()

        assertNull(WebPushKeys.restore(null, minted.publicSpkiB64, minted.authSecretB64))
        assertNull(WebPushKeys.restore(minted.privateB64, null, minted.authSecretB64))
        assertNull(WebPushKeys.restore(minted.privateB64, minted.publicSpkiB64, null))
        assertNull(
            "an auth secret of the wrong width was accepted",
            WebPushKeys.restore(minted.privateB64, minted.publicSpkiB64, "AAAA"),
        )
        assertNull(WebPushKeys.restore("not-base64-at-all!!", minted.publicSpkiB64, minted.authSecretB64))
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun decode(value: String) = requireNotNull(MobilePush.decodeBase64Url(value))

    /** The RFC's receiver, rebuilt from its published private scalar. */
    private fun rfcIdentity(): WebPushKeys.Identity {
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        val scalar = BigInteger(1, decode(uaPrivate))
        val point = decode(uaPublic)
        val half = (point.size - 1) / 2
        val public = factory.generatePublic(
            ECPublicKeySpec(
                java.security.spec.ECPoint(
                    BigInteger(1, point.copyOfRange(1, 1 + half)),
                    BigInteger(1, point.copyOfRange(1 + half, point.size)),
                ),
                params,
            ),
        )
        val private = factory.generatePrivate(ECPrivateKeySpec(scalar, params))
        return WebPushKeys.Identity(KeyPair(public, private), decode(authSecret))
    }
}
