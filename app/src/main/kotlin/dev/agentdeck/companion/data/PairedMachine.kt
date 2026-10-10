package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileProtocol
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * A machine this phone is paired with, as persisted.
 *
 * [hosts] is a list because a machine on a LAN *and* an overlay network has two addresses
 * the phone may reach it by; [preferredHost] is whichever answered last, so a reconnect
 * does not walk the list again.
 */
data class PairedMachine(
    val machineName: String,
    val hosts: List<String>,
    val port: Int,
    val spkiFingerprint: String,
    val token: String,
    val deviceId: String,
    val preferredHost: String? = null,
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

    /** The preferred host first; the rest keep their pairing order. */
    fun dialOrder(): List<String> =
        (listOfNotNull(preferredHost) + hosts).distinct().ifEmpty { hosts }

    /**
     * The same machine, with a remembered address that only meant something on the *previous*
     * network dropped.
     *
     * [preferredHost] is whichever host answered last and [dialOrder] puts it first, always —
     * so one successful connection at the desk pinned `192.168.1.24` to the front of every
     * request and every stream reconnect, on every network, forever. Off the LAN that address
     * cannot answer, and each call paid [LinkPolicy.CONNECT_TIMEOUT_MS] to find out before
     * falling through to the one that could. `MobileBinding.kt`'s KDoc on the plugin side
     * predicted the symptom exactly — "a phone away from the network is unusable" — while
     * describing a client that did not yet reorder.
     *
     * A new default network is precisely the event that invalidates the memory, so this is
     * called from there. An overlay or public address is kept: those are the ones that still
     * mean something, and forgetting them would throw away the only host that would work.
     */
    fun forgettingNetworkScopedHost(): PairedMachine {
        val remembered = preferredHost ?: return this
        return if (Reachability.of(remembered).survivesNetworkChange) this else copy(preferredHost = null)
    }

    /**
     * The same machine, holding the addresses it says it answers on *now*.
     *
     * The list a QR carried was the machine's at the moment it was drawn and this class kept it
     * for the life of the pairing, so a phone paired before the relay had a grant, before
     * Tailscale was up or before the LAN address moved could never reach the machine from
     * another network — whatever the IDE was later set to. The machine's own list leads, in its
     * order (the relay first); an old address is kept only if it still means something off the
     * network it was learned on, or is loopback (an emulator's `adb reverse`). An old LAN
     * address the machine no longer names is dropped: it can only cost a connect timeout.
     *
     * The relay is the case that decides "kept": while it is reconnecting the machine's list
     * omits it, and forgetting it then would strand the phone at exactly the moment it is the
     * only way back.
     */
    fun learningHosts(advertised: List<String>): PairedMachine {
        val fresh = advertised.map(String::trim)
            .filter { it.isNotEmpty() && Reachability.of(it) != HostReach.LOOPBACK }
            .distinct()
        if (fresh.isEmpty()) return this
        val kept = hosts.filter {
            it !in fresh && Reachability.of(it).let { reach ->
                reach.survivesNetworkChange || reach == HostReach.LOOPBACK
            }
        }
        val (loopback, travelling) = kept.partition { Reachability.of(it) == HostReach.LOOPBACK }
        val merged = fresh + travelling + loopback
        if (merged == hosts) return this
        val remembered = preferredHost?.takeIf { it in merged }
        return copy(hosts = merged, preferredHost = remembered)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("v", MobileProtocol.VERSION)
        addProperty("machine", machineName)
        add("hosts", JsonArray().also { arr -> hosts.forEach(arr::add) })
        addProperty("port", port)
        addProperty("spki", spkiFingerprint)
        addProperty("token", token)
        addProperty("deviceId", deviceId)
        preferredHost?.let { addProperty("preferredHost", it) }
    }

    companion object {
        fun fromJson(o: JsonObject): PairedMachine? {
            val token = o.get("token")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val spki = o.get("spki")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            val port = o.get("port")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
            val hosts = o.get("hosts")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                .orEmpty()
            if (hosts.isEmpty()) return null
            return PairedMachine(
                machineName = o.get("machine")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                hosts = hosts,
                port = port,
                spkiFingerprint = spki,
                token = token,
                deviceId = o.get("deviceId")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                preferredHost = o.get("preferredHost")?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }
    }
}
