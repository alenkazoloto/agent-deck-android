package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.agentdeck.companion.R
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Which markdown the phone reads, declared once.
 *
 * [MarkdownText] passes [FLAVOUR] to the renderer and `MarkdownTest` parses through [parse], so
 * the dialect the app renders and the dialect the suite asserts are the same object. Split in
 * two they would drift on the first upgrade — the library's own default is GFM today, and a
 * default is not a decision anybody stated.
 *
 * GFM is not decoration here: tables, task lists and strikethrough are what agent output is
 * *made of*, and the CommonMark core has none of the three.
 *
 * One accepted difference from the regexes this replaced: a line of prose that begins with a
 * number and a dot ("2026. A good year") now opens an ordered list, because this parser lets a
 * list interrupt a paragraph at any start number. The old rule bought that case by refusing
 * four-digit markers, which also refused a real list numbered past 999 — a worse trade for
 * agent output, which numbers plans and almost never opens a sentence with a year.
 */
object AgentMarkdown {

    val FLAVOUR: MarkdownFlavourDescriptor = GFMFlavourDescriptor()

    fun parse(source: String): ASTNode =
        MarkdownParser(FLAVOUR).buildMarkdownTreeFromString(source)
}

/**
 * A short run of markdown as one styled string: `code`, **bold**, *italic*.
 *
 * The block renderer is [MarkdownText] and is a real parser; this stays hand-rolled because its
 * callers — a checklist row, a fleet line — are single-line labels inside a layout that has
 * already been decided, and handing those to a Composable that emits its own `Column` would
 * put a paragraph where a row is.
 */
object InlineMarkdown {

    /** `code`, **bold**, *italic* — applied in that order so a span inside a fence wins. */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        val pattern = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|_([^_]+)_")
        var cursor = 0
        pattern.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val code = match.groupValues[1]
            val bold = match.groupValues[2]
            val italic = match.groupValues[3].ifEmpty { match.groupValues[4] }
            when {
                code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/**
 * An assistant turn's markdown.
 *
 * Rendered by `multiplatform-markdown-renderer` over the GFM flavour of JetBrains' own
 * CommonMark parser. What this replaced was four regexes over `lines()`, which had no notion of
 * a table, a block quote, a link or a nested list — so an agent's comparison table reached the
 * phone as a column of pipes and its citations as literal brackets, on the one screen whose
 * whole content is agent prose.
 *
 * Three slots stay ours, because they are app decisions rather than markdown ones:
 *  - **code fences** keep the copy button ([CodeBlock]); copying a command out of a run is the
 *    one thing a reader does *to* a code block on a phone;
 *  - **checkboxes** keep [TaskBox], so a `- [x]` inside prose and the protocol's own checklist
 *    are the same mark rather than two features on one screen;
 *  - **headings** are re-pointed at the title roles. The library's defaults are `displayLarge`
 *    down to `titleLarge` — a page-title ramp, and an `## Plan` inside a chat bubble would have
 *    been set 57 sp.
 */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Markdown(
        // Parsed on the composition, not off it. The library's default is to parse in a
        // coroutine and render an empty `Box` until it lands — which in a LazyColumn means
        // every bubble is laid out at zero height first and grows a frame later. The list then
        // scrolls to an "end" that stops being the end (the conversation opened three turns
        // above its newest one), and scrolling back through a long transcript pops. These are
        // a few KB each and only the visible bubbles are composed, so the parse is cheap where
        // the mis-measurement was not.
        markdownState = rememberMarkdownState(
            content = source,
            flavour = AgentMarkdown.FLAVOUR,
            immediate = true,
        ),
        colors = markdownColor(
            text = scheme.onSurface,
            codeBackground = scheme.surfaceContainerHighest,
            inlineCodeBackground = scheme.surfaceContainerHighest,
            dividerColor = scheme.outlineVariant,
            tableBackground = scheme.surfaceContainerLow,
        ),
        typography = markdownTypography(
            h1 = type.titleLarge,
            h2 = type.titleMedium,
            h3 = type.titleSmall,
            h4 = type.titleSmall,
            h5 = type.labelLarge,
            h6 = type.labelLarge,
            text = type.bodyMedium,
            paragraph = type.bodyMedium,
            ordered = type.bodyMedium,
            bullet = type.bodyMedium,
            list = type.bodyMedium,
            quote = type.bodyMedium.copy(fontStyle = FontStyle.Italic),
            code = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
            table = type.bodySmall,
        ),
        // The library's own default is `fillMaxSize`, which inside a bubble in a LazyColumn is
        // an infinite height constraint — the parameter is not optional here.
        modifier = modifier.fillMaxWidth(),
        components = markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, language, _ ->
                    CodeBlock(language, code)
                }
            },
            checkbox = { model ->
                MarkdownCheckBox(
                    content = model.content,
                    node = model.node,
                    style = model.typography.text,
                    checkedIndicator = { checked, boxModifier ->
                        TaskBox(
                            if (checked) TaskMark.DONE else TaskMark.PENDING,
                            boxModifier.padding(top = 3.dp, end = 4.dp),
                        )
                    },
                )
            },
        ),
    )
}

/**
 * A fenced code block, with the copy affordance the library's own does not have.
 *
 * Icon-only: the action's name lives in the accessible description.
 */
@Composable
private fun CodeBlock(language: String?, text: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                language.orEmpty().trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_content_copy),
                    contentDescription = "Copy this code block",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        )
    }
}
