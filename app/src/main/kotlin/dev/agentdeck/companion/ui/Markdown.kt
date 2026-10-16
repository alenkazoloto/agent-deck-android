package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
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
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.agentdeck.companion.R
import com.github.claudeagents.core.mobile.MobileImage
import dev.agentdeck.companion.data.SyntaxLite
import kotlinx.coroutines.launch
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
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

    /**
     * Each `![alt](path)` as a one-line note naming the image, except the ones [keep] says the
     * phone will draw itself: a renderer with no loader leaves a blank gap where the picture was
     * (t3code #10322). Read off the parse tree, so an image inside a code fence stays code.
     */
    fun imagesAsText(source: String, keep: (destination: String) -> Boolean = { false }): String {
        val edits = images(source).filterNot { keep(it.destination) }
        return edits.asReversed().fold(source) { text, image ->
            text.replaceRange(image.start, image.end, imageNote(image.alt, image.destination))
        }
    }

    /** The destinations of the images the machine can be asked for, in reading order, once each. */
    fun localImageDestinations(source: String): List<String> =
        images(source).map { it.destination }.filter(MobileImage::isLocalSource).distinct()

    /** Each image's alt text by destination — what a screen reader says over the picture. */
    fun imageAlts(source: String): Map<String, String> =
        images(source).associate { it.destination to it.alt.trim() }

    private class ImageRef(val start: Int, val end: Int, val alt: String, val destination: String)

    private fun images(source: String): List<ImageRef> {
        if ("![" !in source) return emptyList()
        val found = mutableListOf<ImageRef>()
        fun walk(node: ASTNode) {
            if (node.type == MarkdownElementTypes.IMAGE) {
                val alt = node.firstOfType(MarkdownElementTypes.LINK_TEXT)
                    ?.getTextInNode(source)?.toString()?.removeSurrounding("[", "]").orEmpty()
                val destination = node.firstOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getTextInNode(source)?.toString().orEmpty().removeSurrounding("<", ">")
                found += ImageRef(node.startOffset, node.endOffset, alt, destination)
            } else {
                node.children.forEach(::walk)
            }
        }
        walk(parse(source))
        return found
    }

    private fun ASTNode.firstOfType(type: IElementType): ASTNode? =
        if (this.type == type) this else children.firstNotNullOfOrNull { it.firstOfType(type) }

    private fun imageNote(alt: String, destination: String): String {
        val name = alt.trim().ifEmpty { fileName(destination) }.replace(PUNCTUATION) { "\\" + it.value }
        val label = if (name.isEmpty()) "Image" else "Image \u201c$name\u201d"
        return "*$label \u2014 open in the IDE to see it*"
    }

    private fun fileName(destination: String): String {
        val path = destination
        if (path.startsWith("data:")) return ""
        return path.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
    }

    private val PUNCTUATION = Regex("""[!-/:-@\[-`{-~]""")
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
 * Fetches one image of the open conversation by the destination its markdown wrote; null where
 * the machine will not show it. Provided only for a machine that advertises
 * [MobileImage] — absent, every image stays a one-line note.
 */
typealias MarkdownImageLoader = suspend (destination: String) -> ImageBitmap?

val LocalMarkdownImages = staticCompositionLocalOf<MarkdownImageLoader?> { null }

/** The desk's "Render formatting in messages"; off, an answer is drawn as the plain text the agent wrote. */
val LocalRenderMarkup = staticCompositionLocalOf { true }

/** Paints what [MarkdownText] has fetched, and a blank of the same width while it is on its way. */
private class PhoneImageTransformer(
    private val loaded: Map<String, ImageBitmap?>,
    private val alts: Map<String, String>,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val destination = link.removeSurrounding("<", ">")
        val bitmap = loaded[destination]
        val painter = remember(bitmap) { bitmap?.let(::BitmapPainter) ?: ColorPainter(Color.Transparent) }
        return ImageData(
            painter = painter,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .then(if (bitmap == null) Modifier.height(96.dp) else Modifier),
            contentDescription = alts[destination]?.takeIf { it.isNotEmpty() },
            alignment = Alignment.CenterStart,
            contentScale = ContentScale.Fit,
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size = painter.intrinsicSize
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
    val loader = LocalMarkdownImages.current
    val wanted = remember(source, loader) {
        if (loader == null) emptyList() else AgentMarkdown.localImageDestinations(source)
    }
    val alts = remember(source) { AgentMarkdown.imageAlts(source) }
    // A value of null is "the machine would not show this one": it falls back to the note.
    val loaded = remember(loader) { mutableStateMapOf<String, ImageBitmap?>() }
    LaunchedEffect(wanted, loader) {
        if (loader == null) return@LaunchedEffect
        wanted.filter { it !in loaded }.forEach { destination -> launch { loaded[destination] = loader(destination) } }
    }
    val drawn = wanted.filter { !loaded.containsKey(it) || loaded[it] != null }.toSet()
    Markdown(
        // Parsed on the composition, not off it. The library's default is to parse in a
        // coroutine and render an empty `Box` until it lands — which in a LazyColumn means
        // every bubble is laid out at zero height first and grows a frame later. The list then
        // scrolls to an "end" that stops being the end (the conversation opened three turns
        // above its newest one), and scrolling back through a long transcript pops. These are
        // a few KB each and only the visible bubbles are composed, so the parse is cheap where
        // the mis-measurement was not.
        markdownState = rememberMarkdownState(
            content = remember(source, drawn) { AgentMarkdown.imagesAsText(source) { it in drawn } },
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
        imageTransformer = if (loader == null) NoOpImageTransformerImpl() else PhoneImageTransformer(loaded, alts),
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
 * A fenced code block, with the copy affordance the library's own does not have, and
 * syntax-lite colour for the ten languages agent output is made of ([SyntaxLite]).
 *
 * Icon-only: the action's name lives in the accessible description.
 *
 * Wrapping follows Settings › Reading ([LocalCodeWrap]) rather than a control on the block: the
 * answer is a reading habit, not a per-fence decision, and a toggle on every fence is a toggle
 * in the way of every fence. Off, the block scrolls sideways as it always has.
 */
@Composable
private fun CodeBlock(language: String?, text: String) {
    val clipboard = LocalClipboardManager.current
    val body = highlighted(text, SyntaxLite.rulesFor(language))
    val wrap = LocalCodeWrap.current
    val scroll = rememberScrollState()
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
                modifier = Modifier.size(48.dp),
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
            body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = wrap,
            modifier = Modifier
                .then(if (wrap) Modifier else Modifier.horizontalScroll(scroll))
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
        )
    }
}
