package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import kotlin.random.Random

internal interface LinkConnection : AutoCloseable {
    val lastGoodHost: String?
    fun fleet(): MobileFleetSnapshot
    fun stream(onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean)
}

/** One recovery owner from the first fetch through every stream reconnect. */
internal class LinkRecovery(
    private val scope: CoroutineScope,
    private val connect: () -> LinkConnection?,
    private val onSnapshot: (MobileFleetSnapshot, LinkConnection) -> Unit,
    private val onAlive: (Boolean, LinkConnection) -> Unit,
    private val onFrame: (BridgeClient.SseFrame) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    private class Session {
        var job: Job? = null
        var connection: LinkConnection? = null
    }

    private val lock = Any()
    private var session: Session? = null

    fun stop() = synchronized(lock) { stopLocked() }

    fun restart(): Job = synchronized(lock) {
        stopLocked()
        val owner = Session()
        session = owner
        owner.job = scope.launch {
            var backoffMs = LinkPolicy.FIRST_BACKOFF_MS
            while (isActive) {
                val client = synchronized(lock) {
                    if (session !== owner) return@launch
                    connect()?.also { owner.connection = it }
                } ?: return@launch
                var fatal = false
                try {
                    val snapshot = client.fleet()
                    publish(owner) { onSnapshot(snapshot, client) }
                    var proved = false
                    client.stream(onAlive = {
                        publish(owner) {
                            onAlive(!proved, client)
                            if (!proved) {
                                proved = true
                                backoffMs = LinkPolicy.FIRST_BACKOFF_MS
                            }
                        }
                    }) { frame ->
                        publish(owner) { onFrame(frame) }
                        frame.event != MobileEvent.BYE
                    }
                    throw EOFException("The IDE closed the connection.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    publish(owner) { onFailure(error) }
                    fatal = error is PinMismatchException || (error as? BridgeRefusal)?.isRevoked == true
                } finally {
                    client.close()
                    synchronized(lock) { if (owner.connection === client) owner.connection = null }
                }
                if (fatal) return@launch
                pause(backoffMs)
                backoffMs = LinkPolicy.nextBackoffMs(backoffMs, jitter())
            }
        }
        requireNotNull(owner.job)
    }

    private fun publish(owner: Session, action: () -> Unit) = synchronized(lock) {
        if (session !== owner || owner.job?.isActive == false) throw CancellationException("Connection replaced")
        action()
    }

    private fun stopLocked() {
        val old = session
        session = null
        old?.job?.cancel()
        // Disconnect can block on a socket lock; never do it on the activity's caller thread.
        old?.connection?.let { client -> scope.launch { client.close() } }
    }
}
