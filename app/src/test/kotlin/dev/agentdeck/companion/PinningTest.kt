package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobilePairing
import dev.agentdeck.companion.data.PinMismatchException
import dev.agentdeck.companion.data.Pinning
import dev.agentdeck.companion.data.SpkiPinningTrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.SSLHandshakeException

/**
 * The pin is the only identity this app has for the machine — the certificate is self-signed
 * and the connection is by IP, so there is no CA and no usable hostname. These cases are
 * therefore about a security decision, not a parsing one, and they drive the real
 * [SpkiPinningTrustManager] rather than comparing two strings.
 */
class PinningTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    @Test
    fun `the pin from that machine's own key is accepted`() {
        val key = keyPair().public
        val pin = MobilePairing.fingerprint(key.encoded)

        // No exception means trusted; the JSSE contract has no other success signal.
        SpkiPinningTrustManager(pin).checkServerTrusted(arrayOf(StubCertificate(key)), "ECDHE")
    }

    @Test
    fun `a pin that differs by one bit is refused`() {
        val key = keyPair().public
        val pin = MobilePairing.fingerprint(key.encoded)
        val flipped = flipOneBit(pin)

        assertEquals("flipping a bit must not change the pin's length", pin.length, flipped.length)
        val failure = assertThrows(PinMismatchException::class.java) {
            SpkiPinningTrustManager(flipped).checkServerTrusted(arrayOf(StubCertificate(key)), "ECDHE")
        }
        // The refusal has to name the action, because there is no override to offer.
        assertTrue(failure.message!!.contains("Pair this machine again"))
    }

    @Test
    fun `a different key with a valid-looking pin is refused`() {
        val pinned = MobilePairing.fingerprint(keyPair().public.encoded)
        val impostor = keyPair().public

        assertThrows(PinMismatchException::class.java) {
            SpkiPinningTrustManager(pinned).checkServerTrusted(arrayOf(StubCertificate(impostor)), "ECDHE")
        }
    }

    @Test
    fun `an empty chain is refused rather than treated as unpinned`() {
        val pin = MobilePairing.fingerprint(keyPair().public.encoded)
        assertThrows(PinMismatchException::class.java) {
            SpkiPinningTrustManager(pin).checkServerTrusted(emptyArray(), "ECDHE")
        }
        assertThrows(PinMismatchException::class.java) {
            SpkiPinningTrustManager(pin).checkServerTrusted(null, "ECDHE")
        }
    }

    @Test
    fun `a pin failure is still recognisable after the handshake wraps it`() {
        val inner = PinMismatchException("nope")
        val wrapped = SSLHandshakeException("handshake failed").apply { initCause(inner) }

        assertEquals(inner, Pinning.pinFailure(wrapped))
        assertNotNull(Pinning.pinFailure(RuntimeException("outer", wrapped)))
        assertNull(Pinning.pinFailure(RuntimeException("unrelated")))
        assertNull(Pinning.pinFailure(null))
    }

    /** base64url, no padding — flip the lowest bit of the first decoded byte. */
    private fun flipOneBit(fingerprint: String): String {
        val bytes = Base64.getUrlDecoder().decode(fingerprint)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * The trust manager reads exactly one thing from the chain — the leaf's public key — so a
 * stub carrying a real key exercises the whole decision without needing a signing library
 * to mint a certificate.
 */
private class StubCertificate(private val key: PublicKey) : X509Certificate() {
    override fun getPublicKey(): PublicKey = key
    override fun getEncoded(): ByteArray = key.encoded
    override fun toString(): String = "stub"
    override fun verify(k: PublicKey?) = Unit
    override fun verify(k: PublicKey?, provider: String?) = Unit
    override fun checkValidity() = Unit
    override fun checkValidity(date: Date?) = Unit
    override fun getVersion(): Int = 3
    override fun getSerialNumber(): BigInteger = BigInteger.ONE
    override fun getIssuerDN(): Principal = Principal { "CN=stub" }
    override fun getSubjectDN(): Principal = Principal { "CN=stub" }
    override fun getNotBefore(): Date = Date(0)
    override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
    override fun getTBSCertificate(): ByteArray = ByteArray(0)
    override fun getSignature(): ByteArray = ByteArray(0)
    override fun getSigAlgName(): String = "SHA256withECDSA"
    override fun getSigAlgOID(): String = "1.2.840.10045.4.3.2"
    override fun getSigAlgParams(): ByteArray? = null
    override fun getIssuerUniqueID(): BooleanArray? = null
    override fun getSubjectUniqueID(): BooleanArray? = null
    override fun getKeyUsage(): BooleanArray? = null
    override fun getBasicConstraints(): Int = -1
    override fun hasUnsupportedCriticalExtension(): Boolean = false
    override fun getCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
    override fun getNonCriticalExtensionOIDs(): MutableSet<String> = mutableSetOf()
    override fun getExtensionValue(oid: String?): ByteArray? = null
}
