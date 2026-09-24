package dev.agentdeck.companion

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileReviewSort
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * The Changes list's "Sort by" through the view model (M4, P19): a pick writes the desk's order and
 * re-reads the list, and a file opened while that list is still on its way leaves neither the list
 * nor the diff spinning — the list and the diff each own their load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewSortFlowTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore
    private lateinit var live: LiveLink
    private lateinit var model: DeckViewModel

    private val sent = CopyOnWriteArrayList<String>()
    @Volatile private var order = "DEFAULT"
    /** Holds the list's answer back until released, so a file can be opened under it. */
    @Volatile private var listGate: CountDownLatch? = null

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
        live = LiveLink.of(app).also { it.bind(null) }
        live.connectionForTest = { Stream() }
        model = DeckViewModel(app).also { it.connectionForTest = { bridge() } }
        eventually { model.state.value.snapshot?.rows?.takeIf { it.isNotEmpty() } }
        model.openConversation(CHAT)
        model.loadReview(CHAT.key)
        eventually { model.state.value.review?.takeIf { !model.state.value.reviewLoading } }
    }

    @After fun tearDown() {
        listGate?.countDown()
        model.viewModelScope.cancel()
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `a pick writes the desk's order and the list comes back in it`() {
        model.setReviewSort(CHAT.key, "NAME")

        val list = eventually { model.state.value.review?.takeIf { it.sortOrder == "NAME" && !model.state.value.reviewLoading } }
        assertEquals(listOf("a/Alpha.kt", "b/Zeta.kt"), list.files.map { it.path })
        assertEquals(1, sent.count { it.startsWith(MobileReviewSort.ROUTE) })
    }

    @Test fun `a file opened while the sorted list is on its way strands neither spinner`() {
        listGate = CountDownLatch(1)
        model.setReviewSort(CHAT.key, "NAME")
        eventually { sent.lastOrNull()?.takeIf { it.startsWith("/v1/review/") && !it.contains("/file") } }

        model.openReviewFile(CHAT.key, "b/Zeta.kt")
        eventually { model.state.value.reviewDiff?.takeIf { !model.state.value.reviewDiffLoading } }
        listGate!!.countDown()

        eventually { model.state.value.review?.takeIf { it.sortOrder == "NAME" && !model.state.value.reviewLoading } }
        assertFalse(model.state.value.reviewDiffLoading)
        assertEquals("b/Zeta.kt", model.state.value.reviewPath)
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

    private inner class Stream : LinkConnection {
        private val closed = CountDownLatch(1)
        override val lastGoodHost = "a.test"
        override fun fleet() = MobileFleetSnapshot(listOf(CHAT), 0, emptyList(), null, 1)
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
            onAlive()
            closed.await()
        }
        override fun close() = closed.countDown()
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
                val path = url.path
                sent += path
                answer = when {
                    path == MobileReviewSort.ROUTE -> {
                        order = MobileReviewSort.fromJson(com.github.claudeagents.core.mobile.MobileProtocol.parseObject(body.toString())!!)!!.order
                        MobileReviewSort(order).toJson()
                    }
                    path.endsWith("/file") -> MobileReviewFileDiff(FILES.last()).toJson()
                    path.startsWith("/v1/review/") -> {
                        val answered = order
                        listGate?.await(5, TimeUnit.SECONDS)
                        MobileReviewList(
                            key = CHAT.key,
                            files = if (answered == "NAME") FILES.sortedBy { it.path } else FILES,
                            sortOrder = answered,
                        ).toJson()
                    }
                    else -> return 404
                }.toString()
                return 200
            }
            override fun getInputStream(): InputStream = answer.byteInputStream()
            override fun getErrorStream(): InputStream = answer.byteInputStream()
        }
    }

    private companion object {
        val CHAT = MobileFleetRow(
            key = "claude:/repo:s", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
            projectName = "repo", gitBranch = null, title = "Changed two files", attention = null, waitingReason = null,
            lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 4,
        )
        val FILES = listOf(
            MobileReviewFile("b/Zeta.kt", MobileReviewFile.MODIFIED, added = 1, removed = 1),
            MobileReviewFile("a/Alpha.kt", MobileReviewFile.MODIFIED, added = 4, removed = 1),
        )
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("a.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
