package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileProtocol
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** Pure connection deadlines and decisions shared by BridgeClient and LiveLink, without Android dependencies. */
object LinkPolicy {

    /** Allow a slow relay/cellular dial; unreachable-address selection, not timeout impatience, caused MP-04. */
    const val CONNECT_TIMEOUT_MS = 5_000

    /** Ordinary requests need a short deadline; streams legitimately stay silent between events. */
    const val REQUEST_READ_TIMEOUT_MS = 10_000

    /**
     * An export's deadline: the machine reads and renders the whole transcript before its first
     * byte, which for a chat of tens of megabytes takes longer than an ordinary request may.
     */
    const val EXPORT_READ_TIMEOUT_MS = 60_000

    /** A title regeneration is a model turn; the machine answers "still writing" at 50 s, inside this. */
    const val RETITLE_READ_TIMEOUT_MS = 60_000

    /**
     * The deadline on the event stream: three missed keep-alives, defined on the wire format
     * beside the period the machine writes them at, so the two ends cannot drift apart.
     */
    const val STREAM_READ_TIMEOUT_MS = MobileProtocol.Stream.CLIENT_WATCHDOG_MS.toInt()

    /** The reconnect ladder's first rung, and its ceiling. */
    const val FIRST_BACKOFF_MS = 1_000L
    const val MAX_BACKOFF_MS = 30_000L

    /** Proportion of each rung that is randomised, either way. */
    const val JITTER = 0.25

    /**
     * Delay unattributed failure until two dial budgets pass, so a normal handover can recover (MP-05).
     * Known refusals, pin mismatch and absent network remain immediate.
     */
    const val GRACE_MS = 2L * CONNECT_TIMEOUT_MS

    /** Stream read timeout means the watchdog is working; reconnect rather than showing it as a terminal error. */
    fun isTransportSilence(error: Throwable?): Boolean =
        error is SocketTimeoutException || error is InterruptedIOException

    /** Caller-supplied jitter keeps retries from synchronizing across phones and leaves the policy deterministic in tests. */
    fun nextBackoffMs(previousMs: Long, jitter01: Double): Long {
        val doubled = (previousMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val spread = doubled * JITTER
        val offset = (jitter01.coerceIn(0.0, 1.0) * 2.0 - 1.0) * spread
        return (doubled + offset).toLong().coerceIn(FIRST_BACKOFF_MS, MAX_BACKOFF_MS)
    }

    /**
     * Whether a failure that started at [failingSinceMs] has outlasted the grace window.
     *
     * `null` means this is the first failure of a run, which by definition has not.
     */
    fun announces(failingSinceMs: Long?, nowMs: Long): Boolean =
        failingSinceMs != null && nowMs - failingSinceMs >= GRACE_MS

    /**
     * Android can freeze a process past its socket deadline. Recheck on foreground after silence,
     * not merely elapsed foreground time; a demonstrably live stream needs no extra poll (MP-03).
     */
    fun wakesOnForeground(sinceLastAliveMs: Long): Boolean =
        sinceLastAliveMs >= MobileProtocol.Stream.CLIENT_WATCHDOG_MS

    /** When all addresses are network-scoped, say so rather than blaming an IDE that may be running (MP-05/07). */
    fun stalenessSentence(machineName: String, hosts: List<String>): String {
        val name = machineName.ifBlank { "This machine" }
        return if (Reachability.isLanOnly(hosts)) {
            "$name is only reachable on its own network. Join that network, or turn on " +
                "remote access for it in the IDE."
        } else {
            "$name is not answering. The IDE has to be running."
        }
    }
}
