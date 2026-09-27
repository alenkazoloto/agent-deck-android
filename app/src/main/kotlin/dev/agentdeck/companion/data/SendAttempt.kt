package dev.agentdeck.companion.data

/** Identical taps share one in-flight write; an edited message remains sendable. */
data class SendAttempt(val machineId: String, val conversationKey: String, val prompt: String) {
    companion object {
        fun remainingDraft(current: String, sent: String): String =
            if (current == sent) "" else current
    }
}
