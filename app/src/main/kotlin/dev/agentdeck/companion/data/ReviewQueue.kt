package dev.agentdeck.companion.data

import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * The Review destination's rows: every finished task whose changes nobody has marked reviewed.
 *
 * A projection of the fleet snapshot, not a second fetch — the plugin already decides which
 * sessions are `DONE_UNREVIEWED`, and a phone-side opinion of "has changes" would disagree
 * with the desktop's own badge the first time the two drew the line differently. Snooze is
 * deliberately ignored: it hides a row from the Chats triage, it does not mean "reviewed".
 */
object ReviewQueue {

    data class Project(val name: String, val rows: List<MobileFleetRow>)

    /** Grouped by project, the project with the newest finished work first, newest row first. */
    fun projects(rows: List<MobileFleetRow>): List<Project> = rows
        .filter { it.attention == SessionAttentionState.DONE_UNREVIEWED }
        .sortedByDescending { it.lastActivityMs }
        .groupBy { it.projectPath }
        .map { (_, group) -> Project(group.first().projectName.ifBlank { "No project" }, group) }

    fun count(rows: List<MobileFleetRow>): Int =
        rows.count { it.attention == SessionAttentionState.DONE_UNREVIEWED }
}
