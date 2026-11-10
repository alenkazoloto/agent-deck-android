package dev.agentdeck.companion.data

/**
 * The three things a reader needs a colour for in a code fence on a phone: what is a keyword,
 * what is a string, and what is a comment.
 *
 * Not a lexer. A lexer for ten languages is ten grammars to keep, and the one question this
 * answers — "where does the code stop and the prose about it start" — is answered by comment
 * and string boundaries alone. Numbers, types, annotations and operators are deliberately
 * absent: each is a colour the reader has to learn, on a surface four inches wide.
 */
enum class SyntaxToken { KEYWORD, STRING, COMMENT }

/** Half-open `[start, end)` over the fence's own text, in ascending, non-overlapping order. */
data class SyntaxSpan(val start: Int, val end: Int, val token: SyntaxToken)

/**
 * One language's three rules. [lineComment] and [blockComment] may be absent — JSON has
 * neither — and [keywords] may be empty, which is how a language whose whole vocabulary is
 * punctuation still gets its strings coloured.
 */
data class SyntaxRules(
    val lineComment: String?,
    val blockComment: Pair<String, String>?,
    val quotes: String,
    val keywords: Set<String>,
    /** A backslash escapes the next character inside a string — false for YAML's plain scalars. */
    val escapes: Boolean = true,
)

/**
 * Syntax-lite highlighting for the ten languages agent output is actually made of.
 *
 * Pure, and out here rather than inside the Compose fence for two reasons: `MarkdownTest` can
 * assert a token's exact range without a composition, and the same spans colour a *diff* line
 * under Changes, which is the other place the phone shows code. Two tokenizers would have
 * disagreed about a `//` inside a string on the first file that had one.
 *
 * An unknown fence — no info string, a prose fence, `diff`, `text` — renders plain rather than
 * guessing: mis-colouring a language is worse than not colouring it, because the reader cannot
 * tell which of the two happened.
 */
object SyntaxLite {

    /**
     * Above this, a fence renders plain.
     *
     * A fence is scanned on the composition (see `MarkdownText`'s `immediate = true`), so the
     * bound is on the frame, not on memory: 64 KiB is far above any code block a model writes
     * and far below the pasted log that would make a scroll stutter.
     */
    const val MAX_CHARS = 64 * 1024

    private val KOTLIN = SyntaxRules(
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'",
        keywords = setOf(
            "as", "break", "by", "catch", "class", "companion", "const", "constructor", "continue",
            "crossinline", "data", "do", "else", "enum", "external", "false", "finally", "for",
            "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface",
            "internal", "is", "it", "lateinit", "null", "object", "open", "operator", "out",
            "override", "package", "private", "protected", "public", "reified", "return", "sealed",
            "set", "suspend", "super", "this", "throw", "true", "try", "typealias", "val", "var",
            "vararg", "when", "where", "while",
        ),
    )

    private val JAVA = SyntaxRules(
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'",
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "false",
            "final", "finally", "float", "for", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "null", "package", "private", "protected",
            "public", "record", "return", "short", "static", "super", "switch", "synchronized",
            "this", "throw", "throws", "transient", "true", "try", "var", "void", "volatile", "while",
        ),
    )

    private val JS = SyntaxRules(
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'`",
        keywords = setOf(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
            "from", "function", "if", "import", "in", "instanceof", "let", "new", "null", "of",
            "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof",
            "undefined", "var", "void", "while", "yield",
        ),
    )

    private val TS = JS.copy(
        keywords = JS.keywords + setOf(
            "abstract", "any", "as", "boolean", "declare", "enum", "implements", "interface",
            "keyof", "namespace", "never", "number", "private", "protected", "public", "readonly",
            "satisfies", "string", "type", "unknown",
        ),
    )

    private val PYTHON = SyntaxRules(
        lineComment = "#", blockComment = null, quotes = "\"'",
        keywords = setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
            "self", "True", "try", "while", "with", "yield",
        ),
    )

    private val GO = SyntaxRules(
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'`",
        keywords = setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
            "false", "for", "func", "go", "goto", "if", "import", "interface", "map", "nil",
            "package", "range", "return", "select", "struct", "switch", "true", "type", "var",
        ),
    )

    private val RUST = SyntaxRules(
        lineComment = "//", blockComment = "/*" to "*/", quotes = "\"'",
        keywords = setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
            "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
            "trait", "true", "type", "unsafe", "use", "where", "while",
        ),
    )

    private val SHELL = SyntaxRules(
        lineComment = "#", blockComment = null, quotes = "\"'",
        keywords = setOf(
            "case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if",
            "in", "local", "readonly", "return", "set", "then", "until", "while",
        ),
    )

    private val JSON = SyntaxRules(
        lineComment = null, blockComment = null, quotes = "\"",
        keywords = setOf("true", "false", "null"),
    )

    /**
     * YAML's plain scalars are not escaped — `path: C:\tools` is a value, not a broken escape —
     * so [SyntaxRules.escapes] is off here and a lone backslash cannot swallow the quote that
     * would have closed a string.
     */
    private val YAML = SyntaxRules(
        lineComment = "#", blockComment = null, quotes = "\"'",
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off", "~"),
        escapes = false,
    )

    /**
     * The fence's info string, normalised. Aliases are the ones models actually write; anything
     * else is null and renders plain.
     */
    fun rulesFor(language: String?): SyntaxRules? {
        val name = language.orEmpty().trim().lowercase().substringBefore(' ').substringBefore(':')
        return when (name) {
            "kotlin", "kt", "kts" -> KOTLIN
            "java" -> JAVA
            "typescript", "ts", "tsx" -> TS
            "javascript", "js", "jsx", "mjs", "cjs" -> JS
            "python", "py" -> PYTHON
            "go", "golang" -> GO
            "rust", "rs" -> RUST
            "shell", "sh", "bash", "zsh", "console" -> SHELL
            "json", "json5", "jsonc" -> JSON
            "yaml", "yml" -> YAML
            else -> null
        }
    }

    /** What a file's own name says it is — the Changes tab has a path where a fence has a label. */
    fun rulesForPath(path: String?): SyntaxRules? =
        rulesFor(path?.substringAfterLast('/')?.substringAfterLast('.')?.takeIf { it.isNotBlank() })

    /**
     * Ascending, non-overlapping spans. Empty for an unknown language, an oversized body, or a
     * body with nothing worth colouring.
     *
     * One left-to-right pass, because the three rules are not independent: a `//` inside a
     * string opens no comment, a quote inside a comment opens no string, and a keyword inside
     * either is just letters. Three passes would have had to un-decide each other.
     */
    fun spans(text: String, rules: SyntaxRules?): List<SyntaxSpan> {
        if (rules == null || text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        val spans = ArrayList<SyntaxSpan>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val lineComment = rules.lineComment
            val block = rules.blockComment
            when {
                lineComment != null && text.startsWith(lineComment, i) && opensComment(text, i, lineComment) -> {
                    val end = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
                    spans.add(SyntaxSpan(i, end, SyntaxToken.COMMENT))
                    i = end
                }
                block != null && text.startsWith(block.first, i) -> {
                    val close = text.indexOf(block.second, i + block.first.length)
                    val end = if (close >= 0) close + block.second.length else text.length
                    spans.add(SyntaxSpan(i, end, SyntaxToken.COMMENT))
                    i = end
                }
                ch in rules.quotes -> {
                    val end = closeQuote(text, i, ch, rules.escapes)
                    spans.add(SyntaxSpan(i, end, SyntaxToken.STRING))
                    i = end
                }
                ch.isWordStart() -> {
                    var end = i + 1
                    while (end < text.length && text[end].isWordPart()) end++
                    if (text.substring(i, end) in rules.keywords) {
                        spans.add(SyntaxSpan(i, end, SyntaxToken.KEYWORD))
                    }
                    i = end
                }
                // YAML's `~` is a keyword that is not a word, so it is matched outright.
                "$ch" in rules.keywords -> {
                    spans.add(SyntaxSpan(i, i + 1, SyntaxToken.KEYWORD))
                    i++
                }
                else -> i++
            }
        }
        return spans
    }

    /**
     * Where the string opened at [open] ends — past the closing quote, or at the end of the
     * line when it never closes.
     *
     * An unterminated quote stops at the newline rather than running to the end of the body:
     * an apostrophe in a shell comment-free line ("don't") would otherwise paint the rest of
     * the file as a string, which is the single most visible way a highlighter can be wrong.
     * A triple-quoted Kotlin or Python block is the accepted cost — it is coloured line by line
     * rather than as one span, which reads the same.
     */
    private fun closeQuote(text: String, open: Int, quote: Char, escapes: Boolean): Int {
        var i = open + 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\n') return i
            // The escape may not step *over* the newline: a line that ends mid-string with a
            // trailing backslash — a truncated paste, `val s = "C:\` — would otherwise tint
            // every line under it, which is the failure the line bound above exists to prevent.
            if (escapes && ch == '\\' && text.getOrNull(i + 1) != '\n') { i += 2; continue }
            if (ch == quote) return i + 1
            i++
        }
        return text.length
    }

    /**
     * Whether a `#` at [at] really opens a comment.
     *
     * `#` is a comment mark only at the start of a token in every language that uses one: shell
     * spells `${'$'}#` and `${'$'}{#arr[@]}` with it, and YAML requires whitespace before it, so
     * `url: https://x/y#frag` is a value. `//` needs no such rule — it is not a fragment of any
     * other construct — and is left unconditional.
     */
    private fun opensComment(text: String, at: Int, mark: String): Boolean =
        mark != "#" || at == 0 || text[at - 1].isWhitespace()

    private fun Char.isWordStart(): Boolean = isLetter() || this == '_'

    private fun Char.isWordPart(): Boolean = isLetterOrDigit() || this == '_'
}
