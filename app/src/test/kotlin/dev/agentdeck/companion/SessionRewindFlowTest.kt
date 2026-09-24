package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRefusal
import com.github.claudeagents.core.mobile.MobileRewindPoint
import com.github.claudeagents.core.mobile.MobileSessionRewindPoints
import com.github.claudeagents.core.mobile.MobileSessionRewindRequest
import com.github.claudeagents.core.mobile.MobileSessionRewindResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SessionRewindFlow
import dev.agentdeck.companion.ui.scopeNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import javax.net.ssl.HttpsURLConnection

/**
 * Rewind from the phone (M4, P06): the desk's two steps, its per-message scopes, and its order —
 * the conversation goes back first and the code sheet opens only after it has landed. A rewind
 * whose answer never arrived is retried under the same id, so the machine appends one anchor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRewindFlowTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val snacks = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<MobileSessionRewindRequest>()
    private val answers = ArrayDeque<Pair<Int, String>>()
    @Volatile private var points = POINTS
    /** Holds every rewind answer until [gate] opens, so a picker can be reopened mid-flight. */
    @Volatile private var slow = false
    private val gate = java.util.concurrent.CountDownLatch(1)

    private val flow = SessionRewindFlow(
        scope, { bridge() }, { 0L }, { it.message ?: "error" }, { snacks += it },
        rewound = { key, prompt -> events += "rewound $key $prompt" },
        newChat = { key, prompt, open -> events += "new-chat $key $prompt $open" },
        restoreCode = { key, point -> events += "code $key ${point.id}" },
    )

    @After fun tearDown() = scope.cancel()

    @Test fun `a message with several scopes opens the second step, one with a single scope runs it`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }

        flow.choose(SECOND)
        assertEquals(SECOND, flow.picker.value?.chosen)

        flow.back()
        answers += 200 to MobileSessionRewindResult(KEY, true, "Rewound.", promptText = "Talk only").toJson().toString()
        flow.choose(TALK)
        eventually { events.firstOrNull() }
        assertEquals("rewound $KEY Talk only", events.single())
        assertEquals(TALK.id, sent.single().point)
    }

    @Test fun `code and conversation rewinds the conversation first, then opens the code sheet`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(SECOND)
        answers += 200 to MobileSessionRewindResult(KEY, true, "Rewound to before “Second”.", promptText = "Second").toJson().toString()
        flow.run(SECOND, MobileRewindPoint.BOTH)

        eventually { events.takeIf { it.size == 2 } }
        assertEquals(listOf("rewound $KEY Second", "code $KEY p2"), events)
        assertNull(flow.picker.value)
        assertEquals("Rewound to before “Second”.", snacks.single())
    }

    @Test fun `restore code alone writes nothing through the rewind route`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(SECOND)
        flow.run(SECOND, MobileRewindPoint.CODE)

        assertEquals(listOf("code $KEY p2"), events)
        assertTrue(sent.isEmpty())
        assertNull(flow.picker.value)
    }

    @Test fun `a refused conversation half never opens the code sheet`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(SECOND)
        answers += 200 to MobileSessionRewindResult(KEY, false, "Wait for this turn to finish before rewinding.").toJson().toString()
        flow.run(SECOND, MobileRewindPoint.BOTH)

        val failed = eventually { flow.picker.value?.takeIf { it.error != null } }
        assertEquals("Wait for this turn to finish before rewinding.", failed.error)
        assertTrue("files rolled back under a conversation that still remembers them", events.isEmpty())
    }

    @Test fun `the first message continues in a new chat, staying for the code sheet when code goes too`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(FIRST)
        answers += 200 to MobileSessionRewindResult(KEY, false, "continues in a new chat", newChat = true, promptText = "First").toJson().toString()
        flow.run(FIRST, MobileRewindPoint.BOTH)

        eventually { events.takeIf { it.size == 2 } }
        assertEquals(listOf("new-chat $KEY First false", "code $KEY p1"), events)
    }

    @Test fun `an unconfirmed rewind is retried as the same operation, a definite answer retires it`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(SECOND)
        answers += MobileRefusal.REWIND_UNCONFIRMED.status to MobileRefusal.REWIND_UNCONFIRMED.toJson().toString()
        flow.run(SECOND, MobileRewindPoint.CONVERSATION)
        eventually { flow.picker.value?.error }
        answers += 200 to MobileSessionRewindResult(KEY, false, "nothing").toJson().toString()
        flow.run(SECOND, MobileRewindPoint.CONVERSATION)
        eventually { flow.picker.value?.error?.takeIf { it == "nothing" } }
        answers += 200 to MobileSessionRewindResult(KEY, false, "nothing").toJson().toString()
        flow.run(SECOND, MobileRewindPoint.CONVERSATION)
        eventually { sent.takeIf { it.size == 3 } }

        assertEquals("a retry after no answer must not append a second anchor", sent[0].operationId, sent[1].operationId)
        assertNotEquals(sent[1].operationId, sent[2].operationId)
    }

    @Test fun `a refused listing is said once and leaves no picker`() {
        points = MobileSessionRewindPoints(KEY, refused = "Rewind is Claude's — a Codex rollout records no restore point.")
        flow.open(KEY)

        eventually { snacks.firstOrNull() }
        assertEquals("Rewind is Claude's — a Codex rollout records no restore point.", snacks.single())
        assertNull(flow.picker.value)
    }

    @Test fun `a scope the machine did not offer for that message is never sent`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(SECOND)
        flow.run(TALK, MobileRewindPoint.CODE)

        assertTrue(events.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun `an answer that lands after the picker was reopened leaves the new picker alone and reuses the id`() {
        flow.open(KEY)
        eventually { flow.picker.value?.points }
        flow.choose(SECOND)
        slow = true
        answers += 200 to MobileSessionRewindResult(KEY, true, "Rewound.").toJson().toString()
        answers += 200 to MobileSessionRewindResult(KEY, true, "Rewound.").toJson().toString()
        flow.run(SECOND, MobileRewindPoint.CONVERSATION)
        flow.dismiss()
        flow.open(KEY)
        val reopened = eventually { flow.picker.value?.points?.let { flow.picker.value } }
        flow.choose(SECOND)
        flow.run(SECOND, MobileRewindPoint.CONVERSATION)
        gate.countDown()

        eventually { events.takeIf { it.size == 2 } }
        assertEquals("a second tap while the first was in flight must not append a second anchor", sent[0].operationId, sent[1].operationId)
        assertTrue(reopened.key == KEY)
    }

    @Test fun `each scope says what it does before the tap`() {
        assertEquals("starts a new chat · undoes 2 requests' file changes; you check each file next", scopeNote(FIRST, MobileRewindPoint.BOTH))
        assertEquals("starts a new chat", scopeNote(FIRST, MobileRewindPoint.CONVERSATION))
        assertEquals("you check each file next", scopeNote(SECOND, MobileRewindPoint.CODE))
        assertNull(scopeNote(SECOND, MobileRewindPoint.CONVERSATION))
    }

    @Test fun `the wire keeps the desk's scopes and drops one it does not know`() {
        val decoded = MobileSessionRewindPoints.fromJson(
            MobileProtocol.parseObject(
                """{"key":"k","points":[{"id":"p","label":"x","ordinal":1,"scopes":["both","erase","code"],"newChat":true,"files":2,"undone":3,"request":4}]}""",
            )!!,
        )
        assertEquals(
            MobileRewindPoint("p", "x", 1, scopes = listOf(MobileRewindPoint.BOTH, MobileRewindPoint.CODE), newChat = true, files = 2, undone = 3, request = 4),
            decoded.points.single(),
        )
    }

    private fun <T : Any> eventually(value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private fun bridge() = BridgeClient(listOf("a.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private var answer = "{}"
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream(): OutputStream = body
            override fun getResponseCode(): Int {
                if (url.path != MobileSessionRewindRequest.ROUTE) return 404
                val request = MobileSessionRewindRequest.fromJson(MobileProtocol.parseObject(body.toString())!!)
                if (request.listsPoints) {
                    answer = points.toJson().toString()
                    return 200
                }
                sent += request
                if (slow) gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                val (status, text) = synchronized(answers) { answers.removeFirst() }
                answer = text
                return status
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        private val ALL = listOf(MobileRewindPoint.BOTH, MobileRewindPoint.CONVERSATION, MobileRewindPoint.CODE)
        val FIRST = MobileRewindPoint("p1", "First", 1, scopes = ALL, newChat = true, files = 1, undone = 2, request = 1)
        val SECOND = MobileRewindPoint("p2", "Second", 2, scopes = ALL, files = 2, undone = 1, request = 2)
        val TALK = MobileRewindPoint("p3", "Talk only", 3, scopes = listOf(MobileRewindPoint.CONVERSATION))
        val POINTS = MobileSessionRewindPoints(KEY, listOf(FIRST, SECOND, TALK), notes = listOf("Files change; git is not touched — commits stay on the branch."))
    }
}
