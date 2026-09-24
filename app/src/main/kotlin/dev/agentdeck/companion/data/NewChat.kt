package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileAcpAgent
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption

/**
 * Where a phone-started chat will run.
 *
 * Project and vendor are always set — there is no "unset". [model] is the one field that has a
 * meaningful null: it is *omitted* from the request, which is how the machine is told to use
 * whatever it is configured for, and it is what every phone-started chat did before there was
 * a picker at all. [accountId] null means "the machine's active account" — see [NewChat.accountFor].
 *
 * [acpAgentId] set starts the chat on that ACP agent instead of [vendor]'s CLI; ACP has no model,
 * effort, mode, account or toggles, so those stay unset and off the wire — see [NewChat.acpAgentFor].
 */
data class NewChatTarget(
    val projectPath: String,
    val vendor: AgentVendor = AgentVendor.CLAUDE,
    /** The model slug to name on the wire, or null for the machine's own default. */
    val model: String? = null,
    /** The account the user picked, or null for the one the machine would start on. */
    val accountId: String? = null,
    /** An effort rung, or null for the machine's own default — sent only where advertised. */
    val effort: String? = null,
    /** A permission mode (a Codex sandbox), or null for the machine's own default. */
    val permissionMode: String? = null,
    /** The Fast (Claude, Codex) / Thinking (Claude) checkboxes: null is the machine's own default. */
    val fastMode: Boolean? = null,
    val thinking: Boolean? = null,
    val acpAgentId: String? = null,
) {
    /** The vendor-bound picks go with the vendor: another agent's rungs and modes are not this one's. */
    fun withVendor(next: AgentVendor): NewChatTarget = if (next == vendor && acpAgentId == null) this
        else copy(vendor = next, model = null, accountId = null, effort = null, permissionMode = null, fastMode = null, thinking = null, acpAgentId = null)

    fun withAcpAgent(id: String): NewChatTarget = if (id == acpAgentId) this
        else copy(vendor = AgentVendor.CLAUDE, model = null, accountId = null, effort = null, permissionMode = null, fastMode = null, thinking = null, acpAgentId = id)

    val selection: MobileRunSelection get() = MobileRunSelection(model, effort, permissionMode, fastMode, thinking)
}

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
        // A model slug and an account id belong to one vendor, so they survive only where the
        // vendor did. Carrying `claude-opus-5` onto a Codex chat would name a model that vendor
        // has never heard of, and the machine would refuse a prompt the user did not mis-aim.
        val sameVendor = previous?.takeIf { it.vendor == vendor }
        return sameVendor?.copy(projectPath = project) ?: NewChatTarget(project, vendor)
    }

    /**
     * Agents to offer: the snapshot's own set, plus every agent a machine advertising
     * [MobileProtocol.Capability.EFFORT] names a ladder for — the desktop offers both whatever
     * the fleet holds, so a machine with no Codex conversation yet can still start one.
     */
    fun vendorOptions(rows: List<MobileFleetRow>, hello: MobileHello? = null): List<AgentVendor> {
        val advertised = if (hello != null && MobileProtocol.Capability.EFFORT in hello.capabilities) {
            hello.effort.keys
        } else {
            emptySet()
        }
        return (FleetGrouping.vendors(rows) + advertised).distinct().sortedBy { it.ordinal }
            .ifEmpty { listOf(AgentVendor.CLAUDE) }
    }

    /**
     * What a request may name: each pick only under the capability that named its ladder, because
     * an older plugin forwards these strings onto the CLI's command line unread.
     */
    fun forWire(hello: MobileHello?, picks: MobileRunSelection): MobileRunSelection {
        val advertised = hello?.capabilities.orEmpty()
        return MobileRunSelection(
            model = picks.model.takeIf { MobileProtocol.Capability.MODELS in advertised },
            effort = picks.effort.takeIf { MobileProtocol.Capability.EFFORT in advertised },
            permissionMode = picks.permissionMode.takeIf { MobileProtocol.Capability.PERMISSION_MODES in advertised },
            fastMode = picks.fastMode.takeIf { MobileProtocol.Capability.RUN_TOGGLES in advertised },
            thinking = picks.thinking.takeIf { MobileProtocol.Capability.RUN_TOGGLES in advertised },
        )
    }

    /**
     * The accounts to offer for [vendor] — empty, so no picker, unless the machine advertised
     * [MobileProtocol.Capability.ACCOUNTS] *and* has more than one: an older plugin would ignore
     * the pick and start on its default, and a single account is nothing to choose.
     */
    fun accountOptions(hello: MobileHello?, vendor: AgentVendor): List<MobileScheduleAccountOption> {
        if (hello == null || MobileProtocol.Capability.ACCOUNTS !in hello.capabilities) return emptyList()
        return hello.accounts[vendor].orEmpty().takeIf { it.size >= 2 }.orEmpty()
    }

    /**
     * The account id a request names: the user's pick while the machine still lists it, else the
     * machine's active account. Null only when the machine did not advertise accounts, which
     * leaves the field off the wire and the plugin on its pre-picker default.
     */
    fun accountFor(hello: MobileHello?, vendor: AgentVendor, picked: String?): String? {
        if (hello == null || MobileProtocol.Capability.ACCOUNTS !in hello.capabilities) return null
        val listed = hello.accounts[vendor].orEmpty()
        return picked?.takeIf { id -> listed.any { it.id == id } }
            ?: hello.activeAccounts[vendor]
            ?: listed.firstOrNull()?.id
    }

    fun accountFor(hello: MobileHello?, target: NewChatTarget): String? =
        if (acpAgentFor(hello, target) != null) null else accountFor(hello, target.vendor, target.accountId)

    /** The ACP agents to offer: none unless the machine advertised [MobileProtocol.Capability.ACP_AGENTS]. */
    fun acpAgents(hello: MobileHello?): List<MobileAcpAgent> =
        if (hello != null && MobileProtocol.Capability.ACP_AGENTS in hello.capabilities) hello.acpAgents else emptyList()

    /**
     * The ACP agent a request names: the target's while the machine still lists it, else null — the
     * Agent selector then shows the vendor, so what the reader sees is what is sent. An older plugin
     * would ignore the id and run the prompt on Claude, which is why nothing unlisted goes out.
     */
    fun acpAgentFor(hello: MobileHello?, target: NewChatTarget): String? =
        target.acpAgentId?.takeIf { id -> acpAgents(hello).any { it.id == id } }

    /** [forWire] for a target: an ACP agent takes no run picks at all. */
    fun picksFor(hello: MobileHello?, target: NewChatTarget): MobileRunSelection =
        if (acpAgentFor(hello, target) != null) MobileRunSelection(null, null, null) else forWire(hello, target.selection)

    /**
     * What an account row reads: its name, then why it cannot run right now. The reset time is
     * the one the schedule editor's "At reset" choice already offers.
     */
    fun accountLabel(option: MobileScheduleAccountOption, nowMs: Long, clock: (Long, Long) -> String): String {
        val reset = option.resetAtMs ?: return option.label
        val limit = if (option.weeklyLimit) "weekly limit reached" else "limit reached"
        return "${option.label} · $limit, resets ${clock(reset, nowMs)}"
    }
}
