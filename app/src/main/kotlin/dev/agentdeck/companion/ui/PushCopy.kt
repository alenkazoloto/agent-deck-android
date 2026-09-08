package dev.agentdeck.companion.ui

import dev.agentdeck.companion.PushState
import dev.agentdeck.companion.push.UnifiedPush

/**
 * Which sentence Settings shows about being told while the app is closed, and what it offers.
 *
 * A pure function of [PushState] rather than a `when` inside the composable, because the branch
 * a reader actually sees is decided by four facts a screenshot fixture can only hold one
 * arrangement of — and the branch that matters most (push is working) is the one no
 * distributor-free test device can reach. `PushCopyTest` walks all five.
 *
 * The states are separate because their *repairs* are: install an app, tap a row here, wait for
 * a relay, or flip a switch in the IDE at the desk. A single "Push: off" row would be true in
 * every one of them and actionable in none.
 */
data class PushCopy(
    val title: String,
    val body: String,
    /** Distributors to offer; empty unless the reader still has to choose one. */
    val offer: List<UnifiedPush.Distributor> = emptyList(),
    /** Whether to offer turning it back off — only where there is something to turn off. */
    val offerStop: Boolean = false,
) {
    companion object {
        fun of(push: PushState): PushCopy = when {
            push.live -> PushCopy(
                title = "How these arrive",
                body = "Through ${push.chosenLabel} when the app is closed, and over the " +
                    "connection to the machine while it is open. The alert is encrypted end " +
                    "to end — the push service forwards it without being able to read it.",
                offerStop = true,
            )

            // Named separately from "waiting": this one is fixed at the desk and no amount of
            // waiting here will do it, so a shared "not working yet" sentence would send the
            // reader to watch a screen that can never change.
            push.chosen != null && !push.machineSupports -> PushCopy(
                title = "The machine is not sending them",
                body = "${push.chosenLabel} is set up on this phone, but the machine has not " +
                    "been asked to use it. Turn on “Notify the phone while its app is closed” " +
                    "in the IDE, under Settings › Connections › Mobile.",
                offerStop = true,
            )

            push.chosen != null -> PushCopy(
                title = "Waiting for ${push.chosenLabel}",
                body = "No address has come back yet. Alerts arrive over the connection to " +
                    "the machine meanwhile, which means they stop while the app is closed.",
                offerStop = true,
            )

            push.distributors.isEmpty() -> PushCopy(
                title = "How these arrive",
                body = "Over the connection to the machine — so they stop while the app is " +
                    "closed. To be told anyway, install a UnifiedPush app such as ntfy and " +
                    "pick it here.",
            )

            else -> PushCopy(
                title = "How these arrive",
                body = "Over the connection to the machine — so they stop while the app is " +
                    "closed. Pick a push service below to be told anyway.",
                offer = push.distributors,
            )
        }
    }
}
