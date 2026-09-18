package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileRunSelection

/**
 * What the user changed in one conversation's composer, field by field. An untouched field keeps
 * following the page, so a desk that later switches the chat's model is not overruled by an
 * effort pick made on the phone. A picked null is "Default", not "unpicked".
 */
data class ComposerPicks(val picked: Map<Field, String?> = emptyMap()) {

    enum class Field { MODEL, EFFORT, MODE }

    fun with(field: Field, value: String?): ComposerPicks = ComposerPicks(picked + (field to value))

    fun over(base: MobileRunSelection): MobileRunSelection = MobileRunSelection(
        model = if (Field.MODEL in picked) picked[Field.MODEL] else base.model,
        effort = if (Field.EFFORT in picked) picked[Field.EFFORT] else base.effort,
        permissionMode = if (Field.MODE in picked) picked[Field.MODE] else base.permissionMode,
    )
}
