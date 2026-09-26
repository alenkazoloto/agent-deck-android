package dev.agentdeck.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.agentdeck.companion.data.SyntaxLite
import dev.agentdeck.companion.data.SyntaxRules
import dev.agentdeck.companion.data.SyntaxToken

/**
 * Whether the reader wants long code lines wrapped — Settings › Reading, one answer for both
 * places the phone shows code.
 *
 * A composition local rather than a parameter because the two callers are a *markdown fence*,
 * which is reached through the renderer's own component slot and cannot be handed anything, and
 * a diff line, which already takes it explicitly. A second copy of the setting threaded down
 * through `TurnBubble` would have been a fourth parameter on every intermediate composable for
 * a value none of them reads.
 *
 * Defaults to false, which is the layout every existing screenshot holds: a fence scrolls
 * sideways unless the reader asked otherwise.
 */
val LocalCodeWrap: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

@Composable
fun ProvideCodeWrap(wrap: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCodeWrap provides wrap, content = content)
}

/**
 * [text] with its keywords, strings and comments coloured — the one place [SyntaxLite]'s spans
 * become pixels, so a fence and a diff line cannot paint the same token two different colours.
 *
 * Three roles, chosen for contrast on both themes rather than for resemblance to any editor:
 * a keyword is the surface's own accent, a string its secondary accent, a comment the muted
 * role every de-emphasised caption on this phone already uses. No background, no bold, no
 * italic: a code block on a phone is already a dense grey rectangle, and weight on top of
 * colour is what turns one into a stained-glass window.
 */
@Composable
fun highlighted(text: String, rules: SyntaxRules?): AnnotatedString {
    val keyword = MaterialTheme.colorScheme.primary
    val string = MaterialTheme.colorScheme.tertiary
    val comment = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(text, rules, keyword, string, comment) {
        annotate(text, SyntaxLite.spans(text, rules), keyword, string, comment)
    }
}

/** Pure, so a test can assert the ranges without a composition. */
internal fun annotate(
    text: String,
    spans: List<dev.agentdeck.companion.data.SyntaxSpan>,
    keyword: Color,
    string: Color,
    comment: Color,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        spans.forEach { span ->
            if (span.start > cursor) append(text.substring(cursor, span.start))
            val colour = when (span.token) {
                SyntaxToken.KEYWORD -> keyword
                SyntaxToken.STRING -> string
                SyntaxToken.COMMENT -> comment
            }
            withStyle(SpanStyle(color = colour)) { append(text.substring(span.start, span.end)) }
            cursor = span.end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
