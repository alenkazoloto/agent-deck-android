package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Indices refer to turns, excluding transient loading/history rows. No message text is retained. */
data class ReadingAnchor(
    val itemKey: String? = null,
    val index: Int = 0,
    val offset: Int = 0,
    val followingLatest: Boolean = true,
) {
    fun resolvedIndex(keys: List<String>): Int = keys.indexOf(itemKey).takeIf { it >= 0 }
        ?: index.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
}

data class FleetBrowsing(
    val anchor: ReadingAnchor = ReadingAnchor(followingLatest = false),
    val expandedGroups: Set<String> = emptySet(),
    val filter: FleetFilter = FleetFilter(),
    val sort: FleetSort = FleetSort.RECENT,
)

/** One encrypted preference per paired machine, bounded independently of transcript cache eviction. */
data class ReadingPositions(
    val conversations: Map<String, ReadingAnchor> = emptyMap(),
    val fleet: FleetBrowsing = FleetBrowsing(),
) {
    fun remembering(key: String, anchor: ReadingAnchor): ReadingPositions = copy(
        conversations = (conversations.filterKeys { it != key } + (key to anchor)).entries.toList()
            .takeLast(MAX_CONVERSATIONS).associate { it.key to it.value },
    )

    fun toJson(): JsonObject = JsonObject().apply {
        add("chats", JsonArray().also { chats ->
            conversations.entries.toList().takeLast(MAX_CONVERSATIONS).forEach { (key, anchor) ->
                chats.add(anchor.toJson().apply { addProperty("chat", key.take(MAX_KEY)) })
            }
        })
        add("fleet", fleet.anchor.toJson().apply {
            add("expanded", JsonArray().also { groups -> fleet.expandedGroups.take(16).forEach(groups::add) })
            addProperty("sort", fleet.sort.name)
            add("filter", JsonObject().apply {
                addProperty("query", fleet.filter.query.take(MAX_KEY))
                addProperty("project", fleet.filter.projectPath?.take(MAX_KEY))
                addProperty("account", fleet.filter.accountId?.take(MAX_KEY))
                addProperty("vendor", fleet.filter.vendor?.name)
                addProperty("model", fleet.filter.model?.take(MAX_KEY))
                addProperty("scope", fleet.filter.scope.name)
            })
        })
    }

    companion object {
        const val MAX_CONVERSATIONS = 64
        private const val MAX_KEY = 1024
        const val MAX_JSON = 256 * 1024

        fun fromJson(json: JsonObject?): ReadingPositions = runCatching {
            if (json == null) return ReadingPositions()
            val chats = json.getAsJsonArray("chats")?.toList().orEmpty().takeLast(MAX_CONVERSATIONS)
                .mapNotNull { element ->
                    val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val key = obj.string("chat") ?: return@mapNotNull null
                    key to obj.anchor()
                }.toMap()
            val fleet = json.getAsJsonObject("fleet") ?: JsonObject()
            val filter = fleet.getAsJsonObject("filter") ?: JsonObject()
            ReadingPositions(chats, FleetBrowsing(
                anchor = fleet.anchor().copy(followingLatest = false),
                expandedGroups = fleet.getAsJsonArray("expanded")?.toList().orEmpty().take(16)
                    .mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asString?.take(64) }.toSet(),
                sort = FleetSort.entries.firstOrNull { it.name == fleet.string("sort") } ?: FleetSort.RECENT,
                filter = FleetFilter(
                    query = filter.string("query").orEmpty(), projectPath = filter.string("project"),
                    accountId = filter.string("account"), model = filter.string("model"),
                    vendor = AgentVendor.entries.firstOrNull { it.name == filter.string("vendor") },
                    scope = FleetScope.entries.firstOrNull { it.name == filter.string("scope") } ?: FleetScope.ALL,
                ),
            ))
        }.getOrDefault(ReadingPositions())

        private fun JsonObject.string(key: String): String? = get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.take(MAX_KEY)

        private fun JsonObject.anchor() = ReadingAnchor(
            itemKey = string("key"),
            index = runCatching { get("index")?.asInt }.getOrNull()?.coerceIn(0, 1_000_000) ?: 0,
            offset = runCatching { get("offset")?.asInt }.getOrNull()?.coerceIn(0, 1_000_000) ?: 0,
            followingLatest = runCatching { get("following")?.asBoolean }.getOrNull() ?: true,
        )

        private fun ReadingAnchor.toJson() = JsonObject().apply {
            addProperty("key", itemKey?.take(MAX_KEY))
            addProperty("index", index.coerceIn(0, 1_000_000))
            addProperty("offset", offset.coerceIn(0, 1_000_000))
            addProperty("following", followingLatest)
        }
    }
}
