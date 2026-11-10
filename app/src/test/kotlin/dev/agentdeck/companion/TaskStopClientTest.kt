package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileTaskStopQuery
import com.github.claudeagents.core.mobile.MobileTaskStopRequest
import com.github.claudeagents.core.mobile.MobileTaskStopped
import com.google.gson.JsonParser
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/**
 * The phone's Stop on a background task, on the wire: the chat's key and the task's id go out as the
 * one request, the CLI's own refusal comes back as a sentence with the task still running, and a run
 * with no channel is the machine's refusal of the call — never an accepted-looking answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskStopClientTest {

    private var sent: String? = null
    private var reply = MobileTaskStopped().toJson().toString()
    private var status = 200

    @Test fun `the key and the task id are sent and a stop reads as stopped`() {
        val answer = bridge().stopTask(MobileTaskStopRequest("claude:/repo:chat", "a1"))

        assertTrue(answer.stopped)
        assertNull(answer.refused)
        val body = JsonParser.parseString(sent).asJsonObject
        assertEquals("claude:/repo:chat", body.get("key").asString)
        assertEquals("a1", body.get("taskId").asString)
    }

    @Test fun `the CLI's refusal is the sentence and the task is not called stopped`() {
        reply = MobileTaskStopped(refused = "Task a1 belongs to another session").toJson().toString()

        val answer = bridge().stopTask(MobileTaskStopRequest("k", "a1"))

        assertFalse(answer.stopped)
        assertEquals("Task a1 belongs to another session", answer.refused)
    }

    @Test fun `a run with no channel is the machine's refusal of the call, in its words`() {
        status = MobileRefusal.TASK_NOT_STOPPABLE.status
        reply = """{"v":1,"error":"${MobileRefusal.TASK_NOT_STOPPABLE.code}","message":"${MobileRefusal.TASK_NOT_STOPPABLE.message}"}"""

        try {
            bridge().stopTask(MobileTaskStopRequest("k", "a1"))
            fail("a refused stop read as an answer")
        } catch (refusal: BridgeRefusal) {
            assertEquals(MobileRefusal.TASK_NOT_STOPPABLE.code, refusal.code)
            assertEquals(MobileRefusal.TASK_NOT_STOPPABLE.message, refusal.message)
        }
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileTaskStopQuery.ROUTE) return 404
                sent = body.toString(Charsets.UTF_8)
                return status
            }
            override fun getInputStream(): InputStream = reply.byteInputStream()
            override fun getErrorStream(): InputStream = reply.byteInputStream()
        }
    }
}
