package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobilePairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PLAN-MOBILE-QR-LINK.md M1: pairing is checked ahead of ordinary navigation, because a pairing
 * link may be `https://…/pair` — a shape [Navigation.parse] never recognizes on its own.
 */
class IncomingLinkRouterTest {

    private val payload = MobilePairingPayload(
        hosts = listOf("192.168.1.24"),
        port = 63350,
        spkiFingerprint = "abc123",
        code = "12345678",
        machineName = "mac-mini",
    )

    @Test
    fun `a pairing link routes to Pair regardless of scheme`() {
        val https = payload.toLink("https://alenkazoloto.github.io/agents-deck")
        val fragment = https.substringAfter('#')
        assertEquals(payload, (IncomingLinkRouter.route(https) as IncomingLinkRouter.Decision.Pair).payload)
        assertEquals(
            payload,
            (IncomingLinkRouter.route("agentdeck://pair#$fragment") as IncomingLinkRouter.Decision.Pair).payload,
        )
        // The in-app scanner's own format — an old plugin's raw JSON — routes the same way.
        assertEquals(payload, (IncomingLinkRouter.route(payload.encode()) as IncomingLinkRouter.Decision.Pair).payload)
    }

    @Test
    fun `an ordinary deep link navigates instead of pairing`() {
        val decision = IncomingLinkRouter.route("agentdeck://fleet")
        assertEquals(DeepLink.Fleet, (decision as IncomingLinkRouter.Decision.Navigate).link)
    }

    @Test
    fun `a foreign or empty link is dropped`() {
        listOf(null, "", "https://example.com/nowhere", "agentdeck://nowhere", "agentdeck://pair#p=not-valid-base64!!!")
            .forEach { assertTrue("expected Drop for $it", IncomingLinkRouter.route(it) is IncomingLinkRouter.Decision.Drop) }
    }

    /**
     * The router holds no state of its own — the single-use pairing code is enforced by the
     * bridge, so asking about the same raw link twice (a rotation replaying the same intent)
     * must not turn the second ask into a drop.
     */
    @Test
    fun `re-delivering the same link answers the same way both times`() {
        val https = payload.toLink("https://alenkazoloto.github.io/agents-deck")
        val first = IncomingLinkRouter.route(https)
        val second = IncomingLinkRouter.route(https)
        assertEquals(first, second)
    }
}
