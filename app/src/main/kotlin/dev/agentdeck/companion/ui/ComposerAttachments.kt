package dev.agentdeck.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.PendingPhoto

/**
 * The composer's photo half: the button that picks one, and the chips for the ones already
 * uploaded.
 *
 * Absent, not disabled, when the machine does not advertise
 * [com.github.claudeagents.core.mobile.MobileProtocol.Capability.ATTACHMENTS] — the same rule
 * [DictateButton] follows, and the same reason: a control whose only outcome is a refusal
 * changes no decision.
 */
@Composable
fun AttachPhotoButton(busy: Boolean, onPicked: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        // The photo picker, not a storage permission: it runs out of process, returns one
        // grant-scoped uri, and needs no READ_MEDIA_IMAGES prompt on any API level.
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val picked = uri ?: return@rememberLauncherForActivityResult
        // Bounded by the picker's own single-item contract and read straight into memory: the
        // re-encode needs the whole buffer anyway, and a temp file would be a second copy of a
        // private photo living somewhere nothing sweeps.
        runCatching { context.contentResolver.openInputStream(picked)?.use { it.readBytes() } }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let(onPicked)
    }

    if (busy) {
        CircularProgressIndicator(
            modifier = modifier.padding(horizontal = 12.dp).size(20.dp),
            strokeWidth = 2.dp,
        )
        return
    }
    IconButton(
        onClick = {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        modifier = modifier,
    ) {
        Icon(
            AttachPhotoIcon,
            // The action's name, never printed beside the glyph — the phone's own reading of
            // `CLAUDE.md`'s no-label-beside-an-icon rule.
            contentDescription = "Attach a photo",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One chip per uploaded photo, above the field; the ✕ removes it before the send names it. */
@Composable
fun PhotoChips(photos: List<PendingPhoto>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (photos.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        photos.forEach { photo ->
            InputChip(
                selected = false,
                onClick = { onRemove(photo.attachmentId) },
                label = { Text(photo.label) },
                trailingIcon = {
                    Icon(
                        RemoveIcon,
                        contentDescription = "Remove ${photo.label}",
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

/**
 * A paperclip, drawn here rather than pulled from `material-icons-extended`.
 *
 * The app ships `material-icons-core` alone — the extended artifact is thousands of vectors for
 * the handful this app draws — so a missing glyph is inlined, exactly as [DeckIcons] and
 * [DictateButton]'s microphone already are.
 */
private val AttachPhotoIcon: ImageVector = ImageVector.Builder(
    name = "AttachPhoto",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        PathBuilder().apply {
            moveTo(16.5f, 6f)
            verticalLineTo(17.5f)
            arcToRelative(4f, 4f, 0f, false, true, -8f, 0f)
            verticalLineTo(5f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, 5f, 0f)
            verticalLineToRelative(10.5f)
            arcToRelative(1f, 1f, 0f, false, true, -2f, 0f)
            verticalLineTo(6f)
            horizontalLineTo(10f)
            verticalLineToRelative(9.5f)
            arcToRelative(2.5f, 2.5f, 0f, false, false, 5f, 0f)
            verticalLineTo(5f)
            arcToRelative(4f, 4f, 0f, false, false, -8f, 0f)
            verticalLineToRelative(12.5f)
            arcToRelative(5.5f, 5.5f, 0f, false, false, 11f, 0f)
            verticalLineTo(6f)
            close()
        }.nodes,
        fill = SolidColor(Color.Black),
    )
}.build()

private val RemoveIcon: ImageVector = ImageVector.Builder(
    name = "RemoveAttachment",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        PathBuilder().apply {
            moveTo(19f, 6.41f)
            lineTo(17.59f, 5f)
            lineTo(12f, 10.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(12f, 13.41f)
            lineTo(17.59f, 19f)
            lineTo(19f, 17.59f)
            lineTo(13.41f, 12f)
            close()
        }.nodes,
        fill = SolidColor(Color.Black),
    )
}.build()
