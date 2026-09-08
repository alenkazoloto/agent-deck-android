package dev.agentdeck.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.data.ConversationMatch
import dev.agentdeck.companion.data.ConversationSearch

class ConversationFindState internal constructor() {
    var open by mutableStateOf(false)
        internal set
    var query by mutableStateOf("")
        private set
    var searching by mutableStateOf(false)
        internal set
    var matches by mutableStateOf(emptyList<ConversationMatch>())
        internal set
    var selected by mutableIntStateOf(0)
        internal set

    val activeMatch: ConversationMatch? get() = if (open && !searching) matches.getOrNull(selected) else null

    fun changeQuery(value: String) {
        query = value
        matches = emptyList()
        selected = 0
        searching = value.isNotEmpty()
    }

    fun step(delta: Int) {
        if (matches.isNotEmpty()) selected = (selected + delta).mod(matches.size)
    }
}

@Composable
fun rememberConversationFind(key: String, turns: List<MobileTurn>, open: Boolean): ConversationFindState {
    val state = remember(key) { ConversationFindState() }
    state.open = open
    LaunchedEffect(key, turns, state.query, open) {
        if (!open) {
            state.changeQuery("")
            return@LaunchedEffect
        }
        val previous = state.activeMatch
        state.searching = state.query.isNotEmpty()
        val results = ConversationSearch.find(turns, state.query)
        state.matches = results
        state.selected = results.indexOf(previous).coerceAtLeast(0)
        state.searching = false
    }
    return state
}

@Composable
fun ConversationFindBar(state: ConversationFindState, onClose: () -> Unit) {
    if (!state.open) return
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = state::changeQuery,
                label = { Text("Find in conversation") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            DeckIconButton("Close find", Icons.Default.Close, {
                focusManager.clearFocus()
                onClose()
            })
        }
        Text(
            "Downloaded message source text · excludes tools",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.searching -> "Searching…"
                    state.query.isEmpty() -> "Enter text to find"
                    state.matches.isEmpty() -> "No matches"
                    state.matches.size >= ConversationSearch.MAX_MATCHES -> "${state.selected + 1} of first ${ConversationSearch.MAX_MATCHES} matches · refine search"
                    else -> "${state.selected + 1} of ${state.matches.size}"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            DeckIconButton("Previous match", Icons.Default.KeyboardArrowUp, { state.step(-1) },
                enabled = state.matches.isNotEmpty() && !state.searching)
            DeckIconButton("Next match", Icons.Default.KeyboardArrowDown, { state.step(1) },
                enabled = state.matches.isNotEmpty() && !state.searching)
        }
    }
}

/** Source mode makes Markdown/code occurrences visible at their actual source offsets. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationFindPassage(turn: MobileTurn, match: ConversationMatch, modifier: Modifier = Modifier, reveal: Boolean = true) {
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(turn.text) { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val ink = MaterialTheme.colorScheme.onTertiaryContainer
    val text = remember(turn.text, match, highlight, ink) {
        buildAnnotatedString {
            append(turn.text)
            if (match.start >= 0 && match.end <= length && match.start < match.end) {
                addStyle(SpanStyle(background = highlight, color = ink), match.start, match.end)
            }
        }
    }
    LaunchedEffect(match, layout, reveal) {
        if (!reveal) return@LaunchedEffect
        val measured = layout ?: return@LaunchedEffect
        if (match.start !in turn.text.indices) return@LaunchedEffect
        // Let the parent's initial jump mount this lazy item before revealing its exact line.
        withFrameNanos { }
        kotlinx.coroutines.yield()
        requester.bringIntoView(measured.getBoundingBox(match.start))
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .bringIntoViewRequester(requester).testTag("find-passage"),
        onTextLayout = { layout = it },
    )
}
