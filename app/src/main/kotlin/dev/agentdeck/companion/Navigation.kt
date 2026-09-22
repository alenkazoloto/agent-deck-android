package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileDeepLink
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

    /**
     * What this machine has spent and what its plans still allow. Opened *from* Workspace and
     * keeps a back arrow, like Conversation and New chat: it is a page of one destination, not
     * a destination of its own — a bar tab for a screen read once a week would cost the tabs
     * read constantly their width.
     */
    data object Usage : Screen
}

/**
 * The four places the navigation bar switches between (PLAN-MOBILE-REDESIGN.md).
 *
 * A destination is a *place*, not a heading: Scheduled and Settings each used to be one icon
 * in the top app bar, reachable only from Fleet and announcing nothing about being somewhere
 * else. Conversation and New chat are not destinations — they are opened *from* Fleet and keep
 * a back arrow. The bottom bar hides while one is open to leave room for the composer;
 * wider layouts retain the navigation rail. Review earns a place because finished work used
 * to be reachable only one conversation at a time, behind that conversation's Changes tab.
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
     * Whether landing on [screen] owes an immediate `/v1/hello`.
     *
     * A list rather than a condition at the call site, because the call site is a *restore* and
     * the list is the thing that goes stale: a screen whose own fetch is gated on a capability
     * reads an empty capability list when the process is restored straight onto it, declines its
     * one ask, and reports that the machine has nothing. Usage was added to the app as a pushed
     * screen — persisted, restorable — and that is exactly how it got stranded
     * (`memory/wiring.md`). Any new screen that reads `hello` belongs here.
     */
    fun needsHello(screen: Screen): Boolean = when (screen) {
        // Review reads the `review` capability to decide between its list and "update the plugin".
        Screen.Scheduled, Screen.Settings, Screen.Usage, Screen.Review -> true
        else -> false
    }

    /** Whether the bar is drawn. A screen with a composer or its own back arrow keeps the width. */
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

    /**
     * Parses a link this project minted, and refuses everything else.
     *
     * The grammar itself is [MobileDeepLink], shared with the plugin — the IDE mints links
     * into a conversation too, and a second copy of this `when` on the other side of the
     * wire is how the two ends start disagreeing about what a link means.
     */
    fun parse(raw: String?): DeepLink? = when (val target = MobileDeepLink.parse(raw)) {
        MobileDeepLink.Target.Fleet -> DeepLink.Fleet
        MobileDeepLink.Target.Scheduled -> DeepLink.Scheduled
        MobileDeepLink.Target.Settings -> DeepLink.Settings
        MobileDeepLink.Target.Review -> DeepLink.Review
        MobileDeepLink.Target.Usage -> DeepLink.Usage
        MobileDeepLink.Target.NewChat -> DeepLink.NewChat
        is MobileDeepLink.Target.Conversation -> DeepLink.Conversation(
            key = target.key,
            title = target.title,
            vendor = target.vendor,
            projectPath = target.projectPath,
        )
        null -> null
    }

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
            DeepLink.Review -> Screen.Review
            DeepLink.Usage -> Screen.Usage
            // Unreachable from [toJson], which refuses to persist the composer — but a stored
            // link is a string, and a `when` that assumed the writer is the only source would
            // be an exhaustiveness hole the first time one is hand-edited.
            DeepLink.NewChat -> null
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
