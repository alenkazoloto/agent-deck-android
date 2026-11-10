package dev.agentdeck.companion

import dev.agentdeck.companion.data.Dictation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A dictated phrase joins the draft; it never replaces it. Overwriting is the one outcome
 * the draft rules exist to prevent, and a recognizer fires while the user is looking at the
 * recognizer's own screen — they would not see what was lost.
 */
class DictationTest {

    @Test
    fun `an empty draft takes the phrase alone`() {
        assertEquals("run the tests", Dictation.append("", "run the tests"))
    }

    @Test
    fun `a dictation is appended to what is already typed`() {
        assertEquals(
            "Look at ChatsPanel run the tests",
            Dictation.append("Look at ChatsPanel", "run the tests"),
        )
    }

    @Test
    fun `the recognizer's own padding never becomes a double space`() {
        assertEquals("check this now", Dictation.append("check this ", "  now  "))
        assertEquals("check this now", Dictation.append("check this", " now"))
    }

    @Test
    fun `a draft left open mid-word is not split by a space`() {
        // A trailing quote or bracket is the user mid-token; a space there lands inside it.
        assertEquals("""grep "needle""", Dictation.append("""grep """", "needle"))
        assertEquals("run (again", Dictation.append("run (", "again"))
    }

    @Test
    fun `a recognizer that heard nothing leaves the draft untouched`() {
        assertEquals("keep me", Dictation.append("keep me", "   "))
        assertEquals("keep me", Dictation.append("keep me", ""))
    }
}
