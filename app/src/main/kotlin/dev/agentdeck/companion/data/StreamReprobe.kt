package dev.agentdeck.companion.data

import com.github.claudeagents.core.reach.ReprobePolicy
import com.github.claudeagents.core.reach.Route
import com.github.claudeagents.core.reach.RouteDialer

/**
 * Moves the live stream back to a better route when one answers again ([ReprobePolicy]): on
 * foreground and every [ReprobePolicy.INTERVAL_MS] while the stream is on a worse-ranked route.
 * [probe] races only the better routes; when one wins, [move] opens the new stream before the
 * old one closes. Nothing is armed while no stream is open, or while it is on the best route.
 */
internal class StreamReprobe(
    private val timer: RouteDialer.Timer,
    private val run: (Runnable) -> Unit,
    private val now: () -> Long,
    private val probe: (List<Route>) -> Route?,
    private val move: (Route) -> Unit,
) {
    private var current: Route? = null
    private var routes: List<Route> = emptyList()
    private var lastDialMs = 0L
    private var probing = false
    private var tick: RouteDialer.Cancellable? = null

    /** A stream proved live on [route]; [routes] are the pairing's. */
    @Synchronized
    fun opened(route: Route, routes: List<Route>) {
        current = route
        this.routes = routes
        lastDialMs = now()
        arm()
    }

    @Synchronized
    fun closed() {
        current = null
        tick?.cancel()
        tick = null
    }

    fun trigger(trigger: ReprobePolicy.Trigger) {
        val (from, candidates) = synchronized(this) {
            val from = current ?: return
            if (probing || !ReprobePolicy.shouldReprobe(trigger, from, routes, streamOpen = true, now(), lastDialMs)) return
            probing = true
            lastDialMs = now()
            from to ReprobePolicy.candidates(from, routes)
        }
        run(Runnable {
            val won = runCatching { probe(candidates) }.getOrNull()
            val moving = synchronized(this) {
                probing = false
                won != null && current == from
            }
            if (moving) move(won!!)
        })
    }

    /** Under the lock. */
    private fun arm() {
        tick?.cancel()
        tick = null
        val from = current ?: return
        if (ReprobePolicy.candidates(from, routes).isEmpty()) return
        tick = timer.after(ReprobePolicy.INTERVAL_MS) {
            synchronized(this) { tick = null }
            trigger(ReprobePolicy.Trigger.TICK)
            synchronized(this) { if (current != null && tick == null) arm() }
        }
    }
}
