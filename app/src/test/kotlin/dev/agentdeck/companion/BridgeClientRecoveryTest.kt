package dev.agentdeck.companion

import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeDeliveryUncertain
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class BridgeClientRecoveryTest {
    private open class Connection(url: URL) : HttpsURLConnection(url) {
        var disconnected = false
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = "{}".byteInputStream()
        override fun getOutputStream() = output
    }

    private fun client(open: (URL) -> HttpsURLConnection) = BridgeClient(
        listOf("lan.test", "remote.test"), 8443, "00", "token", open,
    )

    @Test fun `lost POST confirmation never replays command on another host`() {
        val hosts = mutableListOf<String>()
        lateinit var sent: Connection
        val bridge = client { url ->
            hosts += url.host
            object : Connection(url) {
                override fun getResponseCode(): Int = throw IOException("reply lost")
            }.also { sent = it }
        }
        try {
            bridge.unpair()
            fail("lost confirmation must be explicit")
        } catch (_: BridgeDeliveryUncertain) {
            assertEquals(listOf("lan.test"), hosts)
            assertTrue("fixture must have submitted actual request bytes", sent.output.size() > 0)
            assertTrue(sent.disconnected)
        }
    }

    @Test fun `POST may fall back before any request body can be sent`() {
        val hosts = mutableListOf<String>()
        val bridge = client { url ->
            hosts += url.host
            if (url.host == "lan.test") object : Connection(url) {
                override fun getOutputStream(): ByteArrayOutputStream = throw IOException("connect failed")
            } else Connection(url)
        }
        bridge.unpair()
        assertEquals(listOf("lan.test", "remote.test"), hosts)
    }

    @Test fun `failed established stream returns to recovery instead of silently changing hosts`() {
        val hosts = mutableListOf<String>()
        val bridge = client { url ->
            hosts += url.host
            object : Connection(url) {
                override fun getInputStream(): InputStream = throw IOException("stream lost")
            }
        }
        try {
            bridge.stream { true }
            fail("stream failure must reach the reconnect supervisor")
        } catch (_: IOException) {
            assertEquals(listOf("lan.test"), hosts)
        }
    }

    @Test fun `close releases a blocked SSE read without dialling another host`() {
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val hosts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bridge = client { url ->
            hosts += url.host
            object : Connection(url) {
                override fun getInputStream() = object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
                        throw IOException("closed")
                    }
                }
                override fun disconnect() { disconnected.countDown() }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit<Boolean> {
                try { bridge.stream { true }; false } catch (_: CancellationException) { true }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            assertEquals("the active stream host is known before its infinite read returns", "lan.test", bridge.lastGoodHost)
            bridge.close()
            assertTrue(task.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("lan.test"), hosts)
        } finally { bridge.close(); executor.shutdownNow() }
    }
}
