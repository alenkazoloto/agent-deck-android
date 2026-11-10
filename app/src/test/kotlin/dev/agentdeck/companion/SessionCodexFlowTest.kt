package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileSessionCodex
import com.github.claudeagents.core.mobile.MobileSessionCodexControl
import com.github.claudeagents.core.mobile.MobileSessionCodexOption
import com.github.claudeagents.core.mobile.MobileSessionCodexQuery
import com.github.claudeagents.core.mobile.MobileSessionCodexRequest
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SessionCodexFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A Codex chat's communication style, web-search mode and config profile from the phone: the dialog lists
 * what the machine holds, a choice sends exactly that one control's value, and the machine's sentence for a
 * refusal or a phone without the grant is shown with the controls as they now stand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionCodexFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = CopyOnWriteArrayList<String>()
    private val held = mutableMapOf("personality" to "", "web-search" to "", "profile" to "")
    @Volatile private var refuseWith: MobileRefusal? = null
    @Volatile private var sentence: String? = null
    @Volatile private var generation = 0L

    private val flow = SessionCodexFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the dialog opens on the chat and reads its three controls`() {
        flow.open(KEY, "Fix the parser")

        assertEquals("Fix the parser", flow.sheet.value?.title)
        val sheet = eventually { flow.sheet.value?.takeIf { it.controls != null } }
        assertEquals(listOf("personality", "web-search", "profile"), sheet.controls?.map { it.id })
        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `a choice sends only that control's value and the answer redraws all three`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }

        flow.choose("web-search", "cached")

        val sheet = eventually { flow.sheet.value?.takeIf { !it.busy && it.controls?.first { c -> c.id == "web-search" }?.chosen == "cached" } }
        assertEquals("POST web-search=cached", sent.last())
        assertEquals("", sheet.controls?.first { it.id == "personality" }?.chosen)
    }

    @Test fun `handing a control back to Codex sends the empty value`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }
        flow.choose("personality", "friendly")
        eventually { flow.sheet.value?.takeIf { !it.busy && it.controls?.first { c -> c.id == "personality" }?.chosen == "friendly" } }

        flow.choose("personality", "")

        eventually { flow.sheet.value?.takeIf { !it.busy && it.controls?.first { c -> c.id == "personality" }?.chosen == "" } }
        assertEquals("POST personality=", sent.last())
    }

    @Test fun `the choice already held, an unlisted value and an unlisted control send nothing`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }

        flow.choose("personality", "")
        flow.choose("personality", "sarcastic")
        flow.choose("temperature", "high")

        assertEquals(listOf("GET $KEY"), sent)
    }

    @Test fun `the machine's refusal shows over the controls as they stand and the next change clears it`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }
        sentence = "archive is not a config profile Codex has."

        flow.choose("profile", "work")

        val refused = eventually { flow.sheet.value?.takeIf { !it.busy && it.refused != null } }
        assertEquals("archive is not a config profile Codex has.", refused.refused)
        assertEquals("", refused.controls?.first { it.id == "profile" }?.chosen)
        sentence = null
        flow.choose("personality", "pragmatic")
        eventually { flow.sheet.value?.takeIf { !it.busy && it.refused == null && it.controls?.first { c -> c.id == "personality" }?.chosen == "pragmatic" } }
    }

    @Test fun `a phone without the grant reads the machine's refusal and no controls`() {
        refuseWith = MobileRefusal.PERMISSIONS_DISABLED

        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.PERMISSIONS_DISABLED.message, sheet.error)
        assertNull(sheet.controls)
    }

    @Test fun `an answer after the dialog moved to another chat paints nothing on it`() {
        flow.open(KEY, "First")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }
        flow.open("codex:/repo:other", "Second")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }

        assertEquals("Second", flow.sheet.value?.title)
        assertEquals("codex:/repo:other", flow.sheet.value?.key)
    }

    @Test fun `a different machine closes the dialog`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.controls != null } }

        flow.forget()

        assertNull(flow.sheet.value)
    }

    @Test fun `the notes and the desk-only marks the machine sent are kept for the dialog`() {
        flow.open(KEY, "Fix the parser")
        val sheet = eventually { flow.sheet.value?.takeIf { it.controls != null } }

        val web = sheet.controls!!.first { it.id == "web-search" }
        assertEquals("Replies sent from the phone use Codex's default.", web.note)
        assertEquals(listOf("indexed", "live"), web.options.filter { it.widens }.map { it.value })
        assertTrue(web.options.filter { it.widens }.none { it.phoneReplies })
    }

    private fun <T : Any> eventually(read: () -> T?): T {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) read()?.let { return it }.also { Thread.sleep(10) }
        throw AssertionError("nothing arrived in 5 s")
    }

    private fun controls(): List<MobileSessionCodexControl> = listOf(
        MobileSessionCodexControl(
            "personality", "Communication Style", held.getValue("personality"),
            listOf("", "none", "friendly", "pragmatic").map { MobileSessionCodexOption(it, it.ifEmpty { "Codex default" }, "") },
            "Replies sent from the phone use Codex's default.",
        ),
        MobileSessionCodexControl(
            "web-search", "Web Search", held.getValue("web-search"),
            listOf(
                MobileSessionCodexOption("", "Codex default", ""),
                MobileSessionCodexOption("indexed", "Indexed and live", "", phoneReplies = false, widens = true),
                MobileSessionCodexOption("live", "Live", "", phoneReplies = false, widens = true),
                MobileSessionCodexOption("cached", "Cached only", ""),
                MobileSessionCodexOption("disabled", "Off", ""),
            ),
            "Replies sent from the phone use Codex's default.",
        ),
        MobileSessionCodexControl(
            "profile", "Config Profile", held.getValue("profile"),
            listOf("", "work").map { MobileSessionCodexOption(it, it.ifEmpty { "No profile" }, "", phoneReplies = it.isEmpty()) },
            "Replies sent from the phone run on config.toml alone.",
        ),
    )

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
                if (url.path != MobileSessionCodexQuery.ROUTE) return 404
                val refused = refuseWith
                var key = ""
                var refusal: String? = null
                if (requestMethod == "POST") {
                    val request = MobileSessionCodexRequest.fromJson(JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonObject)!!
                    key = request.key
                    sent += "POST ${request.control}=${request.value}"
                    if (refused == null) {
                        refusal = sentence
                        if (refusal == null) held[request.control] = request.value
                    }
                } else {
                    key = java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                    sent += "GET $key"
                }
                if (refused != null) {
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                answer = MobileSessionCodex(key, controls(), refused = refusal).toJson().toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "codex:/repo:chat"
    }
}
