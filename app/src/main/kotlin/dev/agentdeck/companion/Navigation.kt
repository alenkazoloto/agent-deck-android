package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDeepLink
import com.github.claudeagents.core.mobile.MobilePairingPayload
import com.github.claudeagents.core.mobile.MobileProtocol
import com.google.gson.JsonObject

/** Where the app is. Hand-rolled rather than a nav library: a handful of screens, one stack. */
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

    /** Every finished task whose changes nobody has looked at yet, across the machine's projects. */
    data object Review : Screen

    /**
     * The Workspace destination. The type keeps its old name because a persisted screen is the
     * link `agentdeck://settings`, and renaming the link would strand every restored install.
     */
    data object Settings : Screen

    /** Usage is pushed from Workspace and keeps Back; it does not consume another permanent bar tab. */
    data object Usage : Screen
}

/**
 * Conversation/New chat are pushed pages: hide the phone bar for composer space, retain the wider rail.
 * Scheduled, Settings and Review remain destinations (PLAN-MOBILE-REDESIGN.md).
 */
enum class Destination(val label: String) {
    FLEET("Chats"),
    REVIEW("Review"),
    SCHEDULED("Schedule"),
    SETTINGS("Workspace"),
}

/** A link that arrived from outside the app — a notification, a widget, or `adb shell am`. */
sealed interface DeepLink {
    data object Fleet : DeepLink
    data object Scheduled : DeepLink
    data object Settings : DeepLink
    data object Review : DeepLink
    data object Usage : DeepLink

    /** The launcher's long-press "New chat". Never persisted — see [Navigation.toJson]. */
    data object NewChat : DeepLink

    /**
     * A pairing QR read as a link rather than through the in-app scanner (PLAN-MOBILE-QR-LINK.md
     * M1). Never persisted, like [NewChat]: it prefills the confirm card, it is not a place.
     */
    data class Pair(val payload: MobilePairingPayload) : DeepLink

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

/** Keep deep-link, back-stack and restoration policy outside the view model for JVM verification. */
object Navigation {

    const val SCHEME = MobileDeepLink.SCHEME

    /** Which tab a screen belongs under. Null for [Screen.Pair], which has no bar at all. */
    fun destinationOf(screen: Screen): Destination? = when (screen) {
        Screen.Pair -> null
        Screen.Fleet, is Screen.Conversation, Screen.NewChat -> Destination.FLEET
        Screen.Review -> Destination.REVIEW
        Screen.Scheduled -> Destination.SCHEDULED
        Screen.Settings, Screen.Usage -> Destination.SETTINGS
    }

    /**
     * The tab to highlight: a pushed screen belongs to the root it was opened from. A task opened
     * from Review is still "in" Review — lighting Chats would send the reader's Back and their
     * eye to two different places. With no history (a restore) the screen's own tab answers.
     */
    fun destinationOf(screen: Screen, stack: List<Screen>): Destination? =
        stack.firstOrNull()?.takeIf { screen !is Screen.Usage }?.let { destinationOf(it) } ?: destinationOf(screen)

    fun screenOf(destination: Destination): Screen = when (destination) {
        Destination.FLEET -> Screen.Fleet
        Destination.REVIEW -> Screen.Review
        Destination.SCHEDULED -> Screen.Scheduled
        Destination.SETTINGS -> Screen.Settings
    }

    /**
     * Restored screens gated on hello capabilities need an immediate hello before their own fetch.
     * Add every such screen here or restoration can strand it with an empty capability list.
     */
    fun needsHello(screen: Screen): Boolean = when (screen) {
        // Review reads the `review` capability to decide between its list and "update the plugin".
        Screen.Scheduled, Screen.Settings, Screen.Usage, Screen.Review -> true
        else -> false
    }

    /** Whether the bar is drawn. A screen with a composer or its own back arrow keeps the width. */
    /** Hide bar labels from 1.5× text size to avoid breaking Workspace mid-word; retain TalkBack names and page titles. */
    fun labelsBar(fontScale: Float): Boolean = fontScale <= 1.3f

    fun showsBar(screen: Screen): Boolean = when (screen) {
        Screen.Fleet, Screen.Review, Screen.Scheduled, Screen.Settings -> true
        else -> false
    }

    // ---- the back stack ---------------------------------------------------------------

    /** Persisted child screens need a parent even when their in-memory history was lost. */
    fun restoredBackStack(screen: Screen): List<Screen> = when (screen) {
        is Screen.Conversation, Screen.NewChat -> listOf(Screen.Fleet)
        // Restored straight from a link or from process death, Usage has no history — and Back
        // out of a page whose parent is the tab it belongs to must land on that tab, not exit.
        Screen.Usage -> listOf(Screen.Settings)
        else -> emptyList()
    }

    fun backTarget(screen: Screen, stack: List<Screen>): Screen? =
        stack.lastOrNull() ?: restoredBackStack(screen).lastOrNull()

    // ---- deep links --------------------------------------------------------------------

    fun link(screen: Screen): String? = when (screen) {
        Screen.Fleet -> MobileDeepLink.FLEET
        Screen.Scheduled -> MobileDeepLink.SCHEDULED
        Screen.Settings -> MobileDeepLink.SETTINGS
        Screen.Review -> MobileDeepLink.REVIEW
        Screen.Usage -> MobileDeepLink.USAGE
        is Screen.Conversation -> MobileDeepLink.conversation(
            key = screen.key,
            title = screen.title,
            vendor = screen.vendor,
            projectPath = screen.projectPath,
        )
        else -> null
    }

    /** Use shared MobileDeepLink grammar because both IDE and phone mint links; reject everything else. */
    fun parse(raw: String?): DeepLink? = when (val target = MobileDeepLink.parse(raw)) {
        MobileDeepLink.Target.Fleet -> DeepLink.Fleet
        MobileDeepLink.Target.Scheduled -> DeepLink.Scheduled
        MobileDeepLink.Target.Settings -> DeepLink.Settings
        MobileDeepLink.Target.Review -> DeepLink.Review
        MobileDeepLink.Target.Usage -> DeepLink.Usage
        MobileDeepLink.Target.NewChat -> DeepLink.NewChat
        // A malformed fragment (bad base64, wrong version) is refused here, the same as a
        // foreign QR — it never opens a confirm card with nothing in it.
        is MobileDeepLink.Target.Pair -> MobilePairingPayload.decode(raw.orEmpty())?.let { DeepLink.Pair(it) }
        is MobileDeepLink.Target.Conversation -> DeepLink.Conversation(
            key = target.key,
            title = target.title,
            vendor = target.vendor,
            projectPath = target.projectPath,
        )
        null -> null
    }

    // ---- what survives process death ----------------------------------------------------

    /** Do not restore Pair or unsent NewChat screens; stored machines decide pairing and drafts survive separately. */
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
            DeepLink.Review -> Screen.Review
            DeepLink.Usage -> Screen.Usage
            // Unreachable from [toJson], which refuses to persist the composer — but a stored
            // link is a string, and a `when` that assumed the writer is the only source would
            // be an exhaustiveness hole the first time one is hand-edited.
            DeepLink.NewChat -> null
            // Same: [toJson] never persists a pairing link, and a code that outlived the
            // process restart would be well past its 120 s window anyway.
            is DeepLink.Pair -> null
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
