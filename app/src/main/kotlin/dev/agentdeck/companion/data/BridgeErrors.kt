package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRefusal
import java.io.IOException

/** Show the machine's refusal message verbatim; these IDE-authored constants contain no user data. */
class BridgeRefusal(
    val status: Int,
    val code: String?,
    override val message: String,
) : IOException(message) {

    /** The pairing is gone on the machine's side; the app must return to pairing. */
    val isRevoked: Boolean
        get() = code == MobileRefusal.DEVICE_REVOKED.code || code == MobileRefusal.UNAUTHORIZED.code
}

class BridgeDeliveryUncertain(cause: IOException) : IOException(
    "The connection was interrupted before confirmation. Check the latest state before trying again.",
    cause,
)
