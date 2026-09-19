package dev.agentdeck.companion

import dev.agentdeck.companion.data.SyntaxLite
import dev.agentdeck.companion.data.SyntaxToken
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
    fun `an image reads as a note naming it, since the phone cannot draw it`() {
        // t3code #10322: the renderer's no-op image loader left a blank gap, no alt text.
        val text = AgentMarkdown.imagesAsText("Here:\n\n![Mobile PNG test](</Users/example/assets/test.png>)\n\nDone")

        assertEquals("Here:\n\n*Image \u201cMobile PNG test\u201d \u2014 open in the IDE to see it*\n\nDone", text)
    }

    @Test
    fun `an image without alt text is named by its file`() {
        assertEquals(
            "*Image \u201ctest\\.png\u201d \u2014 open in the IDE to see it*",
            AgentMarkdown.imagesAsText("![](/Users/example/assets/test.png?v=2)"),
        )
        assertEquals(
            "*Image \u2014 open in the IDE to see it*",
            AgentMarkdown.imagesAsText("![](data:image/png;base64,AAAA)"),
        )
    }

    @Test
    fun `every image in a message is replaced and markup in its alt text stays literal`() {
        val text = AgentMarkdown.imagesAsText("![a*b](one.png) and ![two](two.png)")

        assertEquals(
            "*Image \u201ca\\*b\u201d \u2014 open in the IDE to see it* and " +
                "*Image \u201ctwo\u201d \u2014 open in the IDE to see it*",
            text,
        )
    }

    @Test
    fun `an image inside a code fence is still code, and a message without one is untouched`() {
        val fenced = "```\n![alt](x.png)\n```"

        assertEquals(fenced, AgentMarkdown.imagesAsText(fenced))
        assertEquals("plain **prose**", AgentMarkdown.imagesAsText("plain **prose**"))
    }

    @Test
    fun `inline spans still render in the single-line labels that keep their own parser`() {
        // Checklist rows and fleet lines go through InlineMarkdown, not through the renderer.
        assertTrue(InlineMarkdown.inline("run `./gradlew test`").text.contains("./gradlew test"))
        assertEquals("bold", InlineMarkdown.inline("**bold**").text)
    }

    // ---- syntax-lite (M8) --------------------------------------------------------------
    //
    // The spans are asserted by the *text they cover*, never by offset arithmetic: an offset
    // in a failure message says nothing about which token moved, and these have to survive
    // someone adding a keyword to a set above them.

    private fun tokens(source: String, language: String?): List<Pair<SyntaxToken, String>> =
        SyntaxLite.spans(source, SyntaxLite.rulesFor(language))
            .map { it.token to source.substring(it.start, it.end) }

    @Test
    fun `keywords, strings and comments are the three things coloured`() {
        assertEquals(
            listOf(
                SyntaxToken.KEYWORD to "val",
                SyntaxToken.STRING to "\"ok\"",
                SyntaxToken.COMMENT to "// why",
            ),
            tokens("val x = \"ok\" // why", "kotlin"),
        )
    }

    /** The one rule three passes could not have kept: a mark inside a string is not a mark. */
    @Test
    fun `a comment mark inside a string opens no comment`() {
        assertEquals(
            listOf(SyntaxToken.STRING to "\"https://example.com\""),
            tokens("\"https://example.com\"", "kotlin"),
        )
    }

    @Test
    fun `a keyword inside a comment is just letters`() {
        assertEquals(listOf(SyntaxToken.COMMENT to "# return the value"), tokens("# return the value", "python"))
    }

    /** An apostrophe in prose must not paint the rest of the file as a string. */
    @Test
    fun `an unterminated quote stops at the end of its line`() {
        assertEquals(
            listOf(SyntaxToken.STRING to "'t close", SyntaxToken.KEYWORD to "export"),
            tokens("don't close\nexport PATH=1", "bash"),
        )
    }

    /**
     * A trailing backslash may not carry the string over the newline: a truncated paste —
     * `val s = "C:\\` — would otherwise tint every line under it, which the line bound exists
     * to prevent.
     */
    @Test
    fun `an escape at the end of a line does not carry the string past it`() {
        assertEquals(
            listOf(SyntaxToken.STRING to "\"C:\\", SyntaxToken.KEYWORD to "val"),
            tokens("\"C:\\\nval next = 1", "kotlin"),
        )
    }

    /** `#` is a comment mark only at the start of a token; shell and YAML spell it mid-token. */
    @Test
    fun `a hash inside a token opens no comment`() {
        assertEquals(emptyList<Pair<SyntaxToken, String>>(), tokens("echo \$#", "bash"))
        assertEquals(
            listOf(SyntaxToken.COMMENT to "# real"),
            tokens("echo \${#arr[@]} # real", "bash"),
        )
        assertEquals(
            listOf(SyntaxToken.COMMENT to "#comment at column zero"),
            tokens("#comment at column zero", "yaml"),
        )
    }

    @Test
    fun `a block comment spans lines and an unclosed one runs to the end`() {
        assertEquals(listOf(SyntaxToken.COMMENT to "/* one\n   two */"), tokens("/* one\n   two */", "java"))
        assertEquals(listOf(SyntaxToken.COMMENT to "/* never"), tokens("/* never", "java"))
    }

    /** YAML is not escaped: a trailing backslash may not swallow the quote that closes a value. */
    @Test
    fun `a backslash in a yaml scalar does not escape`() {
        assertEquals(listOf(SyntaxToken.STRING to "\"C:\\\""), tokens("path: \"C:\\\"", "yaml"))
    }

    @Test
    fun `an identifier that merely contains a keyword is not one`() {
        assertEquals(emptyList<Pair<SyntaxToken, String>>(), tokens("returnValue = valve", "kotlin"))
    }

    @Test
    fun `every language the plan named is recognised, by name and by the alias models write`() {
        listOf(
            "kotlin", "kt", "java", "typescript", "ts", "javascript", "js", "python", "py",
            "go", "rust", "rs", "shell", "bash", "json", "yaml", "yml",
        ).forEach { assertTrue(it, SyntaxLite.rulesFor(it) != null) }
    }

    /** Mis-colouring a language is worse than not colouring it: the reader cannot tell which. */
    @Test
    fun `an unknown or absent fence language renders plain`() {
        assertTrue(SyntaxLite.rulesFor(null) == null)
        assertTrue(SyntaxLite.rulesFor("") == null)
        assertTrue(SyntaxLite.rulesFor("diff") == null)
        assertEquals(emptyList<Pair<SyntaxToken, String>>(), tokens("val x = 1", "prose"))
    }

    @Test
    fun `a file's own extension is the language under Changes`() {
        assertEquals(SyntaxLite.rulesFor("kotlin"), SyntaxLite.rulesForPath("src/main/Foo.kt"))
        assertEquals(SyntaxLite.rulesFor("yaml"), SyntaxLite.rulesForPath(".github/workflows/ci.yml"))
        assertTrue(SyntaxLite.rulesForPath("LICENSE") == null)
    }

    /** A pasted 200 KB log is not a code block, and a scan of one is a dropped frame. */
    @Test
    fun `an oversized body renders plain`() {
        val huge = "val x = 1\n".repeat(SyntaxLite.MAX_CHARS / 10 + 1)
        assertTrue(huge.length > SyntaxLite.MAX_CHARS)
        assertTrue(SyntaxLite.spans(huge, SyntaxLite.rulesFor("kotlin")).isEmpty())
    }

    @Test
    fun `spans are ascending and never overlap`() {
        val source = "// head\nfun f() { val s = \"x\" /* mid */ }\n# not kotlin"
        val spans = SyntaxLite.spans(source, SyntaxLite.rulesFor("kotlin"))
        assertTrue(spans.isNotEmpty())
        spans.zipWithNext().forEach { (a, b) ->
            assertTrue("$a then $b", a.end <= b.start)
        }
        spans.forEach { assertTrue(it.start < it.end && it.end <= source.length) }
    }
}
