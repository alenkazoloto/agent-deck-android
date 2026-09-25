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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import android.provider.OpenableColumns
import com.github.claudeagents.core.mobile.MobileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer's attachment half: the button that picks a photo or a text file, and the chips for
 * the ones already uploaded.
 *
 * Absent, not disabled, when the machine does not advertise
 * [com.github.claudeagents.core.mobile.MobileProtocol.Capability.ATTACHMENTS] — the same rule
 * [DictateButton] follows, and the same reason: a control whose only outcome is a refusal
 * changes no decision. With [canAttachFile] (`attachment-text`) the paperclip opens a two-item
 * menu (Photo, and File where the machine also advertises `attachment-pdf`, `attachment-zip`, `attachment-gzip`, `attachment-tar`, `attachment-xz` or `attachment-bzip2` — a PDF, archive, gzip, tar, xz, bzip2 or text file, told apart by its bytes) instead of going straight to the photo picker; an older machine keeps the one-tap photo.
 */
@Composable
fun AttachButton(
    busy: Boolean,
    canAttachFile: Boolean,
    onPhoto: (ByteArray) -> Unit,
    onFile: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    /** `attachment-pdf`: the file menu item reads "File" and a PDF may be picked up to its own cap. */
    canAttachPdf: Boolean = false,
    /** `attachment-zip`: likewise for a ZIP archive, up to the same cap. */
    canAttachZip: Boolean = false,
    /** `attachment-gzip`: likewise for a gzip file (a `.tar.gz` too), up to the same cap. */
    canAttachGzip: Boolean = false,
    /** `attachment-tar`: likewise for a plain tar archive, up to the same cap. */
    canAttachTar: Boolean = false,
    /** `attachment-xz`: likewise for an xz file (a `.tar.xz` too), up to the same cap. */
    canAttachXz: Boolean = false,
    /** `attachment-bzip2`: likewise for a bzip2 file (a `.tar.bz2` too), up to the same cap. */
    canAttachBzip2: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoLauncher = rememberLauncherForActivityResult(
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
            ?.let(onPhoto)
    }
    val fileLauncher = rememberLauncherForActivityResult(
        // The system's own document picker: one grant-scoped uri and no storage permission, like the
        // photo picker. Every type is offered because providers label source files and notes
        // inconsistently; what is text is decided by the bytes, on the phone and again on the machine.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val picked = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // Off the main thread — a document provider may be a cloud drive — and read one byte past
            // the cap, so an oversized file is told apart from one that fits without loading it whole.
            // A file that cannot be read arrives empty, which the view model answers with a sentence:
            // nothing the reader picked may vanish silently.
            val (name, bytes) = withContext(Dispatchers.IO) {
                val name = runCatching {
                    context.contentResolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: picked.lastPathSegment.orEmpty()
                name to runCatching {
                    context.contentResolver.openInputStream(picked)?.use { readAtMost(it, (if (canAttachPdf || canAttachZip || canAttachGzip || canAttachTar || canAttachXz || canAttachBzip2) MobileAttachment.MAX_PDF_BYTES else MobileAttachment.MAX_TEXT_BYTES) + 1) }
                }.getOrNull()
            }
            onFile(name, bytes ?: ByteArray(0))
        }
    }

    if (busy) {
        CircularProgressIndicator(
            modifier = modifier.padding(horizontal = 12.dp).size(20.dp),
            strokeWidth = 2.dp,
        )
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    val pickPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Box(modifier) {
        IconButton(onClick = { if (canAttachFile) menuOpen = true else pickPhoto() }) {
            Icon(
                AttachPhotoIcon,
                // The action's name, never printed beside the glyph — the phone's own reading of
                // `CLAUDE.md`'s no-label-beside-an-icon rule.
                contentDescription = if (canAttachFile) "Attach a photo or file" else "Attach a photo",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Photo") }, onClick = { menuOpen = false; pickPhoto() })
            DropdownMenuItem(text = { Text(if (canAttachPdf || canAttachZip || canAttachGzip || canAttachTar || canAttachXz || canAttachBzip2) "File" else "Text file") }, onClick = { menuOpen = false; fileLauncher.launch(arrayOf("*/*")) })
        }
    }
}

/** `readNBytes(int)` needs API 33 and the app supports 26, so the bounded read is spelled out. */
private fun readAtMost(stream: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var left = limit
    while (left > 0) {
        val n = stream.read(buffer, 0, minOf(buffer.size, left))
        if (n < 0) break
        out.write(buffer, 0, n)
        left -= n
    }
    return out.toByteArray()
}

/** One chip per uploaded photo or file, above the field; the ✕ removes it before the send names it. */
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
                label = { Text(photo.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
