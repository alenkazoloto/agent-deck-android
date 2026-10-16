package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileSubagentThread
import com.github.claudeagents.core.mobile.MobileToolCall

/** One open subagent thread: the call it belongs to, the agent that owns that call's file, and how the read went. */
data class SubagentThreadFrame(
    val callId: String,
    /** The agent whose transcript holds [callId]; null for a call in the conversation itself. */
    val owner: String?,
    val thread: MobileSubagentThread? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** What the conversation screen needs to show subagent threads: the stack of open ones and the four things a reader does to it. */
data class SubagentThreads(
    val frames: List<SubagentThreadFrame> = emptyList(),
    /** Open the thread a call ran; `owner` is the agent whose thread the call sits in, null on the conversation. */
    val onOpen: (MobileToolCall, owner: String?) -> Unit = { _, _ -> },
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/** The sheet's title: what the agent was asked, as the desk's row names it. */
fun subagentThreadTitle(thread: MobileSubagentThread?): String =
    listOfNotNull(thread?.agentType, thread?.description).joinToString(" · ").ifBlank { "Subagent thread" }

/**
 * A Task/Agent call's own conversation, read-only.
 *
 * The turns are the machine's — the same projection a conversation page carries — so a subagent's tool rows, thoughts
 * and checklists read as the main thread's do. A delegation inside the thread opens the next thread on top of it, and
 * Back returns. A subagent still working keeps writing its file; Refresh reads it again rather than promising a stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentThreadSheet(threads: SubagentThreads, vendor: AgentVendor, generatedAtMs: Long) {
    val frame = threads.frames.lastOrNull() ?: return
    val thread = frame.thread
    var detail by remember(frame.callId, frame.owner) { mutableStateOf<MobileToolCall?>(null) }
    ModalBottomSheet(
        onDismissRequest = threads.onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).testTag("subagent-thread")) {
            if (threads.frames.size > 1) {
                TextButton(onClick = threads.onBack, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text("Back", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(
                subagentThreadTitle(thread),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            when {
                thread == null && frame.error != null -> Text(
                    frame.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                thread == null -> Text(
                    "Loading thread…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                thread.turns.isEmpty() -> Text(
                    "This subagent has not written anything yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                else -> LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 560.dp)) {
                    if (thread.truncated) {
                        item("earlier") {
                            Text(
                                "Earlier steps are not shown.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }
                    itemsIndexed(thread.turns, key = { index, turn -> "${turn.id}#$index" }) { index, turn ->
                        TurnBubble(
                            turn = turn,
                            vendor = vendor,
                            agentName = thread.agentType,
                            generatedAtMs = generatedAtMs,
                            runLive = false,
                            first = TurnGrouping.startsBlock(thread.turns, index),
                            last = TurnGrouping.endsBlock(thread.turns, index),
                            stamped = false,
                            // A call in this thread that delegated again opens its own thread; any other row opens its
                            // body from the page, which is all this read carries — the machine's larger copy is keyed
                            // to the conversation, not to a thread.
                            onOpenCall = { call ->
                                if (call.subagent) threads.onOpen(call, thread.agentId) else detail = call
                            },
                            threadsOffered = true,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = threads.onRefresh, enabled = !frame.loading) { Text("Refresh") }
                if (frame.loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).heightIn(max = 20.dp), strokeWidth = 2.dp)
                if (thread != null) frame.error?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    detail?.let { call ->
        ToolDetailSheet(
            call = call, full = null, loading = false, error = null,
            onLoadFull = {}, onDismiss = { detail = null }, canLoadFull = false,
        )
    }
}
