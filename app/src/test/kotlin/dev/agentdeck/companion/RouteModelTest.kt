package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileHosts
import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.reach.Route
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.PairedRoute
import dev.agentdeck.companion.data.PairedRoute.Source.LEARNED
import dev.agentdeck.companion.data.PairedRoute.Source.PAIRED
import dev.agentdeck.companion.data.PinMismatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

/**
 * The phone's half of R1's route model: what an old persisted pairing becomes, what a pairing
 * link with and without `routes` becomes, how `/v1/hosts` replaces learned routes while the
 * link's stay, and that the client dials each route on its own port.
 */
class RouteModelTest {

    private fun lan(host: String, port: Int = 63350) = Route(Route.LAN, host, port)

    private fun machine(routes: List<PairedRoute>?, vararg hosts: String = arrayOf("192.168.1.24")) = PairedMachine(
        machineName = "desk", hosts = hosts.toList(), port = 63350, spkiFingerprint = "00",
        token = "t", deviceId = "d", explicitRoutes = routes,
    )

    private fun stored(json: String): PairedMachine = requireNotNull(PairedMachine.fromJson(MobileProtocol.parseObject(json)!!))

    // ---- migration -------------------------------------------------------------------

    @Test fun `an entry persisted before routes migrates to paired routes on its pairing port`() {
        val old = stored(
            """{"v":1,"machine":"desk","hosts":["192.168.1.24","100.101.1.2","bore.pub","example.dev","127.0.0.1"],
               "port":63350,"spki":"00","token":"t","deviceId":"d","preferredHost":"100.101.1.2"}""",
        )
        assertEquals(
            listOf(
                PairedRoute(Route(Route.LAN, "192.168.1.24", 63350), PAIRED),
                PairedRoute(Route(Route.TAILNET, "100.101.1.2", 63350), PAIRED),
                PairedRoute(Route(Route.RELAY, "bore.pub", 63350), PAIRED),
                PairedRoute(Route(Route.CUSTOM, "example.dev", 63350), PAIRED),
                PairedRoute(Route(Route.LAN, "127.0.0.1", 63350), PAIRED),
            ),
            old.routes,
        )
        assertEquals(Route(Route.TAILNET, "100.101.1.2", 63350), old.preferredRoute)
        assertEquals("the remembered route still leads", "100.101.1.2", old.dialOrder().first().host)
    }

    @Test fun `a fixture copied onto new hosts without routes still derives them`() {
        val moved = machine(null).copy(hosts = listOf("127.0.0.1"), port = 4000)
        assertEquals(listOf(Route(Route.LAN, "127.0.0.1", 4000)), moved.dialOrder())
    }

    // ---- persistence -----------------------------------------------------------------

    @Test fun `toJson writes routes and the legacy hosts and port, and reads back equal`() {
        val relay = Route(Route.RELAY, "k3x.relay.example", 443)
        val before = machine(listOf(PairedRoute(lan("192.168.1.24"), PAIRED), PairedRoute(relay, LEARNED)))
            .copy(preferredRoute = relay)
        val json = before.toJson()

        assertEquals(listOf("192.168.1.24"), json.getAsJsonArray("hosts").map { it.asString })
        assertEquals(63350, json.get("port").asInt)
        assertEquals(2, json.getAsJsonArray("routes").size())
        assertEquals("learned", json.getAsJsonArray("routes")[1].asJsonObject.get("source").asString)
        assertNull("an old app would dial the relay on the bridge port", json.get("preferredHost"))
        assertEquals(before, PairedMachine.fromJson(json))
    }

    @Test fun `a migrated entry saved once keeps the same routes`() {
        val old = machine(null, "192.168.1.24", "bore.pub")
        val reread = requireNotNull(PairedMachine.fromJson(old.toJson()))
        assertEquals(old.routes, reread.routes)
        assertEquals(old.routes, reread.explicitRoutes)
    }

    // ---- pairing ---------------------------------------------------------------------

    private fun pairedFrom(payload: MobilePairingPayload) = PairedMachine.paired(
        "desk", payload.hosts, payload.port, PairedRoute.linkRoutes(payload), "00", "t", "d", null,
    )

    @Test fun `a pairing link with routes pairs over them and the hosts they do not repeat`() {
        // The link carries only what hosts + port cannot say: here, the relay's own port.
        val relay = Route(Route.RELAY, "bore.pub", 41234)
        val payload = requireNotNull(MobilePairingPayload.decode(
            """{"v":1,"hosts":["bore.pub","192.168.1.24","100.101.1.2","127.0.0.1"],"port":63350,"spki":"00","code":"c",
               "routes":[{"kind":"relay","host":"bore.pub","port":41234}]}""",
        ))
        val paired = pairedFrom(payload)
        assertEquals(
            listOf(lan("192.168.1.24"), Route(Route.TAILNET, "100.101.1.2", 63350), relay, lan("127.0.0.1")),
            paired.dialOrder(),
        )
        assertTrue(paired.routes.all { it.source == PAIRED })
        assertEquals("legacy hosts are the link's own", payload.hosts, paired.hosts)
    }

    @Test fun `a pairing link without routes migrates its hosts on its port`() {
        val payload = MobilePairingPayload(hosts = listOf("bore.pub", "192.168.1.24"), port = 63350, spkiFingerprint = "00", code = "c", machineName = "desk")
        val paired = pairedFrom(payload)
        assertEquals(listOf(Route(Route.RELAY, "bore.pub", 63350), lan("192.168.1.24")), paired.dialOrder())
        assertTrue(paired.routes.all { it.source == PAIRED })
        assertEquals("an old link keeps today's dial order", machine(null, "bore.pub", "192.168.1.24").dialOrder(), paired.dialOrder())
    }

    // ---- learning --------------------------------------------------------------------

    @Test fun `hosts naming routes replace learned routes and keep paired ones`() {
        val paired = PairedRoute(lan("192.168.1.24"), PAIRED)
        val staleLearned = PairedRoute(lan("10.0.0.7"), LEARNED)
        val tailnet = Route(Route.TAILNET, "100.101.1.2", 63350)
        val before = machine(listOf(paired, staleLearned)).copy(preferredRoute = lan("10.0.0.7"))

        val after = before.learning(MobileHosts(hosts = listOf("100.101.1.2"), routes = listOf(tailnet)))

        assertEquals(listOf(PairedRoute(tailnet, LEARNED), paired), after.routes)
        assertNull("a learned route that is gone must not stay first", after.preferredRoute)
    }

    @Test fun `a paired route the host also names stays paired`() {
        val before = machine(listOf(PairedRoute(lan("192.168.1.24"), PAIRED)))
        val relay = Route(Route.RELAY, "k3x.relay.example", 443)
        val once = before.learningRoutes(listOf(lan("192.168.1.24"), relay))
        assertEquals(listOf(PairedRoute(lan("192.168.1.24"), PAIRED), PairedRoute(relay, LEARNED)), once.routes)
        // The relay vanishing from the host's list drops only what was learned.
        val twice = once.learningRoutes(listOf(lan("192.168.1.24")))
        assertEquals(listOf(PairedRoute(lan("192.168.1.24"), PAIRED)), twice.routes)
        assertSame(twice, twice.learningRoutes(listOf(lan("192.168.1.24"))))
    }

    @Test fun `routes the host names never include loopback, and naming nothing changes nothing`() {
        val before = machine(listOf(PairedRoute(lan("127.0.0.1"), PAIRED)))
        assertSame(before, before.learningRoutes(listOf(lan("127.0.0.1"))))
        assertSame(before, before.learningRoutes(emptyList()))
    }

    @Test fun `an old host without routes falls back to its hosts on the pairing port`() {
        val after = machine(null, "192.168.1.24").learning(MobileHosts(hosts = listOf("bore.pub", "192.168.1.24")))
        assertEquals(
            listOf(PairedRoute(Route(Route.RELAY, "bore.pub", 63350), LEARNED), PairedRoute(lan("192.168.1.24"), PAIRED)),
            after.routes,
        )
        assertEquals(listOf("bore.pub", "192.168.1.24"), after.hosts)
    }

    // ---- dialing ---------------------------------------------------------------------

    private open class Connection(url: URL) : HttpsURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = """{"hosts":[]}""".byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }

    @Test fun `each route is dialled on its own port`() {
        val dialled = mutableListOf<String>()
        val routes = listOf(lan("192.168.1.24"), Route(Route.RELAY, "k3x.relay.example", 443))
        val client = BridgeClient(routes, "00", "t") { url ->
            dialled += "${url.host}:${url.port}"
            if (url.host == "192.168.1.24") object : Connection(url) {
                override fun getResponseCode(): Int = throw IOException("connect timed out")
            } else Connection(url)
        }

        client.hosts()

        assertEquals(listOf("192.168.1.24:63350", "k3x.relay.example:443"), dialled)
        assertEquals(routes[1], client.lastGoodRoute)
        assertEquals("k3x.relay.example", client.lastGoodHost)
    }

    @Test fun `a pin mismatch on the first route aborts the walk`() {
        val dialled = mutableListOf<String>()
        val client = BridgeClient(listOf(lan("192.168.1.24"), Route(Route.RELAY, "k3x.relay.example", 443)), "00", "t") { url ->
            dialled += url.host
            object : Connection(url) {
                override fun getResponseCode(): Int =
                    throw SSLHandshakeException("handshake").apply { initCause(PinMismatchException("wrong key")) }
            }
        }
        try {
            client.hosts()
            fail("a pin mismatch must surface")
        } catch (e: IOException) {
            assertTrue(e is PinMismatchException)
        }
        assertEquals("no other route is tried after a pin mismatch", listOf("192.168.1.24"), dialled)
        assertNull(client.lastGoodRoute)
    }
}
