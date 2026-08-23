package dev.agentdeck.companion.data

import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobilePush
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * What the app may interrupt the user for.
 *
 * One entry per *trigger* because each one becomes a system notification channel: the user
 * tunes importance, sound and bypass in Android's own settings rather than in a bespoke screen
 * this app would have to invent. Only the three attention states the fleet snapshot actually
 * carries are here — the plan lists plan-limit forecast and spend handoff too, and neither is
 * on the wire, so a toggle for them would be a switch that does nothing.
 *
 * **[id] and [attention] are [MobilePush.Trigger]'s, not this enum's.** The same three names now
 * travel the wire in a push body, and a channel id declared here as well would be a second
 * producer of a format only one side of the pairing can test — the shape that already cost this
 * protocol a `run` frame nobody sent. What stays local is the copy: [title] and [description]
 * are what Android shows in its own notification settings, which is no business of the wire's.
 */
enum class NotifyTrigger(
    val push: MobilePush.Trigger,
    val title: String,
    val description: String,
) {
    NEEDS_YOU(
        MobilePush.Trigger.NEEDS_YOU,
        "Needs you",
        "An agent is blocked on your answer.",
    ),
    FAILED(
        MobilePush.Trigger.FAILED,
        "Failed",
        "A run stopped with an error.",
    ),
    FINISHED(
        MobilePush.Trigger.FINISHED,
        "Finished",
        "A run finished and has not been reviewed.",
    ),
    ;

    val id: String get() = push.id

    val attention: SessionAttentionState get() = push.attention

    companion object {
        fun byId(id: String?): NotifyTrigger? = entries.firstOrNull { it.id == id }

        /** The app-side twin of a trigger that arrived in a push body. */
        fun of(trigger: MobilePush.Trigger): NotifyTrigger = entries.first { it.push == trigger }

        /** Which trigger a row is in, or null for a row nothing should buzz about. */
        fun of(row: MobileFleetRow): NotifyTrigger? = entries.firstOrNull { it.attention == row.attention }
    }
}

enum class ThemeChoice(val label: String) {
    SYSTEM("Match the system"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        fun byName(name: String?): ThemeChoice = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Everything the Settings screen owns, in one persisted object.
 *
 * [triggers] defaults to the two that are *about* the user — a finished run is news, not an
 * interruption, and an app that buzzes for all three on a busy machine is one the user turns
 * off entirely. [stayConnected] is opt-in for the same reason it is opt-in everywhere: it costs
 * a permanent notification and a socket, and nothing should take either without being asked.
 */
data class AppSettings(
    val triggers: Set<NotifyTrigger> = setOf(NotifyTrigger.NEEDS_YOU, NotifyTrigger.FAILED),
    val stayConnected: Boolean = false,
    val dynamicColor: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /**
     * Whether a newer published build is announced in the app. **On** by default, unlike every
     * other opt-in here: this app is sideloaded, so nothing else on the phone will ever tell its
     * owner that the build they are holding has been superseded — and the Settings row still
     * offers the update when this is off, because turning off the *notice* is not asking to be
     * left behind.
     */
    val updateNotices: Boolean = true,
) {
    fun notifies(trigger: NotifyTrigger?): Boolean = trigger != null && trigger in triggers

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("v", MobileProtocol.VERSION)
        add("triggers", JsonArray().also { arr -> triggers.forEach { arr.add(it.id) } })
        addProperty("stayConnected", stayConnected)
        addProperty("dynamicColor", dynamicColor)
        addProperty("theme", theme.name)
        addProperty("updateNotices", updateNotices)
    }

    companion object {
        fun fromJson(o: JsonObject): AppSettings {
            val triggers = o.get("triggers")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { NotifyTrigger.byId(it.takeIf { e -> e.isJsonPrimitive }?.asString) }
                ?.toSet()
            return AppSettings(
                // Absent means "never written", which is the default set; an *empty* array is
                // a user who turned every trigger off and must stay off.
                triggers = triggers ?: AppSettings().triggers,
                stayConnected = o.get("stayConnected")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                dynamicColor = o.get("dynamicColor")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                theme = ThemeChoice.byName(o.get("theme")?.takeIf { it.isJsonPrimitive }?.asString),
                // Absent is a settings blob written before the field existed, and that user
                // wants the announcement as much as a new one does — so absent means on.
                updateNotices = o.get("updateNotices")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            )
        }
    }
}
