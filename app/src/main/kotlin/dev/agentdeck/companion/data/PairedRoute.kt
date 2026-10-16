package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.reach.Route
import com.github.claudeagents.core.str
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * One [Route] to a paired machine, plus where this phone got it. [source] is client-local and
 * never crosses the wire: a route from the pairing link stays until the user removes it, a
 * learned one is replaced by every `/v1/hosts` read that names routes.
 */
data class PairedRoute(val route: Route, val source: Source) {

    enum class Source(val wire: String) { PAIRED("paired"), LEARNED("learned") }

    fun toJson(): JsonObject = route.toJson().apply { addProperty("source", source.wire) }

    companion object {
        /** `MobileTunnel.DEFAULT_RELAY`, restated because that file is not shared with the phone. */
        private const val BORE_RELAY = "bore.pub"

        /**
         * The kind an address from a host that predates routes gets. Loopback is `lan` (an
         * emulator's `adb reverse` is this device's own network) and, being migrated, paired, so
         * no `/v1/hosts` answer can drop it.
         */
        fun kindOf(host: String): String = when (Reachability.of(host)) {
            HostReach.LOOPBACK, HostReach.LAN -> Route.LAN
            HostReach.OVERLAY -> Route.TAILNET
            HostReach.PUBLIC -> host.trim().lowercase().let {
                if (it == BORE_RELAY || it.endsWith(".$BORE_RELAY")) Route.RELAY else Route.CUSTOM
            }
        }

        /** An old pairing's addresses, every one on the pairing-wide [port], in their stored order. */
        fun migrate(hosts: List<String>, port: Int, source: Source = Source.PAIRED): List<PairedRoute> =
            hosts.map(String::trim).filter { it.isNotEmpty() }.distinct()
                .map { PairedRoute(Route(kindOf(it), it, port), source) }

        /**
         * Every route a pairing link names, or empty for a link from a host that predates routes.
         * The link carries only the routes its hosts on its port cannot say, so this expands them.
         */
        fun linkRoutes(payload: MobilePairingPayload): List<Route> =
            if (payload.routes.isEmpty()) emptyList() else payload.allRoutes(::kindOf)

        /**
         * What a pairing names: [routes] (see [linkRoutes]) when there are any, else [hosts] on
         * [port]. A loopback host [routes] leaves out is kept, so an emulator's `adb reverse`
         * pairing still dials it.
         */
        fun fromPairing(hosts: List<String>, port: Int, routes: List<Route>): List<PairedRoute> {
            if (routes.isEmpty()) return migrate(hosts, port)
            val covered = routes.map { it.host }.toSet()
            val loopback = hosts.filter { it !in covered && Reachability.of(it) == HostReach.LOOPBACK }
            return routes.distinct().map { PairedRoute(it, Source.PAIRED) } + migrate(loopback, port)
        }

        /** Unknown or missing `source` reads as paired: keeping a route is safer than dropping it. */
        fun listFrom(o: JsonObject): List<PairedRoute>? {
            val array = o.get("routes")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            return array.mapNotNull { e ->
                val route = Route.fromJson(e) ?: return@mapNotNull null
                val learned = e.asJsonObject.str("source") == Source.LEARNED.wire
                PairedRoute(route, if (learned) Source.LEARNED else Source.PAIRED)
            }.distinctBy { it.route }
        }

        fun toJsonArray(routes: List<PairedRoute>): JsonArray =
            JsonArray().also { arr -> routes.forEach { arr.add(it.toJson()) } }
    }
}
