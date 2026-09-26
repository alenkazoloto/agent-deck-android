package dev.agentdeck.companion.data

/**
 * The pictures of assistant markdown the phone has already asked the machine for (t3code #10322).
 *
 * Kept past the bubble that asked, because a `LazyColumn` drops a bubble the moment it scrolls
 * away and re-asking the machine for the same screenshot on every scroll is a round trip and a
 * decode spent on nothing. Bounded by count, oldest-read out first: each entry is at most a
 * screen-width bitmap, and a chat rarely has more than a handful.
 *
 * Only a **refusal** is remembered as a failure. The machine said no to this file and will say
 * it again; a dropped link says nothing about the picture, so that one is retried the next time
 * the bubble is on screen.
 */
class MarkdownImages<V : Any>(private val maxEntries: Int = 32) {

    private val cache = object : LinkedHashMap<String, V?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V?>): Boolean =
            size > maxEntries
    }

    /** [scope] names whose conversation this is, so two machines' `docs/a.png` never collide. */
    suspend fun load(scope: String, fetch: suspend () -> V?): V? {
        synchronized(cache) { if (cache.containsKey(scope)) return cache[scope] }
        val image = try {
            fetch()
        } catch (refusal: BridgeRefusal) {
            synchronized(cache) { cache[scope] = null }
            return null
        }
        if (image != null) synchronized(cache) { cache[scope] = image }
        return image
    }

    fun clear() = synchronized(cache) { cache.clear() }
}
