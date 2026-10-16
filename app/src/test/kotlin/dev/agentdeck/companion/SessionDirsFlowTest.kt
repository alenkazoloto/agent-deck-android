package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionDirs
import com.github.claudeagents.core.mobile.MobileSessionDirsQuery
import com.github.claudeagents.core.mobile.MobileSessionDirsRequest
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SessionDirsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * A chat's working directories from the phone: the sheet lists what the machine holds, a typed path
 * is sent as an add and a listed one as a remove, and the reader's text survives everything but the
 * machine taking it. A refusal is the machine's sentence with the list unchanged; a phone without the
 * grant is told so in the machine's words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDirsFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<String>()
    @Volatile private var held = listOf("/work/shared")
    @Volatile private var refuseWith: MobileRefusal? = null
    @Volatile private var refusePath: String? = null
    @Volatile private var generation = 0L

    private val flow = SessionDirsFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the chat, reads its directories and starts with an empty field`() {
        flow.open(KEY, "Fix the parser")

        assertEquals("Fix the parser", flow.sheet.value?.title)
        val sheet = eventually { flow.sheet.value?.takeIf { it.dirs != null } }
        assertEquals(listOf("/work/shared"), sheet.dirs)
        assertEquals("", sheet.draft)
        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `a typed path is sent as an add, listed, and clears the field once the machine took it`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }

        flow.edit("  /work/docs ")
        flow.add()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.dirs?.size == 2 } }
        assertEquals(listOf("/work/shared", "/work/docs"), sheet.dirs)
        assertEquals("", sheet.draft)
        assertNull(sheet.refused)
        assertEquals("the path is trimmed and sent as the one change", "POST add=/work/docs", sent.last())
    }

    @Test fun `a listed directory is removed`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }

        flow.remove("/work/shared")

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.dirs?.isEmpty() == true } }
        assertEquals(emptyList<String>(), sheet.dirs)
        assertEquals("POST remove=/work/shared", sent.last())
    }

    @Test fun `removing one keeps the path the reader is still typing`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }
        flow.edit("/work/half-typ")

        flow.remove("/work/shared")

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.dirs?.isEmpty() == true } }
        assertEquals("/work/half-typ", sheet.draft)
    }

    @Test fun `a path the desk refuses keeps the text and the list and shows the desk's sentence`() {
        refusePath = "/work/typo"
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }
        flow.edit("/work/typo")

        flow.add()

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.refused != null } }
        assertEquals("/work/typo is not a directory.", sheet.refused)
        assertEquals("/work/typo", sheet.draft)
        assertEquals(listOf("/work/shared"), sheet.dirs)
        flow.edit("/work/typo2")
        assertNull("the next edit takes the sentence away", flow.sheet.value?.refused)
    }

    @Test fun `a blank field sends nothing`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }
        flow.edit("   ")

        flow.add()

        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `a phone without the grant reads the machine's refusal and no list`() {
        refuseWith = MobileRefusal.PERMISSIONS_DISABLED

        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, sheet.error)
        assertNull(sheet.dirs)
    }

    @Test fun `a typed path survives closing and reopening the sheet, and a different machine drops it`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }
        flow.edit("/work/unsent")
        flow.dismiss()
        assertNull(flow.sheet.value)

        flow.open(KEY, "Fix the parser")
        assertEquals("/work/unsent", flow.sheet.value?.draft)
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }

        flow.forget()
        assertNull(flow.sheet.value)
        flow.open(KEY, "Fix the parser")
        assertEquals("", flow.sheet.value?.draft)
    }

    @Test fun `an answer after the sheet moved to another chat paints nothing on it`() {
        flow.open(KEY, "First")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }
        flow.open("claude:/repo:other", "Second")
        eventually { flow.sheet.value?.takeIf { it.dirs != null } }

        assertEquals("Second", flow.sheet.value?.title)
        assertEquals("claude:/repo:other", flow.sheet.value?.key)
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
                if (url.path != MobileSessionDirsQuery.ROUTE) return 404
                val refused = refuseWith
                val key: String
                if (requestMethod == "POST") {
                    val request = MobileSessionDirsRequest.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)
                    key = request.key
                    sent += "POST " + (request.add?.let { "add=$it" } ?: "remove=${request.remove}")
                    if (refused == null) {
                        request.add?.takeIf { it == refusePath }?.let {
                            answer = MobileSessionDirs(key, held, 16, refused = "$it is not a directory.").toJson().toString()
                            return 200
                        }
                        request.add?.let { held = held + it }
                        request.remove?.let { held = held - it }
                    }
                } else {
                    key = java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                    sent += "GET $key"
                }
                if (refused != null) {
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                answer = MobileSessionDirs(key, held, 16).toJson().toString()
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
