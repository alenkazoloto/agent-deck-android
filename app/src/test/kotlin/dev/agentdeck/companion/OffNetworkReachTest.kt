package dev.agentdeck.companion

import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.Reachability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * A phone must be able to reach its machine from a network the pairing was not made on.
 *
 * The defect: the address list a QR carried was frozen at pairing, so a phone paired before the
 * relay had a grant (or before the LAN address moved) held only desk addresses, and no later
 * change in the IDE could reach it. The list is now re-read from `/v1/hosts` whenever the phone
 * is in touch and merged by [PairedMachine.learningHosts]; these cases pin the merge and then
 * dial through the result the way cellular would — the LAN address refuses, the relay answers.
 */
class OffNetworkReachTest {

    private fun paired(vararg hosts: String, preferred: String? = null) = PairedMachine(
        machineName = "desk", hosts = hosts.toList(), port = 63350, spkiFingerprint = "00",
        token = "t", deviceId = "d", preferredHost = preferred,
    )

    @Test fun `a phone paired on the LAN alone learns the relay and leads with it`() {
        val before = paired("192.168.1.24", "127.0.0.1")
        assertTrue("the fixture must start out unreachable from cellular", Reachability.isLanOnly(before.hosts))

        val after = before.learningHosts(listOf("bore.pub", "192.168.1.24"))

        assertEquals(listOf("bore.pub", "192.168.1.24", "127.0.0.1"), after.hosts)
        assertFalse(Reachability.isLanOnly(after.hosts))
        assertEquals("bore.pub", after.dialOrder().first())
    }

    @Test fun `a tailnet address is learned the same way`() {
        val after = paired("192.168.1.24").learningHosts(listOf("100.101.102.103", "192.168.1.24"))
        assertFalse(Reachability.isLanOnly(after.hosts))
        assertEquals("100.101.102.103", after.hosts.first())
    }

    @Test fun `an address that travels survives a list that omits it`() {
        // The relay is absent from the machine's list exactly while it reconnects, which is when
        // forgetting it would strand the phone.
        val after = paired("bore.pub", "192.168.1.24").learningHosts(listOf("192.168.1.24"))
        assertTrue("bore.pub" in after.hosts)
    }

    @Test fun `a desk address the machine no longer names is dropped with the memory of it`() {
        val before = paired("192.168.1.24", "bore.pub", preferred = "192.168.1.24")
        val after = before.learningHosts(listOf("bore.pub", "10.0.0.7"))
        assertEquals(listOf("bore.pub", "10.0.0.7"), after.hosts)
        assertEquals("a remembered host that is gone must not stay first in the dial order", null, after.preferredHost)
    }

    @Test fun `an emulator's loopback pairing is kept`() {
        val after = paired("127.0.0.1").learningHosts(listOf("192.168.1.24"))
        assertEquals(listOf("192.168.1.24", "127.0.0.1"), after.hosts)
    }

    @Test fun `a machine that names nothing, or only loopback, changes nothing`() {
        val before = paired("192.168.1.24")
        assertSame(before, before.learningHosts(emptyList()))
        assertSame(before, before.learningHosts(listOf("127.0.0.1", "::1")))
    }

    @Test fun `learning the same list twice is a no-op`() {
        val once = paired("192.168.1.24").learningHosts(listOf("bore.pub", "192.168.1.24"))
        assertSame(once, once.learningHosts(listOf("bore.pub", "192.168.1.24")))
    }

    private open class Connection(url: URL, private val body: String = "{}") : HttpsURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = body.byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }

    /** Cellular in one lambda: nothing on the machine's own network answers, the relay does. */
    private fun offLan(dialled: MutableList<String>, body: String = "{}"): (URL) -> HttpsURLConnection = { url ->
        dialled += url.host
        if (Reachability.of(url.host).survivesNetworkChange) Connection(url, body)
        else object : Connection(url) {
            override fun getResponseCode(): Int = throw IOException("connect timed out")
        }
    }

    @Test fun `from another network a learned relay answers the very first request`() {
        val machine = paired("192.168.1.24").learningHosts(listOf("bore.pub", "192.168.1.24"))
        val dialled = mutableListOf<String>()
        val client = BridgeClient(machine.dialOrder(), machine.port, machine.spkiFingerprint, machine.token, offLan(dialled))

        client.hosts()

        assertEquals("the relay must be dialled before any desk address", listOf("bore.pub"), dialled)
        assertEquals("bore.pub", client.lastGoodHost)
    }

    @Test fun `a phone still holding only desk addresses walks all of them and fails`() {
        // The pre-fix state, kept as the control: it is what the two cases above are an
        // improvement on, and it must stay unreachable or the fixture proves nothing.
        val machine = paired("192.168.1.24", "10.0.0.7")
        val dialled = mutableListOf<String>()
        val client = BridgeClient(machine.dialOrder(), machine.port, machine.spkiFingerprint, machine.token, offLan(dialled))

        try {
            client.hosts()
            org.junit.Assert.fail("a LAN-only pairing must not be reachable from cellular")
        } catch (_: IOException) {
            assertEquals(listOf("192.168.1.24", "10.0.0.7"), dialled)
        }
    }

    @Test fun `hosts decodes the machine's answer and ignores blanks and repeats`() {
        val client = BridgeClient(
            listOf("bore.pub"), 63350, "00", "t",
            offLan(mutableListOf(), """{"hosts":["bore.pub"," ","192.168.1.24","bore.pub"]}"""),
        )
        assertEquals(listOf("bore.pub", "192.168.1.24"), client.hosts().hosts)
    }

}
