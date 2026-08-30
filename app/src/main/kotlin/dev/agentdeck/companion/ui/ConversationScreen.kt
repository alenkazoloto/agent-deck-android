package dev.agentdeck.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileTodo
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.Dictation

/** P6's set, verbatim: the four replies that carry most couch-side traffic. */
private val QUICK_REPLIES = listOf("continue", "yes", "run the tests", "explain")

/** The gutter an agent bubble leaves for its avatar, so a block's bubbles stay left-aligned. */
private val AVATAR_SIZE = 30.dp
private val AVATAR_GUTTER = 38.dp

/**
 * Scroll far enough *into* the last item to reach the end of it. `scrollToItem(last)` puts that
 * item's top edge at the top of the viewport, which for a tall final turn — a long answer, a
 * checklist, an open tool-call group — leaves the newest words below the fold. The offset is
 * clamped to the list's own maximum, so overshooting is how you land exactly at the bottom.
 */
private const val LAST_ITEM_BOTTOM = 1_000_000

internal fun streamingTurnLabel(turn: MobileTurn): String? =
    "Still writing…".takeIf { turn.role == "assistant" && turn.streaming }

@Composable
fun ConversationScreen(
    target: Screen.Conversation,
    page: MobileTranscriptPage?,
    loading: Boolean,
    /** The page came off the disk, not off the wire — the marker says so, in its own age. */
    cached: Boolean,
    draft: String,
    notice: String?,
    onDraft: (String) -> Unit,
    onSend: (text: String, stopFirst: Boolean) -> Unit,
    onStop: () -> Unit,
    onDismissNotice: () -> Unit,
    /** The machine advertises `/v1/answer`; without it a question is read-only here. */
    canAnswer: Boolean = false,
    onAnswer: (questionKey: String, label: String) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val composerFocus = remember { FocusRequester() }
    val running = page?.running == true
    val body = conversationBody(page, loading)
    val turns = page?.turns.orEmpty()
    val turnCount = turns.size
    val scope = rememberCoroutineScope()

    val atTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            TranscriptTail.followsTail(
                info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                info.totalItemsCount,
            )
        }
    }
    // The newest turn the reader has actually seen the end of. Saved, so a rotation mid-run
    // does not re-announce every turn that arrived before it as new; keyed to the
    // conversation, so opening another one does not inherit this one's read mark.
    var readThrough by rememberSaveable(target.key) { mutableIntStateOf(0) }
    // Opening a conversation is not "following the tail" — it is arriving at it, and it has
    // to be unconditional. Gating it on `atTail` looked right and shipped backwards: nothing
    // is laid out during the first composition, so the effect ran with a zero-item list, had
    // no last index to scroll to, and by the time there was one `atTail` had flipped false.
    // The conversation then opened at its *oldest* turn under a "6 new turns" pill
    // (docs/img/2026-07-31-mobile-convo-codex.png's first revision).
    var opened by rememberSaveable(target.key) { mutableStateOf(false) }
    LaunchedEffect(target.key, turnCount, atTail) {
        // A skeleton row is an item too, and arriving is a thing you do once: spent on the
        // placeholder, `opened` is already true when the turns it stood in for show up.
        if (turnCount == 0) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return@LaunchedEffect
        if (!opened) {
            listState.scrollToItem(last, LAST_ITEM_BOTTOM)
            opened = true
            readThrough = turnCount
            return@LaunchedEffect
        }
        if (!atTail) return@LaunchedEffect
        readThrough = turnCount
        listState.animateScrollToItem(last, LAST_ITEM_BOTTOM)
    }

    Column(Modifier.fillMaxSize()) {
        // Only over turns already on screen — that is a quiet refresh, and a bar is the
        // honest word for it. A first load has no layout to reassure anyone about, so it gets
        // the shape of the answer instead (TranscriptSkeleton).
        if (body is ConversationBody.Loading && turns.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
            ) {
                // Stamped from the page's own `generatedAtMs`, never from when the phone
                // happened to read the file: an offline transcript says how old it is or it
                // is a live conversation that has silently stopped moving.
                if (cached && page != null) {
                    item(key = "transcript-cached") {
                        TranscriptMarker(
                            "Saved copy · as of ${Times.clock(page.generatedAtMs, LocalNow.current())}",
                        )
                    }
                }

                TranscriptTail.truncationNotice(page)?.let { marker ->
                    item(key = "transcript-truncated") { TranscriptMarker(marker) }
                }

                if (body is ConversationBody.Loading && turns.isEmpty()) {
                    item(key = "transcript-skeleton") { TranscriptSkeleton() }
                }

                itemsIndexed(turns, key = { _, turn -> turn.id }) { index, turn ->
                    TurnBubble(
                        turn = turn,
                        vendor = target.vendor,
                        generatedAtMs = page?.generatedAtMs ?: 0L,
                        runLive = running,
                        first = TurnGrouping.startsBlock(turns, index),
                        last = TurnGrouping.endsBlock(turns, index),
                        stamped = TurnGrouping.carriesTime(turns, index),
                        canAnswer = canAnswer,
                        onAnswer = onAnswer,
                    )
                }

                if (running) {
                    item(key = "working") { WorkingBubble(target.vendor, page?.liveLine, onStop) }
                }
                // Only for a page that really arrived carrying nothing — never for a page that
                // failed to arrive, which is [ConversationBody.Unavailable] and is explained by
                // the link banner above rather than contradicted down here.
                if (body is ConversationBody.Empty) {
                    item(key = "transcript-empty") {
                        ConversationEmpty(target.vendor) { composerFocus.requestFocus() }
                    }
                }
            }

            val unread = TranscriptTail.unreadBelow(turnCount, readThrough)
            JumpToLatest(
                visible = !atTail && unread > 0,
                unread = unread,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                scope.launch {
                    val last = listState.layoutInfo.totalItemsCount - 1
                    if (last >= 0) listState.animateScrollToItem(last)
                }
            }
        }

        notice?.let { message -> NoticeBar(message, onDismissNotice) }

        Composer(
            draft = draft,
            running = running,
            fieldFocus = composerFocus,
            onDraft = onDraft,
            onSend = onSend,
        )
    }
}

/** A centred, quiet line about the list itself — never a bubble, because nobody said it. */
@Composable
private fun TranscriptMarker(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The shape of a conversation, while its first page is still on the wire.
 *
 * Static on purpose. The usual skeleton shimmers, and a shimmer is an `infiniteRepeatable`
 * that by construction never settles — a Roborazzi capture waits for the composition to go
 * idle, so the golden that holds this state would hang the suite rather than fail it
 * (`GoldenScreenshotTest`, which keeps live runs out for the same reason).
 */
@Composable
private fun TranscriptSkeleton() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // One announcement for the whole placeholder — the bars under it say nothing.
            .clearAndSetSemantics { contentDescription = "Loading this conversation" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SkeletonTurn(user = false, lines = 3)
        SkeletonTurn(user = true, lines = 1)
        SkeletonTurn(user = false, lines = 2)
    }
}

/** One placeholder bubble, at the geometry [TurnBubble] will paint into. */
@Composable
private fun SkeletonTurn(user: Boolean, lines: Int) {
    val tone = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        if (!user) {
            Box(Modifier.size(AVATAR_SIZE).clip(CircleShape).background(tone))
            Spacer(Modifier.width(AVATAR_GUTTER - AVATAR_SIZE))
        }
        Surface(
            modifier = if (user) Modifier.width(220.dp) else Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = tone,
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(lines) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth(if (index == lines - 1) 0.6f else 1f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
                    )
                }
            }
        }
    }
}

/**
 * A conversation that really arrived carrying nothing, and the one gesture that ends it.
 *
 * It was a single grey line centred in a blank column, which reads as a screen that failed
 * rather than one waiting to be written in — and it named no way out, though the field that
 * resolves it is two rows below.
 */
@Composable
private fun ConversationEmpty(vendor: AgentVendor, onWrite: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Create,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            "Nothing in this conversation yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            "${vendor.label()} has written nothing here. Send the first message and the answer lands in this column.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onWrite, modifier = Modifier.padding(top = 8.dp)) {
            Text("Write the first message")
        }
    }
}

@Composable
private fun JumpToLatest(
    visible: Boolean,
    unread: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 3.dp,
            modifier = Modifier.clickable(
                onClickLabel = "Jump to the newest turn",
                onClick = onClick,
            ),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (unread == 1) "1 new turn" else "$unread new turns",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
        }
    }
}

/**
 * The agent's live state as a bubble on its own side of the column, with the interrupt on it.
 *
 * "Stop" used to sit in a permanent third row under the composer. It belongs here: this is the
 * row a reader is already watching to decide whether to interrupt, and putting the control
 * anywhere else asks them to look at one thing and act on another.
 */
@Composable
private fun WorkingBubble(vendor: AgentVendor, liveLine: String?, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VendorAvatar(vendor)
        Spacer(Modifier.width(AVATAR_GUTTER - AVATAR_SIZE))
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The viewed conversation's own vendor voice — a Codex run saying
                        // "Claude is working…" is the desktop's mistake to not repeat.
                        Text(
                            vendor.workingText(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TypingDots(Modifier.padding(start = 6.dp))
                    }
                    // The live tool ticker as the plugin already phrased it.
                    liveLine?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                TextButton(onClick = onStop, modifier = Modifier.padding(start = 4.dp)) {
                    Text("Stop")
                }
            }
        }
    }
}

/** Three dots breathing in sequence — the one motion that says "still going" without a number. */
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(5.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** The agent's mark beside its first bubble — one glyph, tinted, never a word. */
@Composable
private fun VendorAvatar(vendor: AgentVendor, visible: Boolean = true) {
    Box(
        Modifier
            .size(AVATAR_SIZE)
            .clip(CircleShape)
            .background(
                if (visible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Text(
                vendor.glyph(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                // Otherwise TalkBack announces the character, not the agent.
                modifier = Modifier.semantics { contentDescription = vendor.label() },
            )
        }
    }
}

/**
 * One bubble in a block of them.
 *
 * The role is carried by **alignment and tonal colour** — the modern chat pattern, and the one
 * that stops a phone-sized column of identical full-width cards from reading as one wall.
 * [first] and [last] are the block's ends ([TurnGrouping]): the avatar and the agent's name go
 * on the first bubble, the tail corner on the last, and everything between is drawn as
 * continuation so a long answer reads as one utterance rather than six. [stamped] is the
 * narrower question of which turn writes the time — one per run of them, not one per block.
 */
@Composable
private fun TurnBubble(
    turn: MobileTurn,
    vendor: AgentVendor,
    generatedAtMs: Long,
    runLive: Boolean,
    first: Boolean,
    last: Boolean,
    stamped: Boolean,
    canAnswer: Boolean = false,
    onAnswer: (questionKey: String, label: String) -> Unit = { _, _ -> },
) {
    val user = turn.role == "user"
    val live = streamingTurnLabel(turn) != null
    val big = 20.dp
    val tail = 6.dp
    // The tail corner is what closes a block, so a turn still being written cannot wear it.
    val closed = last && !live
    val shape = if (user) {
        RoundedCornerShape(big, big, if (closed) tail else big, big)
    } else {
        RoundedCornerShape(big, big, big, if (closed) tail else big)
    }
    Column(
        Modifier.fillMaxWidth().padding(
            start = 12.dp,
            end = 12.dp,
            top = if (first) 8.dp else 2.dp,
            bottom = 0.dp,
        ),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            if (!user) {
                VendorAvatar(vendor, visible = first)
                Spacer(Modifier.width(AVATAR_GUTTER - AVATAR_SIZE))
            }
            Surface(
                // A user bubble stops short of the edge so the two sides are told apart at a
                // glance even in one-sided stretches; an agent turn carries code and checklists
                // and gets the width.
                modifier = if (user) Modifier.widthIn(max = 320.dp) else Modifier.weight(1f),
                shape = shape,
                color = if (user) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (user) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                // Deliberately a static outline and not a second set of dots: a Roborazzi
                // capture waits for the composition to go idle, so an `infiniteRepeatable`
                // here would hang the golden that is meant to hold this state.
                border = if (live) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    // Only on the block's first bubble, and only for a speaker whose name is
                    // not already given by which side the bubble is on.
                    if (!user && first) {
                        Text(
                            if (turn.role == "assistant") vendor.label()
                            else turn.role.replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    if (turn.text.isNotBlank()) {
                        // Selectable, so a sentence can be copied out with the platform's own
                        // long-press toolbar. Nothing in the app could select a word before this,
                        // and the only copy route in a conversation was a code block's button.
                        SelectionContainer {
                            if (user) {
                                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                MarkdownText(turn.text)
                            }
                        }
                    }
                    if (turn.todos.isNotEmpty()) TaskChecklist(turn.todos)
                    // Hoisted out of the group below: a question is the one tool call that is
                    // asking the reader for something — see [TranscriptQuestions].
                    TranscriptQuestions.asked(turn.toolCalls).forEach { call ->
                        QuestionCard(call, canAnswer, onAnswer)
                    }
                    val otherCalls = TranscriptQuestions.otherCalls(turn.toolCalls)
                    if (otherCalls.isNotEmpty()) ToolCallGroup(otherCalls, runLive)
                    // Deliberately no TypingDots: this bubble sits directly above the working
                    // bubble, which already animates a set — two stacked is noise. What the
                    // reader who scrolled up needs is a caption saying the text stops here
                    // because it is still arriving, not a second live indicator.
                    streamingTurnLabel(turn)?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (stamped) {
            // The turn was stamped by the machine, so it is read against the page the
            // machine sent it on — not against the phone's idea of what day it is.
            Text(
                Times.clock(turn.timestampMs, generatedAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = if (user) 0.dp else AVATAR_GUTTER + 6.dp,
                    end = if (user) 6.dp else 0.dp,
                    top = 3.dp,
                    bottom = 4.dp,
                ),
            )
        }
    }
}

/**
 * An `AskUserQuestion`, as the surface the reader answers it on.
 *
 * The options are the plugin's own — the same `InteractiveQuestion` parse the desktop's inline
 * cards and its permission dialog read — so the three surfaces cannot offer different answers to
 * one ask. A tap posts `/v1/answer`, which reaches the CLI on the control channel and resumes the
 * very turn the agent is blocked on; the screen says which route the machine took, because the
 * fallback is a new turn and that is not the same act.
 */
@Composable
private fun QuestionCard(
    call: MobileToolCall,
    canAnswer: Boolean,
    onAnswer: (questionKey: String, label: String) -> Unit,
) {
    val live = TranscriptQuestions.answerable(call, canAnswer)
    // One tap per card. The pick leaves the phone and the page reloads behind it, and until it
    // does the buttons would otherwise still invite a second answer to a settled ask.
    var picked by rememberSaveable(call.id) { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = 8.dp)) {
        call.questions.forEach { question ->
            Text(
                TranscriptQuestions.header(question),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            question.options.forEach { option ->
                val chosen = picked == option.label
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        // Enabled and not merely un-clickable: a disabled row keeps the whole
                        // card readable, which is what an already-answered question is for.
                        .clickable(enabled = live && picked == null) {
                            picked = option.label
                            onAnswer(question.key, option.label)
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    border = if (live && picked == null) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    } else {
                        null
                    },
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        option.description?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        TranscriptQuestions.closedNote(call, canAnswer)?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The turn's checklist, **outside** the collapsed tool-call group and expanded by default.
 *
 * Task progress was on the wire nowhere and on screen nowhere: a TodoWrite reached the phone as
 * one anonymous row inside "Tool calls (25)", so the plan the desktop paints as a checklist was
 * the one thing a reader on the couch could not see. It is the answer to "what is this agent
 * doing", which is the whole reason to open the app — so it is never what a tap has to reveal.
 */
@Composable
private fun TaskChecklist(todos: List<MobileTodo>) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val done = todos.count { it.done }
    Surface(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Tasks  ${MobileTodo.progress(todos)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide the task list" else "Show the task list",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { if (todos.isEmpty()) 0f else done.toFloat() / todos.size },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape),
                // The track defaults to `secondaryContainer`, which in this scheme is the
                // green that means "Codex" everywhere else — a progress bar reading half
                // blue, half green looks like two measurements rather than one. `outlineVariant`
                // rather than a surface role because the track sits *on* a surface: the two
                // surface tones are a shade apart in dark, so the unfilled part disappeared
                // and the bar read as having no remainder at all.
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            if (expanded) {
                Column(
                    Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    todos.forEach { todo ->
                        TaskLine(
                            when {
                                todo.done -> TaskMark.DONE
                                todo.active -> TaskMark.ACTIVE
                                else -> TaskMark.PENDING
                            },
                            InlineMarkdown.inline(todo.text),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsed until the reader opens it — see [ToolDisclosure] for why a live run is not a
 * reason to open one.
 *
 * `rememberSaveable`, not `remember`: this runs inside a `LazyColumn` item, whose composition
 * is discarded once it scrolls out of the buffer. A reader who tidied a group away got it back
 * open by scrolling past it and back, which is indistinguishable from it never having closed.
 */
@Composable
private fun ToolCallGroup(calls: List<MobileToolCall>, runLive: Boolean) {
    var readerOpened by rememberSaveable { mutableStateOf(false) }
    val expanded = ToolDisclosure.expanded(readerOpened, runLive)
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { readerOpened = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tool calls (${calls.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide these tool calls" else "Show these tool calls",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            calls.forEach { call ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(
                        when (call.status) {
                            MobileToolCall.RUNNING -> "◌ "
                            MobileToolCall.ERROR -> "✗ "
                            else -> "✓ "
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (call.status == MobileToolCall.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        // Title and summary arrive already decided by the plugin, so the two
                        // clients cannot disagree about what a tool call is called.
                        Text(call.title, style = MaterialTheme.typography.bodySmall)
                        if (call.summary.isNotBlank()) {
                            Text(
                                call.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The machine's own sentence about why it refused, and the one gesture that clears it. */
@Composable
private fun NoticeBar(message: String, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss this message",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Past this the pill has stopped being enough, and the expander earns its place. */
private const val EXPAND_AT_CHARS = 120

/**
 * The same draft, with the whole screen.
 *
 * A prompt worth sending to an agent is often a paragraph, and a five-line pill above a
 * keyboard shows two of them. Nothing is copied: the field writes straight through to the
 * conversation's draft, so closing this — or the process dying while it is open — leaves the
 * text exactly where the composer will find it.
 */
@Composable
private fun FullScreenEditor(
    draft: String,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Back to the conversation")
                    }
                    Text(
                        "${draft.length} characters",
                        Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Send", Modifier.size(20.dp))
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Message this agent") },
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/**
 * The composer: one pill, one send button.
 *
 * What it replaced was four stacked rows — quick replies, the field, and a permanent
 * "Stop & send"/"Stop" pair — occupying a third of a phone screen at all times. The chips now
 * appear only while there is nothing typed (once there is, they are a way to overwrite it),
 * plain "Stop" moved onto the working bubble where the reader is already looking, and
 * "Stop & send" appears only in the one state where it differs from Send: a live run with
 * something typed.
 */
@Composable
private fun Composer(
    draft: String,
    running: Boolean,
    fieldFocus: FocusRequester,
    onDraft: (String) -> Unit,
    onSend: (String, Boolean) -> Unit,
) {
    val hasDraft = draft.isNotBlank()
    val haptics = LocalHapticFeedback.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (expanded) {
        FullScreenEditor(
            draft = draft,
            onDraft = onDraft,
            onDismiss = { expanded = false },
            onSend = {
                expanded = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSend(draft, false)
            },
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        AnimatedVisibility(visible = !hasDraft, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_REPLIES.forEach { reply ->
                    // Composed into the draft, not fired past it: these used to send instantly
                    // and discard whatever was already typed. Same join rule dictation uses.
                    SuggestionChip(
                        onClick = { onDraft(Dictation.append(draft, reply)) },
                        label = { Text(reply) },
                        shape = CircleShape,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = null,
                    )
                }
            }
        }
        AnimatedVisibility(visible = running && hasDraft, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.padding(bottom = 8.dp)) {
                // One action, mirroring the desktop's interrupt-and-redirect.
                SuggestionChip(
                    onClick = { onSend(draft, true) },
                    label = { Text("Stop & send") },
                    shape = CircleShape,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    border = null,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Message this agent") },
                modifier = Modifier.weight(1f).focusRequester(fieldFocus),
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Only once the pill has stopped being big enough. A long prompt is
                        // the one a phone composer is worst at, and a permanent "expand"
                        // button beside an empty field is a control for nothing.
                        if (draft.length > EXPAND_AT_CHARS) {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Write this in a full-screen editor",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        DictateButton(onSpoken = { onDraft(Dictation.append(draft, it)) })
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
            FilledIconButton(
                onClick = {
                    // The one confirmation a phone can give without taking screen space: the
                    // send left the device.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSend(draft, false)
                },
                enabled = hasDraft,
                modifier = Modifier.padding(start = 8.dp).size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
            }
        }
    }
}
