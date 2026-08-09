package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import com.google.gson.JsonObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Where the app is. Hand-rolled rather than a nav library: six destinations, one stack. */
sealed interface Screen {
    data object Pair : Screen
    data object Fleet : Screen
    data class Conversation(
        val key: String,
        val title: String,
        val vendor: AgentVendor,
        val projectPath: String,
    ) : Screen
    data object Scheduled : Screen
    data object NewChat : Screen
    data object Settings : Screen
}

/**
 * The three places the navigation bar switches between.
 *
 * A destination is a *place*, not a heading: Scheduled and Settings each used to be one icon
 * in the top app bar, reachable only from Fleet and announcing nothing about being somewhere
 * else. Conversation and New chat are not destinations — they are opened *from* Fleet and keep
 * a back arrow, which is why the bar hides while one is open rather than fighting the composer
 * for the bottom of the screen.
 */
enum class Destination(val label: String) {
    FLEET("Fleet"),
    SCHEDULED("Scheduled"),
    SETTINGS("Settings"),
}

/** A link that arrived from outside the app — a notification, a widget, or `adb shell am`. */
sealed interface DeepLink {
    data object Fleet : DeepLink
    data object Scheduled : DeepLink
    data object Settings : DeepLink

    /**
     * [key] is all a link is required to carry. The rest is enriched from the fleet snapshot
     * when it is there, because a notification tapped a day later names a conversation whose
     * title has moved on, and the snapshot is the newer of the two.
     */
    data class Conversation(
        val key: String,
        val title: String? = null,
        val vendor: AgentVendor? = null,
        val projectPath: String? = null,
    ) : DeepLink
}

/**
 * Deep links, the back stack, and what survives process death — the three navigation
 * decisions, out here where a JVM test can hold them.
 *
 * They were all inside the view model and all wrong in the same way: `back()` returned to
 * Fleet from everywhere, so a conversation opened from Scheduled dropped the user somewhere
 * they had not been; nothing was restorable, so the system killing the app in the background
 * lost the open conversation; and there were no links at all, which is what a notification
 * needs before it can be worth tapping.
 */
object Navigation {

    const val SCHEME = "agentdeck"

    /** Which tab a screen belongs under. Null for [Screen.Pair], which has no bar at all. */
    fun destinationOf(screen: Screen): Destination? = when (screen) {
        Screen.Pair -> null
        Screen.Fleet, is Screen.Conversation, Screen.NewChat -> Destination.FLEET
        Screen.Scheduled -> Destination.SCHEDULED
        Screen.Settings -> Destination.SETTINGS
    }

    fun screenOf(destination: Destination): Screen = when (destination) {
        Destination.FLEET -> Screen.Fleet
        Destination.SCHEDULED -> Screen.Scheduled
        Destination.SETTINGS -> Screen.Settings
    }

    /** Whether the bar is drawn. A screen with a composer or its own back arrow keeps the width. */
    fun showsBar(screen: Screen): Boolean = when (screen) {
        Screen.Fleet, Screen.Scheduled, Screen.Settings -> true
        else -> false
    }

    // ---- the back stack ---------------------------------------------------------------

    /**
     * Where back goes from [stack]'s top: the entry beneath it, or null when the top is a
     * root destination and the gesture belongs to the system.
     *
     * The stack holds only what was *pushed* — a conversation, the new-chat composer — so
     * switching tabs never grows it, which is the behaviour every Android app with a bottom
     * bar has and the reason back from Fleet leaves the app instead of walking a history.
     */
    fun popped(stack: List<Screen>): List<Screen>? = if (stack.size <= 1) null else stack.dropLast(1)

    // ---- deep links --------------------------------------------------------------------

    fun link(screen: Screen): String? = when (screen) {
        Screen.Fleet -> "$SCHEME://fleet"
        Screen.Scheduled -> "$SCHEME://scheduled"
        Screen.Settings -> "$SCHEME://settings"
        is Screen.Conversation -> buildString {
            append(SCHEME).append("://conversation/").append(encode(screen.key))
            append("?title=").append(encode(screen.title))
            append("&vendor=").append(screen.vendor.name)
            append("&project=").append(encode(screen.projectPath))
        }
        else -> null
    }

    /**
     * Parses a link this app minted, and refuses everything else.
     *
     * A conversation key is structural (`SessionAttentionKey.persistenceKey()`), so it can
     * arrive percent-encoded and must survive the round trip; anything with no key names no
     * conversation and is rejected rather than opening a blank one.
     */
    fun parse(raw: String?): DeepLink? {
        val uri = raw?.trim().orEmpty()
        if (!uri.startsWith("$SCHEME://")) return null
        val rest = uri.removePrefix("$SCHEME://")
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        return when {
            path == "fleet" -> DeepLink.Fleet
            path == "scheduled" -> DeepLink.Scheduled
            path == "settings" -> DeepLink.Settings
            path.startsWith("conversation/") -> {
                val key = decode(path.removePrefix("conversation/"))
                if (key.isBlank()) return null
                val params = params(query)
                DeepLink.Conversation(
                    key = key,
                    title = params["title"]?.takeIf { it.isNotBlank() },
                    vendor = params["vendor"]?.let { name ->
                        AgentVendor.entries.firstOrNull { it.name == name }
                    },
                    projectPath = params["project"]?.takeIf { it.isNotBlank() },
                )
            }
            else -> null
        }
    }

    private fun params(query: String): Map<String, String> = query.split('&')
        .filter { it.contains('=') }
        .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrElse { value }

    // ---- what survives process death ----------------------------------------------------

    /**
     * The destination as persisted. [Screen.Pair] and [Screen.NewChat] return null on
     * purpose: pairing is decided by whether a machine is stored, and returning a user to a
     * half-typed new chat they never sent would put them in a composer they did not open.
     * The *draft* survives regardless, which is the part they typed.
     */
    fun toJson(screen: Screen): JsonObject? = when (screen) {
        Screen.Pair, Screen.NewChat -> null
        else -> JsonObject().apply {
            addProperty("v", MobileProtocol.VERSION)
            addProperty("link", link(screen))
        }
    }

    fun fromJson(o: JsonObject?): Screen? {
        val link = o?.get("link")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return when (val parsed = parse(link)) {
            DeepLink.Fleet -> Screen.Fleet
            DeepLink.Scheduled -> Screen.Scheduled
            DeepLink.Settings -> Screen.Settings
            is DeepLink.Conversation -> Screen.Conversation(
                key = parsed.key,
                title = parsed.title.orEmpty(),
                vendor = parsed.vendor ?: AgentVendor.CLAUDE,
                projectPath = parsed.projectPath.orEmpty(),
            )
            null -> null
        }
    }
}
