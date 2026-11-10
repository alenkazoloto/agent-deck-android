package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionMcp
import com.github.claudeagents.core.mobile.MobileSessionMcpServer
import com.github.claudeagents.core.mobile.MobileSessionMcpQuery
import com.github.claudeagents.core.mobile.MobileSessionMcpRequest
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SessionMcpFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * A Claude chat's MCP servers from the phone: the sheet lists what the machine holds for the chat's
 * account, a tick sends the whole selection with that one server flipped, "Use all servers" sends all,
 * and a refusal is the machine's sentence with the list as it now stands. A phone without the grant is
 * told so in the machine's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionMcpFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<String>()
    @Volatile private var on = mutableSetOf("alpha", "beta", "gamma")
    @Volatile private var narrowed = false
    @Volatile private var refuseWith: MobileRefusal? = null
    @Volatile private var removedOnMachine: String? = null
    @Volatile private var generation = 0L

    private val flow = SessionMcpFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the chat and reads its servers with the ones it uses`() {
        flow.open(KEY, "Fix the parser")

        assertEquals("Fix the parser", flow.sheet.value?.title)
        val sheet = eventually { flow.sheet.value?.takeIf { it.servers != null } }
        assertEquals(listOf("alpha", "beta", "gamma"), sheet.servers?.map { it.name })
        assertTrue(sheet.servers.orEmpty().all { it.on })
        assertFalse(sheet.narrowed)
        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `unticking one sends the whole selection without it and a second tick sends both gone`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }

        flow.toggle("beta")

        val first = eventually { flow.sheet.value?.takeIf { !it.busy && it.narrowed } }
        assertEquals(listOf("alpha", "gamma"), first.servers?.filter { it.on }?.map { it.name })
        assertEquals("POST names=alpha,gamma", sent.last())

        flow.toggle("alpha")

        val second = eventually { flow.sheet.value?.takeIf { !it.busy && it.servers?.count { s -> s.on } == 1 } }
        assertEquals("the tick is sent from what the sheet showed, not from a diff", "POST names=gamma", sent.last())
        assertEquals(listOf("gamma"), second.servers?.filter { it.on }?.map { it.name })
    }

    @Test fun `ticking a server back on sends it with the ones already chosen`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }
        flow.toggle("beta")
        eventually { flow.sheet.value?.takeIf { !it.busy && it.narrowed } }

        flow.toggle("beta")

        eventually { flow.sheet.value?.takeIf { !it.busy && it.servers?.all { s -> s.on } == true } }
        assertEquals("POST names=alpha,beta,gamma", sent.last())
    }

    @Test fun `Use all servers sends all and only while the chat is limited`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }
        flow.useAll()
        assertEquals("an untouched chat has nothing to take back", listOf("GET $KEY"), sent)
        flow.toggle("beta")
        eventually { flow.sheet.value?.takeIf { !it.busy && it.narrowed } }

        flow.useAll()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && !it.narrowed } }
        assertEquals("POST all", sent.last())
        assertTrue(sheet.servers.orEmpty().all { it.on })
    }

    @Test fun `a server the machine no longer configures shows its sentence over the list as it stands`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }
        removedOnMachine = "gamma"

        flow.toggle("beta")

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.refused != null } }
        assertEquals("gamma is not a configured MCP server for this chat's account.", sheet.refused)
        assertEquals(listOf("alpha", "beta"), sheet.servers?.map { it.name })
        flow.toggle("alpha")
        eventually { flow.sheet.value?.takeIf { !it.busy && it.refused == null } }
    }

    @Test fun `a tick while a change is in flight, or for an unlisted name, sends nothing`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }

        flow.toggle("no-such-server")

        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `a phone without the grant reads the machine's refusal and no list`() {
        refuseWith = MobileRefusal.PERMISSIONS_DISABLED

        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, sheet.error)
        assertNull(sheet.servers)
    }

    @Test fun `an answer after the sheet moved to another chat paints nothing on it`() {
        flow.open(KEY, "First")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }
        flow.open("claude:/repo:other", "Second")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }

        assertEquals("Second", flow.sheet.value?.title)
        assertEquals("claude:/repo:other", flow.sheet.value?.key)
    }

    @Test fun `a different machine closes the sheet`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.servers != null } }

        flow.forget()

        assertNull(flow.sheet.value)
    }

    private fun <T : Any> eventually(read: () -> T?): T {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) read()?.let { return it }.also { Thread.sleep(10) }
        throw AssertionError("nothing arrived in 5 s")
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private var answer = "{}"
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileSessionMcpQuery.ROUTE) return 404
                val refused = refuseWith
                var key = ""
                var sentence: String? = null
                if (requestMethod == "POST") {
                    val request = MobileSessionMcpRequest.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)
                    key = request.key
                    sent += "POST " + (request.names?.let { "names=" + it.joinToString(",") } ?: "all")
                    if (refused == null) {
                        val gone = removedOnMachine
                        if (gone != null && request.names.orEmpty().contains(gone)) {
                            sentence = "$gone is not a configured MCP server for this chat's account."
                        } else if (request.all) {
                            on = mutableSetOf("alpha", "beta", "gamma"); narrowed = false
                        } else {
                            on = request.names.orEmpty().toMutableSet(); narrowed = on.size != 3
                        }
                    }
                } else {
                    key = java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                    sent += "GET $key"
                }
                if (refused != null) {
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                val names = listOf("alpha", "beta", "gamma").filter { it != removedOnMachine }
                answer = MobileSessionMcp(
                    key, names.map { MobileSessionMcpServer(it, "user", "stdio", "npx", it in on) }, narrowed, refused = sentence,
                ).toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:chat"
    }
}
