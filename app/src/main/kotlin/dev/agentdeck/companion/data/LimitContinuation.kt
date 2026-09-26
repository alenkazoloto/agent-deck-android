package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol

/**
 * The desk's "Continue after reset": a chat cut off by its account's usage limit, or a drained
 * plan in Usage, offers to queue a prompt for two minutes past the reset (`LimitBarBinder`,
 * `ScheduledRunBinder.scheduleContinuation`).
 *
 * The phone reads the limit off `/v1/hello`'s account options — the same reset the Schedule
 * dialog's "At <time>, after the limit resets" choice uses — so the offer and the choice it
 * preselects can never disagree about when that is.
 */
object LimitContinuation {
    /** The desk's own prefill (`LimitBarBinder.CONTINUE_PROMPT`). */
    const val PROMPT = "Continue with the current task."

    fun draftKey(conversationKey: String) = "continue-after-reset:$conversationKey"

    /**
     * The reset [accountId] is waiting on, while the machine can queue a prompt for it. The exact
     * id only: [NewChat.accountFor]'s fallback to the active account would put another
     * account's reset on this one.
     */
    fun resetOf(hello: MobileHello?, vendor: AgentVendor, accountId: String, nowMs: Long): AfterReset? {
        if (hello == null || MobileProtocol.Capability.SCHEDULE_CREATE !in hello.capabilities) return null
        return AfterReset.of(hello.accounts[vendor]?.firstOrNull { it.id == accountId }, nowMs)
    }

    /** Null while the chat is running: a live turn has not been cut off, whatever the plan says. */
    fun forChat(hello: MobileHello?, row: MobileFleetRow?, running: Boolean, nowMs: Long): AfterReset? =
        row?.takeUnless { running }?.let { resetOf(hello, it.vendor, it.accountId, nowMs) }
}
