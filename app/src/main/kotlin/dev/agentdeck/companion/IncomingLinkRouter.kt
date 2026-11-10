package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobilePairingPayload

/**
 * What an incoming link (a notification, a widget, `adb shell am`, or now a pairing QR read as
 * a link) is for, decided in one place so a new link shape is one `when` to extend rather than
 * a second copy of "is this pairing?" beside [Navigation.parse] (PLAN-MOBILE-QR-LINK.md M1).
 *
 * Pairing is checked first: a pairing link may be `https://…/pair`, a shape [Navigation.parse]
 * never recognizes (it only reads `agentdeck://`), so trying navigation first would drop it.
 *
 * Pure and stateless — the same [raw] always yields the same [Decision]. The single-use pairing
 * code is enforced by the bridge, not here, so a router asked twice about one link (a rotation
 * replaying the same intent) answers identically rather than treating the second ask as "already
 * used"; [MainActivity] is what keeps a re-delivered intent from opening a second confirm card.
 */
object IncomingLinkRouter {

    sealed interface Decision {
        data class Pair(val payload: MobilePairingPayload) : Decision
        data class Navigate(val link: DeepLink) : Decision
        data object Drop : Decision
    }

    fun route(raw: String?): Decision {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Decision.Drop
        MobilePairingPayload.decode(text)?.let { return Decision.Pair(it) }
        return Navigation.parse(text)?.let { Decision.Navigate(it) } ?: Decision.Drop
    }
}
