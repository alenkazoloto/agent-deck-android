package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.mobile.MobileFleetRow

/**
 * The fleet's groups, in the order they are shown.
 *
 * Blocked, live, broken, just-touched, backlog, archive. Waiting comes first because it is
 * the only state the badge counts and the only one that blocks the user; then the states
 * that need a decision. Declaration order **is** display order, so a new group is a ranking
 * decision that has to be made here rather than at a call site.
 *
 * [RUNNING] outranks [FAILED] because a run in flight is the only one of the two still
 * changing: watching it is time-critical and its rows go stale by the second, while a failure
 * has already finished failing and reads the same an hour later. Both keep their own heading,
 * so nothing is buried either way — this only decides which the user's thumb reaches first.
 *
 * [RECENT] is the one group the plugin holds no attention opinion about, and it is a group
 * rather than a re-rank inside [OTHER] for exactly that reason: attention is decided once on
 * the desktop and the phone may not invent more of it. What it may do is say when something
 * happened. It sits above [DONE_UNREVIEWED] because that group is a *backlog* — the machine
 * this was measured on holds 167 rows in it, and a conversation the user was in a minute ago
 * is not something they should have to scroll a backlog to find.
 */
enum class FleetGroup(val title: String) {
    WAITING("Waiting on you"),
    RUNNING("Running"),
    FAILED("Failed"),
    RECENT("Recently active"),
    DONE_UNREVIEWED("Done, unreviewed"),
    OTHER("Everything else"),
}

/**
 * How a flat list is ranked. [ATTENTION] is the derived order — the groups above, which is
 * the desktop's blocked-first contract — and every explicit order replaces that grouping
 * with one flat ranked list, exactly as `ReviewSortOrder.DEFAULT` does on the desktop.
 *
 * [RECENT] is the default ([DeckState.sort]) because opening the app is usually "carry on
 * with what I was just doing", and a triage order answers a different question: it puts a
 * conversation the user has never seen above the one they left mid-sentence, and buries that
 * one under whichever backlog group happens to be large that day. Attention is one tap away
 * and keeps the badge, which is the signal that says triage is worth opening.
 */
enum class FleetSort(val label: String) {
    ATTENTION("Attention"),
    RECENT("Last message"),
    COST("Cost"),
    TITLE("Title"),
}

/** Inline, always-visible scoping. Never a "Filters (N)" summary the user has to open. */
data class FleetFilter(
    val projectPath: String? = null,
    val accountId: String? = null,
    val vendor: AgentVendor? = null,
    val model: String? = null,
    /** Free text over title, project and branch — the desktop Sessions search, on a phone. */
    val query: String = "",
) {
    fun matches(row: MobileFleetRow): Boolean =
        (projectPath == null || row.projectPath == projectPath) &&
            (accountId == null || row.accountId == accountId) &&
            (vendor == null || row.vendor == vendor) &&
            (model == null || row.model == model) &&
            matchesQuery(row)

    private fun matchesQuery(row: MobileFleetRow): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return listOfNotNull(row.title, row.projectName, row.gitBranch)
            .any { it.contains(needle, ignoreCase = true) }
    }

    val isEmpty: Boolean
        get() = projectPath == null && accountId == null && vendor == null &&
            model == null && query.isBlank()
}

/** [group] is null for an explicitly sorted list, which is one section and not a grouping. */
data class FleetSection(val group: FleetGroup?, val rows: List<MobileFleetRow>) {
    val title: String get() = group?.title ?: "All conversations"
}

/**
 * One section as the list paints it: the rows above the fold, and how many wait behind the
 * expander. [hidden] is 0 whenever the section shows everything it has.
 */
data class SectionView(val rows: List<MobileFleetRow>, val hidden: Int) {
    val total: Int get() = rows.size + hidden
}

object FleetGrouping {

    /**
     * [generatedAtMs] is the snapshot's own stamp, never `System.currentTimeMillis()`: the
     * recency rule lives on [MobileFleetRow.isRecentAt] so the phone's grouping and the
     * plugin's transport order are one decision, and so the comparison stays inside the
     * machine's clock.
     */
    fun groupOf(row: MobileFleetRow, generatedAtMs: Long): FleetGroup = when (row.attention) {
        SessionAttentionState.WAITING_ON_YOU -> FleetGroup.WAITING
        SessionAttentionState.FAILED -> FleetGroup.FAILED
        SessionAttentionState.RUNNING -> FleetGroup.RUNNING
        SessionAttentionState.DONE_UNREVIEWED -> FleetGroup.DONE_UNREVIEWED
        null -> if (row.isRecentAt(generatedAtMs)) FleetGroup.RECENT else FleetGroup.OTHER
    }

    /**
     * Filtered, grouped, and newest-first inside each group. Empty groups are dropped: a
     * heading with nothing under it is a claim that a state exists when it does not.
     *
     * Any [sort] other than [FleetSort.ATTENTION] drops the grouping for one flat ranked
     * list — a heading per attention state under a cost ranking would claim an ordering the
     * rows no longer follow.
     */
    fun sections(
        rows: List<MobileFleetRow>,
        filter: FleetFilter = FleetFilter(),
        generatedAtMs: Long,
        sort: FleetSort = FleetSort.ATTENTION,
    ): List<FleetSection> {
        val kept = rows.filter(filter::matches)
        if (sort != FleetSort.ATTENTION) {
            return listOf(FleetSection(null, rank(kept, sort))).filter { it.rows.isNotEmpty() }
        }
        return FleetGroup.entries.mapNotNull { group ->
            val members = kept.filter { groupOf(it, generatedAtMs) == group }
                .sortedByDescending { it.lastActivityMs }
            members.takeIf { it.isNotEmpty() }?.let { FleetSection(group, it) }
        }
    }

    /** Rows one group shows before the rest go behind "Show all N". */
    const val SECTION_CAP = 10

    /**
     * Caps a section so one group cannot bury the others.
     *
     * The machine this was measured on holds 167 rows in `DONE_UNREVIEWED`, which puts
     * "Everything else" thousands of pixels below the fold and does the same to `RECENT`
     * whenever the backlog grows. The cap never hides a group's *first* rows — the top ten
     * of every group stay visible, which is the whole point — and it applies only where
     * burying is possible: a grouped list with more than one section. A flat ranked list
     * (any sort other than Attention) is the user's own ordering of everything, so it is
     * never capped.
     */
    fun view(section: FleetSection, sectionCount: Int, expanded: Boolean): SectionView {
        val all = section.rows
        val cap = section.group != null && sectionCount > 1 && !expanded && all.size > SECTION_CAP
        return if (cap) SectionView(all.take(SECTION_CAP), all.size - SECTION_CAP)
        else SectionView(all, 0)
    }

    /** [MobileFleetRow.key] breaks every tie, so a ranking is stable across refreshes. */
    private fun rank(rows: List<MobileFleetRow>, sort: FleetSort): List<MobileFleetRow> = when (sort) {
        FleetSort.ATTENTION -> rows
        FleetSort.RECENT -> rows.sortedWith(compareByDescending<MobileFleetRow> { it.lastActivityMs }.thenBy { it.key })
        FleetSort.COST -> rows.sortedWith(compareByDescending<MobileFleetRow> { it.costUsd }.thenBy { it.key })
        FleetSort.TITLE ->
            rows.sortedWith(compareBy<MobileFleetRow, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.key })
    }

    /** Distinct project paths present in the snapshot, for the inline project filter. */
    fun projects(rows: List<MobileFleetRow>): List<MobileFleetRow> =
        rows.distinctBy { it.projectPath }.sortedBy { it.projectName }

    fun accounts(rows: List<MobileFleetRow>): List<String> =
        rows.map { it.accountId }.filter { it.isNotBlank() }.distinct().sorted()

    fun vendors(rows: List<MobileFleetRow>): List<AgentVendor> =
        rows.map { it.vendor }.distinct().sortedBy { it.ordinal }

    fun models(rows: List<MobileFleetRow>): List<String> =
        rows.mapNotNull { it.model?.takeIf { m -> m.isNotBlank() } }.distinct().sorted()

    /**
     * What to call an account. The machine's own name for it wins; a phone falls back to the
     * id only when the snapshot carried none, and then shortens it — a secondary Claude
     * account's id is a raw UUID, and 36 characters of hex in a selector names nothing.
     */
    fun accountLabel(rows: List<MobileFleetRow>, accountId: String): String {
        val named = rows.firstOrNull { it.accountId == accountId }?.accountLabel
        return named?.takeIf { it.isNotBlank() } ?: shortenId(accountId)
    }

    private fun shortenId(accountId: String): String =
        if (accountId.length > SHORT_ID_CHARS) accountId.take(SHORT_ID_CHARS) + "…" else accountId

    private const val SHORT_ID_CHARS = 8
}
