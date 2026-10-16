package dev.agentdeck.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether a turn's tool-call group is open.
 *
 * Only the reader opens one — or Settings › Reading › "Open tool calls", which the reader chose. The group used to default to open whenever the *page* was running,
 * and `running` is a property of the conversation rather than of a turn — so opening a
 * conversation whose agent happened to be busy unrolled the tool calls of every turn in it,
 * including ones from hours earlier. The transcript then read as a log of `Bash`/`Read`/`Edit`
 * lines with the answers buried inside it, which is what "all the tools are opened by default"
 * describes.
 *
 * Nothing is lost by collapsing: the header carries the count, and what a live run is doing
 * right now is on the `WorkingBubble`'s ticker, which is pinned to the bottom where a reader
 * watching a run is already looking.
 */
object ToolDisclosure {

    /**
     * [toggled] is whether the reader has flipped this group away from its starting state, which is
     * [openByDefault] (Settings › Reading, off unless asked for). A flip rather than "opened" so a
     * reader who opened everything can still fold one, and so a setting change never fights a tap.
     *
     * [runLive] is taken and deliberately not consulted. It is the input this decision was
     * previously made from, so the parameter is where the "no" is written down and the
     * regression is caught, rather than a comment that the next change can step over.
     */
    fun expanded(toggled: Boolean, runLive: Boolean, openByDefault: Boolean = false): Boolean = toggled != openByDefault
}

/**
 * Settings › Reading › "Open tool calls", one answer for every group in the transcript. A composition
 * local for the reason [LocalCodeWrap] is one: it would otherwise be a parameter on every composable
 * between the screen and the group. Off, which is the layout every screenshot holds.
 */
val LocalOpenToolCalls: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

@Composable
fun ProvideOpenToolCalls(open: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOpenToolCalls provides open, content = content)
}
