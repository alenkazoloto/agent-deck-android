package dev.agentdeck.companion.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Compact action glyph, full touch target, and one name for long-press, hover and TalkBack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(48.dp),
            interactionSource = interactions,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = tint,
                containerColor = if (hovered && enabled) tint.copy(alpha = 0.1f) else Color.Transparent,
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}

/** Small transport glyphs absent from material-icons-core; no extra icon dependency. */
internal object DeckIcons {
    val Pause = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 5f); horizontalLineTo(10f); verticalLineTo(19f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); horizontalLineTo(18f); verticalLineTo(19f); horizontalLineTo(14f); close()
        }
    }.build()
    val RunNow = ImageVector.Builder("RunNow", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 5f); lineTo(13f, 12f); lineTo(4f, 19f); close()
            moveTo(13f, 5f); lineTo(22f, 12f); lineTo(13f, 19f); close()
        }
    }.build()
    val CancelAll = ImageVector.Builder("CancelAll", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 8f); horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(4f); close()
            moveTo(3f, 4f); horizontalLineTo(7f); verticalLineTo(2f); horizontalLineTo(11f)
            verticalLineTo(4f); horizontalLineTo(15f); verticalLineTo(6f); horizontalLineTo(3f); close()
            moveTo(17f, 8f); horizontalLineTo(23f); verticalLineTo(10f); horizontalLineTo(17f); close()
            moveTo(17f, 13f); horizontalLineTo(22f); verticalLineTo(15f); horizontalLineTo(17f); close()
            moveTo(17f, 18f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(17f); close()
        }
    }.build()
    val Stop = ImageVector.Builder("Stop", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 5f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
        }
    }.build()
    val StopAndSend = ImageVector.Builder("StopAndSend", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(2f, 5f); horizontalLineTo(8f); verticalLineTo(19f); horizontalLineTo(2f); close()
            moveTo(11f, 5f); lineTo(23f, 12f); lineTo(11f, 19f); verticalLineTo(14f)
            lineTo(18f, 12f); lineTo(11f, 10f); close()
        }
    }.build()
}
