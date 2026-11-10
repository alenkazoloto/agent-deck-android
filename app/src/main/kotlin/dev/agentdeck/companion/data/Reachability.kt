package dev.agentdeck.companion.data

/**
 * What kind of address this is, in the only sense the phone cares about: **does it still mean
 * anything after the phone changes network?**
 *
 * Three items turned out to be one question. The remembered host was kept across the network
 * it was learned on, so a phone that had once succeeded on `192.168.1.24` dialled it first on
 * cellular forever, spending a five-second connect timeout per call (MP-04). The banner then
 * blamed the machine for it (MP-05). And a phone genuinely off the LAN was told "the IDE has
 * to be running" about an IDE that was running perfectly (MP-07).
 *
 * [OVERLAY] is why this is not a boolean. Tailscale hands out `100.64.0.0/10`, which is
 * private by RFC 6598 and reachable from anywhere on the tailnet — the one address class that
 * looks local and travels. `MobileBinding.preferredHost` on the plugin side reasons about
 * exactly this, and treating it as LAN would forget the only host that would have worked.
 */
enum class HostReach {
    /** This device. Never reachable from a phone; only ever seen in a fixture or a mistake. */
    LOOPBACK,

    /** Meaningful on one network and nowhere else — RFC 1918, link-local, mDNS. */
    LAN,

    /** A private-looking address that is routed by an overlay, so it survives a handover. */
    OVERLAY,

    /** A public address or name — a relay, a port forward, a DNS name that resolves anywhere. */
    PUBLIC,
    ;

    /** Whether dialling this address is worth trying from a network it was not learned on. */
    val survivesNetworkChange: Boolean get() = this == OVERLAY || this == PUBLIC
}

/** Classifies the addresses a paired machine advertises. */
object Reachability {

    fun of(host: String): HostReach {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return HostReach.LAN
        if (h == "localhost" || h == "::1" || h.startsWith("127.")) return HostReach.LOOPBACK

        val v4 = h.split('.').takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 4 && it.all { part -> part in 0..255 } }
        if (v4 != null) return classifyV4(v4)

        if (h.contains(':')) return classifyV6(h)
        // A name, not an address. `.local` is mDNS and resolves on one link; anything else is
        // a name someone published, which is the definition of reachable from elsewhere.
        return if (h.endsWith(".local")) HostReach.LAN else HostReach.PUBLIC
    }

    private fun classifyV4(o: List<Int>): HostReach = when {
        o[0] == 10 -> HostReach.LAN
        o[0] == 172 && o[1] in 16..31 -> HostReach.LAN
        o[0] == 192 && o[1] == 168 -> HostReach.LAN
        // Link-local (APIPA). A phone on the same segment might reach it; a phone anywhere
        // else certainly cannot.
        o[0] == 169 && o[1] == 254 -> HostReach.LAN
        // RFC 6598 carrier-grade NAT, which is also Tailscale's range. Ambiguous by address
        // alone; treated as the overlay because that is the case where guessing wrong loses
        // the user their only working host.
        o[0] == 100 && o[1] in 64..127 -> HostReach.OVERLAY
        else -> HostReach.PUBLIC
    }

    private fun classifyV6(h: String): HostReach = when {
        h.startsWith("fe80:") -> HostReach.LAN
        // fc00::/7 — unique local. WireGuard and Tailscale both hand these out.
        h.startsWith("fc") || h.startsWith("fd") -> HostReach.OVERLAY
        else -> HostReach.PUBLIC
    }

    /**
     * True when nothing this machine advertises could be dialled from another network — the
     * condition MP-07 exists to name. The user is not looking at a broken IDE; they are
     * looking at an address that only means something at their desk.
     */
    fun isLanOnly(hosts: List<String>): Boolean =
        hosts.isNotEmpty() && hosts.none { of(it).survivesNetworkChange }
}
