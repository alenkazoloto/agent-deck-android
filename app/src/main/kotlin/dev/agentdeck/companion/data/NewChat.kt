package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow

/** Where a phone-started chat will run. Both halves are always set — there is no "unset". */
data class NewChatTarget(
    val projectPath: String,
    val vendor: AgentVendor = AgentVendor.CLAUDE,
)

/** The draft key a phone-started chat's prompt is kept under; never a conversation key. */
const val NEW_CHAT_DRAFT_KEY = "new-chat"

object NewChat {

    /**
     * What the composer opens on.
     *
     * A previous pick wins, but only while it is still valid — an IDE that closed that
     * project would otherwise leave the composer aimed at a destination the machine can only
     * refuse. Failing that it opens on the project the user was most recently working in,
     * because on a phone the next thing you start is nearly always beside the last thing you
     * touched, and only then on the first open project.
     *
     * Returns null when the machine has nothing open, which is a state the screen explains
     * rather than a default it can invent.
     */
    fun defaultTarget(
        openProjects: List<String>,
        rows: List<MobileFleetRow>,
        previous: NewChatTarget? = null,
    ): NewChatTarget? {
        if (openProjects.isEmpty()) return null
        val newest = rows.filter { it.projectPath in openProjects }.maxByOrNull { it.lastActivityMs }
        val project = previous?.projectPath?.takeIf { it in openProjects }
            ?: newest?.projectPath
            ?: openProjects.first()
        val vendor = previous?.vendor ?: newest?.vendor ?: AgentVendor.CLAUDE
        return NewChatTarget(project, vendor)
    }

    /** Agents to offer. The snapshot's own set, and never an empty selector. */
    fun vendorOptions(rows: List<MobileFleetRow>): List<AgentVendor> =
        FleetGrouping.vendors(rows).ifEmpty { listOf(AgentVendor.CLAUDE) }
}
