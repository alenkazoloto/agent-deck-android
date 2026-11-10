package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileEvent
import com.github.claudeagents.core.reach.Route
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
    /** Null for a connection that only knows its host; the machine's route on that host stands in. */
    val lastGoodRoute: Route? get() = null
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
        /** Its current stream has delivered a line and not failed since. */
        var live = false
    }

    private val lock = Any()
    private var session: Session? = null
    /** A stream opened beside [session] by [move], not yet live. */
    private var moving: Session? = null

    fun stop() = synchronized(lock) { stopLocked() }

    fun restart(): Job = synchronized(lock) {
        stopLocked()
        val owner = Session()
        session = owner
        owner.job = scope.launch { supervise(owner, replacing = null) }
        requireNotNull(owner.job)
    }

    /**
     * Make-before-break: a new connection streams beside the current session, which keeps
     * publishing until the new stream's first line; only then is the old one closed. A move
     * that fails before that line is dropped silently and the old stream carries on.
     */
    fun move(): Job? = synchronized(lock) {
        val old = session ?: return null
        moving?.let { retire(it) }
        val next = Session()
        moving = next
        next.job = scope.launch { supervise(next, replacing = old) }
        next.job
    }

    /**
     * A new default network. A live stream [move]s, so a handover the old network outlives shows
     * no gap; when the old network is gone its socket fails and the session reconnects as usual.
     * False when nothing is streaming, so the caller reconnects instead.
     */
    fun moveIfLive(): Boolean = synchronized(lock) {
        if (session?.live != true) return false
        move() != null
    }

    private suspend fun CoroutineScope.supervise(owner: Session, replacing: Session?) {
        var pending = replacing
        var backoffMs = LinkPolicy.FIRST_BACKOFF_MS
        while (isActive) {
            val client = synchronized(lock) {
                if ((if (pending == null) session else moving) !== owner) return
                connect()?.also { owner.connection = it }
            } ?: return
            var fatal = false
            try {
                if (pending == null) {
                    val snapshot = client.fleet()
                    publish(owner) { onSnapshot(snapshot, client) }
                }
                var proved = false
                client.stream(onAlive = {
                    pending?.let { old ->
                        takeOver(owner, old)
                        pending = null
                    }
                    publish(owner) {
                        onAlive(!proved, client)
                        if (!proved) {
                            proved = true
                            owner.live = true
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
                synchronized(lock) { owner.live = false }
                if (pending != null) {
                    synchronized(lock) { if (moving === owner) moving = null }
                    return
                }
                publish(owner) { onFailure(error) }
                fatal = error is PinMismatchException || (error as? BridgeRefusal)?.isRevoked == true
            } finally {
                client.close()
                synchronized(lock) { if (owner.connection === client) owner.connection = null }
            }
            if (fatal) return
            pause(backoffMs)
            backoffMs = LinkPolicy.nextBackoffMs(backoffMs, jitter())
        }
    }

    private fun takeOver(owner: Session, old: Session) = synchronized(lock) {
        if (moving !== owner || session !== old) throw CancellationException("Connection replaced")
        moving = null
        session = owner
        retire(old)
    }

    private fun publish(owner: Session, action: () -> Unit) = synchronized(lock) {
        if (session !== owner || owner.job?.isActive == false) throw CancellationException("Connection replaced")
        action()
    }

    private fun stopLocked() {
        session?.let(::retire)
        session = null
        moving?.let(::retire)
        moving = null
    }

    private fun retire(old: Session) {
        old.job?.cancel()
        // Disconnect can block on a socket lock; never do it on the activity's caller thread.
        old.connection?.let { client -> scope.launch { client.close() } }
    }
}
