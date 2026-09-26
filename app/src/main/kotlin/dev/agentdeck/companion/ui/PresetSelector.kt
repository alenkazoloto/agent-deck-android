package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.claudeagents.core.mobile.MobileHello
import dev.agentdeck.companion.data.NewChat
import dev.agentdeck.companion.data.NewChatTarget

/**
 * The desk's preset picker — a saved bundle of agent, account, model, effort and access — beside the pills it
 * fills. Absent unless the machine lists one. The pill reads what the target *is*: moving Agent, Model, Effort or
 * Mode by hand reads "No preset", and picking "No preset" keeps the cells as they are, as on the desk.
 */
@Composable
fun PresetSelector(
    hello: MobileHello?,
    target: NewChatTarget,
    onTarget: (NewChatTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = NewChat.presets(hello, target)
    if (presets.isEmpty()) return
    val on = NewChat.presetFor(hello, target)
    Column(modifier) {
        Selector(
            options = listOf(SelectorOption<String?>(NO_PRESET, null)) + presets.map { SelectorOption(it.name, it.name) },
            selected = on?.name,
            onSelect = { name ->
                val picked = presets.firstOrNull { it.name == name }
                onTarget(if (picked == null) target.copy(presetName = null) else target.withPreset(picked))
            },
            prefix = "Preset:",
        )
        // Named, never quoted: the text is the reader's own and long.
        if (on != null && on.extras.isNotEmpty()) Text(
            on.extras.joinToString(" · ") + " apply on this machine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal const val NO_PRESET = "No preset"
