package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileQuestion
import com.github.claudeagents.core.mobile.MobileQuestionOption
import com.github.claudeagents.core.mobile.MobileToolCall
import dev.agentdeck.companion.ui.TranscriptQuestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A question is the one tool call that is asking the *reader* for something, so where it is
 * rendered and whether its options can be tapped are two separate decisions — and both were
 * previously answered by the same "it is a tool call" rule, which is how a run blocked on a
 * question reached the phone as the word "AskUserQuestion" inside a collapsed group.
 */
class TranscriptQuestionsTest {

    private val question = MobileQuestion(
        key = "Which fix?",
        header = "Which fix?",
        options = listOf(MobileQuestionOption("Pin the clock"), MobileQuestionOption("Leave it")),
    )

    private fun ask(status: String) =
        MobileToolCall("q1", "AskUserQuestion", "AskUserQuestion", "", status, questions = listOf(question))

    private val read = MobileToolCall("c1", "Read", "Read Main.kt", "lines 1-20", MobileToolCall.OK)

    @Test
    fun `a question leaves the collapsed group and everything else stays in it`() {
        val calls = listOf(read, ask(MobileToolCall.RUNNING))

        assertEquals(listOf("q1"), TranscriptQuestions.asked(calls).map { it.id })
        assertEquals(listOf("c1"), TranscriptQuestions.otherCalls(calls).map { it.id })
    }

    @Test
    fun `a parked question is answerable and says nothing extra about it`() {
        val call = ask(MobileToolCall.RUNNING)

        assertTrue(TranscriptQuestions.answerable(call, canAnswer = true))
        assertNull(TranscriptQuestions.closedNote(call, canAnswer = true))
    }

    /**
     * The control the whole card depends on: a settled call is a record of something the run has
     * moved past, and a tap on it could only ever land as a new prompt in a conversation the
     * reader was reading.
     */
    @Test
    fun `a question the run already moved past cannot be answered`() {
        val call = ask(MobileToolCall.OK)

        assertFalse(TranscriptQuestions.answerable(call, canAnswer = true))
        assertEquals("Already answered.", TranscriptQuestions.closedNote(call, canAnswer = true))
    }

    /** A machine with no `/v1/answer` refuses the pick, so the buttons must not invite one. */
    @Test
    fun `a machine that cannot answer says so rather than offering a dead button`() {
        val call = ask(MobileToolCall.RUNNING)

        assertFalse(TranscriptQuestions.answerable(call, canAnswer = false))
        assertEquals(
            "This machine's plugin is too old to answer from here.",
            TranscriptQuestions.closedNote(call, canAnswer = false),
        )
    }

    @Test
    fun `a question with no header of its own still gets a heading`() {
        assertEquals("Which fix?", TranscriptQuestions.header(question))
        assertEquals("Claude has a question", TranscriptQuestions.header(question.copy(header = null)))
        assertEquals("Claude has a question", TranscriptQuestions.header(question.copy(header = "  ")))
    }

    /** P17: "Something else" is exclusive with the labels, so the host can tell words from a pick. */
    @Test
    fun `words replace the labels of their question and a label replaces the words`() {
        val (picks, words) = TranscriptQuestions.pickLabel(emptyMap(), emptyMap(), question, "Leave it")
        assertEquals(mapOf(question.key to listOf("Leave it")), picks)

        val (typingPicks, typingWords) = TranscriptQuestions.openWords(picks, words, question)
        assertEquals(emptyMap<String, List<String>>(), typingPicks)
        assertEquals(mapOf(question.key to ""), typingWords)

        val (backPicks, backWords) = TranscriptQuestions.pickLabel(typingPicks, typingWords + (question.key to "Both"), question, "Pin the clock")
        assertEquals(mapOf(question.key to listOf("Pin the clock")), backPicks)
        assertEquals(emptyMap<String, String>(), backWords)
    }

    @Test
    fun `an opened but empty text field is not an answer`() {
        val call = ask(MobileToolCall.RUNNING)
        val open = mapOf(question.key to "  ")

        assertFalse(TranscriptQuestions.complete(call, emptyMap(), open))
        assertEquals(emptySet<String>(), TranscriptQuestions.typedKeys(call, open))
    }

    @Test
    fun `typed words are the whole answer and are marked as typed`() {
        val call = ask(MobileToolCall.RUNNING)
        val words = mapOf(question.key to "  Pin the clock, but keep the retry  ")

        assertTrue(TranscriptQuestions.complete(call, emptyMap(), words))
        assertEquals(mapOf(question.key to "Pin the clock, but keep the retry"), TranscriptQuestions.answers(call, emptyMap(), words))
        assertEquals(setOf(question.key), TranscriptQuestions.typedKeys(call, words))
    }
}
