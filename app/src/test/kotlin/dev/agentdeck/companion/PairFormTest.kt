package dev.agentdeck.companion

import dev.agentdeck.companion.ui.missingPairField
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
    private val validFingerprint = "AA:BB:CC:DD"

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

    /** Negative control: fixing the one named field clears the message instead of another appearing. */
    @Test
    fun `fixing the named field is what clears the message`() {
        assertEquals("Enter the 8-digit pairing code.", missingPairField(validHost, validPort, "", validFingerprint))
        assertNull(missingPairField(validHost, validPort, validCode, validFingerprint))
    }
}
