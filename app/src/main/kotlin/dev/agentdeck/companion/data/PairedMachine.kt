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
