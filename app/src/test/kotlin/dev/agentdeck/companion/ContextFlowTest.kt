package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileContextBreakdown
import com.github.claudeagents.core.mobile.MobileContextQuery
import com.github.claudeagents.core.mobile.MobileContextRow
import com.github.claudeagents.core.mobile.MobileRefusal
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.ContextFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * The context grid from the phone (M2 P15): the sheet opens on the chat it names, asks the machine
 * for that chat only, and shows what came back — the grid, or the machine's own refusal. An answer
 * that arrives after the sheet was closed, or reopened on another chat, paints nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val asked = CopyOnWriteArrayList<String>()
    @Volatile private var refuseWith: MobileRefusal? = null
    @Volatile private var hold: CountDownLatch? = null
    @Volatile private var generation = 0L

    private val flow = ContextFlow(scope, { bridge() }, { generation }, { it.message ?: "error" })

    @After fun tearDown() = scope.cancel()

    @Test fun `the sheet opens on the chat's title, asks for that chat and shows the machine's grid`() {
        flow.open(KEY, "Fix the parser")

        assertEquals("Fix the parser", flow.sheet.value?.title)
        val sheet = eventually { flow.sheet.value?.takeIf { it.breakdown != null } }
        assertEquals(listOf(KEY), asked)
        assertEquals("30% full · 60.0K of 200.0K tokens", sheet.breakdown?.headline)
        assertNull(sheet.error)
    }

    @Test fun `a refusal is shown in the machine's words and no grid`() {
        refuseWith = MobileRefusal.CONTEXT_UNAVAILABLE

        flow.open(KEY, "Fix the parser")

        val sheet = eventually { flow.sheet.value?.takeIf { it.error != null } }
        assertEquals(MobileRefusal.CONTEXT_UNAVAILABLE.message, sheet.error)
        assertNull(sheet.breakdown)
    }

    @Test fun `an answer after the sheet was closed paints nothing`() {
        val gate = CountDownLatch(1).also { hold = it }
        flow.open(KEY, "Fix the parser")
        flow.dismiss()

        gate.countDown()
        Thread.sleep(200)

        assertNull(flow.sheet.value)
    }

    @Test fun `a slow answer for one chat never lands on the sheet reopened for another`() {
        val gate = CountDownLatch(1).also { hold = it }
        flow.open(KEY, "First")
        hold = null
        flow.open("claude:/repo:other", "Second")
        val second = eventually { flow.sheet.value?.takeIf { it.breakdown != null } }
        gate.countDown()
        Thread.sleep(200)

        assertEquals("Second", flow.sheet.value?.title)
        assertEquals(second.key, flow.sheet.value?.key)
        assertNotNull(flow.sheet.value?.breakdown)
    }

    @Test fun `a different machine drops the sheet`() {
        flow.open(KEY, "Fix the parser")
        eventually { flow.sheet.value?.takeIf { it.breakdown != null } }

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
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = java.io.ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                if (url.path != MobileContextQuery.ROUTE) return 404
                val key = java.net.URLDecoder.decode(url.query.removePrefix("key="), "UTF-8")
                asked += key
                hold?.await(5, TimeUnit.SECONDS)
                val refused = refuseWith
                if (refused != null) {
                    answer = """{"v":1,"error":"${refused.code}","message":"${refused.message}"}"""
                    return refused.status
                }
                answer = MobileContextBreakdown(
                    key = key,
                    measured = true,
                    headline = "30% full · 60.0K of 200.0K tokens",
                    rows = listOf(MobileContextRow("Messages", "48.0K · 24%", 24)),
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
