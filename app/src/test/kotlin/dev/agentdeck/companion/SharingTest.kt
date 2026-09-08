package dev.agentdeck.companion

import dev.agentdeck.companion.data.Sharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What another app's share sheet becomes — the whole of it, because everything above this is
 * a `ContentResolver` read and a navigation.
 */
class SharingTest {

    @Test
    fun `a browser share does not print its title twice`() {
        // Chrome sends the page title as EXTRA_SUBJECT and "Title https://…" as EXTRA_TEXT.
        // A naive join puts the title on two lines of the prompt.
        val shared = Sharing.compose("Kotlin coroutines", "Kotlin coroutines https://kotlinlang.org/x")!!
        assertEquals("Kotlin coroutines https://kotlinlang.org/x", shared.text)
    }

    @Test
    fun `a subject the body does not contain is kept`() {
        val shared = Sharing.compose("build.log", "FAILURE: task :app:compile")!!
        assertEquals("build.log\n\nFAILURE: task :app:compile", shared.text)
        assertEquals("build.log", shared.label)
    }

    @Test
    fun `a share with no text at all is not a share`() {
        assertNull(Sharing.compose(null, null))
        assertNull(Sharing.compose("   ", "\n \t "))
    }

    @Test
    fun `a subject alone still sends`() {
        // A file with a name and no readable content: the name is the only thing the machine
        // gets, and it is more than nothing.
        val shared = Sharing.compose("notes.md", null)!!
        assertEquals("notes.md", shared.text)
    }

    @Test
    fun `an article is clipped, and says so`() {
        val shared = Sharing.compose(null, "x".repeat(Sharing.MAX_CHARS * 2))!!
        assertTrue(shared.text.length < Sharing.MAX_CHARS * 2)
        assertTrue(shared.text.endsWith("characters]"))
    }

    @Test
    fun `the label is one line, short enough for a banner`() {
        val shared = Sharing.compose(null, "\n\n" + "a very long first line ".repeat(10) + "\nsecond")!!
        assertTrue(shared.label.length <= 61)
        assertTrue(shared.label.endsWith("…"))
        assertTrue('\n' !in shared.label)
    }

    @Test
    fun `a share never overwrites what the user was already typing`() {
        // The one thing this app promises about drafts. A share landing on a half-written
        // question would delete it with no undo.
        assertEquals("half a question\n\nshared", Sharing.appendedTo("half a question ", "shared"))
        assertEquals("shared", Sharing.appendedTo("   ", "shared"))
    }
}
