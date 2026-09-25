package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor

@Composable
internal fun AgentIdentity(vendor: AgentVendor, modifier: Modifier = Modifier, announce: Boolean = true, acp: Boolean = false) {
    Icon(
        imageVector = when {
            acp -> AcpIdentity
            vendor == AgentVendor.CLAUDE -> ClaudeIdentity
            else -> CodexIdentity
        },
        contentDescription = (if (acp) "ACP agent" else vendor.label()).takeIf { announce },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(24.dp),
    )
}

// Match the plugin's icons/claude.svg and icons/codex.svg, including rounded strokes.
private val ClaudeIdentity = ImageVector.Builder("Claude", 16.dp, 16.dp, 16f, 16f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
        moveTo(11.09f, 4.32f)
        arcTo(4.8f, 4.8f, 0f, true, false, 11.09f, 11.68f)
    }
}.build()

private val CodexIdentity = ImageVector.Builder("Codex", 16.dp, 16.dp, 16f, 16f).apply {
    path(
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3.6f, 4.6f); lineTo(7.6f, 8f); lineTo(3.6f, 11.4f)
        moveTo(8.8f, 11.4f); horizontalLineTo(12.6f)
    }
}.build()

/** A chat on an ACP agent: neither vendor's glyph, which would name an agent that is not answering. */
private val AcpIdentity = ImageVector.Builder("ACP", 16.dp, 16.dp, 16f, 16f).apply {
    path(
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(6f, 4.6f); lineTo(2.6f, 8f); lineTo(6f, 11.4f)
        moveTo(10f, 4.6f); lineTo(13.4f, 8f); lineTo(10f, 11.4f)
    }
}.build()
