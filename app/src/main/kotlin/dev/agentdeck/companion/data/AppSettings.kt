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
 * this app would have to invent. Three are the attention states the fleet snapshot carries; the
 * fourth, [PLAN_USAGE], is the desk's plan-limit warning, which only a push brings. Spend handoff
 * is on no wire, so a toggle for it would be a switch that does nothing.
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
    PLAN_USAGE(
        MobilePush.Trigger.PLAN_USAGE,
        "Plan usage",
        "A plan window has reached the percentage set at the desk.",
    ),
    ;

    val id: String get() = push.id

    /** Null for [PLAN_USAGE]: no conversation is ever in it. */
    val attention: SessionAttentionState? get() = push.attention

    companion object {
        fun byId(id: String?): NotifyTrigger? = entries.firstOrNull { it.id == id }

        /** The app-side twin of a trigger that arrived in a push body. */
        fun of(trigger: MobilePush.Trigger): NotifyTrigger = entries.first { it.push == trigger }

        /** Which trigger a row is in, or null for a row nothing should buzz about. */
        fun of(row: MobileFleetRow): NotifyTrigger? =
            entries.firstOrNull { it.attention != null && it.attention == row.attention }
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
    /**
     * How code is laid out. Both off by default: a phone is narrow, and a wrapped line and a
     * line-number gutter each cost the column the code is in. They are settings rather than a
     * control on the diff itself because the answer is a reading habit, not a per-file
     * decision — and a toggle on every file is a toggle in the way of every file.
     *
     * [diffSoftWrap] reaches a *markdown code fence* as well as a diff line (`LocalCodeWrap`):
     * a reader who wants long lines folded wants it wherever the phone shows code, and two
     * settings for one habit is the kind of pair nobody keeps in sync. [diffLineNumbers] stays
     * the diff's alone — a chat's code fence has no line numbers to be right about.
     */
    val diffSoftWrap: Boolean = false,
    val diffLineNumbers: Boolean = false,
    /** The desk's "Complete emoji after :", on by default there too: the `:` popup and `:name:` swap. */
    val emojiCompletion: Boolean = true,
    /**
     * Whether each turn's "Tool calls (N)" group starts unfolded. **Off** by default: unrolled
     * groups buried the answers under `Bash`/`Read` lines once already (`ToolDisclosure`), so
     * only a reader who asked for the log gets it. The desk's Ctrl+O / Ctrl+E ladder is a
     * session-only key; a phone has no keys, so this is the persisted form of its first rung.
     */
    val openToolCalls: Boolean = false,
) {
    fun notifies(trigger: NotifyTrigger?): Boolean = trigger != null && trigger in triggers

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("v", MobileProtocol.VERSION)
        add("triggers", JsonArray().also { arr -> triggers.forEach { arr.add(it.id) } })
        addProperty("stayConnected", stayConnected)
        addProperty("dynamicColor", dynamicColor)
        addProperty("theme", theme.name)
        addProperty("updateNotices", updateNotices)
        addProperty("diffSoftWrap", diffSoftWrap)
        addProperty("diffLineNumbers", diffLineNumbers)
        addProperty("emojiCompletion", emojiCompletion)
        addProperty("openToolCalls", openToolCalls)
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
                diffSoftWrap = o.get("diffSoftWrap")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                diffLineNumbers = o.get("diffLineNumbers")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                emojiCompletion = o.get("emojiCompletion")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                openToolCalls = o.get("openToolCalls")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            )
        }
    }
}
