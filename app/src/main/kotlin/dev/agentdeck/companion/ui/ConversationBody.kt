package dev.agentdeck.companion.ui

import com.github.claudeagents.core.mobile.MobileTranscriptPage

/**
 * What the conversation screen paints, decided outside the Composable.
 *
 * It lives here because the decision is the part that can be *wrong*: while the bridge parsed
 * Codex rollouts with the Claude parser it answered 200 with `turns: []`, and the screen —
 * whose only rule was "a page with no turns is empty" — told the user their conversation had
 * nothing in it. Nothing in the app could have caught that, because the rule was three lines
 * inside a `LazyColumn` that no test can reach.
 *
 * [Unavailable] is the state that rule was missing: no page **and** nothing in flight is a
 * machine that refused, not a conversation that is empty. The banner above carries the
 * refusal's own sentence, so the body stays quiet — but the two are now distinct, and a test
 * can hold them apart.
 */
sealed interface ConversationBody {

    /** The first page has not arrived yet. */
    data object Loading : ConversationBody

    /** The request failed; `LinkBanner` is showing the machine's own sentence. */
    data object Unavailable : ConversationBody

    /** A page that genuinely carries no turns. */
    data object Empty : ConversationBody

    /** A page with content to paint. */
    data class Turns(val page: MobileTranscriptPage) : ConversationBody
}

fun conversationBody(page: MobileTranscriptPage?, loading: Boolean): ConversationBody = when {
    page != null && page.turns.isNotEmpty() -> ConversationBody.Turns(page)
    // Loading wins over an already-painted page: a quiet refresh keeps the turns on screen.
    loading -> ConversationBody.Loading
    page != null -> ConversationBody.Empty
    else -> ConversationBody.Unavailable
}
