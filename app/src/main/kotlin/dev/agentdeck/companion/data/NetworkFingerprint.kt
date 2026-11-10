package dev.agentdeck.companion.data

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Which network the phone is on, as far as [RouteMemory][com.github.claudeagents.core.reach.RouteMemory]
 * needs: stable across reconnects to the same Wi-Fi, different between networks. Built only from
 * what `ACCESS_NETWORK_STATE` exposes — the SSID needs location permission and is not used; the
 * network handle changes on every reconnect and is not used either.
 */
internal object NetworkFingerprint {

    /** Null when there is no default network or Android will not describe it. */
    fun of(manager: ConnectivityManager?): String? = runCatching {
        val m = manager ?: return null
        val active = m.activeNetwork ?: return null
        val own = describe(m, active) ?: return null
        // A VPN (Tailscale) looks the same on every network; the network under it is what changed.
        if (!own.startsWith(VPN)) return hash(own)
        @Suppress("DEPRECATION")
        val under = m.allNetworks.asSequence().filter { it != active }
            .mapNotNull { describe(m, it) }.firstOrNull { !it.startsWith(VPN) }
        hash(own + "|" + under.orEmpty())
    }.getOrNull()

    private fun describe(m: ConnectivityManager, network: Network): String? {
        val caps = m.getNetworkCapabilities(network) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        val link = m.getLinkProperties(network) ?: return null
        val transport = transport(caps)
        return describe(
            transport,
            link.interfaceName,
            link.dnsServers.mapNotNull { it.hostAddress },
            link.linkAddresses.mapNotNull(::prefix),
            link.routes.mapNotNull { it.gateway?.takeUnless(InetAddress::isAnyLocalAddress)?.hostAddress },
        )
    }

    /**
     * Pure, for tests. Cellular keeps only transport, interface and DNS: its addresses move
     * within one carrier, and a fingerprint that drifts only costs a race, never a wrong winner.
     */
    fun describe(transport: String, iface: String?, dns: List<String>, prefixes: List<String>, gateways: List<String>): String =
        if (transport == CELLULAR) listOf(transport, iface.orEmpty(), dns.sorted().joinToString(",")).joinToString("|")
        else listOf(
            transport, iface.orEmpty(), dns.sorted().joinToString(","),
            prefixes.sorted().joinToString(","), gateways.sorted().joinToString(","),
        ).joinToString("|")

    fun hash(description: String): String =
        MessageDigest.getInstance("SHA-256").digest(description.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    private fun transport(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR
        else -> "other"
    }

    /** The network part of an address, e.g. `192.168.1.0/24`; the host part changes per lease. */
    private fun prefix(address: LinkAddress): String? {
        val bytes = address.address?.address ?: return null
        val bits = address.prefixLength.coerceIn(0, bytes.size * 8)
        for (i in bytes.indices) {
            val keep = (bits - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xff shl (8 - keep)) and 0xff).toByte()
        }
        return InetAddress.getByAddress(bytes).hostAddress + "/" + bits
    }

    private const val VPN = "vpn"
    private const val CELLULAR = "cellular"
}
