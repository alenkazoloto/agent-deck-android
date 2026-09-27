package dev.agentdeck.companion

import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.PinMismatchException
import dev.agentdeck.companion.ui.PairTarget
import dev.agentdeck.companion.ui.missingPairField
import dev.agentdeck.companion.ui.splitPairTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `PairScreen`'s Pair button used to stay disabled with no explanation
 * (docs/img/2026-07-31-mobile-pair-first-run.png). `missingPairField` is the pure decision
 * behind that copy; the `rememberSaveable` half of MU-06 is Compose state this JVM suite has
 * no way to exercise, and is covered by the build and by inspection instead.
 */
class PairFormTest {

    private val validHost = "192.168.1.20"
    private val validPort = "63350"
    private val validCode = "12345678"
    private val validFingerprint = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO-_"

    @Test
    fun `every field present and valid names nothing`() {
        assertNull(missingPairField(validHost, validPort, validCode, validFingerprint))
    }

    @Test
    fun `a blank host is named first, ahead of every other empty field`() {
        assertEquals("Enter the host or IP.", missingPairField("", "", "", ""))
    }

    @Test
    fun `an invalid port is named once the host is filled in`() {
        assertEquals("Enter a valid port.", missingPairField(validHost, "", validCode, validFingerprint))
        assertEquals("Enter a valid port.", missingPairField(validHost, "0", validCode, validFingerprint))
        assertEquals("Enter a valid port.", missingPairField(validHost, "70000", validCode, validFingerprint))
    }

    @Test
    fun `a short code is named once host and port are filled in`() {
        assertEquals(
            "Enter the 8-digit pairing code.",
            missingPairField(validHost, validPort, "123", validFingerprint),
        )
    }

    @Test
    fun `a blank fingerprint is the last thing named`() {
        assertEquals("Enter the certificate fingerprint.", missingPairField(validHost, validPort, validCode, ""))
    }

    /** t3code #4889: a typed fingerprint is checked here, so a typo does not surface as a pin mismatch. */
    @Test
    fun `a fingerprint of the wrong shape is named before the pair is sent`() {
        val shape = "The fingerprint is 43 letters, digits, - and _. Check it against the IDE."
        assertEquals(shape, missingPairField(validHost, validPort, validCode, validFingerprint.dropLast(1)))
        assertEquals(shape, missingPairField(validHost, validPort, validCode, validFingerprint + "A"))
        assertEquals(shape, missingPairField(validHost, validPort, validCode, "AA:BB:CC:DD"))
        assertEquals(shape, missingPairField(validHost, validPort, validCode, validFingerprint.dropLast(1) + "="))
    }

    /** t3code #3995: the IDE's "Address" row is `host:port`; typed into the host field it read as IPv6. */
    @Test
    fun `an address pasted as host and port fills both fields`() {
        assertEquals(PairTarget("192.168.1.20", "63350"), splitPairTarget("192.168.1.20:63350", "1"))
        assertEquals(PairTarget("mac.local", "4444"), splitPairTarget("  mac.local:4444 ", "63350"))
        assertNull(missingPairField("192.168.1.20:63350", "", validCode, validFingerprint))
    }

    @Test
    fun `a pasted scheme and path are dropped`() {
        assertEquals(PairTarget("192.168.1.20", "63350"), splitPairTarget("https://192.168.1.20:63350/v1/pair", "1"))
        assertEquals(PairTarget("mac.local", "63350"), splitPairTarget("HTTPS://mac.local/", "63350"))
    }

    @Test
    fun `a bracketed IPv6 address keeps its colons and gives up only the port`() {
        assertEquals(PairTarget("fe80::1", "63350"), splitPairTarget("[fe80::1]:63350", "1"))
        assertEquals(PairTarget("fe80::1", "63350"), splitPairTarget("[fe80::1]", "63350"))
        assertEquals(PairTarget("fe80::1", "63350"), splitPairTarget("fe80::1", "63350"))
    }

    /** Negative controls: a plain host, or a colon with no port yet, leaves the Port field alone. */
    @Test
    fun `a host with no port in it leaves the port field alone`() {
        assertEquals(PairTarget("192.168.1.20", "63350"), splitPairTarget("192.168.1.20", "63350"))
        assertEquals(PairTarget("192.168.1.20", "63350"), splitPairTarget("192.168.1.20:", "63350"))
        assertEquals(PairTarget("", "63350"), splitPairTarget("https://", "63350"))
        assertEquals("Enter the host or IP.", missingPairField("https://", "63350", validCode, validFingerprint))
    }

    @Test
    fun `a port pasted into the host is validated like a typed one`() {
        assertEquals("Enter a valid port.", missingPairField("192.168.1.20:0", "63350", validCode, validFingerprint))
        assertEquals("Enter a valid port.", missingPairField("192.168.1.20:70000", "63350", validCode, validFingerprint))
    }

    /** While pairing, a pin mismatch is the fingerprint entered — not a machine to "pair again". */
    @Test
    fun `a pin mismatch while pairing blames the fingerprint`() {
        assertEquals(
            "The fingerprint does not match this machine. Check it against the one shown in the IDE.",
            DeckViewModel.pairPinSentence(PinMismatchException("Pair this machine again.")),
        )
        assertNull(DeckViewModel.pairPinSentence(BridgeRefusal(401, "pairing-code-rejected", "wrong code")))
        assertNull(DeckViewModel.pairPinSentence(java.io.IOException("unreachable")))
    }

    /** Negative control: fixing the one named field clears the message instead of another appearing. */
    @Test
    fun `fixing the named field is what clears the message`() {
        assertEquals("Enter the 8-digit pairing code.", missingPairField(validHost, validPort, "", validFingerprint))
        assertNull(missingPairField(validHost, validPort, validCode, validFingerprint))
    }
}
