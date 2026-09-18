package dev.agentdeck.companion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileToolResult

/**
 * What one tool call returned.
 *
 * The reason the companion exists is to save the walk to the desk, and until this sheet the
 * walk was still the only way to read a failing test's output: the transcript showed that
 * `./gradlew test` had run and nothing about what it printed. The body arrives already cut by
 * the machine — head, an omission sentence, tail — because a phone cannot receive the megabytes
 * a build prints, and the sentence names the size so the reader can decide whether the rest is
 * worth asking for.
 *
 * [onLoadFull] is offered only for a body the page had to cut and only once: the larger copy is
 * a second request, and a sheet that fetched on open would spend it on every tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailSheet(
    call: MobileToolCall,
    /** The larger body, once the reader asked for it; null until then. */
    full: MobileToolResult?,
    loading: Boolean,
    error: String?,
    onLoadFull: () -> Unit,
    onDismiss: () -> Unit,
) {
    val result = full ?: call.result ?: return
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(call.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    toolDetailSubtitle(call, result),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            result.input?.takeIf { it.isNotBlank() }?.let { input ->
                // The call's own input in full: a Bash command and a search pattern are both
                // routinely longer than the collapsed row could show.
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    SelectionContainer {
                        Text(
                            input,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState()),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                SelectionContainer {
                    Text(
                        result.output.ifBlank { "This call returned nothing." },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .padding(10.dp)
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }

            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(result.output)) }) {
                    Text("Copy output")
                }
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.padding(start = 12.dp).heightIn(max = 20.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (result.omittedBytes > 0) {
                    TextButton(onClick = onLoadFull) { Text("Load more output") }
                }
            }
        }
    }
}

/**
 * The one line under the title: what kind of call this was, and how much of its output the
 * reader is looking at. Separate and public so a test can pin the sentence without a device.
 */
fun toolDetailSubtitle(call: MobileToolCall, result: MobileToolResult): String {
    val kind = when (result.kind) {
        MobileToolResult.READ -> "Read"
        MobileToolResult.SEARCH -> "Search"
        MobileToolResult.EXECUTE -> "Command"
        MobileToolResult.FETCH -> "Fetch"
        MobileToolResult.EDIT -> "Edit"
        else -> call.name
    }
    val state = when (call.status) {
        MobileToolCall.ERROR -> "failed"
        MobileToolCall.RUNNING -> "running"
        else -> "done"
    }
    val omission = result.omittedBytes.takeIf { it > 0 }
        ?.let { " · ${MobileToolResult.humanBytes(it)} not shown" }.orEmpty()
    return "$kind · $state$omission"
}
