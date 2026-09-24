package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.google.gson.JsonObject

/**
 * What the two schedule dialogs open on: the choices the user last scheduled with, so a habitual
 * cadence is not re-picked every time — the desk's `ScheduleDefaults.Settings`. One habit per
 * machine, shared by the create and the into-chat dialog as the desk shares its global one; the
 * prompt is not here (it is the draft's).
 *
 * "After sessions finish…" is never remembered: its sources name sessions that are gone by next
 * time, so a schedule made with it leaves the timing habit as it was.
 */
data class ScheduleHabit(
    val due: Due? = null,
    val relative: ScheduleWhen = ScheduleWhen.entries.first(),
    val inAmount: Int = 1,
    val inMinutes: Boolean = false,
    val atTime: String = "",
    val repeat: Boolean = false,
    /** The create dialog's Where and run choices; null until one was scheduled from it. */
    val target: NewChatTarget? = null,
) {
    /** The When pill's picks worth remembering; the pick itself only holds while it is still on offer. */
    enum class Due { RELATIVE, IN, AT, AT_RESET, AFTER_RUN }

    fun toJson(): JsonObject = JsonObject().apply {
        due?.let { addProperty("due", it.name) }
        addProperty("relative", relative.name)
        addProperty("inAmount", inAmount)
        addProperty("inMinutes", inMinutes)
        addProperty("atTime", atTime)
        addProperty("repeat", repeat)
        target?.let { t ->
            add("target", JsonObject().apply {
                addProperty("project", t.projectPath)
                addProperty("vendor", t.vendor.name)
                t.model?.let { addProperty("model", it) }
                t.accountId?.let { addProperty("account", it) }
                t.effort?.let { addProperty("effort", it) }
                t.permissionMode?.let { addProperty("mode", it) }
                t.fastMode?.let { addProperty("fast", it) }
                t.thinking?.let { addProperty("thinking", it) }
            })
        }
    }

    companion object {
        private const val MAX_TEXT = 1024
        const val MAX_JSON = 16 * 1024

        /** Stored preferences are untrusted: every field falls back to its default rather than failing the dialog. */
        fun fromJson(o: JsonObject?): ScheduleHabit {
            if (o == null) return ScheduleHabit()
            fun text(obj: JsonObject, name: String) = obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.take(MAX_TEXT)
            fun flag(obj: JsonObject, name: String) =
                obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            val target = o.get("target")?.takeIf { it.isJsonObject }?.asJsonObject?.let { t ->
                val vendor = AgentVendor.entries.firstOrNull { it.name == text(t, "vendor") }
                val project = text(t, "project")
                if (vendor == null || project == null) null
                else NewChatTarget(
                    projectPath = project,
                    vendor = vendor,
                    model = text(t, "model"),
                    accountId = text(t, "account"),
                    effort = text(t, "effort"),
                    permissionMode = text(t, "mode"),
                    fastMode = flag(t, "fast"),
                    thinking = flag(t, "thinking"),
                )
            }
            val amount = o.get("inAmount")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            return ScheduleHabit(
                due = Due.entries.firstOrNull { it.name == text(o, "due") },
                relative = ScheduleWhen.entries.firstOrNull { it.name == text(o, "relative") } ?: ScheduleWhen.entries.first(),
                inAmount = (amount ?: 1).coerceIn(1, AfterDelay.MAX_AMOUNT),
                inMinutes = flag(o, "inMinutes") ?: false,
                atTime = text(o, "atTime")?.takeIf { AtTime(it).valid }.orEmpty(),
                repeat = flag(o, "repeat") ?: false,
                target = target,
            )
        }
    }
}
