package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/**
 * Robolectric gives every test a new Application and files dir. The process-wide link kept the
 * first one's, so a snapshot it cached outlived the test's `forget` and the next test's `bind`
 * painted it — SessionActionsTest's refusal read the rename test's "Parser rewrite". The order
 * is fixed: `a` makes the link an earlier test's, as any earlier class in a full run does, `b`
 * caches a row and `c` must not see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LiveLinkPerApplicationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = SecureStore(app).also { it.save(MACHINE) }
    private val live = LiveLink.of(app).also { it.bind(null) }
    private val closed = CountDownLatch(1)

    @After fun tearDown() {
        live.connectionForTest = null
        live.bind(null)
        store.forget(MACHINE.id)
        closed.countDown()
    }

    @Test fun `a - an earlier test takes the link first`() = Unit

    @Test fun `b - a bound link caches the machine's rows`() {
        live.connectionForTest = { Stream(delivers = true) }
        live.bind(MACHINE)
        eventually { live.fleet.value != null }
        assertEquals("Parser rewrite", live.fleet.value!!.rows.single().title)
    }

    @Test fun `c - the next test's link starts from its own empty store`() {
        live.connectionForTest = { Stream(delivers = false) }
        live.bind(MACHINE)
        assertNull("the previous test's cached rows", live.fleet.value)
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("the condition never held")
    }

    private inner class Stream(private val delivers: Boolean) : LinkConnection {
        override val lastGoodHost = "test-machine"
        override fun fleet(): MobileFleetSnapshot {
            if (!delivers) closed.await()
            return MobileFleetSnapshot(listOf(row()), 0, emptyList(), null, 1)
        }
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) = closed.await()
        override fun close() = closed.countDown()
    }

    private fun row() = MobileFleetRow(
        key = "claude:/repo:session", vendor = AgentVendor.CLAUDE, accountId = "default", projectPath = "/repo",
        projectName = "repo", gitBranch = null, title = "Parser rewrite", attention = null, waitingReason = null,
        lastActivityMs = 1, costUsd = 0.0, costKnown = true, contextPct = null, messageCount = 2,
        pinned = false, done = false,
    )

    private companion object {
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
