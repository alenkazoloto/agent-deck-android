package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * One chat's own scheduling form — the desk's `ScheduleDefaults.FormDraft` plus its per-chat
 * dependency draft. It wins over the machine-wide [ScheduleHabit] when that chat's dialog opens,
 * and is written when the dialog closes as well as when it schedules, as the desk's is.
 *
 * [dependencies] are the "After sessions finish…" picks left unscheduled; scheduling clears them.
 */
data class ScheduleChatForm(
    val habit: ScheduleHabit,
    val dependencies: List<MobileScheduleDependencySelection> = emptyList(),
)

/**
 * The chats' forms on one machine, oldest first. Bounded so a phone that schedules from many chats
 * keeps the recent ones rather than growing without limit; the desk caps its dependency drafts alike.
 */
class ScheduleChatForms(val forms: Map<String, ScheduleChatForm> = emptyMap()) {
    operator fun get(chatKey: String): ScheduleChatForm? = forms[chatKey]

    /** [form] for [chatKey], now the newest; the [MAX_CHATS]+1th oldest is dropped. */
    fun with(chatKey: String, form: ScheduleChatForm): ScheduleChatForms {
        val next = LinkedHashMap(forms).apply { remove(chatKey); put(chatKey, form) }
        while (next.size > MAX_CHATS) next.remove(next.keys.first())
        return ScheduleChatForms(next)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        forms.forEach { (key, form) ->
            add(key, JsonObject().apply {
                add("habit", form.habit.toJson())
                if (form.dependencies.isNotEmpty()) add("dependencies", JsonArray().also { arr ->
                    form.dependencies.forEach { arr.add(it.toJson()) }
                })
            })
        }
    }

    override fun equals(other: Any?) = other is ScheduleChatForms && other.forms == forms
    override fun hashCode() = forms.hashCode()

    companion object {
        const val MAX_CHATS = 64
        const val MAX_JSON = 512 * 1024
        private const val MAX_DEPENDENCIES = 20
        private const val MAX_ID = 256

        /** Stored preferences are untrusted: an unreadable chat entry is dropped, never failing the dialog. */
        fun fromJson(o: JsonObject?): ScheduleChatForms {
            if (o == null) return ScheduleChatForms()
            val forms = LinkedHashMap<String, ScheduleChatForm>()
            for ((key, value) in o.entrySet()) {
                val entry = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val dependencies = entry.get("dependencies")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
                    .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                    .map(MobileScheduleDependencySelection::fromJson)
                    .filter { it.id.isNotEmpty() && it.id.length <= MAX_ID }
                    .take(MAX_DEPENDENCIES)
                forms[key] = ScheduleChatForm(ScheduleHabit.fromJson(entry.get("habit")?.takeIf { it.isJsonObject }?.asJsonObject), dependencies)
            }
            while (forms.size > MAX_CHATS) forms.remove(forms.keys.first())
            return ScheduleChatForms(forms)
        }
    }
}
