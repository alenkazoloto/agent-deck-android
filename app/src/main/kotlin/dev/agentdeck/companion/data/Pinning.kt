package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobilePairing
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The pinned certificate did not match. This is a **hard refusal**: there is no override and
 * no "proceed anyway", because on a LAN the key pin is the only thing standing between the
 * user and an interceptor. It is its own type so the UI can say "re-pair this machine"
 * instead of showing it as an ordinary network error.
 */
class PinMismatchException(message: String) : IOException(message)

/**
 * Accepts a chain only when SHA-256 of the leaf's SubjectPublicKeyInfo equals the pin the
 * pairing payload carried.
 *
 * The bridge serves a self-signed certificate and the phone reaches it by IP, so neither a
 * CA nor a hostname can identify it. The public key can, and pinning the SPKI rather than
 * the whole certificate means the machine may re-issue its certificate — on expiry, on a
 * name change — without breaking every paired phone.
 */
class SpkiPinningTrustManager(private val pinnedFingerprint: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw PinMismatchException("This machine presented no certificate.")
        val presented = MobilePairing.fingerprint(leaf.publicKey.encoded)
        // The plugin's own compare: SHA-256 both sides, then MessageDigest.isEqual, so
        // neither a length difference nor a shared prefix can short-circuit it.
        if (!MobilePairing.constantTimeEquals(presented, pinnedFingerprint)) {
            throw PinMismatchException(
                "This machine's certificate does not match the one you paired with. " +
                    "Pair this machine again.",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Agent Deck is never a TLS server.")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object Pinning {

    fun socketFactory(pinnedFingerprint: String): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(SpkiPinningTrustManager(pinnedFingerprint)), SecureRandom())
        return context.socketFactory
    }

    /**
     * Always true — deliberately. The bridge's certificate carries the machine's hostname
     * while the phone dials an IP or an overlay address, so a name check would refuse every
     * correct connection. Identity is [SpkiPinningTrustManager]'s job, and it is strictly
     * stronger than a name.
     */
    val hostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

    /**
     * A pin failure surfaces wrapped in whatever the handshake threw, so callers unwrap
     * rather than matching on the outermost type.
     */
    fun pinFailure(error: Throwable?): PinMismatchException? {
        var cursor = error
        val seen = HashSet<Throwable>()
        while (cursor != null && seen.add(cursor)) {
            if (cursor is PinMismatchException) return cursor
            cursor = cursor.cause
        }
        return null
    }
}
