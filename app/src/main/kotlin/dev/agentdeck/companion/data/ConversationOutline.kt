package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileTurn

/**
 * What an outline row is. The three ranks are the shape of a supervised run — the user asks
 * ([PROMPT]), the agent works ([ACTIVITY]), the agent answers ([ANSWER]) — and they are ranks
 * rather than a flat list because that is what makes the sheet skimmable: a reader looking for
 * "where did I ask about the flaky test" reads only the chapters.
 */
enum class OutlineKind { PROMPT, ANSWER, ACTIVITY }

/**
 * One outline row. [turnIndex] is the index into the page's `turns`, which is what
 * `ConversationReading.showTurn` takes — resolving a row to a scroll position is the sheet's
 * whole job, so it carries the index rather than making the caller search for it.
 */
data class OutlineEntry(
    val turnIndex: Int,
    val turnId: String,
    val kind: OutlineKind,
    val label: String,
    val detail: String? = null,
    /**
     * The turn's whole text, on a [OutlineKind.PROMPT] row only.
     *
     * [label] is a *headline* — first line, stripped of markdown marks, cut at
     * [ConversationOutline.MAX_LABEL] — and "start a new chat from this prompt" that started
     * from the headline would silently drop everything after the first line of a prompt whose
     * length is exactly why anyone wants to re-run it.
     */
    val source: String? = null,
)

/**
 * A conversation's table of contents, and the two counts that describe it.
 *
 * [toolCalls] is the page's, not the session's: the phone holds 120 turns and the plugin's
 * session index has never counted tool calls for any surface, desktop included. Saying "in
 * this conversation" over a windowed count would be the kind of number a reader cannot
 * reconcile, so the sheet names what it counted.
 */
data class ConversationOutline(
    val entries: List<OutlineEntry>,
    val prompts: Int,
    val toolCalls: Int,
) {
    /**
     * Whether the conversation has earned the sheet.
     *
     * One prompt is not a table of contents — it is the conversation's title, already at the
     * top of the screen — so the outline action is absent until there are two chapters to
     * choose between.
     */
    val offered: Boolean get() = prompts >= MIN_PROMPTS

    companion object {
        const val MIN_PROMPTS = 2

        /** Longer than a phone's width; the label is elided by the renderer, not truncated to a lie. */
        const val MAX_LABEL = 120

        /**
         * A bound on the sheet, not on the page. 120 turns can yield three rows each — a prompt,
         * an activity and an answer — and a list past this stops being an outline and becomes
         * the transcript again, which the reader already has.
         */
        const val MAX_ENTRIES = 300

        /**
         * Whether [turns] has earned the outline action, without building the outline.
         *
         * The app bar recomposes on every state change a conversation screen sees — a live
         * line, a tick of the clock, a queued send — and building 300 entries over 120 turns
         * to answer a yes/no is work nothing keeps. This stops at the second prompt.
         */
        fun offers(turns: List<MobileTurn>): Boolean {
            var prompts = 0
            turns.forEach { turn ->
                if (turn.role == "user" && headline(turn.text) != null) {
                    prompts++
                    if (prompts >= MIN_PROMPTS) return true
                }
            }
            return false
        }

        /**
         * The outline of [turns], in transcript order.
         *
         * A turn contributes what it *has*: a prompt, an activity row when it ran tools, an
         * answer row when it wrote text. Within an assistant turn the activity precedes the
         * answer because that is the order the work happened in and the order the bubble paints
         * it. A turn that is neither — a system note, an empty streaming placeholder —
         * contributes nothing rather than an untitled row.
         */
        fun of(turns: List<MobileTurn>): ConversationOutline {
            val entries = ArrayList<OutlineEntry>()
            var prompts = 0
            var toolCalls = 0
            turns.forEachIndexed { index, turn ->
                toolCalls += turn.toolCalls.size
                when (turn.role) {
                    "user" -> {
                        val label = headline(turn.text)
                        if (label != null) {
                            prompts++
                            entries.add(
                                OutlineEntry(index, turn.id, OutlineKind.PROMPT, label, source = turn.text),
                            )
                        }
                    }
                    "assistant" -> {
                        turn.toolCalls.firstOrNull()?.let { first ->
                            entries.add(
                                OutlineEntry(
                                    index, turn.id, OutlineKind.ACTIVITY,
                                    headline(first.title) ?: first.name.ifBlank { "Tool call" },
                                    detail = (turn.toolCalls.size - 1).takeIf { it > 0 }?.let { "+$it more" },
                                ),
                            )
                        }
                        headline(turn.text)?.let { label ->
                            entries.add(OutlineEntry(index, turn.id, OutlineKind.ANSWER, label))
                        }
                    }
                }
            }
            return ConversationOutline(entries.take(MAX_ENTRIES), prompts, toolCalls)
        }

        /**
         * A turn's first line as a heading, or null when there is nothing to title it with.
         *
         * Markdown's own heading and list marks are stripped, because an outline built out of
         * "## " and "- " reads as punctuation. The text itself is not otherwise parsed: an
         * outline row is a landmark, and a reader recognises their own sentence faster than any
         * summary of it.
         */
        internal fun headline(text: String): String? {
            val line = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && it.trimStart('#', '-', '*', '>', ' ').isNotBlank() }
                ?: return null
            return line.trimStart('#', '-', '*', '>', ' ').trim().take(MAX_LABEL).ifBlank { null }
        }
    }
}
