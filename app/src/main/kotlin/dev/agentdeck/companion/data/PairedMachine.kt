package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileHosts
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.reach.Route
import com.github.claudeagents.core.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * A machine this phone is paired with, as persisted.
 *
 * [routes] is what the phone dials: each address with its own port, and whether it came from
 * the pairing link or was learned from `/v1/hosts`. [hosts] + [port] are the pre-route shape,
 * still written so a downgraded app can read the entry. [explicitRoutes] is null for an entry
 * that predates routes; [routes] then derives from [hosts] + [port], so `copy(hosts = …)` on such
 * an entry still means what it did. [preferredRoute] is whichever answered last, so a reconnect
 * does not walk the list again.
 */
data class PairedMachine(
    val machineName: String,
    val hosts: List<String>,
    val port: Int,
    val spkiFingerprint: String,
    val token: String,
    val deviceId: String,
    val preferredRoute: Route? = null,
    val explicitRoutes: List<PairedRoute>? = null,
) {
    /**
     * What every per-machine cache is keyed by, now that more than one may be paired.
     *
     * The device id is minted by the machine at pairing, so two pairings to the same host are
     * two entries rather than one overwriting the other. The fallback exists because a very
     * old plugin could accept a pairing without returning one, and a blank key would make two
     * machines share one drafts file.
     */
    val id: String
        get() = deviceId.takeIf { it.isNotBlank() } ?: "$machineName@${spkiFingerprint.take(16)}"

    val routes: List<PairedRoute>
        get() = explicitRoutes ?: PairedRoute.migrate(hosts, port)

    val preferredHost: String? get() = preferredRoute?.host

    /** Every address any route names, in route order — what the reach copy reasons about. */
    fun addresses(): List<String> = routes.map { it.route.host }.distinct()

    /** The preferred route first; the rest keep their stored order. */
    fun dialOrder(): List<Route> = (listOfNotNull(preferredRoute) + routes.map { it.route }).distinct()

    /** The route on [host] this machine holds, for a connection that reports only a host. */
    fun routeOn(host: String): Route? =
        routes.firstOrNull { it.route.host == host }?.route

    /**
     * The same machine, with a remembered route that only meant something on the *previous*
     * network dropped.
     *
     * [preferredRoute] is whichever route answered last and [dialOrder] puts it first, always —
     * so one successful connection at the desk pinned `192.168.1.24` to the front of every
     * request and every stream reconnect, on every network, forever. Off the LAN that address
     * cannot answer, and each call paid [LinkPolicy.CONNECT_TIMEOUT_MS] to find out before
     * falling through to the one that could. `MobileBinding.kt`'s KDoc on the plugin side
     * predicted the symptom exactly — "a phone away from the network is unusable" — while
     * describing a client that did not yet reorder.
     *
     * A new default network is precisely the event that invalidates the memory, so this is
     * called from there. An overlay or public address is kept: those are the ones that still
     * mean something, and forgetting them would throw away the only route that would work.
     */
    fun forgettingNetworkScopedRoute(): PairedMachine {
        val remembered = preferredRoute ?: return this
        return if (Reachability.of(remembered.host).survivesNetworkChange) this else copy(preferredRoute = null)
    }

    /** `/v1/hosts` from a host that names routes replaces learned ones; an older host's hosts list is merged. */
    fun learning(advertised: MobileHosts): PairedMachine =
        if (advertised.routes.isNotEmpty()) learningRoutes(advertised.routes) else learningLegacyHosts(advertised.hosts)

    /**
     * The same machine, holding the routes it says it answers on *now*: the host's list replaces
     * every learned route, in the host's order, and routes from the pairing link follow it until
     * the user removes them. A route the host names that the link also named stays paired.
     *
     * Paired routes staying is what used to need a rule of its own: the relay is absent from the
     * machine's list exactly while it reconnects, and forgetting it then would strand the phone.
     */
    fun learningRoutes(fresh: List<Route>): PairedMachine {
        val named = fresh.filter { Reachability.of(it.host) != HostReach.LOOPBACK }.distinct()
        if (named.isEmpty()) return this
        val paired = routes.filter { it.source == PairedRoute.Source.PAIRED }
        val pairedSet = paired.map { it.route }.toSet()
        val namedSet = named.toSet()
        val merged = named.map {
            PairedRoute(it, if (it in pairedSet) PairedRoute.Source.PAIRED else PairedRoute.Source.LEARNED)
        } + paired.filterNot { it.route in namedSet }
        return withRoutes(merged)
    }

    /**
     * [learningRoutes] for a host that predates routes, keeping that era's merge exactly.
     *
     * The list a QR carried was the machine's at the moment it was drawn and this class kept it
     * for the life of the pairing, so a phone paired before the relay had a grant, before
     * Tailscale was up or before the LAN address moved could never reach the machine from
     * another network — whatever the IDE was later set to. The machine's own list leads, in its
     * order (the relay first); an old address is kept only if it still means something off the
     * network it was learned on, or is loopback (an emulator's `adb reverse`). An old LAN
     * address the machine no longer names is dropped whatever its source: such a host cannot
     * tell paired from learned, and the address can only cost a connect timeout.
     *
     * The relay is the case that decides "kept": while it is reconnecting the machine's list
     * omits it, and forgetting it then would strand the phone at exactly the moment it is the
     * only way back. New addresses dial the pairing [port], as every address of such a host does.
     */
    fun learningLegacyHosts(advertised: List<String>): PairedMachine {
        val fresh = advertised.map(String::trim)
            .filter { it.isNotEmpty() && Reachability.of(it) != HostReach.LOOPBACK }
            .distinct()
        if (fresh.isEmpty()) return this
        val current = addresses()
        val kept = current.filter {
            it !in fresh && Reachability.of(it).let { reach ->
                reach.survivesNetworkChange || reach == HostReach.LOOPBACK
            }
        }
        val (loopback, travelling) = kept.partition { Reachability.of(it) == HostReach.LOOPBACK }
        val merged = fresh + travelling + loopback
        if (merged == current) return this
        val byHost = routes.groupBy { it.route.host }
        return withRoutes(merged.flatMap { host ->
            byHost[host] ?: PairedRoute.migrate(listOf(host), port, PairedRoute.Source.LEARNED)
        })
    }

    /**
     * [hosts] follows as the routes on the pairing [port], which is what a downgraded app can
     * dial; a remembered route that is gone must not stay first in the dial order.
     */
    private fun withRoutes(next: List<PairedRoute>): PairedMachine {
        if (next == routes) return this
        val legacy = next.filter { it.route.port == port }.map { it.route.host }.distinct().ifEmpty { hosts }
        val remembered = preferredRoute?.takeIf { p -> next.any { it.route == p } }
        return copy(hosts = legacy, explicitRoutes = next, preferredRoute = remembered)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("v", MobileProtocol.VERSION)
        addProperty("machine", machineName)
        add("hosts", JsonArray().also { arr -> hosts.forEach(arr::add) })
        addProperty("port", port)
        add("routes", PairedRoute.toJsonArray(routes))
        addProperty("spki", spkiFingerprint)
        addProperty("token", token)
        addProperty("deviceId", deviceId)
        preferredRoute?.let { preferred ->
            add("preferredRoute", preferred.toJson())
            // An older app dials every host on the pairing port; any other port would mislead it.
            if (preferred.port == port) addProperty("preferredHost", preferred.host)
        }
    }

    companion object {
        /** A machine just paired over [routes] (or, from a link without routes, [hosts] on [port]). */
        fun paired(
            machineName: String,
            hosts: List<String>,
            port: Int,
            routes: List<Route>,
            spkiFingerprint: String,
            token: String,
            deviceId: String,
            preferredRoute: Route?,
        ) = PairedMachine(
            machineName, hosts, port, spkiFingerprint, token, deviceId, preferredRoute,
            explicitRoutes = PairedRoute.fromPairing(hosts, port, routes),
        )

        fun fromJson(o: JsonObject): PairedMachine? {
            val token = o.get("token")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val spki = o.get("spki")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val port = o.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
            val hosts = o.get("hosts")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                .orEmpty()
            val explicit = PairedRoute.listFrom(o)?.takeIf { it.isNotEmpty() }
            if (hosts.isEmpty() && explicit == null) return null
            val routes = explicit ?: PairedRoute.migrate(hosts, port)
            val preferred = Route.fromJson(o.get("preferredRoute"))
                ?: o.str("preferredHost")?.let { host ->
                    routes.firstOrNull { it.route.host == host }?.route ?: Route(PairedRoute.kindOf(host), host, port)
                }
            return PairedMachine(
                machineName = o.get("machine")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                hosts = hosts,
                port = port,
                spkiFingerprint = spki,
                token = token,
                deviceId = o.get("deviceId")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                preferredRoute = preferred,
                explicitRoutes = explicit,
            )
        }
    }
}
