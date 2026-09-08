package dev.agentdeck.companion.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import dev.agentdeck.companion.data.ReadingAnchor
import kotlinx.coroutines.flow.first

class ConversationReading internal constructor(initial: ReadingAnchor?, private val tolerance: Int) {
    val listState = LazyListState()
    var readThrough by mutableIntStateOf(0)
        private set
    private var readTail: String? = null
    private var following by mutableStateOf(initial?.followingLatest ?: true)
    internal var opened by mutableStateOf(false)
    internal var automatic by mutableStateOf(false)
    internal var anchor: ReadingAnchor? = initial
    internal var keys: List<String> = emptyList()
    internal var pageAvailable = false
    internal var leadingItems: Int = 0
    val atTail by derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        TranscriptTail.atEnd(last?.index ?: -1, info.totalItemsCount,
            last?.let { it.offset + it.size } ?: 0, info.viewportEndOffset, tolerance)
    }

    suspend fun latest() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        automatic = true
        try {
            listState.scrollToItem(last, 1_000_000)
            following = true
            readThrough = keys.size
            readTail = keys.lastOrNull()
            opened = true
        } finally { automatic = false }
    }

    fun pauseFollowing() { following = false }

    suspend fun showTurn(index: Int, offset: Int = 0) {
        if (keys.isEmpty()) return
        automatic = true
        following = false
        try {
            listState.scrollToItem(index.coerceIn(0, keys.lastIndex) + leadingItems, offset)
            opened = true
        } finally { automatic = false }
    }

    internal suspend fun acceptPage() {
        if (keys.isEmpty()) return
        if (readTail == null) readTail = keys.lastOrNull()
        val readIndex = keys.indexOf(readTail)
        readThrough = if (readIndex >= 0) readIndex + 1 else readThrough.coerceAtMost(keys.size)
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it >= keys.size + leadingItems }
        if (following) latest()
        else {
            val saved = anchor
            if (saved != null) showTurn(saved.resolvedIndex(keys), saved.offset)
            opened = true
        }
    }

    internal fun userScrolled(scrolling: Boolean) {
        if (opened && !automatic && scrolling) {
            following = atTail
            if (following) {
                readThrough = keys.size
                readTail = keys.lastOrNull()
            }
        }
    }

    internal fun capture(): ReadingAnchor? {
        if (!opened || automatic || !pageAvailable || keys.isEmpty()) return null
        val firstIndex = listState.firstVisibleItemIndex
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstIndex } ?: return null
        val turnIndex = keys.indexOf(visible?.key as? String)
        val index = if (turnIndex >= 0) turnIndex else (firstIndex - leadingItems).coerceIn(0, keys.lastIndex)
        return ReadingAnchor(keys[index], index,
            if (turnIndex >= 0) listState.firstVisibleItemScrollOffset else 0, following)
            .also { anchor = it }
    }
}

@Composable
fun rememberConversationReading(
    scope: String,
    key: String,
    page: MobileTranscriptPage?,
    leadingItems: Int,
    initialAnchor: ReadingAnchor?,
    onAnchor: (ReadingAnchor) -> Unit,
): ConversationReading {
    val tolerance = with(LocalDensity.current) { 48.dp.roundToPx() }
    val reading = remember(scope, key) { ConversationReading(initialAnchor, tolerance) }
    val callback = remember(reading) { mutableStateOf(onAnchor) }
    SideEffect { callback.value = onAnchor }
    reading.pageAvailable = page != null
    if (page != null) reading.keys = page.turns.map { it.id }
    reading.leadingItems = leadingItems
    LaunchedEffect(reading, page, leadingItems) {
        if (page != null) reading.acceptPage()
    }
    LaunchedEffect(reading) {
        snapshotFlow {
            listOf(reading.listState.isScrollInProgress, reading.atTail, reading.automatic,
                reading.opened, reading.listState.firstVisibleItemIndex,
                reading.listState.firstVisibleItemScrollOffset)
        }.collect {
            val scrolling = reading.listState.isScrollInProgress
            reading.userScrolled(scrolling)
            if (!scrolling) reading.capture()?.let(callback.value)
        }
    }
    DisposableEffect(reading) {
        onDispose { reading.capture()?.let(callback.value) }
    }
    return reading
}
