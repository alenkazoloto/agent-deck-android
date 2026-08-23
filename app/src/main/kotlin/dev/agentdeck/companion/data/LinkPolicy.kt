package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileProtocol
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Every clock the connection keeps, and the two judgements made off them.
 *
 * This is a pure object with no Android in it because the decisions here are the ones that
 * were previously spread across a transport constant, a `while` loop and a `when` branch —
 * three places, none of which could be asked a question. [BridgeClient] reads the timeouts,
 * [dev.agentdeck.companion.LiveLink] reads the ladder and the verdict, and `LinkPolicyTest`
 * is the only reason any of it is checkable.
 */
object LinkPolicy {

    /**
     * How long a dial may take before the next address is tried.
     *
     * Deliberately generous: five seconds is right for a relay hop on a bad cell, and the
     * defect this project actually had was dialling an address that *cannot* answer, not
     * running out of patience with one that can (MP-04).
     */
    const val CONNECT_TIMEOUT_MS = 5_000

    /**
     * The deadline on an ordinary request — one that has a response coming.
     *
     * **Not** the stream's. A stream is silent by design between events, and giving it this
     * value would drop a healthy connection every ten seconds; giving a request the stream's
     * value would leave a user staring at a dead tap for the better part of a minute. They are
     * two numbers because they answer two questions, and they are in one file so that stays visible.
     */
    const val REQUEST_READ_TIMEOUT_MS = 10_000

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
     * How long a failure must persist before the app says whose fault it is.
     *
     * Two dial budgets. The banner used to be written on the *first* failed frame, so an
     * ordinary handover painted "This machine is not answering. The IDE has to be running."
     * for one second and then took it back — an accusation about a machine that was fine,
     * retracted before it could be read. `memory/wiring.md`: a verdict of "only the user can
     * fix this" must outlast the retry that would have fixed it.
     *
     * Only the *unattributed* verdict waits. A refusal the machine authored, a pin mismatch
     * and a dead radio are all facts already in hand, and [announces] is not consulted for them.
     */
    const val GRACE_MS = 2L * CONNECT_TIMEOUT_MS

    /**
     * The watchdog fired, or the socket died mid-read — the connection is gone and the only
     * useful response is to redial.
     *
     * A [SocketTimeoutException] off the stream is not an error worth showing: it *is* the
     * supervision working. Before there was a read deadline this exception could not occur,
     * which is exactly why nothing reconnected.
     */
    fun isTransportSilence(error: Throwable?): Boolean =
        error is SocketTimeoutException || error is InterruptedIOException

    /**
     * The next rung, jittered.
     *
     * [jitter01] is a caller-supplied uniform in `[0, 1)` so this stays pure and the test can
     * pin both edges. Without it several reconnects — on one phone across an outage, or
     * several phones behind one router — climb the ladder in lockstep and retry in a chorus.
     */
    fun nextBackoffMs(previousMs: Long, jitter01: Double): Long {
        val doubled = (previousMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val spread = doubled * JITTER
        val offset = (jitter01.coerceIn(0.0, 1.0) * 2.0 - 1.0) * spread
        return (doubled + offset).toLong().coerceAtLeast(FIRST_BACKOFF_MS)
    }

    /**
     * Whether a failure that started at [failingSinceMs] has outlasted the grace window.
     *
     * `null` means this is the first failure of a run, which by definition has not.
     */
    fun announces(failingSinceMs: Long?, nowMs: Long): Boolean =
        failingSinceMs != null && nowMs - failingSinceMs >= GRACE_MS

    /**
     * Whether returning to the foreground should re-ask the machine what is true now.
     *
     * Android freezes background processes, so the socket that was alive when the user left is
     * frequently dead when they come back — and *invisibly* dead, because a frozen process
     * cannot notice its own read deadline expiring. Nothing in this app ever observed the
     * lifecycle: the only things that woke the link were a bind, a network callback, a pull and
     * the Retry button (MP-03).
     *
     * The gate is silence rather than elapsed time so this cannot become a poll: inside
     * [MobileProtocol.Stream.CLIENT_WATCHDOG_MS] the stream has demonstrably been writing, and
     * asking again would be a second source of the same answer.
     */
    fun wakesOnForeground(sinceLastAliveMs: Long): Boolean =
        sinceLastAliveMs >= MobileProtocol.Stream.CLIENT_WATCHDOG_MS

    /**
     * What to say when the machine did not answer and nothing else explains why.
     *
     * The app had one sentence for every non-refusal failure — "This machine is not answering.
     * The IDE has to be running." — and said it about IDEs that were running perfectly, to
     * users on a network the phone was never going to reach (MP-05, MP-07). When every address
     * the machine advertises is network-scoped, that is a fact the phone holds and the user
     * can act on, so it is the one to say.
     */
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
