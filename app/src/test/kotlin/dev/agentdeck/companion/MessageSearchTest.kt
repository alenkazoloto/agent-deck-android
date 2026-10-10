package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileSessionSearchHit
import com.github.claudeagents.core.mobile.MobileSessionSearchRequest
import com.github.claudeagents.core.mobile.MobileSessionSearchResult
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.MessageSearch
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.ui.messageSearchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

/**
 * The Chats search's message half (M3, `/v1/session-search`): which chats the phone asks about,
 * that a page is asked once however many fleet frames arrive, that "Search older chats" resumes
 * where the machine stopped, and that a failure waits for the reader's Retry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageSearchTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    private val asked = CopyOnWriteArrayList<MobileSessionSearchRequest>()
    @Volatile private var failing = false

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
    }

    @After fun tearDown() {
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `the population is what the filters leave, minus title hits, newest first`() {
        val rows = listOf(
            row("old", "Parser", activityMs = 10),
            row("new", "Docs", activityMs = 30),
            row("title", "Login redirect", activityMs = 20),
            row("done", "Release", activityMs = 40, done = true),
        )
        assertEquals(
            listOf("new", "old"),
            MessageSearch.population(rows, FleetFilter(query = "login")),
        )
        assertEquals(
            "a scope the reader picked narrows the messages searched too",
            listOf("done"),
            MessageSearch.population(rows, FleetFilter(query = "login", scope = FleetScope.DONE)),
        )
    }

    @Test fun `a page is asked once, and older chats resume where the machine stopped`() {
        model.searchMessages("login", listOf("a", "b", "c"))
        eventually { model.state.value.messageSearch?.searching == false }
        // Fleet frames re-sort and drop rows; neither asks again.
        model.searchMessages("login", listOf("c", "a"))
        assertEquals(1, asked.size)
        val first = model.state.value.messageSearch!!
        assertEquals(listOf("a"), first.hits.map { it.key })
        assertEquals(2, first.nextCursor)

        model.searchMoreMessages()
        eventually { model.state.value.messageSearch?.complete == true }
        assertEquals("each request carries only its own page", listOf(listOf("a", "b", "c"), listOf("c")), asked.map { it.keys })
        assertEquals(listOf("a", "c"), model.state.value.messageSearch!!.hits.map { it.key })
        assertEquals(3, model.state.value.messageSearch!!.scanned)
    }

    @Test fun `a chat that appears later is read without restarting the pages already read`() {
        model.searchMessages("login", listOf("a", "b", "c"))
        eventually { model.state.value.messageSearch?.searching == false }
        model.searchMoreMessages()
        eventually { model.state.value.messageSearch?.complete == true }

        model.searchMessages("login", listOf("d", "a", "b", "c"))
        eventually { model.state.value.messageSearch?.complete == true && asked.size == 3 }
        assertEquals("only the newcomer is asked about", listOf("d"), asked.last().keys)
        assertEquals(listOf("a", "c"), model.state.value.messageSearch!!.hits.map { it.key })
    }

    @Test fun `a request never carries more than one page of keys`() {
        val many = (1..450).map { "k$it" }
        val search = MessageSearch("login", many)
        assertEquals(MobileSessionSearchRequest.PAGE_FILES, search.pageKeys().size)
        assertEquals(listOf("k401"), search.copy(nextCursor = 400).pageKeys().take(1))
        assertTrue(search.copy(nextCursor = null).pageKeys().isEmpty())
    }

    @Test fun `a new query replaces the old search, and a short one clears it`() {
        model.searchMessages("login", listOf("a"))
        eventually { model.state.value.messageSearch?.searching == false }
        model.searchMessages("logout", listOf("a"))
        eventually { model.state.value.messageSearch?.query == "logout" && model.state.value.messageSearch?.searching == false }
        assertEquals(2, asked.size)
        model.searchMessages("lo", listOf("a"))
        assertNull(model.state.value.messageSearch)
    }

    @Test fun `a failed page waits for Retry and keeps its place`() {
        failing = true
        model.searchMessages("login", listOf("a", "b", "c"))
        eventually { model.state.value.messageSearch?.error != null }
        model.searchMessages("login", listOf("a", "b", "c"))
        assertEquals("the next fleet frame does not retry by itself", 1, asked.size)
        assertEquals("Retry", messageSearchStatus(model.state.value.messageSearch!!, 0).second)

        failing = false
        model.searchMoreMessages()
        eventually { model.state.value.messageSearch?.error == null && model.state.value.messageSearch?.searching == false }
        assertEquals("Retry asks the same page again", asked[0].keys, asked[1].keys)
        assertEquals(listOf("a"), model.state.value.messageSearch!!.hits.map { it.key })
    }

    @Test fun `the status names how much was read`() {
        val base = MessageSearch("login", listOf("a"))
        assertEquals("Searching messages…" to null, messageSearchStatus(base.copy(searching = true), 0))
        assertEquals(
            "Messages searched in the 200 newest chats" to "Search older chats",
            messageSearchStatus(base.copy(scanned = 200, nextCursor = 200), 3),
        )
        assertEquals(
            "a last chat cut off by the machine is not an empty answer",
            "Messages searched in 0 chats; one was too long to finish" to null,
            messageSearchStatus(MessageSearch("login", listOf("a")).plus(0, MobileSessionSearchResult("login", emptyList(), 0, 1, timedOut = true)), 0),
        )
        assertEquals(
            "The machine stopped after reading messages in 40 chats" to "Continue",
            messageSearchStatus(base.copy(scanned = 40, nextCursor = 40, timedOut = true), 0),
        )
        assertEquals("No messages match in 12 chats" to null, messageSearchStatus(base.copy(scanned = 12, nextCursor = null), 0))
        assertFalse(base.copy(nextCursor = 5).complete)
        assertTrue(base.copy(nextCursor = null).complete)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private fun row(key: String, title: String, activityMs: Long, done: Boolean = false) = MobileFleetRow(
        key = key, vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = title, attention = null, waitingReason = null,
        lastActivityMs = activityMs, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        done = done,
    )

    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "test-machine"
        override fun fleet() = MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
    }

    /** The machine: a slow one that stops after two chats; "a" and "c" mention the query. */
    private fun answer(body: String): Pair<Int, String> {
        val request = MobileSessionSearchRequest.fromJson(MobileProtocol.parseObject(body)!!)
        asked += request
        if (failing) return 503 to """{"v":1,"error":"busy","message":"The machine is busy."}"""
        val page = request.keys.take(2)
        val hits = page.filter { it == "a" || it == "c" }.map { MobileSessionSearchHit(it, "…the $it login…") }
        val result = MobileSessionSearchResult(request.query, hits, page.size, page.size, timedOut = page.size < request.keys.size)
        return 200 to result.toJson().toString()
    }

    private fun bridge() = BridgeClient(listOf("machine.test"), 8443, "00", "token") { url ->
        object : HttpsURLConnection(url) {
            private val body = ByteArrayOutputStream()
            private val reply by lazy {
                if (url.path == MobileSessionSearchRequest.ROUTE) answer(body.toString(Charsets.UTF_8.name())) else 200 to "{}"
            }
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getCipherSuite() = "test"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = body
            override fun getResponseCode(): Int = reply.first
            override fun getErrorStream(): InputStream = reply.second.byteInputStream()
            override fun getInputStream(): InputStream = reply.second.byteInputStream()
        }
    }

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
