package dev.agentdeck.companion.ui

import com.github.claudeagents.core.mobile.MobileQuestion
import com.github.claudeagents.core.mobile.MobileToolCall

/**
 * Where an `AskUserQuestion` is rendered, and whether its options can still be picked.
 *
 * The question the agent is *blocked on* used to arrive as one anonymous row inside the
 * collapsed "Tool calls (N)" group, titled with the tool's own name — so a run waiting on an
 * answer reached the phone as the word "AskUserQuestion" and stayed waiting. It is hoisted out
 * for the reason [TaskChecklist] is: a collapsed group is right for what the agent *did*, and
 * wrong for the one thing it is asking the reader to do.
 *
 * [answerable] is the whole of the difference between an offer and a record. A question whose
 * tool call has a result is one the run has already moved past, and painting live buttons on it
 * would invite a tap that can only ever land as a *new* prompt — a turn the reader did not mean
 * to start, in a conversation they were only reading.
 */
object TranscriptQuestions {

    /** The question calls of a turn, in the order the agent asked them. */
    fun asked(calls: List<MobileToolCall>): List<MobileToolCall> =
        calls.filter { it.questions.isNotEmpty() }

    /** Everything else — what the collapsed tool-call group is left holding. */
    fun otherCalls(calls: List<MobileToolCall>): List<MobileToolCall> =
        calls.filter { it.questions.isEmpty() }

    /**
     * Whether this call's options may still be tapped.
     *
     * [canAnswer] is the machine's advertised `/v1/answer` — a plugin without it would refuse
     * the pick as an unknown route, and buttons that can only fail are worse than none.
     */
    fun answerable(call: MobileToolCall, canAnswer: Boolean): Boolean =
        canAnswer && call.status == MobileToolCall.RUNNING

    /**
     * What the card says under the options when they cannot be tapped. Null while they can:
     * a live question needs no caption, and the buttons are their own invitation.
     */
    fun closedNote(call: MobileToolCall, canAnswer: Boolean): String? = when {
        answerable(call, canAnswer) -> null
        call.status == MobileToolCall.RUNNING -> "This machine's plugin is too old to answer from here."
        else -> "Already answered."
    }

    /**
     * A lone single-choice question sends on its tap: there is nothing else to collect, and the
     * one-tap answer is what a reader on the couch came for. Anything more collects first.
     */
    fun sendsOnTap(call: MobileToolCall): Boolean =
        call.questions.size == 1 && !call.questions.single().multiSelect

    /** [picks] after tapping [label]: a multi-select question toggles it, any other replaces. */
    fun toggle(picks: Map<String, List<String>>, question: MobileQuestion, label: String): Map<String, List<String>> {
        val current = picks[question.key].orEmpty()
        val next = when {
            !question.multiSelect -> listOf(label)
            label in current -> current - label
            else -> current + label
        }
        return picks + (question.key to next)
    }

    /** Every question has at least one pick, so the whole ask can be answered at once. */
    fun complete(call: MobileToolCall, picks: Map<String, List<String>>): Boolean =
        call.questions.all { picks[it.key].orEmpty().isNotEmpty() }

    /**
     * The `/v1/answer` map for a [complete] set of picks. One entry per question — sending
     * only the tapped one would let the host resume the ask with the rest unanswered, since the
     * relay settles the whole parked request on the first answer it takes. A multi-select
     * answer is its labels in the ask's own order, joined by ", " as the desktop form does.
     */
    fun answers(call: MobileToolCall, picks: Map<String, List<String>>): Map<String, String> =
        call.questions.associate { question ->
            val chosen = picks[question.key].orEmpty()
            question.key to question.options.map { it.label }.filter { it in chosen }.joinToString(", ")
        }

    /** The heading of one question, falling back to a neutral one rather than to nothing. */
    fun header(question: MobileQuestion): String =
        question.header?.takeIf { it.isNotBlank() } ?: "Claude has a question"
}
