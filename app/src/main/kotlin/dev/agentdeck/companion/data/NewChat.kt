package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * Where a phone-started chat will run.
 *
 * Project and vendor are always set — there is no "unset". [model] is the one field that has a
 * meaningful null: it is *omitted* from the request, which is how the machine is told to use
 * whatever it is configured for, and it is what every phone-started chat did before there was
 * a picker at all.
 */
data class NewChatTarget(
    val projectPath: String,
    val vendor: AgentVendor = AgentVendor.CLAUDE,
    /** The model slug to name on the wire, or null for the machine's own default. */
    val model: String? = null,
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
        // A model slug belongs to one vendor's ladder, so it survives only where the vendor
        // did. Carrying `claude-opus-5` onto a Codex chat would name a model that vendor has
        // never heard of, and the machine would refuse a prompt the user did not mis-aim.
        return NewChatTarget(project, vendor, previous?.model?.takeIf { previous.vendor == vendor })
    }

    /** Agents to offer. The snapshot's own set, and never an empty selector. */
    fun vendorOptions(rows: List<MobileFleetRow>): List<AgentVendor> =
        FleetGrouping.vendors(rows).ifEmpty { listOf(AgentVendor.CLAUDE) }
}
