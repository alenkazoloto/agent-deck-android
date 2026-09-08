package dev.agentdeck.companion

import dev.agentdeck.companion.ui.AgentMarkdown
import dev.agentdeck.companion.ui.InlineMarkdown
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an assistant turn's markdown parses to.
 *
 * The block renderer used to be four regexes over `lines()`, and these tests pinned their
 * corner cases. It is now `multiplatform-markdown-renderer` over JetBrains' parser, so what is
 * left to assert is the one decision that is still the app's: **which dialect**. [AgentMarkdown]
 * is the single declaration of it — the Composable renders through `AgentMarkdown.FLAVOUR` and
 * these parse through `AgentMarkdown.parse`, so swapping the flavour cannot pass here and fail
 * on screen.
 *
 * A CommonMark flavour compiles, renders, and silently loses all three of the constructs an
 * agent writes most: every case below fails under it.
 */
class MarkdownTest {

    /** Every node type in the tree, so a rule can be asserted without walking by hand. */
    private fun types(source: String): List<IElementType> {
        val found = mutableListOf<IElementType>()
        fun walk(node: ASTNode) {
            found += node.type
            node.children.forEach(::walk)
        }
        walk(AgentMarkdown.parse(source))
        return found
    }

    @Test
    fun `a task item parses as a checkbox, not as brackets in a bullet`() {
        val types = types("- [ ] Wire the push loop\n- [x] Run the suite")

        assertEquals(2, types.count { it == GFMTokenTypes.CHECK_BOX })
    }

    @Test
    fun `a table is a table`() {
        // The whole reason for the dialect: this reached the phone as four lines of pipes.
        val types = types(
            """
            | step | state |
            |------|-------|
            | boot | ok    |
            """.trimIndent(),
        )

        assertTrue(GFMElementTypes.TABLE in types)
    }

    @Test
    fun `strikethrough is a span, not four literal tildes`() {
        assertTrue(GFMElementTypes.STRIKETHROUGH in types("~~dropped~~ kept"))
    }

    @Test
    fun `a bracketed link at the start of a bullet is a link, not a checkbox`() {
        // `[ ]`/`[x]` is a checkbox; `[text](url)` is not, and the two share a first character.
        val types = types("- [the plan](PLAN.md) is next")

        assertTrue(MarkdownElementTypes.INLINE_LINK in types)
        assertFalse(GFMTokenTypes.CHECK_BOX in types)
    }

    @Test
    fun `a fence wins over every line rule inside it`() {
        val types = types("```kotlin\n- [ ] not a task\n1. not a list\n```")

        assertTrue(MarkdownElementTypes.CODE_FENCE in types)
        assertFalse(GFMTokenTypes.CHECK_BOX in types)
        assertFalse(MarkdownElementTypes.ORDERED_LIST in types)
    }

    @Test
    fun `an ordered list keeps its own start number`() {
        // A plan that resumes at 3 must not silently restart at 1.
        val source = "3. third\n4. fourth"
        val markers = mutableListOf<String>()
        fun walk(node: ASTNode) {
            if (node.type == MarkdownTokenTypes.LIST_NUMBER) {
                markers += source.substring(node.startOffset, node.endOffset).trim()
            }
            node.children.forEach(::walk)
        }
        walk(AgentMarkdown.parse(source))

        assertEquals(listOf("3.", "4."), markers)
    }

    @Test
    fun `a block quote is a block quote`() {
        assertTrue(MarkdownElementTypes.BLOCK_QUOTE in types("> the plan changed"))
    }

    @Test
    fun `inline spans still render in the single-line labels that keep their own parser`() {
        // Checklist rows and fleet lines go through InlineMarkdown, not through the renderer.
        assertTrue(InlineMarkdown.inline("run `./gradlew test`").text.contains("./gradlew test"))
        assertEquals("bold", InlineMarkdown.inline("**bold**").text)
    }
}
