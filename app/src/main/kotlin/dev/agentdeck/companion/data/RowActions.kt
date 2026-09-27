package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * What a fleet row lets you do without opening it (MU-14).
 *
 * Out here rather than inside the sheet because the *same* set has to reach three surfaces —
 * the long-press sheet, the swipe, and TalkBack's `customActions` — and a set that is rebuilt
 * per surface drifts one surface at a time. That is not hypothetical: the accessible actions
 * and the sheet's buttons were two `if (group == RUNNING)` branches written separately, so
 * either could have gained an action the other never offered, and nothing would have failed.
 */
enum class RowAction(val label: String) {
    OPEN("Open the conversation"),
    STOP("Stop this run"),
    SNOOZE("Snooze until this agent moves"),
    COPY_TITLE("Copy the title"),
    /** The desk's "Copy session ID": the conversation's own id, what `claude --resume` and the transcript file are named by. */
    COPY_ID("Copy the session ID"),
    MARK_REVIEWED("Mark every changed file reviewed"),
    PIN("Pin to the top"),
    UNPIN("Unpin"),
    MOVE_PIN_UP("Move pin up"),
    MOVE_PIN_DOWN("Move pin down"),
    DONE("Mark done"),
    REOPEN("Reopen"),
    RENAME("Rename"),
    /** The desk's "Regenerate title": a model turn on the machine writes a new name. */
    RETITLE("Regenerate title"),
    FOLDER("Move to folder…"),
    SHARE("Share the conversation"),
    /** A Claude chat, at one of the reader's messages: the desk's "New chat from here…". */
    FORK("New chat from here…"),
    /** The whole chat, the desk's Branch chat: Codex's `thread/fork`, or a Claude copy on a new id. */
    BRANCH("Branch chat"),
    /** A Claude chat: the desk's "Continue on <account>", the transcript copied onto another account and opened there. */
    HANDOFF("Continue on another account…"),
    /** A Claude chat: the desk's `/rewind`, back to before one of the reader's messages. */
    REWIND("Rewind…"),
    /** The desk's Repository row: commit what is staged in the checkout the chat ran in. */
    COMMIT_STAGED("Commit staged changes…"),
    /** The desk's context gauge grid: how the chat's window is divided and which instruction files loaded. */
    CONTEXT("Context window…"),
    /** The desk's working-directories button: the chat's `--add-dir` list, changed from a typed path. */
    DIRS("Working directories…"),
    /** A Claude chat: the desk composer's servers menu, which MCP servers the chat may use. */
    MCP("MCP servers…"),

    /** A Codex chat: the desk composer's communication-style, web-search and config-profile buttons. */
    CODEX("Codex settings…"),
    /** The desk's "Chat Spend Limits": this chat's own soft and hard ceilings and handoff. */
    SPEND("Spend limits…"),
    DELETE("Delete the conversation…"),
}

object RowActions {

    /**
     * The actions [group] earns, in the order they are offered.
     *
     * [RowAction.STOP] is the only conditional one, and the condition is the row's own state:
     * a run that has already finished has nothing to stop, and an action that silently no-ops
     * is how a list teaches its user to stop trusting it.
     *
     * [RowAction.MARK_REVIEWED] is conditional on two things rather than one: the row has to be
     * a "Done, unreviewed" one — a tick is meaningless on a chat that changed nothing anyone is
     * waiting to judge — and the machine has to advertise `review`, because an app that offered
     * it against an older plugin would be claiming a route that answers 404. "Open on desktop"
     * is still absent; the bridge serves no focus route.
     *
     * Pin, Done and Rename write the desk's own stores, so they need a machine advertising
     * `session-actions` ([canOrganize]); each offers the one direction [row] can go. Share
     * needs `session-export` ([canShare]): the machine renders the file the desk's `/export` writes.
     * Delete needs `session-delete` ([canDelete]) and is withheld from a running row, which the
     * machine would only refuse: the desk's own delete waits for the run to stop.
     * Move to folder needs `session-folders` ([canFolder]): it files into the desk's Sessions folders.
     * Fork needs `session-fork` ([canFork]) and is withheld from a running row for Delete's reason:
     * the desk refuses to copy a conversation mid-write. Claude forks at a message, Codex whole.
     * A Claude chat's whole-chat Branch also needs `session-branch` ([canBranch]): an older machine
     * refuses it.
     * Move pin up/down needs `pin-order` ([canPinOrder]) and another pin painted beside the row
     * ([paintedPins]); both stay offered at the ends, where the tap says why nothing moved.
     * Regenerate title needs `session-retitle` ([canRetitle]); a running Codex row is left out,
     * because Codex refuses a new name mid-turn.
     * Continue on another account needs `session-handoff` and another Claude account to copy to
     * ([canHandoff] holds both); it is withheld from a running row and a Codex row, for Fork's reason.
     * Rewind needs `session-rewind` ([canRewind]), a Claude row (a Codex rollout records no restore
     * point) and one that is not running, which the desk refuses.
     * Commit staged needs `commit-staged` ([canCommitStaged]) and nothing else: the desk offers it
     * mid-run too, because it commits what the reader staged, not what the agent is writing.
     * Context window needs `context-breakdown` ([canContext]) and nothing else: it only reads, and a
     * chat with no reply yet answers with the desk's "measured from its first reply".
     * Working directories needs `session-dirs` ([canDirs]), which the machine advertises only while a
     * phone holds the tool-permission grant: a directory widens what the agent may read.
     * MCP servers needs `session-mcp` ([canMcp]), under the same grant (a ticked server widens what the agent may reach), and a Claude row:
     * `codex exec` has no `--mcp-config`, so the desk offers the menu on Claude chats only.
     * Codex settings needs `session-codex` ([canCodex]), under the same grant (live web and a profile widen or re-layer the run), and a Codex row:
     * the three controls are Codex's launch keys, which a Claude chat has no argv for.
     * Spend limits needs `session-spend` ([canSpend]), advertised under the same grant: a raised ceiling lets a run spend more.
     */
    fun of(
        group: FleetGroup,
        canReview: Boolean = false,
        row: MobileFleetRow? = null,
        canOrganize: Boolean = false,
        canShare: Boolean = false,
        canDelete: Boolean = false,
        canFolder: Boolean = false,
        canFork: Boolean = false,
        canPinOrder: Boolean = false,
        paintedPins: Int = 0,
        canRetitle: Boolean = false,
        canBranch: Boolean = false,
        canRewind: Boolean = false,
        canCommitStaged: Boolean = false,
        canContext: Boolean = false,
        canDirs: Boolean = false,
        canMcp: Boolean = false,
        canCodex: Boolean = false,
        canSpend: Boolean = false,
        canHandoff: Boolean = false,
    ): List<RowAction> = buildList {
        add(RowAction.OPEN)
        if (group == FleetGroup.RUNNING) add(RowAction.STOP)
        if (canReview && group == FleetGroup.DONE_UNREVIEWED) add(RowAction.MARK_REVIEWED)
        if (canOrganize && row != null) add(if (row.pinned) RowAction.UNPIN else RowAction.PIN)
        if (canPinOrder && row != null && row.pinned && paintedPins > 1) {
            add(RowAction.MOVE_PIN_UP)
            add(RowAction.MOVE_PIN_DOWN)
        }
        add(RowAction.SNOOZE)
        if (canOrganize && row != null) {
            add(if (row.done) RowAction.REOPEN else RowAction.DONE)
            add(RowAction.RENAME)
        }
        if (canRetitle && row != null &&
            !(group == FleetGroup.RUNNING && row.vendor == com.github.claudeagents.core.AgentVendor.CODEX)
        ) {
            add(RowAction.RETITLE)
        }
        if (canFolder && row != null) add(RowAction.FOLDER)
        if (canShare && row != null) add(RowAction.SHARE)
        if (canFork && row != null && group != FleetGroup.RUNNING) {
            val codex = row.vendor == com.github.claudeagents.core.AgentVendor.CODEX
            if (!codex) add(RowAction.FORK)
            if (codex || canBranch) add(RowAction.BRANCH)
        }
        if (canHandoff && row != null && group != FleetGroup.RUNNING && row.vendor == com.github.claudeagents.core.AgentVendor.CLAUDE) {
            add(RowAction.HANDOFF)
        }
        if (canRewind && row != null && group != FleetGroup.RUNNING && row.vendor == com.github.claudeagents.core.AgentVendor.CLAUDE) {
            add(RowAction.REWIND)
        }
        if (canCommitStaged && row != null) add(RowAction.COMMIT_STAGED)
        if (canContext && row != null) add(RowAction.CONTEXT)
        if (canDirs && row != null) add(RowAction.DIRS)
        if (canMcp && row != null && row.vendor == com.github.claudeagents.core.AgentVendor.CLAUDE) add(RowAction.MCP)
        if (canCodex && row != null && row.vendor == com.github.claudeagents.core.AgentVendor.CODEX) add(RowAction.CODEX)
        if (canSpend && row != null) add(RowAction.SPEND)
        add(RowAction.COPY_TITLE)
        if (row != null && sessionIdOf(row.key) != null) add(RowAction.COPY_ID)
        // Last, apart from everything a slip of the thumb could undo.
        if (canDelete && row != null && group != FleetGroup.RUNNING) add(RowAction.DELETE)
    }
}

/**
 * The session id inside a row's `SessionAttentionKey.persistenceKey()` — `v2:` then vendor, account, session and
 * project, each URL-safe base64 of its UTF-8. Null for a key of another shape, so a machine that changes it costs the
 * phone one menu row rather than a wrong id on the clipboard.
 */
fun sessionIdOf(key: String): String? {
    val parts = key.split(':')
    if (parts.size != 5 || parts[0] != "v2") return null
    val id = runCatching { String(java.util.Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8) }.getOrNull()
    return id?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
}
