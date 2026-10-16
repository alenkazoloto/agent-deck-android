package dev.agentdeck.companion.push

import com.github.claudeagents.core.mobile.MobilePush
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * This phone's Web Push identity, and the half of RFC 8291 that *reads* a message.
 *
 * **Why the phone owns the keys.** The endpoint a distributor hands out is a capability URL —
 * anyone holding it can post to this device. What makes that acceptable is that the *content* is
 * encrypted to a keypair generated here and sent nowhere but to the paired machine, so the relay
 * in the middle forwards bytes it cannot read. Decision **G4** chose a user-picked,
 * self-hostable distributor for exactly that reason; keys minted anywhere else would undo it.
 *
 * **Why the decrypt is written out rather than taken from a library.** `mobile/` follows the
 * dependency rule in `ArchitectureRulesTest` rule 23, and this is four JDK primitives — ECDH
 * P-256, HMAC-SHA-256, HKDF and AES-GCM — all present on Android since API 26. The only
 * hand-written part is the derivation *order*, which is the same order the shared protocol's
 * `WebPushEncrypt` runs forwards and which RFC 8291's published vector pins on that side.
 * `WebPushKeysTest` decrypts a record produced by that encoder, so a step changed here fails
 * against the encoder rather than against a copy of it.
 */
object WebPushKeys {

    private const val CURVE = "secp256r1"

    /** RFC 8291 §3.3 / RFC 8188 §2.2 — NUL-terminated; the CEK/nonce infos carry HKDF's counter. */
    private val KEY_INFO_PREFIX = "WebPush: info".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    private val CEK_INFO = "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1)
    private val NONCE_INFO = "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 1)

    private val random = SecureRandom()

    /** A subscription's secrets, as this phone holds them. */
    class Identity(val keys: KeyPair, val authSecret: ByteArray) {

        val publicPoint: ByteArray = encodePoint(keys.public as ECPublicKey)

        /** The `p256dh` the machine stores, base64url — WebPush's own spelling. */
        val publicKeyB64: String get() = MobilePush.encodeBase64Url(publicPoint)

        val authSecretB64: String get() = MobilePush.encodeBase64Url(authSecret)

        /** PKCS#8 and X.509, which is what a JCE `KeyFactory` can hand back on any host. */
        val privateB64: String get() = MobilePush.encodeBase64Url(keys.private.encoded)
        val publicSpkiB64: String get() = MobilePush.encodeBase64Url(keys.public.encoded)
    }

    fun generate(): Identity = Identity(
        keys = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(CURVE), random) }
            .generateKeyPair(),
        authSecret = ByteArray(MobilePush.AUTH_SECRET_LEN).also(random::nextBytes),
    )

    /**
     * Rebuilds a stored identity, or null when any of the three parts is missing or unusable.
     *
     * All-or-nothing on purpose. A half-restored identity would register a public key the
     * private half cannot open, and the only symptom would be a phone that stays quiet —
     * indistinguishable from having no push at all, which is the state this exists to leave.
     */
    fun restore(privateB64: String?, publicB64: String?, authB64: String?): Identity? = runCatching {
        val privateBytes = privateB64?.let(MobilePush::decodeBase64Url) ?: return null
        val publicBytes = publicB64?.let(MobilePush::decodeBase64Url) ?: return null
        val auth = authB64?.let(MobilePush::decodeBase64Url) ?: return null
        if (auth.size != MobilePush.AUTH_SECRET_LEN) return null
        val factory = KeyFactory.getInstance("EC")
        Identity(
            keys = KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicBytes)),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
            ),
            authSecret = auth,
        )
    }.getOrNull()

    /**
     * Reads one `aes128gcm` record — `salt(16) || rs(4) || idlen(1) || sender-key || ciphertext`.
     *
     * Returns null rather than throwing. The caller is a `BroadcastReceiver` on the main thread
     * with a few seconds of budget, and a message from a subscription this phone no longer holds
     * the key for — the machine re-paired, the app's data was cleared — is an ordinary event.
     */
    fun decrypt(identity: Identity, record: ByteArray): ByteArray? = runCatching {
        if (record.size < 22) return null
        val salt = record.copyOfRange(0, 16)
        val idLen = record[20].toInt() and 0xFF
        if (idLen != MobilePush.PUBLIC_KEY_LEN || record.size <= 21 + idLen) return null
        val senderPublic = record.copyOfRange(21, 21 + idLen)
        val ciphertext = record.copyOfRange(21 + idLen, record.size)
        val shared = KeyAgreement.getInstance("ECDH").apply {
            init(identity.keys.private)
            doPhase(decodePoint(senderPublic, identity), true)
        }.generateSecret()
        val keyInfo = KEY_INFO_PREFIX + identity.publicPoint + senderPublic + byteArrayOf(0x01)
        val prk = hmac(salt, hmac(hmac(identity.authSecret, shared), keyInfo))
        val cek = hmac(prk, CEK_INFO).copyOf(16)
        val nonce = hmac(prk, NONCE_INFO).copyOf(12)
        val padded = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(ciphertext)
        // RFC 8188 §2's delimiter: 0x02 marks the last record.
        padded.dropLast(1).toByteArray()
    }.getOrNull()

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    fun encodePoint(key: ECPublicKey): ByteArray {
        val length = (key.params.curve.field.fieldSize + 7) / 8
        return byteArrayOf(0x04) +
            unsignedFixed(key.w.affineX, length) +
            unsignedFixed(key.w.affineY, length)
    }

    /**
     * The sender's ephemeral point, on the curve this identity already names.
     *
     * The parameters come from the phone's *own* key rather than from a fresh
     * `AlgorithmParameters` lookup: they are by definition the same curve, and reading them off
     * the key means there is one place a curve is chosen on this side.
     */
    private fun decodePoint(point: ByteArray, identity: Identity): ECPublicKey {
        val half = (point.size - 1) / 2
        val x = BigInteger(1, point.copyOfRange(1, 1 + half))
        val y = BigInteger(1, point.copyOfRange(1 + half, point.size))
        val params = (identity.keys.public as ECPublicKey).params
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    private fun unsignedFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val body = if (raw.size > length) raw.copyOfRange(raw.size - length, raw.size) else raw
        return ByteArray((length - body.size).coerceAtLeast(0)) + body
    }
}
