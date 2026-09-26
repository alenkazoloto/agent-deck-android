package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRunSelection

/**
 * What the user changed in one conversation's composer, field by field. An untouched field keeps
 * following the page, so a desk that later switches the chat's model is not overruled by an
 * effort pick made on the phone. A picked null is "Default", not "unpicked".
 */
data class ComposerPicks(val picked: Map<Field, String?> = emptyMap()) {

    /** [FAST] and [THINKING] hold "true" / "false"; a picked null is "Default" (the desk's own cell). */
    enum class Field { MODEL, EFFORT, MODE, FAST, THINKING }

    fun with(field: Field, value: String?): ComposerPicks = ComposerPicks(picked + (field to value))

    /**
     * What is left once [sent] has landed: a named pick the send carried is now the desk's own
     * choice and follows the page again, while one re-made during the send keeps its new answer.
     * A picked Default (null) stays: the machine cannot store "no value", so nothing else holds it.
     */
    fun without(sent: MobileRunSelection): ComposerPicks = ComposerPicks(
        picked.filterNot { (field, value) ->
            value != null && value == when (field) {
                Field.MODEL -> sent.model
                Field.EFFORT -> sent.effort
                Field.MODE -> sent.permissionMode
                Field.FAST -> sent.fastMode?.toString()
                Field.THINKING -> sent.thinking?.toString()
            }
        },
    )

    /** The fields nothing was picked for — the ones a follow-up leaves to the desk. */
    fun unpicked(): Set<MobileRunSelection.Field> = buildSet {
        if (Field.MODEL !in picked) add(MobileRunSelection.Field.MODEL)
        if (Field.EFFORT !in picked) add(MobileRunSelection.Field.EFFORT)
        if (Field.MODE !in picked) add(MobileRunSelection.Field.MODE)
        if (Field.FAST !in picked) add(MobileRunSelection.Field.FAST)
        if (Field.THINKING !in picked) add(MobileRunSelection.Field.THINKING)
    }

    fun over(base: MobileRunSelection): MobileRunSelection = MobileRunSelection(
        model = if (Field.MODEL in picked) picked[Field.MODEL] else base.model,
        effort = if (Field.EFFORT in picked) picked[Field.EFFORT] else base.effort,
        permissionMode = if (Field.MODE in picked) picked[Field.MODE] else base.permissionMode,
        fastMode = if (Field.FAST in picked) picked[Field.FAST]?.toBooleanStrictOrNull() else base.fastMode,
        thinking = if (Field.THINKING in picked) picked[Field.THINKING]?.toBooleanStrictOrNull() else base.thinking,
    )
}
