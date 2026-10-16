package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.BridgeRefusal
import dev.agentdeck.companion.data.LinkConnection
import dev.agentdeck.companion.data.LinkPolicy
import dev.agentdeck.companion.data.LinkRecovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinkRecoveryTest {
    private val snapshot = MobileFleetSnapshot(emptyList(), 0, emptyList(), null, 1)

    private open inner class Connection : LinkConnection {
        override val lastGoodHost = "test-machine"
        override fun fleet() = snapshot
        override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) = Unit
        override fun close() = Unit
    }

    @Test fun `failed first fetch retries without a network or screen callback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val attempts = AtomicInteger()
        val recovered = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val recovery = LinkRecovery(scope, connect = {
            object : Connection() {
                override fun fleet(): MobileFleetSnapshot {
                    if (attempts.incrementAndGet() == 1) throw IOException("IDE restarting")
                    return snapshot
                }
                override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
                    onAlive()
                    recovered.complete(Unit)
                    throw BridgeRefusal(401, "unauthorized", "Stopped fixture")
                }
            }
        }, onSnapshot = { _, _ -> }, onAlive = { _, _ -> }, onFrame = {}, onFailure = {},
            pause = { delays += it }, jitter = { 0.5 })
        try {
            recovery.restart()
            withTimeout(5_000) { recovered.await() }
            assertEquals(2, attempts.get())
            assertEquals(listOf(LinkPolicy.FIRST_BACKOFF_MS), delays)
        } finally { recovery.stop(); scope.cancel() }
    }

    @Test fun `clean stream end reports disconnection and retries`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val retry = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<Throwable>()
        val recovery = LinkRecovery(scope, { Connection() }, { _, _ -> }, { _, _ -> }, {},
            { failure.complete(it) }, pause = { retry.complete(Unit); CompletableDeferred<Unit>().await() })
        try {
            recovery.restart()
            withTimeout(5_000) { retry.await() }
            assertTrue(failure.await() is EOFException)
        } finally { recovery.stop(); scope.cancel() }
    }

    @Test fun `replacing a machine closes old socket and rejects its delayed snapshot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldClosed = CountDownLatch(1)
        val oldFinished = CountDownLatch(1)
        val closes = AtomicInteger()
        val fresh = CompletableDeferred<Unit>()
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val old = object : Connection() {
            override fun fleet(): MobileFleetSnapshot {
                oldStarted.countDown()
                assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                return snapshot.copy(generatedAtMs = 99)
            }
            override fun close() {
                oldClosed.countDown()
                releaseOld.countDown()
                if (closes.incrementAndGet() == 2) oldFinished.countDown()
            }
        }
        val calls = AtomicInteger()
        val recovery = LinkRecovery(scope, {
            if (calls.incrementAndGet() == 1) old else object : Connection() {
                override fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
                    fresh.complete(Unit)
                    throw BridgeRefusal(401, "unauthorized", "Stop fixture")
                }
            }
        }, { page, _ -> snapshots += page.generatedAtMs }, { _, _ -> }, {}, {},
            pause = { CompletableDeferred<Unit>().await() })
        try {
            recovery.restart()
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            recovery.restart()
            assertTrue(oldClosed.await(5, TimeUnit.SECONDS))
            assertTrue(oldFinished.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { fresh.await() }
            assertEquals("late old-machine response must not reach the cache or fleet", listOf(1L), snapshots)
        } finally { releaseOld.countDown(); recovery.stop(); scope.cancel() }
    }

    @Test fun `revoked pairing stops recovery without retrying`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val closed = CompletableDeferred<Unit>()
        val retry = AtomicInteger()
        val recovery = LinkRecovery(scope, { object : Connection() {
            override fun fleet(): MobileFleetSnapshot = throw BridgeRefusal(401, "unauthorized", "Pair again")
            override fun close() { closed.complete(Unit) }
        } }, { _, _ -> }, { _, _ -> }, {}, {}, pause = { retry.incrementAndGet() })
        try {
            val job = recovery.restart()
            withTimeout(5_000) { job.join() }
            assertTrue(closed.isCompleted)
            assertEquals(0, retry.get())
        } finally { recovery.stop(); scope.cancel() }
    }

    @Test fun `jitter cannot exceed the maximum reconnect delay`() {
        for (sample in 0..100) {
            assertTrue(LinkPolicy.nextBackoffMs(LinkPolicy.MAX_BACKOFF_MS, sample / 100.0) <= LinkPolicy.MAX_BACKOFF_MS)
        }
    }
}
