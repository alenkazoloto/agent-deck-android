package dev.agentdeck.companion.ui

/**
 * Whether a turn's tool-call group is open.
 *
 * Only the reader opens one. The group used to default to open whenever the *page* was running,
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
     * [runLive] is taken and deliberately not consulted. It is the input this decision was
     * previously made from, so the parameter is where the "no" is written down and the
     * regression is caught, rather than a comment that the next change can step over.
     */
    fun expanded(readerOpened: Boolean, runLive: Boolean): Boolean = readerOpened
}
