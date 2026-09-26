package dev.agentdeck.companion.fixture

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.SessionAttentionState
import com.github.claudeagents.core.WaitingReason
import com.github.claudeagents.core.mobile.MobileFleetRow
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileModelOption
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileReviewFile
import com.github.claudeagents.core.mobile.MobileReviewFileDiff
import com.github.claudeagents.core.mobile.MobileReviewHunk
import com.github.claudeagents.core.mobile.MobileReviewList
import com.github.claudeagents.core.mobile.MobileReviewScope
import com.github.claudeagents.core.mobile.MobileReviewSortOption
import com.github.claudeagents.core.mobile.MobileRunSelection
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduledRow
import com.github.claudeagents.core.mobile.MobileTodo
import com.github.claudeagents.core.mobile.MobilePendingPermission
import com.github.claudeagents.core.mobile.MobilePendingPlan
import com.github.claudeagents.core.mobile.MobilePlanMode
import com.github.claudeagents.core.mobile.MobileRecap
import com.github.claudeagents.core.mobile.MobileQuestion
import com.github.claudeagents.core.mobile.MobileQuestionOption
import com.github.claudeagents.core.mobile.MobileBackgroundTask
import com.github.claudeagents.core.mobile.MobileSubagentThread
import com.github.claudeagents.core.mobile.MobileToolCall
import com.github.claudeagents.core.mobile.MobileToolResult
import com.github.claudeagents.core.mobile.MobileUsageAccount
import com.github.claudeagents.core.mobile.MobileUsageBucket
import com.github.claudeagents.core.mobile.MobileUsageCap
import com.github.claudeagents.core.mobile.MobileUsageCard
import com.github.claudeagents.core.mobile.MobileUsageFilter
import com.github.claudeagents.core.mobile.MobileUsageFilterAccount
import com.github.claudeagents.core.mobile.MobileUsageDay
import com.github.claudeagents.core.mobile.MobileUsageProject
import com.github.claudeagents.core.mobile.MobileUsageReport
import com.github.claudeagents.core.mobile.MobileUsageWindow
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.mobile.MobileTurn
import dev.agentdeck.companion.ui.SubagentThreadFrame
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.LastGoodHost
import dev.agentdeck.companion.Link
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.FleetFilter
import dev.agentdeck.companion.data.RecapOffer
import dev.agentdeck.companion.data.MessageSearch
import com.github.claudeagents.core.mobile.MobileSessionSearchHit
import dev.agentdeck.companion.data.FleetScope
import dev.agentdeck.companion.data.HostReach
import dev.agentdeck.companion.data.FleetSort
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SharedInput
import dev.agentdeck.companion.data.UpdateRelease
import dev.agentdeck.companion.data.UpdateState

/**
 * Every state the UI branches on, as a `DeckState` a screenshot can be taken of.
 *
 * **Debug source set only** — nothing here is compiled into a release build, and the seam it
 * reaches (`DeckFixtureHook`) is null unless [FixtureInstaller] ran.
 *
 * Two rules these fixtures follow, both learned the hard way (`Memory.md`):
 *
 * 1. **Selected on the property under test, never "the first row".** `waiting-first` puts the
 *    waiting rows *last* in the input list, so blocked-first ordering and no ordering cannot
 *    photograph identically. The backlog is 167 rows because ten of each group would make the
 *    capped and uncapped lists the same picture.
 * 2. **Every state ships with the negative control that isolates it.** `fleet-capped` /
 *    `fleet-uncapped`, `convo-truncated` / `convo-whole`, `convo-codex` / `convo-claude`
 *    differ in exactly one input, so the pair is evidence and either shot alone is a picture.
 *
 * Clocks are fixed: [NOW] is the snapshot's own stamp, so a day-stamped time renders the same
 * on every run instead of flipping at local midnight.
 */
object DeckFixtures {

    /** 2026-07-31 21:14 UTC, fixed so the golden never depends on when it was taken. */
    const val NOW = 1_785_532_440_000L
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private val MACHINE = PairedMachine(
        machineName = "workshop",
        hosts = listOf("192.168.1.24", "100.71.4.9"),
        port = 63350,
        spkiFingerprint = "9f2c4a1e7b3d5068a2c9e14f7b6d3a85c0e2f419d7a6b3c85e0f21d4a97b6c3e",
        token = "fixture-token",
        deviceId = "fixture-device",
        preferredHost = "192.168.1.24",
    )

    /** The second pairing. Only `settings-two-machines` holds it — see that state for why. */
    private val LAPTOP = MACHINE.copy(
        machineName = "laptop",
        hosts = listOf("192.168.1.31"),
        deviceId = "fixture-device-2",
        preferredHost = "192.168.1.31",
    )

    /** What `/v1/hello` answers, including the capability the Schedule button is gated on. */
    private val HELLO = MobileHello(
        protocolVersion = MobileProtocol.VERSION,
        machineName = "workshop",
        ideName = "IntelliJ IDEA",
        pluginVersion = "1.6.0",
        capabilities = listOf(
            MobileProtocol.Capability.FLEET,
            MobileProtocol.Capability.TRANSCRIPT,
            MobileProtocol.Capability.SEND,
            MobileProtocol.Capability.STOP,
            MobileProtocol.Capability.SCHEDULED,
            MobileProtocol.Capability.SCHEDULE_CREATE,
            MobileProtocol.Capability.MODELS,
            MobileProtocol.Capability.UNPAIR,
        ),
        // The ladder `/v1/hello` carries, minus the null-slug "Default model" row: sending no
        // model at all is a property of the request, so the client mints that row itself.
        models = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("sonnet", "Sonnet 5"),
                MobileModelOption("opus", "Opus 5"),
                MobileModelOption("haiku", "Haiku 4.5"),
            ),
            AgentVendor.CODEX to listOf(
                MobileModelOption("gpt-5.1-codex", "gpt-5.1-codex"),
                MobileModelOption("gpt-5.1-codex-mini", "gpt-5.1-codex-mini"),
            ),
        ),
    )

    /**
     * The whole state space, by name. `deck-screenshot.sh --state <name>` passes one of these.
     */
    /**
     * The commit sheet over `convo-commit`: the desk's seed, a modified and a deleted file ticked,
     * an untracked one offered unticked, and the count this commit leaves out. The `-failed` state
     * carries git's refusal under an edited message — the draft a failed commit keeps.
     */
    fun commitSheet(name: String): dev.agentdeck.companion.data.CommitSheet? {
        if (name != "convo-commit" && name != "convo-commit-failed") return null
        val preview = com.github.claudeagents.core.mobile.MobileReviewCommitPreview(
            key = "convo-1",
            branch = "fix/clock-drift",
            message = "Make the clock injectable\n\nClaude changed 3 files",
            files = listOf(
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/main/kotlin/Clock.kt", "modified", true),
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/test/kotlin/ClockTest.kt", "untracked", false),
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/main/kotlin/LegacyTimer.kt", "deleted", true),
            ),
            alreadyCommitted = 1,
            previewToken = "fixture",
        )
        val failed = name == "convo-commit-failed"
        return dev.agentdeck.companion.data.CommitSheet(
            key = "convo-1",
            preview = preview,
            message = if (failed) "Inject the clock into the scheduler" else preview.message,
            chosen = setOf("src/main/kotlin/Clock.kt", "src/main/kotlin/LegacyTimer.kt"),
            error = if (failed) "ktlint: src/main/kotlin/Clock.kt:12 Unexpected blank line" else null,
        )
    }

    /**
     * The revert sheet over `convo-revert`: the desk's notes, an edited file to restore, a created
     * one to delete, and one with no recorded start that cannot be ticked. The `-failed` state
     * carries the machine's refusal under the sheet, which stays open. Below the files, the chat's
     * requests; `-request` and `-after` are the sheet after choosing request 2's two reverts.
     */
    fun revertSheet(name: String): dev.agentdeck.companion.data.RevertSheet? {
        if (name == "convo-rewind-code") {
            // The rewind's code half: request 2's checkpoint, the file it and request 3 changed.
            val choice = com.github.claudeagents.core.mobile.MobileReviewRevertChoice("p2", 2, "Format stamps in UTC and add a test for midnight rollover", files = 1)
            return dev.agentdeck.companion.data.RevertSheet(
                key = "convo-1",
                scope = "before",
                request = choice,
                preview = com.github.claudeagents.core.mobile.MobileReviewRevertPreview(
                    key = "convo-1",
                    files = listOf(
                        com.github.claudeagents.core.mobile.MobileReviewRevertFile("src/main/kotlin/Stamps.kt", "restore"),
                        com.github.claudeagents.core.mobile.MobileReviewRevertFile("src/test/kotlin/StampsTest.kt", "delete"),
                    ),
                    notes = listOf(
                        "Files change; git is not touched — commits stay on the branch.",
                        "The IDE's Local History records this restore, so the contents it overwrites stay recoverable from there.",
                        "1 shell command ran from here on — files they changed are not part of this checkpoint.",
                    ),
                    previewToken = "fixture",
                    scope = "before",
                    request = choice,
                ),
                chosen = setOf("src/main/kotlin/Stamps.kt", "src/test/kotlin/StampsTest.kt"),
            )
        }
        if (name !in setOf("convo-revert", "convo-revert-failed", "convo-revert-request", "convo-revert-after")) return null
        val requests = listOf(
            com.github.claudeagents.core.mobile.MobileReviewRevertChoice("p1", 1, "Make the clock injectable so tests can pin time", files = 2, laterFiles = 1),
            com.github.claudeagents.core.mobile.MobileReviewRevertChoice("p2", 2, "Format stamps in UTC and add a test for midnight rollover", files = 1, laterFiles = 1),
            com.github.claudeagents.core.mobile.MobileReviewRevertChoice("p3", 3, "Regenerate the build stamps", files = 1),
        )
        if (name == "convo-revert-request" || name == "convo-revert-after") {
            val after = name == "convo-revert-after"
            val scope = if (after) "after" else "request"
            return dev.agentdeck.companion.data.RevertSheet(
                key = "convo-1",
                scope = scope,
                request = requests[1],
                requests = requests,
                preview = com.github.claudeagents.core.mobile.MobileReviewRevertPreview(
                    key = "convo-1",
                    files = if (after) {
                        listOf(
                            com.github.claudeagents.core.mobile.MobileReviewRevertFile("src/main/kotlin/Stamps.kt", "restore"),
                            com.github.claudeagents.core.mobile.MobileReviewRevertFile("build/generated/Stamps.kt", "skip"),
                        )
                    } else {
                        listOf(com.github.claudeagents.core.mobile.MobileReviewRevertFile("src/main/kotlin/Clock.kt", "restore"))
                    },
                    notes = listOf(
                        "Files change; git is not touched — commits stay on the branch.",
                        "The IDE's Local History records this restore, so the contents it overwrites stay recoverable from there.",
                    ),
                    previewToken = "fixture",
                    scope = scope,
                    request = requests[1],
                    requests = requests,
                ),
                chosen = setOf(if (after) "src/main/kotlin/Stamps.kt" else "src/main/kotlin/Clock.kt"),
            )
        }
        val preview = com.github.claudeagents.core.mobile.MobileReviewRevertPreview(
            key = "convo-1",
            files = listOf(
                com.github.claudeagents.core.mobile.MobileReviewRevertFile("src/main/kotlin/Clock.kt", "restore"),
                com.github.claudeagents.core.mobile.MobileReviewRevertFile("src/test/kotlin/ClockTest.kt", "delete"),
                com.github.claudeagents.core.mobile.MobileReviewRevertFile("build/generated/Stamps.kt", "skip"),
            ),
            notes = listOf(
                "Files change; git is not touched — commits stay on the branch.",
                "Your own edits to those files during the session are not protected — a restored file goes back to the session start.",
                "The IDE's Local History records this restore, so the contents it overwrites stay recoverable from there.",
            ),
            previewToken = "fixture",
            requests = requests,
        )
        val failed = name == "convo-revert-failed"
        return dev.agentdeck.companion.data.RevertSheet(
            key = "convo-1",
            requests = requests,
            preview = preview,
            chosen = setOf("src/main/kotlin/Clock.kt", "src/test/kotlin/ClockTest.kt"),
            error = if (failed) "Nothing was reverted because the session transcript is still being written. Wait for it to settle." else null,
        )
    }

    /** Codex's `/diff`: the uncommitted files (`convo-working-diff`) and one file's patch. */
    fun workingDiff(name: String): dev.agentdeck.companion.data.WorkingDiffSheet? {
        val patch = """
            diff --git a/src/build/Stamps.kt b/src/build/Stamps.kt
            index 3f1c2aa..8d0e4b1 100644
            --- a/src/build/Stamps.kt
            +++ b/src/build/Stamps.kt
            @@ -38,9 +38,10 @@ object Stamps {
                 fun stamp(at: Instant, zone: ZoneId): String {
            -        val day = at.truncatedTo(ChronoUnit.DAYS)
            -        return FORMAT.format(day.atZone(zone))
            +        val local = at.atZone(zone)
            +        val day = local.truncatedTo(ChronoUnit.DAYS)
            +        return FORMAT.format(day)
                 }
        """.trimIndent() + "\n"
        val files = listOf(
            com.github.claudeagents.core.mobile.MobileWorkingDiffFile("src/build/Stamps.kt", 3, 2, untracked = false, patch = patch),
            com.github.claudeagents.core.mobile.MobileWorkingDiffFile("test/StampsTest.kt", 14, 1, untracked = false, patch = ""),
            com.github.claudeagents.core.mobile.MobileWorkingDiffFile("notes/rollover.md", 9, 0, untracked = true, patch = ""),
        )
        val diff = com.github.claudeagents.core.mobile.MobileWorkingDiff(
            key = "convo-1",
            root = "/Users/you/projects/stamps",
            summary = "3 files changed, +26 −3",
            boundary = "Everything not yet committed — staged and unstaged changes, plus untracked files.",
            files = files,
        )
        val sheet = dev.agentdeck.companion.data.WorkingDiffSheet(key = "convo-1", diff = diff)
        return when (name) {
            "convo-working-diff" -> sheet
            "convo-working-diff-patch" -> sheet.copy(openPath = files[0].path, openFile = files[0])
            else -> null
        }
    }

    /**
     * The context grid over a Claude conversation: the desk's rows with an estimated memory line and
     * the instruction files (`convo-context`), and a chat with no reply yet (`convo-context-fresh`).
     */
    fun contextSheet(name: String): dev.agentdeck.companion.data.ContextSheet? = when (name) {
        "convo-context" -> dev.agentdeck.companion.data.ContextSheet(
            key = "convo-1",
            title = "Fix the flaky login redirect",
            breakdown = com.github.claudeagents.core.mobile.MobileContextBreakdown(
                key = "convo-1",
                measured = true,
                headline = "30% full · 60.0K of 200.0K tokens",
                rows = listOf(
                    com.github.claudeagents.core.mobile.MobileContextRow("System prompt, tools, and memory", "18.4K · 9%", 9, "Everything sent before the conversation's first message."),
                    com.github.claudeagents.core.mobile.MobileContextRow("Memory files", "≈3.2K · 2%", 2, "The instruction files, approximated from their size.", nested = true),
                    com.github.claudeagents.core.mobile.MobileContextRow("Messages", "41.6K · 21%", 21, "What the conversation added since."),
                    com.github.claudeagents.core.mobile.MobileContextRow("Autocompact buffer", "13.0K · 7%", 7, "Held back so auto-compaction can run before the window fills."),
                    com.github.claudeagents.core.mobile.MobileContextRow("Free space", "127.0K · 64%", 64, "What is left before the window is full."),
                ),
                files = listOf(
                    com.github.claudeagents.core.mobile.MobileContextFile("User · CLAUDE.md", "≈1.1K · 4.4 KB"),
                    com.github.claudeagents.core.mobile.MobileContextFile("Project · CLAUDE.md", "≈2.1K · 8.4 KB"),
                    com.github.claudeagents.core.mobile.MobileContextFile("Project · .claude/rules", "3 files"),
                ),
                estimateNote = "Approximate — about 4 characters per token.",
            ),
        )
        "convo-context-fresh" -> dev.agentdeck.companion.data.ContextSheet(
            key = "convo-1",
            title = "Fix the flaky login redirect",
            breakdown = com.github.claudeagents.core.mobile.MobileContextBreakdown("convo-1", measured = false),
        )
        else -> null
    }

    /**
     * The `/btw` sheet over a Claude conversation: one answered ask and one still running
     * (`convo-side-question`), and a refused question with the text kept (`convo-side-question-refused`).
     */
    fun sideQuestionSheet(name: String): dev.agentdeck.companion.data.SideQuestionSheet? = when (name) {
        "convo-side-question" -> dev.agentdeck.companion.data.SideQuestionSheet(
            key = "convo-1",
            title = "Fix the flaky pairing test",
            asks = listOf(
                com.github.claudeagents.core.mobile.MobileSideAsk(
                    id = "a1",
                    question = "Which test is the flaky one?",
                    running = false,
                    answer = "It is `PairingTest.expiresAtMidnight`: it compares against the wall clock, so it fails when a run crosses a day boundary.",
                    askedAtMs = NOW - 90_000,
                ),
                com.github.claudeagents.core.mobile.MobileSideAsk(
                    id = "a2",
                    question = "Would a fixed clock in the test be enough?",
                    running = true,
                    askedAtMs = NOW - 5_000,
                ),
            ),
        )
        "convo-side-question-refused" -> dev.agentdeck.companion.data.SideQuestionSheet(
            key = "convo-1",
            title = "Fix the flaky pairing test",
            draft = "Why did the second retry not help",
            refused = "Other side questions are still being answered on this machine. Ask again when they finish.",
        )
        else -> null
    }

    /**
     * Settings › Resources › Memory and instructions: the project's files (`settings-memory-files`), the
     * same list on a phone the owner has not allowed to save (`settings-memory-readonly`), a file open
     * with an unsaved edit (`settings-memory-editor`), an agent note that can be deleted (`settings-memory-note`) and the same edit after the machine's file moved on
     * (`settings-memory-conflict`); the desk's Load order over the list (`settings-memory-load-order`), an agent-memory index past its
     * cap (`settings-memory-cap`) and a conditional rule with its globs (`settings-memory-rule`).
     */
    fun memorySheet(name: String): dev.agentdeck.companion.data.MemorySheet? {
        val project = com.github.claudeagents.core.mobile.MobileMemoryProject("/Users/me/work/agents-deck", "agents-deck")
        fun entry(group: String, label: String, source: String, has: Boolean = true, description: String? = null, conditional: Boolean = false) =
            com.github.claudeagents.core.mobile.MobileMemoryEntry(
                com.github.claudeagents.core.mobile.MobileMemoryEntry.idOf(group, label), label, group, source, description, has, conditional,
            )
        fun entries(writable: Boolean) = com.github.claudeagents.core.mobile.MobileMemoryEntries(
            project.path,
            listOf(
                entry("Agent memory", "MEMORY.md", "Agent memory", description = "Index of what the agent remembers about this project"),
                entry("Agent memory", "user_role.md", "Agent memory", description = "The reader is a plugin maintainer"),
                entry("Instructions", "Project (CLAUDE.md)", "Instructions · Project"),
                entry("Instructions", "Project local (CLAUDE.local.md)", "Instructions · Project local", has = false),
                entry("Rules", "testing.md", "Rules · .claude/rules", conditional = true),
            ),
            writable,
        )
        val text = "# Agents Deck\n\nKotlin plugin for Claude Code and Codex chat.\n\n- Run tests, then `./gradlew buildPlugin`.\n- Comments explain non-obvious reasons only.\n"
        val theirs = "# Agents Deck\n\nKotlin plugin for Claude Code and Codex chat.\n\n- Run tests, then `./gradlew buildPlugin`.\n- Never restart the IDE unasked.\n"
        fun file(writable: Boolean, edited: String, conflict: com.github.claudeagents.core.mobile.MobileMemoryFile? = null) =
            dev.agentdeck.companion.data.OpenMemoryFile(
                id = "Instructions:Project (CLAUDE.md)", label = "Project (CLAUDE.md)", writable = writable, deletable = false, existed = true,
                savedText = text, revision = "3f9a1c", text = edited, conflict = conflict,
            )
        val note = "---\nname: user-role\ndescription: The reader is a plugin maintainer\n---\n\nThe reader maintains this IntelliJ plugin and reviews changes before merging.\n"
        fun agentNote() = dev.agentdeck.companion.data.OpenMemoryFile(
            id = "Agent memory:user_role.md", label = "user_role.md", writable = true, deletable = true, existed = true,
            savedText = note, revision = "9c2d47", text = note,
        )
        val index = "- [User role](user_role.md) — the reader is a plugin maintainer\n- [Testing](testing.md) — run the suite before pushing\n"
        val rule = "---\npaths:\n  - \"src/test/**\"\n  - \"**/*Test.kt\"\n---\n\nTests name what they hold, not how they are built.\n"
        val opened = dev.agentdeck.companion.data.MemorySheet(listOf(project), project, entries(true))
        return when (name) {
            "settings-memory-files" -> opened
            "settings-memory-readonly" -> opened.copy(entries = entries(false))
            "settings-memory-editor" -> opened.copy(open = file(true, text + "- Prefer small diffs.\n"))
            "settings-memory-note" -> opened.copy(open = agentNote())
            "settings-memory-conflict" -> opened.copy(
                open = file(
                    true, text + "- Prefer small diffs.\n",
                    com.github.claudeagents.core.mobile.MobileMemoryFile(project.path, "Instructions:Project (CLAUDE.md)", "Project (CLAUDE.md)", theirs, true, "b71e00", true),
                ),
            )
            "settings-memory-load-order" -> opened.copy(
                loadOrderOpen = true,
                loadOrder = com.github.claudeagents.core.mobile.MobileMemoryLoadOrder(
                    project.path,
                    listOf(
                        step("Managed · CLAUDE.md", "not present", "Set by your organization; loaded before anything of yours."),
                        step("User · CLAUDE.md", "812 B", "Your own instructions, in every project."),
                        step("Project · CLAUDE.md", "3.4 kB", "This repository's shared instructions."),
                        step("@style.md", "1.2 kB", "Imported by @path from CLAUDE.md.", depth = 1),
                        step("@git-workflow.md", "900 B", "Imported by @path from CLAUDE.md.", depth = 1),
                        step("@testing.md", "missing", "Imported by @path from git-workflow.md.", depth = 2),
                        step("Project local · CLAUDE.local.md", "not present", "Yours only, not committed."),
                        step("Project · .claude/rules/", "2 files", "Rule files with no paths: front-matter — always loaded."),
                        step("Project · .claude/rules/ (conditional)", "1 file", "Rule files with paths: front-matter — loaded only for files they match."),
                        step(
                            "Subdirectory · packages/api/CLAUDE.md", "excluded",
                            "In a subdirectory; loads when Claude reads a file there, not at launch.\nSkipped by claudeMdExcludes pattern: **/packages/**",
                        ),
                        step("Subdirectory · docs/CLAUDE.md", "on demand · 640 B", "In a subdirectory; loads when Claude reads a file there, not at launch."),
                    ),
                    "6 of 11 load for this project, in this order. 3 @imports, 2 on demand, 1 excluded.",
                ),
            )
            "settings-memory-cap" -> opened.copy(
                open = dev.agentdeck.companion.data.OpenMemoryFile(
                    id = "Agent memory:MEMORY.md (index)", label = "MEMORY.md (index)", writable = true, deletable = false, existed = true,
                    savedText = index, revision = "5d1e88", text = index + "- [Tone](tone.md) — how to answer\n",
                    cap = com.github.claudeagents.core.mobile.MobileMemoryCap(
                        "Over the 200-line load limit",
                        "201 lines of 200, 9,842 of 25,000 characters.\nClaude loads only the start of this file and ignores the rest — everything past line 200 is already invisible to it.\nCut it to under 140 lines: keep index entries to one line each and move detail into topic files.",
                        com.github.claudeagents.core.mobile.MobileMemoryCap.OVER,
                    ),
                ),
            )
            "settings-memory-rule" -> opened.copy(
                open = dev.agentdeck.companion.data.OpenMemoryFile(
                    id = "Rules:testing.md", label = "testing.md", writable = true, deletable = false, existed = true,
                    savedText = rule, revision = "77ab10", text = rule,
                    rulePaths = com.github.claudeagents.core.mobile.MobileMemoryRulePaths(
                        "Loads only for files matching:", "This rule loads only for files matching:", listOf("src/test/**", "**/*Test.kt"),
                    ),
                ),
            )
            else -> null
        }
    }

    private fun step(text: String, status: String, detail: String = "", depth: Int = 0) =
        com.github.claudeagents.core.mobile.MobileMemoryLoadStep(text, status, detail, depth)

    /**
     * A chat's working directories over a Claude conversation: two held (`convo-dirs`), none
     * (`convo-dirs-empty`), and the desk's refusal of a typed path with the text kept (`convo-dirs-refused`).
     */
    fun sessionDirsSheet(name: String): dev.agentdeck.companion.data.SessionDirsSheet? = when (name) {
        "convo-dirs" -> dev.agentdeck.companion.data.SessionDirsSheet(
            key = "convo-1",
            title = "Fix the flaky login redirect",
            dirs = listOf("/Users/me/work/shared-ui", "/Users/me/notes/auth-rfcs"),
        )
        "convo-dirs-empty" -> dev.agentdeck.companion.data.SessionDirsSheet("convo-1", "Fix the flaky login redirect", dirs = emptyList())
        "convo-dirs-refused" -> dev.agentdeck.companion.data.SessionDirsSheet(
            key = "convo-1",
            title = "Fix the flaky login redirect",
            dirs = listOf("/Users/me/work/shared-ui", "/Users/me/notes/auth-rfcs"),
            draft = "/Users/me/work/shared-uii",
            refused = "/Users/me/work/shared-uii is not a directory.",
        )
        else -> null
    }

    /**
     * A Codex chat's three controls: a live web mode the phone's replies do not use (`convo-codex-settings`), the
     * narrowing modes and a profile all in use (`convo-codex-settings-narrow`), and the desk's refusal of a profile
     * whose file is gone (`convo-codex-settings-refused`).
     */
    fun sessionCodexSheet(name: String): dev.agentdeck.companion.data.SessionCodexSheet? {
        fun option(value: String, label: String, detail: String, phone: Boolean = true, widens: Boolean = false) =
            com.github.claudeagents.core.mobile.MobileSessionCodexOption(value, label, detail, phone, widens)
        val used = "Replies sent from the phone use this."
        val default = "Replies sent from the phone use Codex's default."
        fun controls(style: String, web: String, profile: String, webNote: String, profileNote: String) = listOf(
            com.github.claudeagents.core.mobile.MobileSessionCodexControl(
                "personality", "Communication Style", style,
                listOf(
                    option("", "Codex default", "Whatever config.toml resolves — no personality unless you set one."),
                    option("none", "Default", "No personality instructions."),
                    option("friendly", "Friendly", "Warm, collaborative, and helpful."),
                    option("pragmatic", "Pragmatic", "Concise, task-focused, and direct."),
                ),
                if (style.isEmpty()) default else used,
            ),
            com.github.claudeagents.core.mobile.MobileSessionCodexControl(
                "web-search", "Web Search", web,
                listOf(
                    option("", "Codex default", "Whatever config.toml resolves — cached pages unless you changed it."),
                    option("indexed", "Indexed and live", "Search an index of the web and open live pages.", phone = false, widens = true),
                    option("live", "Live", "Open live pages from the web.", phone = false, widens = true),
                    option("cached", "Cached only", "Read pages from OpenAI's cache; nothing is fetched live."),
                    option("disabled", "Off", "Codex gets no web search tool at all."),
                ),
                webNote,
            ),
            com.github.claudeagents.core.mobile.MobileSessionCodexControl(
                "profile", "Config Profile", profile,
                listOf(
                    option("", "No profile", "Run with config.toml alone."),
                    option("work", "work", "Layer work.config.toml over config.toml.", phone = false),
                    option("review", "review", "Layer review.config.toml over config.toml.", phone = false),
                ),
                profileNote,
            ),
        )
        val desk = "Replies sent from the phone run on config.toml alone; this applies to replies typed at the desk."
        return when (name) {
            "convo-codex-settings" -> dev.agentdeck.companion.data.SessionCodexSheet(
                key = "convo-1", title = "Fix the flaky login redirect",
                controls = controls(
                    "pragmatic", "live", "work",
                    "Replies sent from the phone run with Codex's default (cached pages); this applies to replies typed at the desk.", desk,
                ),
            )
            "convo-codex-settings-narrow" -> dev.agentdeck.companion.data.SessionCodexSheet(
                key = "convo-1", title = "Fix the flaky login redirect",
                controls = controls("", "cached", "", used, "Replies sent from the phone run on config.toml alone."),
            )
            "convo-codex-settings-refused" -> dev.agentdeck.companion.data.SessionCodexSheet(
                key = "convo-1", title = "Fix the flaky login redirect",
                controls = controls("friendly", "disabled", "", used, "Replies sent from the phone run on config.toml alone."),
                refused = "archive is not a config profile Codex has.",
            )
            else -> null
        }
    }

    /**
     * A Claude chat's MCP servers: limited to two of three (`convo-mcp`), every server in use (`convo-mcp-all`),
     * and the desk's refusal of a server it no longer configures (`convo-mcp-refused`).
     */
    fun sessionMcpSheet(name: String): dev.agentdeck.companion.data.SessionMcpSheet? {
        fun server(name: String, scope: String, transport: String, target: String, on: Boolean) =
            com.github.claudeagents.core.mobile.MobileSessionMcpServer(name, scope, transport, target, on)
        return when (name) {
            "convo-mcp" -> dev.agentdeck.companion.data.SessionMcpSheet(
                key = "convo-1",
                title = "Fix the flaky login redirect",
                servers = listOf(
                    server("github", "user", "http", "https://api.githubcopilot.com", on = true),
                    server("postgres", "project", "stdio", "npx", on = false),
                    server("sentry", "user", "http", "https://mcp.sentry.dev", on = true),
                ),
                narrowed = true,
            )
            "convo-mcp-all" -> dev.agentdeck.companion.data.SessionMcpSheet(
                key = "convo-1",
                title = "Fix the flaky login redirect",
                servers = listOf(
                    server("github", "user", "http", "https://api.githubcopilot.com", on = true),
                    server("postgres", "project", "stdio", "npx", on = true),
                    server("sentry", "user", "http", "https://mcp.sentry.dev", on = true),
                ),
            )
            "convo-mcp-refused" -> dev.agentdeck.companion.data.SessionMcpSheet(
                key = "convo-1",
                title = "Fix the flaky login redirect",
                servers = listOf(
                    server("github", "user", "http", "https://api.githubcopilot.com", on = true),
                    server("sentry", "user", "http", "https://mcp.sentry.dev", on = false),
                ),
                narrowed = true,
                refused = "postgres is not a configured MCP server for this chat's account.",
            )
            else -> null
        }
    }

    /**
     * A chat's own spend limits over a Claude conversation: limits of its own (`convo-spend`), the global
     * defaults it inherits (`convo-spend-defaults`), and the desk's refusal of an inverted pair with the
     * typed text kept (`convo-spend-refused`).
     */
    fun sessionSpendSheet(name: String): dev.agentdeck.companion.data.SessionSpendSheet? {
        val title = "Fix the flaky login redirect"
        val own = dev.agentdeck.companion.data.SpendForm(
            useDefaults = false, soft = "2.5", hard = "10", session = "90", weekly = "",
            prompt = "Summarize completed work, record remaining steps in TODO.md, and stop.", startNewChat = true,
        )
        return when (name) {
            "convo-spend" -> dev.agentdeck.companion.data.SessionSpendSheet("convo-1", title, saved = own, form = own)
            "convo-spend-defaults" -> own.copy(useDefaults = true, soft = "", hard = "", session = "", startNewChat = false)
                .let { dev.agentdeck.companion.data.SessionSpendSheet("convo-1", title, saved = it, form = it) }
            "convo-spend-refused" -> dev.agentdeck.companion.data.SessionSpendSheet(
                "convo-1", title, saved = own, form = own.copy(soft = "12"),
                refused = "Soft limit must be lower than hard limit.",
            )
            // The machine-wide defaults, opened from Settings › Usage › Spend limits (`usage-spend-defaults`).
            "usage-spend-defaults-sheet" -> own.copy(useDefaults = false, soft = "5", hard = "10", session = "", startNewChat = false)
                .let { dev.agentdeck.companion.data.SessionSpendSheet("", "", saved = it, form = it, defaults = true, revision = 4) }
            else -> null
        }
    }

    /**
     * Codex's review sheet: the form (`convo-ai-review`), a run going, and its findings; then the
     * project's rules — a finding just learned (`-learned`) and the rules editor (`-rules`).
     */
    fun aiReview(name: String): dev.agentdeck.companion.data.AiReviewSheet? {
        val base = com.github.claudeagents.core.mobile.MobileAiReviewState(
            key = "convo-1",
            spendNote = "Runs Codex on the machine's default Codex account, read-only.",
            rules = listOf("Flag any new console.log"),
        )
        val sheet = dev.agentdeck.companion.data.AiReviewSheet(key = "convo-1")
        return when (name) {
            "convo-ai-review" -> sheet.copy(
                state = base.copy(kind = com.github.claudeagents.core.mobile.MobileAiReviewRequest.BASE, branch = "main"),
                kind = com.github.claudeagents.core.mobile.MobileAiReviewRequest.BASE,
                branch = "main",
            )
            "convo-ai-review-running" -> sheet.copy(
                state = base.copy(running = true, target = "Uncommitted changes", startedAtMs = NOW - 45_000),
            )
            "convo-ai-review-learned" -> aiReview("convo-ai-review-findings")?.let { found ->
                found.copy(
                    state = found.state?.copy(rules = base.rules.orEmpty() + "Do not report findings like \"Clock is read twice in one stamp\" (seen in Stamps.kt)."),
                    notice = dev.agentdeck.companion.data.AiReviewFlow.LEARNED,
                )
            }
            // The findings as a machine that opens them shows them (`-location-findings`), and the first opened.
            "convo-ai-review-location-findings" -> aiReview("convo-ai-review-findings")
            "convo-ai-review-location" -> aiReview("convo-ai-review-findings")?.let { found ->
                val finding = found.state!!.report!!.findings[0]
                found.copy(
                    location = dev.agentdeck.companion.data.AiReviewLocation(
                        0,
                        finding,
                        com.github.claudeagents.core.mobile.MobileAiReviewExcerpt(
                            finding.path, finding.startLine, finding.endLine, firstLine = 21, lines = STAMPS_LINES,
                        ),
                    ),
                )
            }
            "convo-ai-review-rules" -> sheet.copy(
                state = base,
                rulesText = "Flag any new console.log\nNo TODO without an issue link",
                rulesOpenedOn = base.rules,
            )
            "convo-ai-review-findings" -> sheet.copy(
                state = base.copy(
                    target = "Uncommitted changes",
                    report = com.github.claudeagents.core.mobile.MobileAiReviewReport(
                        findings = listOf(
                            com.github.claudeagents.core.mobile.MobileAiReviewFinding(
                                "Midnight rollover formats the previous day",
                                "`stamp()` converts to UTC after truncating to the local day, so a build at 00:30 CET is labelled with yesterday's date. Truncate after the conversion.",
                                priority = 1, confidence = 82, path = "src/build/Stamps.kt", startLine = 41, endLine = 47,
                                fixPrompt = "Fix this Codex review finding in src/build/Stamps.kt lines 41–47:\nP1 — Midnight rollover formats the previous day",
                            ),
                            com.github.claudeagents.core.mobile.MobileAiReviewFinding(
                                "Clock is read twice in one stamp",
                                "The date and the time come from two `now()` calls; a stamp taken across a second boundary can disagree with itself.",
                                priority = 2, confidence = 64, path = "src/build/Stamps.kt", startLine = 58, endLine = 58,
                                fixPrompt = "Fix this Codex review finding in src/build/Stamps.kt line 58:\nP2 — Clock is read twice in one stamp",
                            ),
                            com.github.claudeagents.core.mobile.MobileAiReviewFinding(
                                "Test name says UTC but pins the default zone",
                                "", priority = null, path = "test/StampsTest.kt", startLine = 12, endLine = 12,
                                fixPrompt = "Fix this Codex review finding in test/StampsTest.kt line 12:\nUnranked — Test name says UTC but pins the default zone",
                            ),
                        ),
                        summary = "3 findings.",
                        correctness = "patch is incorrect",
                        explanation = "The rollover fix is applied before the zone conversion, so the bug it targets is still reachable.",
                    ),
                ),
            )
            else -> null
        }
    }

    /** `src/build/Stamps.kt` lines 21–67, around the first finding's 41–47. */
    private val STAMPS_LINES = listOf(
        "package build",
        "",
        "import java.time.Instant",
        "import java.time.ZoneId",
        "import java.time.ZoneOffset",
        "import java.time.format.DateTimeFormatter",
        "import java.time.temporal.ChronoUnit",
        "",
        "/**",
        " * Build stamps: the day and time a build was cut, written into",
        " * the artifact's manifest and its release notes.",
        " */",
        "object Stamps {",
        "    private val dayFormat = DateTimeFormatter.ofPattern(\"yyyy-MM-dd\")",
        "    private val timeFormat = DateTimeFormatter.ofPattern(\"HH:mm\")",
        "",
        "    /** The zone a stamp is read in when the build names none. */",
        "    val defaultZone: ZoneId = ZoneId.systemDefault()",
        "",
        "    /** The build's stamp for [instant], labelled with its UTC day. */",
        "    fun stamp(instant: Instant, zone: ZoneId = defaultZone): String {",
        "        val local = instant.atZone(zone)",
        "        val day = local.truncatedTo(ChronoUnit.DAYS)",
        "        val utc = day.withZoneSameInstant(ZoneOffset.UTC)",
        "        val time = local.toLocalTime()",
        "        return dayFormat.format(utc) + \" \" + timeFormat.format(time)",
        "    }",
        "",
        "    /** The stamp for a build cut now. */",
        "    fun now(): String = date() + \" \" + time()",
        "",
        "    private fun date(): String = dayFormat.format(Instant.now().atZone(ZoneOffset.UTC))",
        "",
        "    private fun time(): String = timeFormat.format(Instant.now().atZone(ZoneOffset.UTC))",
        "",
        "    /** Parses a stamp back; null for anything [stamp] could not have written. */",
        "    fun parse(text: String): Instant? = runCatching {",
        "        val (day, time) = text.split(' ', limit = 2)",
        "        Instant.parse(day + \"T\" + time + \":00Z\")",
        "    }.getOrNull()",
        "",
        "    /** Whether [a] and [b] were cut on the same UTC day. */",
        "    fun sameDay(a: Instant, b: Instant): Boolean =",
        "        a.atZone(ZoneOffset.UTC).toLocalDate() == b.atZone(ZoneOffset.UTC).toLocalDate()",
        "}",
        "",
        "",
    )

    /** The rewind picker over `convo-rewind-targets` (step one) and `convo-rewind-scopes` (step two). */
    fun rewind(name: String): dev.agentdeck.companion.data.RewindPicker? {
        if (name != "convo-rewind-targets" && name != "convo-rewind-scopes") return null
        val all = listOf(
            com.github.claudeagents.core.mobile.MobileRewindPoint.BOTH,
            com.github.claudeagents.core.mobile.MobileRewindPoint.CONVERSATION,
            com.github.claudeagents.core.mobile.MobileRewindPoint.CODE,
        )
        val points = listOf(
            com.github.claudeagents.core.mobile.MobileRewindPoint("p1", "Make the clock injectable so tests can pin time", 1, NOW - 3_600_000, all, newChat = true, files = 2, undone = 3, request = 1),
            com.github.claudeagents.core.mobile.MobileRewindPoint("p2", "Format stamps in UTC and add a test for midnight rollover", 2, NOW - 1_800_000, all, files = 1, undone = 2, request = 2),
            com.github.claudeagents.core.mobile.MobileRewindPoint("q", "Why does the stamp test fail at 23:59?", 3, NOW - 1_200_000, listOf(com.github.claudeagents.core.mobile.MobileRewindPoint.CONVERSATION)),
            com.github.claudeagents.core.mobile.MobileRewindPoint("p3", "Regenerate the build stamps", 4, NOW - 600_000, all, files = 1, undone = 1, request = 4),
        )
        val picker = dev.agentdeck.companion.data.RewindPicker(
            key = "convo-1",
            points = com.github.claudeagents.core.mobile.MobileSessionRewindPoints(
                "convo-1", points,
                notes = listOf(
                    "Files change; git is not touched — commits stay on the branch.",
                    "Your own edits to those files since that point are not protected — a restored file goes back to the checkpoint.",
                    "Files none of those requests changed are left exactly as they are.",
                ),
            ),
        )
        return if (name == "convo-rewind-scopes") picker.copy(chosen = points[1]) else picker
    }

    /**
     * The review notes over `convo-notes-*`: two open notes on `Clock.kt` (one under its line in the
     * diff), a feedback batch whose delivery failed, and a note being written over two lines.
     */
    fun notes(name: String): dev.agentdeck.companion.data.NotesState? {
        if (!name.startsWith("convo-notes")) return null
        val saved = com.github.claudeagents.core.mobile.MobileReviewNotes(
            key = "convo-1",
            notes = listOf(
                com.github.claudeagents.core.mobile.MobileReviewNote("n1", "src/main/kotlin/Clock.kt", com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT, 19, 19, "fun now(): Long = Clock.getInstance().nowMs()",
                    "Inject the clock instead of reaching for the singleton.", com.github.claudeagents.core.mobile.MobileReviewNote.OPEN),
                com.github.claudeagents.core.mobile.MobileReviewNote("n2", "src/main/kotlin/Clock.kt", com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT, 46, 46, "        dateFormatter.format(Instant.ofEpochMilli(ms))",
                    "Is the zone fixed here? Stamps in tests depend on it.", com.github.claudeagents.core.mobile.MobileReviewNote.OPEN),
                com.github.claudeagents.core.mobile.MobileReviewNote("n3", "src/main/kotlin/Clock.kt", com.github.claudeagents.core.mobile.MobileReviewNote.BASELINE, 19, 19, "fun now(): Long = System.currentTimeMillis()",
                    "Old version was fine for the CLI.", com.github.claudeagents.core.mobile.MobileReviewNote.DELIVERED),
            ),
            pending = listOf(
                com.github.claudeagents.core.mobile.MobileReviewFeedbackPending("b1", "failed", listOf("src/main/kotlin/Clock.kt"), 1),
            ),
            chips = listOf(
                com.github.claudeagents.core.mobile.MobileReviewFeedbackChip("b2", "@review-notes:b2", "src/main/kotlin/Clock.kt", "attached", listOf(
                    com.github.claudeagents.core.mobile.MobileReviewNote("n1", "src/main/kotlin/Clock.kt", com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT, 19, 19,
                        "fun now(): Long = Clock.getInstance().nowMs()", "Inject the clock instead of reaching for the singleton.", com.github.claudeagents.core.mobile.MobileReviewNote.OPEN),
                    com.github.claudeagents.core.mobile.MobileReviewNote("n2", "src/main/kotlin/Clock.kt", com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT, 46, 46,
                        "        dateFormatter.format(Instant.ofEpochMilli(ms))", "Is the zone fixed here? Stamps in tests depend on it.", com.github.claudeagents.core.mobile.MobileReviewNote.OPEN),
                )),
                com.github.claudeagents.core.mobile.MobileReviewFeedbackChip("b2", "@review-notes:b2", "src/test/kotlin/ClockTest.kt", "attached", listOf(
                    com.github.claudeagents.core.mobile.MobileReviewNote("n4", "src/test/kotlin/ClockTest.kt", com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT, 8, 8,
                        "    val clock = FakeClock()", "Start it at a fixed instant.", com.github.claudeagents.core.mobile.MobileReviewNote.OPEN),
                )),
                com.github.claudeagents.core.mobile.MobileReviewFeedbackChip("b1", "@review-notes:b1", "src/main/kotlin/Clock.kt", "failed", listOf(
                    com.github.claudeagents.core.mobile.MobileReviewNote("n3", "src/main/kotlin/Clock.kt", com.github.claudeagents.core.mobile.MobileReviewNote.BASELINE, 19, 19,
                        "fun now(): Long = System.currentTimeMillis()", "Old version was fine for the CLI.", com.github.claudeagents.core.mobile.MobileReviewNote.OPEN),
                )),
            ),
        )
        val state = dev.agentdeck.companion.data.NotesState("convo-1", notes = saved)
        return when (name) {
            "convo-notes-list" -> state.copy(listing = true, selected = setOf("n1"))
            "convo-notes-reattach-list" -> state.copy(listing = true)
            "convo-notes-reattach" -> state.copy(moving = "n2")
            "convo-notes-reattach-editor" -> state.copy(
                moving = "n2",
                editor = dev.agentdeck.companion.data.NoteEditor(
                    key = "convo-1", path = "src/main/kotlin/Clock.kt", side = com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT,
                    lines = listOf(dev.agentdeck.companion.data.NoteLine(21, "/** The one clock a test can move. */")),
                    following = listOf(dev.agentdeck.companion.data.NoteLine(22, "fun now(): Long = clock.nowMs()")),
                    body = "Is the zone fixed here? Stamps in tests depend on it.", noteId = "n2", reattach = true,
                ),
            )
            "convo-notes-editor" -> state.copy(
                editor = dev.agentdeck.companion.data.NoteEditor(
                    key = "convo-1", path = "src/main/kotlin/Clock.kt", side = com.github.claudeagents.core.mobile.MobileReviewNote.CURRENT,
                    lines = listOf(
                        dev.agentdeck.companion.data.NoteLine(20, ""),
                        dev.agentdeck.companion.data.NoteLine(21, "/** The one clock a test can move. */"),
                    ),
                    body = "This KDoc now describes the wrong function.",
                ),
            )
            else -> state
        }
    }

    /**
     * A row's "Commit staged changes…": two staged files on an unpublished branch, a written
     * message and the rename ticked. `-failed` carries a hook's refusal under the typed message.
     */
    /** Request 2 chosen, so Commit and Revert name its files. */
    fun scope(name: String): String? = "p2".takeIf { name == "convo-changes-scope-commit" }

    fun commitStaged(name: String): dev.agentdeck.companion.data.CommitStagedSheet? {
        if (name !in setOf("fleet-commit-staged", "fleet-commit-staged-failed")) return null
        val preview = com.github.claudeagents.core.mobile.MobileCommitStagedPreview(
            key = "claude|default|fixture-staged",
            branch = "wip",
            files = listOf(
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/core/StreamingLoader.kt", com.github.claudeagents.core.mobile.MobileReviewCommitFile.MODIFIED, true),
                com.github.claudeagents.core.mobile.MobileReviewCommitFile("src/core/LoaderBudget.kt", com.github.claudeagents.core.mobile.MobileReviewCommitFile.ADDED, true),
            ),
            renamable = true,
            writer = true,
            previewToken = "fixture",
        )
        return dev.agentdeck.companion.data.CommitStagedSheet(
            key = preview.key,
            preview = preview,
            subject = "Budget the streaming loader's reads",
            body = "Reads stop at the loader budget, so a 10 MB transcript no longer loads whole.",
            rename = true,
            renameTo = "loader-budget",
            error = if (name == "fleet-commit-staged-failed") "pre-commit: ktlint found 2 problems in LoaderBudget.kt" else null,
        )
    }

    /**
     * New chat's "New worktree" pick: the desk's suggested name and both bases. `-failed` carries
     * git's refusal under the name the reader typed — the draft a failed create keeps.
     */
    fun worktreeSheet(name: String): dev.agentdeck.companion.data.WorktreeSheet? {
        if (name !in setOf("new-chat-worktrees", "new-chat-worktrees-remove", "new-chat-worktrees-request", "new-chat-worktrees-link", "new-chat-worktrees-review-button")) return null
        val project = "/Users/dev/Plugin"
        val fleet = com.github.claudeagents.core.mobile.MobileWorktreeFleet(
            project, "main", requestNoun = "pull request",
            rows = listOf(
                com.github.claudeagents.core.mobile.MobileWorktreeRow(
                    "fix-clock", "$project/.claude/worktrees/fix-clock", "fix-clock", "⑂ fix-clock · 2 files · ↑3", ahead = 3, dirtyFiles = 2,
                    mergeRefusal = "fix-clock has 2 uncommitted file(s). Commit them there first.",
                    discardWarning = "fix-clock has 2 uncommitted file(s) that removing it would discard.",
                    requestRefusal = "fix-clock has 2 uncommitted file(s). Commit them there first.",
                ),
                com.github.claudeagents.core.mobile.MobileWorktreeRow(
                    "wt-2", "$project/.claude/worktrees/wt-2", "wt-2", "⑂ wt-2 · ↑1", ahead = 1,
                ),
                com.github.claudeagents.core.mobile.MobileWorktreeRow(
                    "wt-1", "$project/.claude/worktrees/wt-1", "wt-1", "⑂ wt-1 · ↑2", ahead = 2,
                    mergeRefusal = "A chat is still running in wt-1. Stop it, then merge.",
                    removeRefusal = "A chat is still running in wt-1. Stop it, then remove the worktree.",
                ),
                com.github.claudeagents.core.mobile.MobileWorktreeRow(
                    "old-spike", "$project/.claude/worktrees/old-spike", "old-spike", "⑂ old-spike · directory is gone", missing = true,
                    requestRefusal = "This worktree's directory is gone. Prune it first.",
                ),
            ),
        )
        val sheet = dev.agentdeck.companion.data.WorktreeSheet(project, fleet)
        val actions = com.github.claudeagents.core.mobile.MobileWorktreeActionRequest
        return when (name) {
            "new-chat-worktrees", "new-chat-worktrees-review-button" -> sheet
            "new-chat-worktrees-request" -> sheet.copy(confirm = dev.agentdeck.companion.data.WorktreeConfirm(fleet.rows[1], actions.REQUEST_STACKED, "main"))
            "new-chat-worktrees-link" -> sheet.copy(
                link = dev.agentdeck.companion.data.WorktreeLink("Opened a draft pull request for wt-2.", "https://github.com/dev/plugin/pull/42"),
            )
            else -> sheet.copy(confirm = dev.agentdeck.companion.data.WorktreeConfirm(fleet.rows.first(), actions.REMOVE, "main"))
        }
    }

    /**
     * A worktree row's change-request review: GitHub's threads with a reply and a review typed,
     * `-gitlab` with the verdict GitLab has not got, `-none` a branch with nothing open;
     * `-edit-request` / `-edit-comment` with an editor open, `-bitbucket` without reactions or labels.
     */
    fun reviewSheet(name: String): dev.agentdeck.companion.data.ReviewSheet? {
        if (!name.startsWith("new-chat-worktrees-review-") || name == "new-chat-worktrees-review-button") return null
        val project = "/Users/dev/Plugin"
        val path = "$project/.claude/worktrees/wt-2"
        fun c(author: String, body: String, id: String = "", reactions: Int = 0, mine: Boolean = false) =
            com.github.claudeagents.core.mobile.MobileChangeRequestComment(
                author, body, id, reactions, editRefusal = "Only the person who wrote a comment can edit it.".takeIf { !mine },
            )
        fun t(id: String, label: String, resolved: Boolean = false, resolvable: Boolean = false, comments: List<com.github.claudeagents.core.mobile.MobileChangeRequestComment>) =
            com.github.claudeagents.core.mobile.MobileChangeRequestThread(id, label, resolved, resolvable, comments)
        val r = com.github.claudeagents.core.mobile.MobileChangeRequestReviewRequest
        val gitlab = name == "new-chat-worktrees-review-gitlab"
        val bitbucket = name == "new-chat-worktrees-review-bitbucket"
        val review = com.github.claudeagents.core.mobile.MobileChangeRequestReview(
            project, path, "wt-2", requestId = "42", url = "https://github.com/dev/plugin/pull/42",
            title = "Make the clock injectable", base = "main", noun = if (gitlab) "merge request" else "pull request",
            forge = if (gitlab) "GitLab" else "GitHub",
            checkoutCommand = (if (gitlab) "glab mr checkout 42" else "gh pr checkout 42").takeUnless { bitbucket },
            threads = listOf(
                t("t1", "src/main/kotlin/Clock.kt:18 · 2 comments", resolvable = true, comments = listOf(
                    c("ana", "Could this default to the system clock so callers do not have to pass one?", "c1", reactions = 2),
                    c("dev", "It does — `Clock.System`. Tests hand in their own.", "c2", mine = true),
                )),
                t("t2", "README.md:4 · resolved · 1 comment", resolved = true, resolvable = true, comments = listOf(c("bo", "Typo: \"injectible\".", "c3"))),
                t("t3", "On the request · 1 comment", comments = listOf(c("ana", "Looks good once the tests pass.", "c4", reactions = 1))),
            ),
            verdictRefusals = if (gitlab) mapOf(r.REQUEST_CHANGES to "GitLab has no \"request changes\" on a merge request — leave a comment and withhold approval.") else emptyMap(),
            body = "Clock is now a constructor parameter, defaulting to `Clock.System`.",
            reactRefusal = "Bitbucket has no reactions on pull request comments.".takeIf { bitbucket },
            labelRefusal = "Bitbucket pull requests have no labels.".takeIf { bitbucket },
            repoLabels = listOf("bug", "build", "documentation", "enhancement", "tests"),
        ).let { base ->
            if (name != "new-chat-worktrees-review-files") return@let base
            // A stacked request with its checklist: two of seven ticked, so the fold and the partial box both show.
            fun f(path: String, add: Int, del: Int, viewed: Boolean = false) =
                com.github.claudeagents.core.mobile.MobileChangeRequestFile(path, add, del, viewed)
            base.copy(
                files = listOf(
                    f("src/main/kotlin/Clock.kt", 24, 6, viewed = true), f("src/main/kotlin/Scheduler.kt", 11, 9, viewed = true),
                    f("src/test/kotlin/ClockTest.kt", 48, 0), f("README.md", 1, 1), f("build.gradle.kts", 2, 0),
                    f("src/main/kotlin/Timer.kt", 7, 3), f("docs/clock.md", 30, 0),
                ),
                stackParent = com.github.claudeagents.core.mobile.MobileChangeRequestStackEntry("41", "wt-1", "#41 Extract the Clock interface"),
                stackChildren = listOf(com.github.claudeagents.core.mobile.MobileChangeRequestStackEntry("44", "wt-3", "#44 Use the clock in the scheduler (draft)")),
            )
        }
        return when (name) {
            "new-chat-worktrees-review-none" -> dev.agentdeck.companion.data.ReviewSheet(
                project, path, "wt-2",
                com.github.claudeagents.core.mobile.MobileChangeRequestReview(project, path, "wt-2", refused = "wt-2 has no open change request to review yet."),
            )
            else -> dev.agentdeck.companion.data.ReviewSheet(
                project, path, "wt-2", review,
                drafts = dev.agentdeck.companion.data.ReviewDrafts(
                    mapOf("t1" to "Thanks — adding a test for the default."), "Approving once CI is green.",
                    reviewers = "ana, bo",
                    labels = "tests, b",
                    request = dev.agentdeck.companion.data.RequestEdit(
                        "Make the clock injectable", "Clock is now a constructor parameter, defaulting to `Clock.System`.\n\nTests pass a fixed clock.",
                        review.title.orEmpty(), review.body.orEmpty(),
                    ).takeIf { name == "new-chat-worktrees-review-edit-request" },
                    comments = listOfNotNull(
                        dev.agentdeck.companion.data.CommentEdit(
                            "t1", "c2", "It does — `Clock.System`. Tests hand in `Clock.Fixed`.",
                            // Rebased after a refused save: someone edited it on the web meanwhile.
                            "It does — `Clock.System`. Tests pass their own clock.", rebased = true,
                        ).takeIf { name == "new-chat-worktrees-review-edit-comment" },
                    ).associateBy { it.commentId },
                ),
            )
        }
    }

    /** Clone with the desk's paste, Publish with the directory's name typed as `owner/…`, and a landed clone. */
    fun repositorySheet(name: String): dev.agentdeck.companion.data.RepositorySheet? {
        if (!name.startsWith("new-chat-repository-") || name == "new-chat-repository-button") return null
        val project = "/Users/dev/Plugin"
        val options = com.github.claudeagents.core.mobile.MobileRepositorySetupOptions(
            project,
            hosts = listOf(
                com.github.claudeagents.core.mobile.MobileRepositoryHost("github", "GitHub", "owner/repository"),
                com.github.claudeagents.core.mobile.MobileRepositoryHost("gitlab", "GitLab", "group/project, or group/subgroup/project"),
                com.github.claudeagents.core.mobile.MobileRepositoryHost("bitbucket", "Bitbucket", "workspace/repository"),
                com.github.claudeagents.core.mobile.MobileRepositoryHost("azure", "Azure DevOps", "organization/project/repository", false, "An Azure DevOps repository is as visible as its project."),
            ),
            cloneParents = listOf("/Users/dev", "/Users/dev/work"),
            publishable = true,
            suggestedRepository = "Plugin",
        )
        return when (name) {
            "new-chat-repository-publish" -> dev.agentdeck.companion.data.RepositorySheet(project, true, options, input = "dev/plugin", host = "github")
            "new-chat-repository-cloned" -> dev.agentdeck.companion.data.RepositorySheet(
                project, false, options, input = "https://github.com/dev/widgets", host = "github", parentDir = "/Users/dev",
                done = dev.agentdeck.companion.data.RepositoryDone("Cloned widgets into /Users/dev/widgets.", path = "/Users/dev/widgets"),
            )
            else -> dev.agentdeck.companion.data.RepositorySheet(project, false, options, input = "https://github.com/dev/widgets/tree/main", host = "github", parentDir = "/Users/dev")
        }
    }

    fun worktree(name: String): dev.agentdeck.companion.data.WorktreeChoice? {
        // "Existing worktree": the machine's rows, the directory that is gone left out of the choice.
        if (name == "new-chat-worktree-existing" || name == "new-chat-worktree-existing-picked") {
            val project = "/Users/dev/Plugin"
            val rows = worktreeSheet("new-chat-worktrees")!!.fleet!!
            return dev.agentdeck.companion.data.WorktreeChoice(
                projectPath = project,
                existing = true,
                fleet = rows,
                picked = "$project/.claude/worktrees/fix-clock".takeIf { name.endsWith("-picked") },
            )
        }
        if (name != "new-chat-worktree" && name != "new-chat-worktree-failed") return null
        val failed = name == "new-chat-worktree-failed"
        return dev.agentdeck.companion.data.WorktreeChoice(
            projectPath = "/Users/dev/Plugin",
            options = com.github.claudeagents.core.mobile.MobileWorktreeOptions(
                "/Users/dev/Plugin", "wt-3", "origin/main", listOf("wt-1", "wt-2"),
            ),
            name = if (failed) "fix-clock" else "wt-3",
            error = if (failed) "fatal: 'fix-clock' is already checked out at '/Users/dev/Plugin'" else null,
        )
    }

    fun byName(name: String): DeckState? = when (name) {
        "pair" -> pair()
        "fleet-capped" -> fleet(backlog = 167)
        "fleet-uncapped" -> fleet(backlog = 4)
        // A row's "Commit staged changes…" sheet ([commitStaged]) over the list.
        "fleet-commit-staged", "fleet-commit-staged-failed" -> fleet(backlog = 12)
        "fleet-stale" -> fleet(backlog = 167).copy(link = Link.Stale("workshop is not answering"))
        "fleet-repair" -> fleet(backlog = 12).copy(link = Link.Repair("This machine's key changed."))
        "fleet-empty" -> fleet(backlog = 0, waiting = 0, running = 0, failed = 0, recent = 0)
        // P07: a chat on an ACP agent, folded into Chats from the machine's `acpSessions` through the same
        // parse the phone runs. The control is `fleet-uncapped`, the same list without it.
        "fleet-acp" -> fleet(backlog = 4, sort = FleetSort.RECENT).let { base ->
            base.copy(snapshot = MobileFleetSnapshot.fromJson(base.snapshot!!.copy(acpSessions = listOf(ACP_SESSION)).toJson()))
        }
        "fleet-filtered" -> fleet(backlog = 167).copy(filter = FleetFilter(query = "no-such-conversation"))
        // The opening view, and only this one reads the app's real default: every other fleet
        // fixture pins Attention so it can photograph the grouping, which would also pin — and
        // so hide — the very choice this state exists to show.
        "fleet-opening" -> fleet(backlog = 167, sort = DeckState().sort)
        // M3: a pinned row leads the list and one Done row has left it for the Done tab. The
        // control is `fleet-uncapped` without `session-actions`: no star, no tab, no sheet rows.
        "fleet-organized" -> organized(FleetScope.ALL)
        // M3: the Chats list narrowed to one desk folder. Its control is `fleet-organized`, the
        // same rows under "All folders".
        "fleet-folder" -> organized(FleetScope.ALL).let { it.copy(filter = it.filter.copy(folderId = "release")) }
        "fleet-organized-done" -> organized(FleetScope.DONE)
        // M3: two pins in the desk's order — the older one first — on a machine that moves pins
        // and writes titles. Its control is `fleet-organized`: one pin, neither capability, so its
        // sheet has no Move pin rows and no Regenerate title.
        "fleet-pins" -> organized(FleetScope.ALL).let { state ->
            val snapshot = state.snapshot!!
            state.copy(
                snapshot = snapshot.copy(
                    rows = snapshot.rows.map {
                        when (it.key) {
                            "done-3" -> it.copy(pinRank = 0)
                            "recent-1" -> it.copy(pinned = true, pinRank = 1)
                            else -> it
                        }
                    },
                ),
                hello = state.hello!!.copy(
                    capabilities = state.hello!!.capabilities + MobileProtocol.Capability.PIN_ORDER +
                        MobileProtocol.Capability.SESSION_RETITLE,
                ),
            )
        }
        // The machine's preview of a delete, awaiting the reader. Its negative control is
        // `fleet-organized`: the same list with no dialog, because nothing was asked yet.
        "fleet-delete-confirm" -> organized(FleetScope.ALL).copy(
            deletePreview = com.github.claudeagents.core.mobile.MobileSessionDeletePreview(
                key = "recent-1", title = "Fix the pairing retry", movesToTrash = true,
                transcriptCopies = 1, scheduledPrompts = 1, previewToken = "fixture",
            ),
        )
        // M3: the machine's list of messages a Claude chat forks from, awaiting the reader's pick.
        // Its negative control is `fleet-organized`: the same list with no picker.
        "fleet-fork-picker" -> organized(FleetScope.ALL).copy(
            forkPoints = com.github.claudeagents.core.mobile.MobileSessionForkPoints(
                key = "recent-1",
                points = listOf(
                    com.github.claudeagents.core.mobile.MobileForkPoint("a1", "Why does pairing retry forever on a dead host?", 1, NOW - 50 * 60_000),
                    com.github.claudeagents.core.mobile.MobileForkPoint("a2", "Cap the retries at five and back off", 2, NOW - 35 * 60_000),
                    com.github.claudeagents.core.mobile.MobileForkPoint("a3", "Now add a test for the timeout path", 3, NOW - 12 * 60_000),
                ),
            ),
        )
        // "Continue on another account…": the accounts a Claude chat can be copied to, one of them at its limit.
        // Its negative control is `fleet-organized`: the same list with no dialog.
        "fleet-handoff-picker" -> organized(FleetScope.ALL).copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_HANDOFF,
                accounts = mapOf(
                    AgentVendor.CLAUDE to listOf(
                        MobileScheduleAccountOption("default", "Personal"),
                        MobileScheduleAccountOption("work", "Work", resetAtMs = NOW + 90 * 60_000),
                        MobileScheduleAccountOption("side", "Side project"),
                    ),
                ),
            ),
            handoffKey = "recent-1",
        )
        // M3: "pairing" matches two titles, and the machine's message search found two more
        // chats that only mention it. The control is `fleet-filtered`: no capability, no section.
        "fleet-message-search" -> messageSearch()
        "convo-codex" -> conversation(vendor = AgentVendor.CODEX)
        "convo-truncated" -> conversation(vendor = AgentVendor.CLAUDE, hasMore = true, turns = 40)
        "convo-whole" -> conversation(vendor = AgentVendor.CLAUDE, hasMore = false, turns = 40)
        "convo-idle" -> conversation(vendor = AgentVendor.CLAUDE, running = false)
        // P07: that chat opened — the agent's own words, a call and its output, no cost or context figure
        // (ACP reports none), and no Changes tab.
        // Running, so the working row names the agent too; the row is in the snapshot because the speaker's
        // name comes from it (`acpAgentVoice`), as it does on a phone.
        "convo-acp" -> conversation(vendor = AgentVendor.CLAUDE, running = true, toolResults = true).let { base ->
            base.copy(
                snapshot = MobileFleetSnapshot.fromJson(fleet(backlog = 4).snapshot!!.copy(acpSessions = listOf(ACP_SESSION)).toJson()),
                screen = (base.screen as Screen.Conversation).copy(key = ACP_SESSION.key, title = ACP_SESSION.title),
                transcript = base.transcript!!.copy(
                    key = ACP_SESSION.key, title = ACP_SESSION.title, costUsd = 0.0, costKnown = false,
                    contextPct = null, model = null,
                ),
                drafts = emptyMap(),
            )
        }
        "convo-refused" -> conversation(vendor = AgentVendor.CLAUDE)
            .copy(notice = "This project is not open in the IDE on workshop.")
        "convo-empty" -> conversation(vendor = AgentVendor.CLAUDE, turns = 0, running = false)
        // The negative control for `convo-idle`, differing in exactly one field: the last
        // turn's `streaming`. A half-written assistant turn used to paint identically to a
        // finished one (MP-14).
        "convo-writing" ->
            conversation(vendor = AgentVendor.CLAUDE, running = false, streamingTail = true)
        // The pair: identical routes differing only in the checklist the fix put on the wire.
        // Two turns, not the default six: the question sits on the *newest* turn, which is
        // where a live ask really is — and at six the card was below the fold, so the pair
        // recorded two byte-identical PNGs of the conversation above it and reported success.
        "convo-question" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION),
        )
        // Two questions, the second multi-select: the card collects a pick per question and
        // sends them together (PLAN-MOBILE-REDESIGN P16).
        "convo-questions-multi" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION, SCOPE_QUESTION),
        ).let { it.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.ANSWER)) }
        // The same ask against a machine that takes the reader's own words: "Something else"
        // sits under the options (P17). `convo-question` is its negative control — no row.
        "convo-question-typed" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION),
        ).let {
            it.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.ANSWER + MobileProtocol.Capability.ANSWER_TYPED,
                ),
            )
        }
        // A Bash call the run is parked on, with an owner-enabled Allow/Deny card (P18). Two
        // turns so the card sits above the fold, as `convo-question` does.
        "convo-permission" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            it.copy(
                transcript = it.transcript!!.copy(pendingPermission = PERMISSION),
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.PERMISSION_DECISIONS),
            )
        }
        // The negative control: the same parked ask on a machine whose owner left phone decisions off.
        "convo-permission-off" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            it.copy(transcript = it.transcript!!.copy(pendingPermission = PERMISSION))
        }
        // A finished plan waiting for approval (P18), with the two modes the desk window offers.
        "convo-plan" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            it.copy(
                transcript = it.transcript!!.copy(pendingPlan = PLAN),
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.PERMISSION_DECISIONS),
            )
        }
        // A chat that ended on a server error: the machine put the desk's Retry prompt on the failure (M59).
        "convo-retry" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            it.copy(
                transcript = it.transcript!!.copy(
                    turns = it.transcript.turns + MobileTurn(
                        id = "t2",
                        role = "system",
                        text = "Claude stopped — the service failed\n\nThis turn stopped part-way: the provider answered " +
                            "with an error. The conversation is intact — sending again usually works.",
                        timestampMs = NOW - MINUTE,
                        retryPrompt = "The previous turn was interrupted by a transient failure. Continue where you left off.",
                    ),
                ),
            )
        }
        // A `/goal` armed on the chat (P-goal): the desk's goal bar as a strip above the composer.
        "convo-goal" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            it.copy(transcript = it.transcript!!.copy(goal = "every test in the payments module passes and the changelog is updated"))
        }
        // A run fanned out to subagents: the working bubble's "N subagents running" line.
        "convo-subagents" -> conversation(vendor = AgentVendor.CLAUDE, running = true).let {
            it.copy(
                transcript = it.transcript!!.copy(
                    subagents = 5,
                    subagentNames = listOf(
                        "Explore · Find the payment retry paths",
                        "general-purpose · Draft the migration for ledger rows",
                        "Plan · Sequence the rollout",
                    ),
                ),
            )
        }
        // A run with work left in the background (P-background-tasks): the desk's `/tasks` rows. The desk
        // streams this run, so the machine named an id for each and the app offers a Stop on it.
        "convo-background-tasks" -> conversation(vendor = AgentVendor.CLAUDE, running = true).let {
            it.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.TASK_STOP),
                transcript = it.transcript!!.copy(
                    backgroundTasks = listOf(
                        MobileBackgroundTask("Agent", "Review the payment retry changes", "a1"),
                        MobileBackgroundTask("Shell command", "npm run test:integration", "b2"),
                        MobileBackgroundTask("Monitor", null, "c3"),
                    ),
                ),
            )
        }
        // A Task call opened as its own thread (P-subagent-threads): the sheet of the subagent's turns over the chat.
        "convo-subagent-thread" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            val nested = MobileToolCall("toolu_nested", "Agent", "Agent", "description: Sequence the rollout", MobileToolCall.OK, subagent = true)
            val thread = MobileSubagentThread(
                callId = "toolu_task", agentId = "a1", agentType = "Explore", description = "Find the payment retry paths",
                turns = listOf(
                    MobileTurn("s0", "user", "Find every place the payment client retries and say which ones back off.", NOW - 4 * MINUTE),
                    MobileTurn(
                        "s1", "assistant", "Two paths retry: `PaymentClient.charge` backs off exponentially, `LedgerSync.flush` retries immediately.",
                        NOW - 3 * MINUTE,
                        toolCalls = listOf(
                            MobileToolCall("toolu_grep", "Grep", "Grep retry", "pattern: retry", MobileToolCall.OK),
                            nested,
                        ),
                    ),
                ),
            )
            it.copy(subagentThreads = listOf(SubagentThreadFrame("toolu_task", null, thread, loading = false)))
        }
        // "Where you left off" on return after time away (P-recap): the desk's strip over the transcript.
        "convo-recap" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 3).let {
            val recap = MobileRecap(
                "You asked: \"add the retry to the upload client\". 2 files changed.", NOW - 3 * 3_600_000L, 3,
            )
            // No draft: typing retires the strip, so a shot with one would show a state the app never holds.
            it.copy(drafts = emptyMap(), recap = RecapOffer((it.screen as Screen.Conversation).key, recap))
        }
        // The desk's predicted next prompt (`--prompt-suggestions`), offered on a finished turn.
        "convo-suggestion" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 3).let {
            // No draft: typing the prediction's opening retires the strip, as it does live.
            it.copy(drafts = emptyMap(), transcript = it.transcript!!.copy(suggestion = "Run the upload tests and fix whatever fails"))
        }
        "convo-plan-off" -> conversation(vendor = AgentVendor.CLAUDE, running = false, turns = 2).let {
            it.copy(transcript = it.transcript!!.copy(pendingPlan = PLAN))
        }
        "convo-question-answered" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            turns = 2,
            questions = listOf(QUESTION),
            questionLive = false,
        )
        "convo-tasks" -> conversation(vendor = AgentVendor.CLAUDE, todos = TODOS)
        "convo-tasks-none" -> conversation(vendor = AgentVendor.CLAUDE)
        "convo-markdown" -> conversation(vendor = AgentVendor.CLAUDE, markdown = RICH_MARKDOWN)
        // A fence with a line wider than any phone, so the wrap setting has something to do.
        // `convo-code` and `convo-code-wrapped` are the *same* state: the pair differs only in
        // the `LocalCodeWrap` the golden composes it under, which is the field under test.
        "convo-code", "convo-code-wrapped" ->
            conversation(vendor = AgentVendor.CLAUDE, running = false, markdown = LONG_FENCE)
        // The phone has no network, which is not the machine failing to answer — the pair
        // exists because one sentence blames the wrong end of the link.
        "fleet-offline" -> fleet(backlog = 12).copy(link = Link.Offline)
        // Another app's share sheet, waiting for the user to name a destination. Differs from
        // `fleet-capped` in one field, so the pair photographs where the banner sits in the real
        // stack — under the connection's row, over the update one — which is a decision
        // `MainActivity` makes and the golden, rendering the strip alone, cannot see.
        "fleet-sharing" -> fleet(backlog = 167).copy(
            sharing = SharedInput(
                text = "Kotlin coroutines https://kotlinlang.org/docs/coroutines-guide.html",
                label = "Kotlin coroutines https://kotlinlang.org/docs/co…",
            ),
        )
        // Two swipes taken, against the same fleet `fleet-capped` paints untouched.
        "fleet-snoozed" -> fleet(backlog = 167).let { state ->
            val waiting = state.snapshot?.rows.orEmpty().filter { it.key.startsWith("waiting-") }
            state.copy(snoozed = waiting.take(2).associate { it.key to it.lastActivityMs })
        }
        "convo-claude" -> conversation(vendor = AgentVendor.CLAUDE)
        // The offline read: the same page, off the disk, saying how old it is.
        "convo-cached" -> conversation(vendor = AgentVendor.CLAUDE, running = false)
            .copy(transcriptCached = true)
        "scheduled" -> scheduled()
        // The desk's "run up to N in the background at once", with three prompts queued; `scheduled`
        // is the control — the same list without the count the machine reports.
        "scheduled-background-runs" -> scheduled().copy(scheduledBackgroundRuns = 2)
        // The create button appears only where the machine advertises it; the pair differs in
        // that one capability and in nothing else.
        "scheduled-no-create" -> scheduled().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities - MobileProtocol.Capability.SCHEDULE_CREATE,
            ),
        )
        // "After the usage limit resets" in the create dialog's When pill: the active account's
        // window resets in 90 minutes. `scheduled` is the control — no accounts, so no choice.
        "scheduled-reset" -> scheduled().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.ACCOUNTS,
                accounts = mapOf(
                    AgentVendor.CLAUDE to listOf(
                        MobileScheduleAccountOption("default", "Personal"),
                        MobileScheduleAccountOption("work", "Work", resetAtMs = NOW + 90 * MINUTE),
                    ),
                ),
                activeAccounts = mapOf(AgentVendor.CLAUDE to "work"),
            ),
        )
        // The schedule editor on the nightly sweep, its Fast (on) and Thinking (default) pills below effort and mode.
        // `scheduled-edit-plain` is the control: a machine with no `run-toggles` shows neither pill.
        "scheduled-edit-toggles", "scheduled-edit-plain" -> scheduled().copy(
            hello = HELLO_RUN_OPTIONS.copy(
                capabilities = HELLO_RUN_OPTIONS.capabilities +
                    if (name == "scheduled-edit-toggles") listOf(MobileProtocol.Capability.RUN_TOGGLES) else emptyList(),
            ),
            scheduleEditId = "s1",
            scheduleEdit = MobileScheduleEditDetail(
                "s1", "Run the nightly regression sweep", "/Users/dev/Plugin", null, NOW + DAY, DAY, null, "opus",
                "CLAUDE", "default", null, "Europe/Amsterdam", editable = true, canRepeat = true,
                effort = "medium", runOptionsEditable = true, fastMode = true, runTogglesEditable = true,
            ),
        )
        "settings" -> settings()
        // The Review root: the fleet's finished-unreviewed rows across two projects, then the
        // same screen with nothing to review, then a plugin too old to serve `/v1/review`.
        "review" -> review(backlog = 6)
        "review-empty" -> review(backlog = 0)
        "review-unsupported" -> review(backlog = 6).copy(hello = HELLO)
        // The Usage row is progressive disclosure on the `usage` capability, and `settings`
        // above is the negative control: it carries HELLO without it, so "this machine can
        // report its spend" and "it cannot" are two frames of the Settings page, not one.
        "settings-usage" -> settings().copy(hello = HELLO_USAGE)
        // The keep-awake row is drawn only once the machine answered, so `settings` is its negative
        // control; the two values are the switch's two positions.
        "settings-keep-awake" -> settings().copy(keepAwake = true)
        "settings-keep-awake-off" -> settings().copy(keepAwake = false)
        // The review row follows the keep-awake row once the machine reports the value; `settings-keep-awake` is its negative control.
        "settings-keep-awake-review" -> settings().copy(keepAwake = true, holdForReview = true)
        // Drawn only once a phone holding the owner's grant has read the desk's value, so `settings` is its negative control.
        "settings-prompt-suggestions" -> settings().copy(keepAwake = true, promptSuggestions = true)
        // Same gate as the switch above: `settings` is the negative control.
        "settings-restricted-mode" -> settings().copy(keepAwake = true, restrictedMode = true)
        // Same gate again: `settings` is the negative control.
        "settings-questions-keep-working" -> settings().copy(keepAwake = true, questionsKeepWorking = true)
        "settings-continue-interrupted" -> settings().copy(keepAwake = true, continueInterrupted = true)
        // The number is drawn once the machine has answered with it, so `settings` is the negative control.
        "settings-chat-auto-hide" -> settings().copy(keepAwake = true, chatAutoHideDays = 14)
        // Drawn once a phone holding the grant has read the desk's age, so `settings` is its negative control too.
        "settings-chat-auto-compact" -> settings().copy(keepAwake = true, chatAutoHideDays = 14, chatAutoCompactDays = 7)
        "settings-auto-review" -> settings().copy(keepAwake = true, autoReview = com.github.claudeagents.core.mobile.MobileAutoReview(onRunFinished = true, onCommit = false))
        // "Start new chats in" is drawn once the machine answered with the desk's pin, so `settings` is its
        // negative control; this frame is the pin on Plan, the pill's menu offering the desk's words.
        "settings-new-chat-mode" -> settings().copy(
            keepAwake = true,
            newChatDefaults = com.github.claudeagents.core.mobile.MobileNewChatDefaults(
                "plan",
                listOf("last-used" to "Last used", "default" to "Supervised", "acceptEdits" to "Accept edits", "plan" to "Plan")
                    .map { (slug, label) -> com.github.claudeagents.core.mobile.MobileModelOption(slug, label) },
            ),
        )
        "usage" -> usage()
        // Its pair. A machine that has never had a plan window answered for it is not a machine
        // at 0%, and the card that says so is the one a reader meets on a fresh pairing.
        "usage-unmeasured" -> usage().copy(usage = USAGE_UNMEASURED)
        // The drained Work week offers "Schedule a prompt for …" once the machine lists that
        // account's reset; `usage` is the control — no account list, so no offer.
        "usage-limit" -> usage().copy(hello = HELLO_LIMIT)
        // "Edit spend defaults…" under Spend limits needs the machine to advertise the defaults route;
        // `usage` is its negative control. The sheet is [sessionSpendSheet]'s `usage-spend-defaults-sheet`.
        "usage-spend-defaults", "usage-spend-defaults-sheet" ->
            usage().copy(hello = HELLO_USAGE.copy(capabilities = HELLO_USAGE.capabilities + MobileProtocol.Capability.SESSION_SPEND_DEFAULTS))
        // "Export usage as CSV…" at the foot of the screen needs the machine to advertise the route;
        // `usage` is its negative control.
        "usage-export" ->
            usage().copy(hello = HELLO_USAGE.copy(capabilities = HELLO_USAGE.capabilities + MobileProtocol.Capability.USAGE_EXPORT))
        // "Set active for new chats" on the Claude account that is not active needs the machine to advertise
        // the route; `usage` is its negative control. Codex has one account here, so it never offers it.
        "usage-set-active" ->
            usage().copy(hello = HELLO_USAGE.copy(capabilities = HELLO_USAGE.capabilities + MobileProtocol.Capability.ACTIVE_ACCOUNT))
        // Rename and Remove on each account card need the per-phone grant's capability; `usage` is the negative control.
        "usage-account-edit" ->
            usage().copy(hello = HELLO_USAGE.copy(capabilities = HELLO_USAGE.capabilities + MobileProtocol.Capability.ACCOUNT_EDITS))
        // Where that offer lands: the create dialog on Work, "after the limit resets" picked.
        "scheduled-after-reset" -> scheduled().copy(
            hello = HELLO_LIMIT,
            scheduleAfterReset = NewChatTarget("", AgentVendor.CLAUDE, accountId = "work"),
        )
        // A chat on the drained account offers "Continue at …"; `convo-idle` is the control.
        "convo-limit" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO_LIMIT, snapshot = MobileFleetSnapshot(listOf(limitRow()), badgeCount = 0, openProjects = listOf("/Users/dev/Plugin"), usageLine = null, generatedAtMs = NOW))
        }
        // The machine switcher is progressive disclosure: absent with one pairing, present
        // with two, and `settings` above is that negative control.
        "settings-two-machines" -> settings().copy(machines = listOf(MACHINE, LAPTOP))
        // The self-update offer. `settings` above is the negative control and differs in exactly
        // one input — the published `versionCode` — so "Up to date." and the Download row cannot
        // photograph identically.
        "settings-update" -> settings().copy(update = UPDATED)
        "new-chat" -> newChat()
        // The model picker's pair. They differ in the one capability that decides whether the
        // machine ever named a ladder — an older plugin says nothing, and the row is absent
        // rather than an empty dropdown.
        "new-chat-no-models" -> newChat().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities - MobileProtocol.Capability.MODELS,
                models = emptyMap(),
            ),
        )
        // M1's account picker. `new-chat` is the control: same screen, same machine, minus the
        // capability and the second account, so "no picker" and "a picker" cannot photograph alike.
        "new-chat-accounts" -> newChat().copy(
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.ACCOUNTS,
                accounts = mapOf(
                    AgentVendor.CLAUDE to listOf(
                        MobileScheduleAccountOption("default", "Personal"),
                        MobileScheduleAccountOption("work", "Work"),
                    ),
                ),
                activeAccounts = mapOf(AgentVendor.CLAUDE to "work"),
            ),
        )
        // An ACP agent picked in the Agent pill: no account, model, effort or mode pills. `new-chat` is the
        // control — same machine without `acp-agents`, where the pill offers the vendors only.
        "new-chat-acp" -> newChat().let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.ACP_AGENTS,
                    acpAgents = listOf(com.github.claudeagents.core.mobile.MobileAcpAgent("gemini", "Gemini CLI")),
                ),
                newChatTarget = state.newChatTarget!!.withAcpAgent("gemini"),
            )
        }
        // The desk's saved presets: "Reviewer" picked, filling the pills below it and naming what it injects.
        // `new-chat` is the control — the same machine without `agent-presets`, where no Preset pill exists.
        "new-chat-presets" -> newChat().let { state ->
            val reviewer = com.github.claudeagents.core.mobile.MobilePreset(
                "Reviewer", AgentVendor.CLAUDE, effort = "xhigh", permissionMode = "acceptEdits", extras = listOf("Extra instructions"),
            )
            val hello = HELLO_RUN_OPTIONS.copy(
                capabilities = HELLO_RUN_OPTIONS.capabilities + MobileProtocol.Capability.AGENT_PRESETS,
                presets = listOf(
                    reviewer,
                    com.github.claudeagents.core.mobile.MobilePreset("Plan first", AgentVendor.CLAUDE, permissionMode = "plan", builtIn = true),
                ),
            )
            state.copy(hello = hello, newChatTarget = state.newChatTarget!!.withPreset(reviewer))
        }
        // M2: the effort and mode pills. `new-chat` is the control, minus the two capabilities.
        // The worktree pick on ([worktree]); `new-chat-worktree-unsupported` is the control — a
        // machine without `worktree-create` offers no Checkout selector at all.
        // Manage worktrees: the button beside Checkout, and the sheet ([worktreeSheet]) over it.
        "new-chat-worktrees-button", "new-chat-worktrees", "new-chat-worktrees-remove", "new-chat-worktrees-request", "new-chat-worktrees-link" -> newChat().let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.WORKTREE_CREATE + MobileProtocol.Capability.WORKTREE_MANAGE))
        }
        // Change-request review: the row's Review entry ([worktreeSheet]) and the review sheet ([reviewSheet]).
        "new-chat-worktrees-review-button", "new-chat-worktrees-review-github", "new-chat-worktrees-review-gitlab", "new-chat-worktrees-review-none",
        "new-chat-worktrees-review-edit-request", "new-chat-worktrees-review-edit-comment", "new-chat-worktrees-review-bitbucket", "new-chat-worktrees-review-files" -> newChat().let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.WORKTREE_CREATE + MobileProtocol.Capability.WORKTREE_MANAGE +
                        MobileProtocol.Capability.CHANGE_REQUEST_REVIEW + MobileProtocol.Capability.CHANGE_REQUEST_EDITS,
                ),
            )
        }
        // Clone and Publish repository: the two buttons ([repositorySheet] marks the project publishable) and each sheet.
        "new-chat-repository-button", "new-chat-repository-clone", "new-chat-repository-publish", "new-chat-repository-cloned" -> newChat().let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.WORKTREE_CREATE + MobileProtocol.Capability.WORKTREE_MANAGE + MobileProtocol.Capability.REPOSITORY_SETUP))
        }
        "new-chat-worktree", "new-chat-worktree-failed", "new-chat-worktree-unsupported",
        "new-chat-worktree-existing", "new-chat-worktree-existing-picked" -> newChat().let { state ->
            state.copy(
                hello = if (name == "new-chat-worktree-unsupported") HELLO
                else HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.WORKTREE_CREATE +
                        MobileProtocol.Capability.WORKTREE_MANAGE.takeIf { name.contains("existing") }.let { listOfNotNull(it) },
                ),
                drafts = state.drafts + (dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY to "Make the clock injectable and cover it with tests."),
            )
        }
        "new-chat-run-options" -> newChat().let { state ->
            state.copy(
                hello = HELLO_RUN_OPTIONS,
                newChatTarget = state.newChatTarget?.copy(effort = "xhigh"),
            )
        }
        // The composer's pills appear with a draft, opened on what the desk would send next;
        // `convo-idle` is the control — no capability, no pills.
        "convo-composer-pills" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO_RUN_OPTIONS,
                transcript = state.transcript?.copy(
                    selection = MobileRunSelection(model = "opus", effort = "xhigh", permissionMode = "acceptEdits"),
                ),
            )
        }
        // Redesign M2: a run blocked on a tool permission names the wait in place of "working…".
        // `convo-claude` is the control — the same live run with no waiting row.
        "convo-waiting" -> conversation(vendor = AgentVendor.CLAUDE, streamingTail = false).let { state ->
            state.copy(
                hello = HELLO_RUN_OPTIONS,
                snapshot = MobileFleetSnapshot(
                    rows = listOf(
                        row("convo-1", "Fix the flaky pairing test", SessionAttentionState.WAITING_ON_YOU,
                            waitingReason = WaitingReason.PERMISSION),
                    ),
                    badgeCount = 1,
                    openProjects = listOf("/Users/dev/Plugin"),
                    usageLine = null,
                    generatedAtMs = NOW,
                ),
            )
        }
        // What a finished run looks like once the machine carries tool output: every call
        // opens, the failed one says why, and the model's reasoning is folded away above.
        "convo-tool-results" -> conversation(
            vendor = AgentVendor.CLAUDE,
            running = false,
            toolResults = true,
            thought = "Two candidate causes. The stamp is formatted without a date, so a " +
                "turn from yesterday reads as today — that fits the failure better than the " +
                "clock skew does.",
        ).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.TOOL_RESULTS,
                ),
            )
        }
        // Settings › Reading › "Open tool calls" on: the group is unfolded before any tap. The negative
        // control is `convo-tool-results`, the same conversation with the setting at its default.
        "convo-tool-calls-open" -> conversation(vendor = AgentVendor.CLAUDE, running = false, toolResults = true).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.TOOL_RESULTS),
                settings = state.settings.copy(openToolCalls = true),
            )
        }
        // The checklist a reviewer lands on: mixed sizes, one already ticked, one the ladder
        // could not measure — the negative control that keeps "+0 −0" from reading as real.
        "convo-changes" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW),
                review = REVIEW,
            )
        }
        // "Commit…" under the checklist, and its sheet over it ([commitSheet]). The negative
        // control, `convo-commit-unsupported`, is the same open list from a machine without
        // `review-commit`: no button, no sheet.
        "convo-commit", "convo-commit-failed", "convo-commit-unsupported" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW +
                        if (name == "convo-commit-unsupported") emptySet() else setOf(MobileProtocol.Capability.REVIEW_COMMIT),
                ),
                review = REVIEW,
                changesOpenKey = "convo-1",
            )
        }
        // The context grid ([contextSheet]) over a Claude conversation.
        "convo-context", "convo-context-fresh" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.CONTEXT_BREAKDOWN))
        }
        // The `/btw` sheet ([sideQuestionSheet]) over a Claude conversation.
        "convo-side-question", "convo-side-question-refused" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SIDE_QUESTION))
        }
        // The working-directories sheet ([sessionDirsSheet]) over a Claude conversation.
        "convo-dirs", "convo-dirs-empty", "convo-dirs-refused" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_DIRS))
        }
        // The Codex-settings dialog ([sessionCodexSheet]) over a Codex conversation.
        "convo-codex-settings", "convo-codex-settings-narrow", "convo-codex-settings-refused" -> conversation(vendor = AgentVendor.CODEX, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_CODEX))
        }
        // The MCP-servers sheet ([sessionMcpSheet]) over a Claude conversation.
        "convo-mcp", "convo-mcp-all", "convo-mcp-refused" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_MCP))
        }
        // The Memory dialog ([memorySheet]) over Settings on a machine that advertises `memory-files`.
        "settings-memory-files", "settings-memory-readonly", "settings-memory-editor", "settings-memory-note", "settings-memory-conflict",
        "settings-memory-load-order", "settings-memory-cap", "settings-memory-rule" ->
            settings().copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.MEMORY_FILES + MobileProtocol.Capability.MEMORY_INSIGHTS))
        // The spend-limits sheet ([sessionSpendSheet]) over a Claude conversation.
        "convo-spend", "convo-spend-defaults", "convo-spend-refused" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_SPEND))
        }
        // Codex's `/review` sheet ([aiReview]) and `/diff` sheet ([workingDiff]) over a Codex conversation.
        "convo-ai-review", "convo-ai-review-running", "convo-ai-review-findings", "convo-ai-review-learned", "convo-ai-review-rules", "convo-working-diff", "convo-working-diff-patch" -> conversation(vendor = AgentVendor.CODEX, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.AI_REVIEW))
        }
        "convo-ai-review-location", "convo-ai-review-location-findings" -> conversation(vendor = AgentVendor.CODEX, running = false).let { state ->
            state.copy(hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.AI_REVIEW + MobileProtocol.Capability.AI_REVIEW_LOCATION))
        }
        // The rewind picker's two steps ([rewind]) and its code half, the revert sheet's `before` scope.
        "convo-rewind-targets", "convo-rewind-scopes", "convo-rewind-code" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_REVERT +
                        MobileProtocol.Capability.SESSION_REWIND,
                ),
            )
        }
        // "Revert…" beside "Commit…" (`convo-revert-list`), and its sheet over it ([revertSheet]).
        "convo-revert", "convo-revert-failed", "convo-revert-list", "convo-revert-request", "convo-revert-after" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW +
                        MobileProtocol.Capability.REVIEW_COMMIT + MobileProtocol.Capability.REVIEW_REVERT,
                ),
                review = REVIEW,
                changesOpenKey = "convo-1",
            )
        }
        // Review notes: the "Review notes" row on the file list, a note under its diff line, and the
        // notes sheet and editor over the diff ([notes]).
        "convo-notes-files" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_NOTES),
                review = REVIEW,
                changesOpenKey = "convo-1",
            )
        }
        // The composer's feedback chips: an attached batch over two files and feedback that was not delivered.
        "convo-notes-chips" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW +
                    MobileProtocol.Capability.REVIEW_NOTES + MobileProtocol.Capability.REVIEW_FEEDBACK_CHIPS),
                drafts = state.drafts + ("convo-1" to "Please address my review. @review-notes:b2"),
            )
        }
        "convo-notes-diff", "convo-notes-list", "convo-notes-editor",
        "convo-notes-reattach-list", "convo-notes-reattach", "convo-notes-reattach-editor" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_NOTES +
                    if (name.startsWith("convo-notes-reattach")) listOf(MobileProtocol.Capability.REVIEW_NOTE_REATTACH) else emptyList()),
                review = REVIEW,
                changesOpenKey = "convo-1",
                reviewPath = "src/main/kotlin/Clock.kt",
                reviewDiff = REVIEW_DIFF,
            )
        }
        // The desk's "Group by request" scope above the checklist; `convo-changes` is the control —
        // a machine without `review-request-scope` sends no groups, so no selector.
        "convo-changes-scope" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_REQUEST_SCOPE),
                review = REVIEW_SCOPED,
                changesOpenKey = "convo-1",
            )
        }
        // Request 2 chosen on a machine that commits and reverts: both buttons name its files.
        "convo-changes-scope-commit" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_REQUEST_SCOPE +
                        MobileProtocol.Capability.REVIEW_COMMIT + MobileProtocol.Capability.REVIEW_REVERT,
                ),
                review = REVIEW_SCOPED,
                changesOpenKey = "convo-1",
            )
        }
        // One file opened under request 2: its own sides, named as the desk's diff names them.
        "convo-changes-scope-diff" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_REQUEST_SCOPE),
                review = REVIEW_SCOPED,
                changesOpenKey = "convo-1",
                reviewPath = "src/main/kotlin/Clock.kt",
                reviewDiff = REVIEW_DIFF.copy(beforeTitle = "Before request 2", afterTitle = "After request 2", omittedBytes = 0),
            )
        }
        // The desk `/diff` side's Base beside the request scope (`review-diff-base`), and one file held against `main`.
        "convo-changes-base", "convo-changes-base-diff" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW +
                        MobileProtocol.Capability.REVIEW_REQUEST_SCOPE + MobileProtocol.Capability.REVIEW_DIFF_BASE,
                ),
                review = REVIEW_SCOPED,
                changesOpenKey = "convo-1",
                reviewPath = "src/main/kotlin/Clock.kt".takeIf { name == "convo-changes-base-diff" },
                reviewDiff = REVIEW_DIFF.copy(beforeTitle = "main", afterTitle = "Current", omittedBytes = 0)
                    .takeIf { name == "convo-changes-base-diff" },
            )
        }
        // The desk's "Ignore whitespace when comparing files" on one file's diff (`review-ignore-whitespace`), off and on.
        "convo-changes-whitespace", "convo-changes-whitespace-on" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_IGNORE_WHITESPACE,
                ),
                review = REVIEW,
                changesOpenKey = "convo-1",
                reviewPath = "src/main/kotlin/Clock.kt",
                reviewDiff = REVIEW_DIFF.copy(omittedBytes = 0, ignoreWhitespace = name == "convo-changes-whitespace-on"),
            )
        }
        // The desk review's "Sort by" (`review-sort`) beside the request scope, the list as the machine ranks it by Lines changed.
        "convo-changes-sort" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW +
                        MobileProtocol.Capability.REVIEW_REQUEST_SCOPE + MobileProtocol.Capability.REVIEW_SORT,
                ),
                review = REVIEW_SCOPED.copy(
                    files = REVIEW.files.sortedByDescending { it.added + it.removed },
                    sortOrder = "SIZE",
                    sortOptions = listOf(
                        MobileReviewSortOption("DEFAULT", "Session order"), MobileReviewSortOption("RISK", "Risk"),
                        MobileReviewSortOption("NAME", "Name"), MobileReviewSortOption("SIZE", "Lines changed"),
                    ),
                ),
                changesOpenKey = "convo-1",
            )
        }
        // The desk checklist's "Group by directory" (`review-view-options`): headings over the machine's blocks.
        "convo-changes-grouped" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW + MobileProtocol.Capability.REVIEW_VIEW_OPTIONS,
                ),
                review = REVIEW.copy(
                    files = (REVIEW.files + MobileReviewFile("src/main/kotlin/Scheduler.kt", MobileReviewFile.MODIFIED, added = 12, removed = 3))
                        .groupBy { it.path.substringBeforeLast('/', "") }.values.flatten()
                        .map { it.copy(directory = it.path.substringBeforeLast('/', "")) } +
                        MobileReviewFile("README.md", MobileReviewFile.MODIFIED, added = 2, removed = 0, directory = ""),
                    groupedByDirectory = true, groupingAdjustable = true, showFilePaths = false,
                ),
                changesOpenKey = "convo-1",
            )
        }
        // The second depth: one file's hunks, with the omission sentence a cut diff carries.
        "convo-changes-diff" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW),
                review = REVIEW,
                reviewPath = "src/main/kotlin/Clock.kt",
                reviewDiff = REVIEW_DIFF,
            )
        }
        // M5: an instruction the machine refused, still owed and waiting on the reader. The
        // parked state rather than the waiting one, because it is the only one with controls.
        "convo-queued" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            state.copy(
                outgoing = dev.agentdeck.companion.data.OutgoingQueue(
                    listOf(
                        dev.agentdeck.companion.data.OutgoingSend(
                            clientMessageId = "queued-1",
                            key = (state.screen as Screen.Conversation).key,
                            projectPath = state.screen.projectPath,
                            vendor = AgentVendor.CLAUDE,
                            label = state.screen.title,
                            prompt = "Rerun the failing test and paste the output.",
                            lastError = "This chat has reached its spending limit. Raise it in the IDE to continue.",
                            attempts = 1,
                            parked = true,
                            uncertain = true,
                        ),
                    ),
                ),
            )
        }
        // M6: a draft mid-mention with a photo already uploaded. Both surfaces at once, because
        // they share the composer and the frame's whole claim is what that row looks like now.
        "convo-composer-attachments" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            val key = (state.screen as Screen.Conversation).key
            state.copy(
                // Spelled out rather than copied from `state.hello`: `conversation()` leaves it
                // null, and a `?.copy` there silently produced a frame with neither surface.
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities +
                        MobileProtocol.Capability.ATTACHMENTS + MobileProtocol.Capability.FILES,
                ),
                drafts = state.drafts + (key to "Why does this fail? @Conver"),
                pendingPhotos = mapOf(
                    key to listOf(
                        dev.agentdeck.companion.data.PendingPhoto("photo-1", "Photo · 1.2 MB"),
                    ),
                ),
            )
        }
        // A photo, two text files (the second with a name too long for its chip), a PDF and a ZIP archive uploaded, on a machine
        // that advertises `attachment-text`, `attachment-pdf`, `attachment-zip`, `attachment-gzip`, `attachment-tar`, `attachment-xz`, `attachment-bzip2`, `attachment-zstd`, `attachment-7z` and `attachment-rar` — so the paperclip is the photo-or-file menu.
        "convo-composer-files" -> conversation(vendor = AgentVendor.CLAUDE, running = false).let { state ->
            val key = (state.screen as Screen.Conversation).key
            state.copy(
                hello = HELLO.copy(
                    capabilities = HELLO.capabilities +
                        MobileProtocol.Capability.ATTACHMENTS + MobileProtocol.Capability.ATTACHMENT_TEXT +
                        MobileProtocol.Capability.ATTACHMENT_PDF + MobileProtocol.Capability.ATTACHMENT_ZIP +
                        MobileProtocol.Capability.ATTACHMENT_GZIP + MobileProtocol.Capability.ATTACHMENT_TAR + MobileProtocol.Capability.ATTACHMENT_XZ + MobileProtocol.Capability.ATTACHMENT_BZIP2 +
                        MobileProtocol.Capability.ATTACHMENT_ZSTD + MobileProtocol.Capability.ATTACHMENT_7Z + MobileProtocol.Capability.ATTACHMENT_RAR,
                ),
                drafts = state.drafts + (key to "Why does this fail?"),
                pendingPhotos = mapOf(
                    key to listOf(
                        dev.agentdeck.companion.data.PendingPhoto("photo-1", "Photo · 1.2 MB"),
                        dev.agentdeck.companion.data.PendingPhoto("file-10", dev.agentdeck.companion.data.TextFileAttachment.label("client-drop.rar", 6_100 * 1024, "RAR archive")),
                        dev.agentdeck.companion.data.PendingPhoto("file-9", dev.agentdeck.companion.data.TextFileAttachment.label("design-assets.7z", 5_300 * 1024, "7z archive")),
                        dev.agentdeck.companion.data.PendingPhoto("file-8", dev.agentdeck.companion.data.TextFileAttachment.label("heap-snapshots.tar.zst", 3_900 * 1024, "Zstandard file")),
                        dev.agentdeck.companion.data.PendingPhoto("file-7", dev.agentdeck.companion.data.TextFileAttachment.label("crash-dumps.tar.bz2", 4_600 * 1024, "bzip2 file")),
                        dev.agentdeck.companion.data.PendingPhoto("file-6", dev.agentdeck.companion.data.TextFileAttachment.label("release-logs.tar.xz", 2_800 * 1024, "xz file")),
                        dev.agentdeck.companion.data.PendingPhoto("file-5", dev.agentdeck.companion.data.TextFileAttachment.label("sources-snapshot.tar", 7_200 * 1024, "tar archive")),
                        dev.agentdeck.companion.data.PendingPhoto("file-4", dev.agentdeck.companion.data.TextFileAttachment.label("node_modules-cache.tar.gz", 5_400 * 1024, "gzip file")),
                        dev.agentdeck.companion.data.PendingPhoto("file-3", dev.agentdeck.companion.data.TextFileAttachment.label("repro-project.zip", 3_100 * 1024, "ZIP archive")),
                        dev.agentdeck.companion.data.PendingPhoto("file-0", dev.agentdeck.companion.data.TextFileAttachment.label("Q3-usage-report.pdf", 2_300 * 1024, "PDF")),
                        dev.agentdeck.companion.data.PendingPhoto("file-1", dev.agentdeck.companion.data.TextFileAttachment.label("build.log", 412 * 1024)),
                        dev.agentdeck.companion.data.PendingPhoto("file-2", dev.agentdeck.companion.data.TextFileAttachment.label("ConversationTranscriptRendererTest.kt", 9 * 1024)),
                    ),
                ),
            )
        }
        else -> null
    }

    private val REVIEW = MobileReviewList(
        key = "convo-1",
        files = listOf(
            MobileReviewFile("src/main/kotlin/Clock.kt", MobileReviewFile.MODIFIED, added = 31, removed = 4),
            MobileReviewFile("src/test/kotlin/ClockTest.kt", MobileReviewFile.ADDED, added = 88, removed = 0),
            MobileReviewFile("docs/architecture/time.md", MobileReviewFile.MODIFIED, added = 6, removed = 2, reviewed = true),
            MobileReviewFile(
                "build/generated/Stamps.kt", MobileReviewFile.UNKNOWN,
                note = "No recorded version from before this conversation, so its changes cannot be shown.",
            ),
        ),
        added = 125, removed = 6, reviewedFiles = 1,
    )

    private val REVIEW_SCOPED = REVIEW.copy(
        requests = listOf(
            MobileReviewScope("p1", 1, "Put time behind a Clock service", "Request 1 · 2 files", listOf("src/main/kotlin/Clock.kt", "build/generated/Stamps.kt")),
            MobileReviewScope("p2", 2, "Add tests for the clock and document it", "Request 2 · 3 files · +125 −6", listOf("src/main/kotlin/Clock.kt", "src/test/kotlin/ClockTest.kt", "docs/architecture/time.md")),
        ),
    )

    private val REVIEW_DIFF = MobileReviewFileDiff(
        file = MobileReviewFile("src/main/kotlin/Clock.kt", MobileReviewFile.MODIFIED, added = 31, removed = 4),
        hunks = listOf(
            MobileReviewHunk(
                beforeStart = 17, afterStart = 17,
                lines = listOf(
                    " import java.time.Instant",
                    " ",
                    "-fun now(): Long = System.currentTimeMillis()",
                    "+fun now(): Long = Clock.getInstance().nowMs()",
                    " ",
                    " /** The one clock a test can move. */",
                ),
            ),
            MobileReviewHunk(
                beforeStart = 44, afterStart = 45,
                lines = listOf(
                    "     fun stamp(ms: Long): String =",
                    "-        formatter.format(Instant.ofEpochMilli(ms))",
                    "+        dateFormatter.format(Instant.ofEpochMilli(ms))",
                    " }",
                ),
            ),
        ),
        omittedBytes = 41_000,
    )

    private val READ_RESULT = MobileToolResult(
        MobileToolResult.READ,
        output = "    44\tfun clock(ms: Long, now: Long): String =\n" +
            "    45\t    if (sameDay(ms, now)) time(ms) else dateAndTime(ms)\n",
    )

    private val BASH_RESULT = MobileToolResult(
        MobileToolResult.EXECUTE,
        input = "./gradlew testDebugUnitTest --tests '*PairingTest*'",
        output = "> Task :app:testDebugUnitTest\n\nPairingTest > pairs once PASSED\n\n" +
            "BUILD SUCCESSFUL in 41s\n",
    )

    private val EDIT_RESULT = MobileToolResult(
        MobileToolResult.EDIT,
        output = "String to replace not found in file.\n  ui/Common.kt: 1 occurrence expected, 0 found\n",
        omittedBytes = 42_000,
    )

    private val HELLO_RUN_OPTIONS = HELLO.copy(
        capabilities = HELLO.capabilities + MobileProtocol.Capability.EFFORT +
            MobileProtocol.Capability.PERMISSION_MODES,
        effort = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("low", "Low effort"),
                MobileModelOption("medium", "Medium effort"),
                MobileModelOption("xhigh", "Extra-high effort"),
                MobileModelOption("max", "Max effort"),
            ),
            AgentVendor.CODEX to listOf(
                MobileModelOption("low", "Low effort"),
                MobileModelOption("high", "High effort"),
            ),
        ),
        permissionModes = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileModelOption("plan", "Plan mode"),
                MobileModelOption("acceptEdits", "Auto-accept edits"),
                MobileModelOption("auto", "Auto"),
                MobileModelOption("bypassPermissions", "Full access"),
            ),
            AgentVendor.CODEX to listOf(
                MobileModelOption("read-only", "Supervised"),
                MobileModelOption("workspace-write", "Auto-accept edits"),
                MobileModelOption("danger-full-access", "Full access"),
            ),
        ),
    )

    /** Names in the order a full sweep shoots them. */
    fun names(): List<String> = listOf(
        "pair",
        "fleet-capped", "fleet-uncapped", "fleet-acp", "convo-acp", "fleet-stale", "fleet-repair", "fleet-empty",
        "fleet-filtered", "fleet-opening", "fleet-offline", "fleet-snoozed", "fleet-sharing",
        "convo-permission", "convo-permission-off", "convo-plan", "convo-plan-off", "convo-goal", "convo-subagents", "convo-background-tasks", "convo-subagent-thread", "convo-recap", "convo-suggestion", "convo-retry", "convo-claude", "convo-codex", "convo-truncated", "convo-whole", "convo-idle",
        "convo-refused", "convo-empty", "convo-cached", "convo-writing",
        "convo-tasks", "convo-tasks-none", "convo-markdown", "convo-code", "convo-code-wrapped",
        "scheduled", "scheduled-background-runs", "scheduled-no-create", "scheduled-reset", "scheduled-edit-toggles", "scheduled-edit-plain", "settings", "settings-two-machines",
        "settings-update", "new-chat", "new-chat-no-models", "new-chat-accounts", "new-chat-acp", "new-chat-presets",
        "new-chat-run-options", "new-chat-worktree", "new-chat-worktree-failed", "new-chat-worktree-unsupported", "new-chat-worktree-existing", "new-chat-worktree-existing-picked", "new-chat-worktrees-button", "new-chat-worktrees", "new-chat-worktrees-remove", "new-chat-worktrees-request", "new-chat-worktrees-link", "new-chat-worktrees-review-button", "new-chat-worktrees-review-github", "new-chat-worktrees-review-gitlab", "new-chat-worktrees-review-none", "new-chat-worktrees-review-edit-request", "new-chat-worktrees-review-edit-comment", "new-chat-worktrees-review-bitbucket", "new-chat-worktrees-review-files", "new-chat-repository-button", "new-chat-repository-clone", "new-chat-repository-publish", "new-chat-repository-cloned", "convo-composer-pills", "convo-waiting", "convo-questions-multi", "convo-question-typed", "convo-tool-results", "convo-tool-calls-open",
        "convo-changes", "convo-changes-scope", "convo-changes-scope-commit", "convo-changes-scope-diff", "convo-changes-base", "convo-changes-base-diff", "convo-changes-whitespace", "convo-changes-whitespace-on", "convo-changes-sort", "convo-changes-grouped", "convo-changes-diff", "convo-commit", "convo-commit-failed", "convo-commit-unsupported", "convo-revert", "convo-revert-failed", "convo-revert-list", "convo-revert-request", "convo-revert-after", "convo-notes-files", "convo-notes-diff", "convo-notes-list", "convo-notes-editor", "convo-notes-reattach-list", "convo-notes-reattach", "convo-notes-reattach-editor", "convo-notes-chips", "convo-queued", "convo-composer-attachments", "convo-composer-files",
        "settings-usage", "settings-keep-awake", "settings-keep-awake-off", "settings-keep-awake-review", "settings-prompt-suggestions", "settings-restricted-mode", "settings-questions-keep-working", "settings-continue-interrupted", "settings-chat-auto-hide", "settings-chat-auto-compact", "settings-auto-review", "settings-new-chat-mode", "usage", "usage-unmeasured", "usage-limit", "usage-spend-defaults", "usage-spend-defaults-sheet", "usage-export", "usage-set-active", "usage-account-edit", "scheduled-after-reset", "convo-limit",
        "review", "review-empty", "review-unsupported",
        "fleet-organized", "fleet-organized-done", "fleet-message-search", "fleet-delete-confirm", "fleet-folder",
        "fleet-fork-picker", "fleet-handoff-picker", "fleet-pins", "convo-rewind-targets", "convo-rewind-scopes", "convo-rewind-code",
        "convo-ai-review", "convo-ai-review-running", "convo-ai-review-findings", "convo-ai-review-learned", "convo-ai-review-rules", "convo-working-diff", "convo-working-diff-patch",
        "convo-ai-review-location", "convo-ai-review-location-findings",
        "fleet-commit-staged", "fleet-commit-staged-failed",
        "convo-context", "convo-context-fresh",
        "convo-side-question", "convo-side-question-refused",
        "convo-dirs", "convo-dirs-empty", "convo-dirs-refused",
        "convo-mcp", "convo-mcp-all", "convo-mcp-refused",
        "convo-codex-settings", "convo-codex-settings-narrow", "convo-codex-settings-refused",
        "settings-memory-files", "settings-memory-readonly", "settings-memory-editor", "settings-memory-note", "settings-memory-conflict",
        "settings-memory-load-order", "settings-memory-cap", "settings-memory-rule",
        "convo-spend", "convo-spend-defaults", "convo-spend-refused",
    )

    // ---- pairing --------------------------------------------------------------------------

    /**
     * The empty form only.
     *
     * **A half-filled one is not reachable from here, and there is deliberately no fixture
     * pretending otherwise.** This hook overrides [DeckState]; the pairing form's five fields
     * live in `rememberSaveable` *inside* `PairScreen`, one layer below it. A `pair-partial`
     * state was written anyway and was byte-identical to this one — a name claiming a second
     * state, photographing the first.
     *
     * Drive the real form instead; it is the better evidence anyway, because it exercises the
     * production input path rather than a seeded copy of it:
     *
     * ```
     * adb shell input tap 540 1014 && adb shell input text "192.168.1.24"
     * ```
     *
     * That is how docs/img/2026-07-31-mobile-pair-host-filled.png was taken, and how the
     * rotation pair beside it was.
     */
    private fun pair() = DeckState(screen = Screen.Pair, link = Link.Connecting)

    // ---- fleet ----------------------------------------------------------------------------

    private val WAITING_REASONS = listOf(WaitingReason.PERMISSION, WaitingReason.QUESTION, WaitingReason.PLAN_APPROVAL)

    private fun row(
        key: String,
        title: String,
        attention: SessionAttentionState?,
        vendor: AgentVendor = AgentVendor.CLAUDE,
        project: String = "/Users/dev/Plugin",
        branch: String? = "main",
        ageMs: Long = 4 * MINUTE,
        liveLine: String? = null,
        costUsd: Double = 0.42,
        contextPct: Int? = 37,
        messages: Int = 24,
        model: String? = "Sonnet 5",
        waitingReason: WaitingReason? =
            WaitingReason.QUESTION.takeIf { attention == SessionAttentionState.WAITING_ON_YOU },
    ) = MobileFleetRow(
        key = key,
        vendor = vendor,
        accountId = "default",
        accountLabel = null,
        projectPath = project,
        projectName = project.substringAfterLast('/'),
        gitBranch = branch,
        title = title,
        attention = attention,
        waitingReason = waitingReason,
        lastActivityMs = NOW - ageMs,
        costUsd = costUsd,
        costKnown = true,
        contextPct = contextPct,
        messageCount = messages,
        model = model,
        liveLine = liveLine,
    )

    /**
     * [backlog] is the knob the cap is photographed against: 167 is this developer's real
     * `DONE_UNREVIEWED` count and 4 is the same screen with nothing to cap.
     *
     * The waiting and running rows are appended **last**, so a build that lost blocked-first
     * ordering paints them at the bottom instead of accidentally looking correct.
     */
    private fun organized(scope: FleetScope): DeckState = fleet(backlog = 4, sort = FleetSort.RECENT).let { state ->
        val snapshot = state.snapshot!!
        state.copy(
            snapshot = snapshot.copy(
                rows = snapshot.rows.map {
                    when (it.key) {
                        "done-3" -> it.copy(pinned = true, title = "Release checklist", folderId = "release")
                        "recent-2" -> it.copy(done = true)
                        "recent-1", "done-1" -> it.copy(folderId = "release")
                        else -> it
                    }
                },
                folders = listOf(
                    com.github.claudeagents.core.mobile.MobileFolder("release", "Release 1.4", note = "Everything that has to land before the tag", count = 3),
                    com.github.claudeagents.core.mobile.MobileFolder("bugs", "Bug bash"),
                    com.github.claudeagents.core.mobile.MobileFolder("old", "Onboarding", done = true),
                ),
            ),
            hello = HELLO.copy(
                capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_ACTIONS + MobileProtocol.Capability.SESSION_EXPORT +
                    MobileProtocol.Capability.SESSION_DELETE + MobileProtocol.Capability.SESSION_FOLDERS +
                    MobileProtocol.Capability.FOLDER_ACTIONS + MobileProtocol.Capability.SESSION_FORK + MobileProtocol.Capability.SESSION_BRANCH,
            ),
            filter = FleetFilter(scope = scope),
        )
    }

    private fun messageSearch(): DeckState = fleet(backlog = 8, sort = FleetSort.RECENT).let { state ->
        val filter = FleetFilter(query = "pairing")
        val keys = MessageSearch.population(state.snapshot!!.rows, filter)
        state.copy(
            filter = filter,
            hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.SESSION_SEARCH),
            messageSearch = MessageSearch(
                query = "pairing",
                keys = keys,
                hits = listOf(
                    MobileSessionSearchHit("running-1", "\u2026register the token only after pairing succeeds, so a revoked phone\u2026"),
                    MobileSessionSearchHit("done-2", "\u2026the pairing code expires after 120 s; the extractor keeps\u2026"),
                ),
                scanned = keys.size,
                nextCursor = null,
            ),
        )
    }

    private fun fleet(
        backlog: Int,
        waiting: Int = 3,
        running: Int = 1,
        failed: Int = 1,
        recent: Int = 2,
        sort: FleetSort = FleetSort.ATTENTION,
    ): DeckState {
        val rows =
            (1..backlog).map {
                row(
                    key = "done-$it",
                    title = "Refactor the $it${ordinal(it)} extractor pass",
                    attention = SessionAttentionState.DONE_UNREVIEWED,
                    ageMs = it * HOUR,
                    vendor = if (it % 3 == 0) AgentVendor.CODEX else AgentVendor.CLAUDE,
                    project = if (it % 2 == 0) "/Users/dev/Plugin" else "/Users/dev/site",
                )
            } +
                (1..recent).map {
                    row("recent-$it", "Look at the flaky pairing test", null, ageMs = it * MINUTE)
                } +
                (1..failed).map {
                    row(
                        key = "failed-$it",
                        title = "Publish the 2.1 release notes",
                        attention = SessionAttentionState.FAILED,
                        vendor = AgentVendor.CODEX,
                        ageMs = 20 * MINUTE,
                        liveLine = "Exited with status 1 · npm run build",
                    )
                } +
                (1..running).map {
                    row(
                        key = "running-$it",
                        title = "Wire the push registration endpoint",
                        attention = SessionAttentionState.RUNNING,
                        ageMs = 30_000,
                        liveLine = "Bash — ./gradlew testDebugUnitTest",
                        contextPct = 71,
                    )
                } +
                (1..waiting).map {
                    row(
                        key = "waiting-$it",
                        title = WAITING_TITLES[(it - 1) % WAITING_TITLES.size],
                        attention = SessionAttentionState.WAITING_ON_YOU,
                        vendor = if (it == 2) AgentVendor.CODEX else AgentVendor.CLAUDE,
                        ageMs = it * 2 * MINUTE,
                        // One of each reason, matching WAITING_TITLES; the host's live line is
                        // the pending tool's title, which a question or plan ask does not add.
                        waitingReason = WAITING_REASONS[(it - 1) % WAITING_REASONS.size],
                        liveLine = "Bash — psql -f migrate_accounts_v3.sql".takeIf { _ -> (it - 1) % WAITING_REASONS.size == 0 },
                        contextPct = 88,
                        messages = 132,
                    )
                }

        return DeckState(
            screen = Screen.Fleet,
            machine = MACHINE,
            link = Link.Live,
            sort = sort,
            snapshot = MobileFleetSnapshot(
                rows = rows,
                badgeCount = waiting,
                openProjects = listOf("/Users/dev/Plugin", "/Users/dev/site"),
                usageLine = "Session 46% · resets 23:00 · week 61%",
                generatedAtMs = NOW,
            ),
        )
    }

    private val ACP_SESSION = com.github.claudeagents.core.mobile.MobileAcpSession(
        key = "acp:gemini/sess-1", agentName = "Gemini CLI", projectPath = "/Users/dev/Plugin",
        title = "Port the retry ladder to the new client", running = false, lastActivityMs = NOW - 4 * MINUTE, messageCount = 6,
    )

    /** Distinct per row: three identical titles read as a rendering bug in a screenshot. */
    private val WAITING_TITLES = listOf(
        "Approve the migration for accounts_v3",
        "Which of the two retry ladders should stay?",
        "Plan ready — 6 files, 2 deletions",
    )

    private fun ordinal(n: Int) = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    // ---- conversation -----------------------------------------------------------------------

    /**
     * The checklist an agent published, as `TodoWrite` sends it. Used by `convo-tasks` and
     * withheld by `convo-tasks-none` — the two differ in this value and in nothing else, so
     * the pair is evidence rather than a picture (Memory.md).
     */
    private val TODOS = listOf(
        MobileTodo("Reproduce the flake with a fixed clock", MobileTodo.COMPLETED),
        MobileTodo("Pin Times.clock either side of midnight", MobileTodo.COMPLETED),
        MobileTodo("Running the suite", MobileTodo.IN_PROGRESS),
        MobileTodo("Check the Codex path", MobileTodo.PENDING),
        MobileTodo("Update the changelog", MobileTodo.PENDING),
    )

    /**
     * Markdown an agent really writes: a task list, an ordered list, a fence. Before the
     * parser learned the first two, `- [x]` matched the bullet rule and the checkbox arrived
     * on screen as the literal text "[x]".
     */
    /**
     * One fence, one line too wide for a phone, in a language `SyntaxLite` knows.
     *
     * The comment, the string and the three keywords are deliberately all present: a fence that
     * only had keywords would photograph the same whether the string and comment rules ran.
     */
    private const val LONG_FENCE =
        "The clock reader now takes its own instant:\n\n" +
            "```kotlin\n" +
            "// A pinned clock, so a stamp written either side of midnight reads the same twice.\n" +
            "fun clock(ms: Long, now: Long): String =\n" +
            "    if (sameDay(ms, now)) hourMinute(ms) else \"\${dayOfWeek(ms)} \${hourMinute(ms)}\"\n" +
            "```\n"

    private const val RICH_MARKDOWN =
        "Here is the plan:\n\n" +
            "- [x] Reproduce the flake\n" +
            "- [x] Pin `Times.clock` either side of midnight\n" +
            "- [ ] Check the Codex path\n\n" +
            "Then, in order:\n\n" +
            "1. Run the suite\n" +
            "2. Shoot the screenshot\n" +
            "3. Update the changelog\n"

    /**
     * The `AskUserQuestion` a run is parked on, in the shape the plugin puts it on the wire.
     * `convo-question` shows it live and `convo-question-answered` shows the same call with a
     * result behind it — the pair differs in the tool call's status alone, which is exactly the
     * fact that decides whether the options may be tapped.
     */
    private val QUESTION = MobileQuestion(
        key = "What should the flaky-test fix cover?",
        header = "What should the flaky-test fix cover?",
        options = listOf(
            MobileQuestionOption(
                "Pin the clock only",
                "Smallest change: the test supplies its own instant and nothing else moves.",
            ),
            MobileQuestionOption(
                "Pin the clock and the pairing window",
                "Also bounds the retry ladder, which is the other thing that reads wall time.",
            ),
            MobileQuestionOption("Leave it — I'll look at the ladder myself"),
        ),
    )

    private val SCOPE_QUESTION = MobileQuestion(
        key = "Which suites should it re-run?",
        header = "Which suites should it re-run?",
        options = listOf(
            MobileQuestionOption("Pairing"),
            MobileQuestionOption("Outgoing queue"),
            MobileQuestionOption("Goldens"),
        ),
        multiSelect = true,
    )

    private val PERMISSION = MobilePendingPermission(
        requestId = "perm-1",
        tool = "Bash",
        title = "Remove build output",
        detail = "rm -rf build && ./gradlew assembleDebug",
        reason = "This command is not in your allow rules.",
        rememberScope = "Bash(rm -rf build:*)",
    )

    private val PLAN = MobilePendingPlan(
        requestId = "plan-1",
        plan = "## Plan\n\n1. Add a `retry` option to the pairing client.\n2. Cover the flaky handshake with a test.\n3. Run the suite.",
        modes = listOf(
            MobilePlanMode("acceptEdits", "Auto-accept edits", "Edits inside the workspace run without asking."),
            MobilePlanMode("supervised", "Supervised", "Nothing is edited or run until you allow it."),
        ),
    )

    private fun conversation(
        vendor: AgentVendor,
        running: Boolean = true,
        /**
         * Whether the newest turn is mid-write. Defaults to [running] so every existing
         * fixture is byte-identical; it is separable because the *golden* for a live turn
         * must not also be a live run — `TypingDots` is an `infiniteRepeatable` and a capture
         * waits for the composition to go idle.
         */
        streamingTail: Boolean = running,
        hasMore: Boolean = false,
        turns: Int = 6,
        todos: List<MobileTodo> = emptyList(),
        markdown: String? = null,
        /** An `AskUserQuestion` on the newest assistant turn; empty leaves the turn as it was. */
        questions: List<MobileQuestion> = emptyList(),
        /** Whether that question is still parked — the one field the pair differs in. */
        questionLive: Boolean = true,
        /** The machine advertises `tool-results`, so finished calls carry what they returned. */
        toolResults: Boolean = false,
        /** Extended thinking on the newest assistant turn. */
        thought: String? = null,
    ): DeckState {
        val key = "convo-1"
        val body = List(turns) { i ->
            if (i % 2 == 0) {
                MobileTurn(
                    id = "t$i",
                    role = "user",
                    text = "Can you check why the pairing test is flaky and fix it?",
                    timestampMs = NOW - (turns - i) * MINUTE,
                )
            } else {
                MobileTurn(
                    id = "t$i",
                    role = "assistant",
                    text = markdown
                        ?: ("It fails when the clock crosses a **day boundary** — `Times.clock` " +
                            "formatted `HH:mm` with no date, so a stamp from yesterday read as today.\n\n" +
                            "```kotlin\nfun clock(ms: Long, now: Long): String\n```\n\n" +
                            "I pinned it with a fixed clock either side of midnight."),
                    timestampMs = NOW - (turns - i) * MINUTE,
                    // On the newest assistant turn, where a live run's checklist actually sits.
                    todos = if (i == turns - 1) todos else emptyList(),
                    toolCalls = if (questions.isNotEmpty() && i == turns - 1) {
                        listOf(
                            MobileToolCall(
                                "q1", "AskUserQuestion", "AskUserQuestion", "",
                                if (questionLive) MobileToolCall.RUNNING else MobileToolCall.OK,
                                questions = questions,
                            ),
                        )
                    } else if (i == 1) {
                        listOf(
                            MobileToolCall(
                                "c1", "Read", "Read ui/Common.kt", "lines 44-81", MobileToolCall.OK,
                                result = if (toolResults) READ_RESULT else null,
                            ),
                            MobileToolCall(
                                "c2", "Bash", "Bash — run the tests",
                                "command: ./gradlew testDebugUnitTest",
                                if (running) MobileToolCall.RUNNING else MobileToolCall.OK,
                                // A running call has no result yet — which is the case the
                                // capability exists to keep telling apart from an old IDE.
                                result = if (toolResults && !running) BASH_RESULT else null,
                            ),
                            MobileToolCall(
                                "c3", "Edit", "Edit ui/Common.kt", "1 replacement", MobileToolCall.ERROR,
                                result = if (toolResults) EDIT_RESULT else null,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                    streaming = streamingTail && i == turns - 1,
                    thought = thought?.takeIf { i == turns - 1 },
                )
            }
        }

        return DeckState(
            screen = Screen.Conversation(key, "Fix the flaky pairing test", vendor, "/Users/dev/Plugin"),
            machine = MACHINE,
            link = Link.Live,
            transcript = MobileTranscriptPage(
                key = key,
                title = "Fix the flaky pairing test",
                turns = body,
                hasMore = hasMore,
                costUsd = 1.37,
                costKnown = true,
                contextPct = 44,
                model = if (vendor == AgentVendor.CODEX) "GPT-5.4-Codex" else "Sonnet 5",
                liveLine = if (running) "Bash — ./gradlew testDebugUnitTest" else null,
                running = running,
                generatedAtMs = NOW,
            ),
            drafts = mapOf(key to "also check the Codex path"),
        )
    }

    // ---- scheduled and new chat ---------------------------------------------------------------

    /**
     * On the newest published build. The release is *present* rather than null: "up to date" and
     * "never checked" are two different sentences, and the control that must not paint a
     * Download row is the one that has looked.
     */
    private val CURRENT = UpdateState(
        installedCode = 2,
        installedName = "1.1",
        checkedAtMs = NOW,
        release = UpdateRelease(
            versionName = "1.1",
            versionCode = 2,
            variant = "debug",
            apkName = "agent-deck-1.1-debug.apk",
            apkUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.1/agent-deck-1.1-debug.apk",
            sizeBytes = 6_711_000,
            sha256 = "d1e7c0aa5f2b48c39a7e6d1f0b3c845e29f7a6b0c3d5e18f24a9b7c6d0e3f512",
            minSdk = 26,
            releaseUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/tag/v1.1",
        ),
    )

    /** One build behind: the same state with the published `versionCode` one higher. */
    private val UPDATED = CURRENT.copy(
        release = CURRENT.release!!.copy(
            versionName = "1.2",
            versionCode = 3,
            apkName = "agent-deck-1.2-debug.apk",
            apkUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.2/agent-deck-1.2-debug.apk",
            releaseUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/tag/v1.2",
        ),
    )

    private val HELLO_USAGE = HELLO.copy(
        capabilities = HELLO.capabilities + MobileProtocol.Capability.USAGE,
    )

    /**
     * A week that is drained while the 5-hour window is nearly idle — the whole reason the
     * screen lists windows rather than one number. `usage-unmeasured` is the negative control
     * and differs in exactly what the machine measured, not in what the screen draws.
     */
    private val USAGE = MobileUsageReport(
        cards = listOf(
            MobileUsageCard("Today", MobileUsageBucket(input = 41_200, output = 8_900, cacheRead = 2_100_000, costUsd = 3.17)),
            MobileUsageCard("7 days", MobileUsageBucket(input = 512_000, output = 96_400, cacheRead = 31_000_000, costUsd = 41.02)),
            MobileUsageCard("30 days", MobileUsageBucket(input = 2_100_000, output = 410_000, cacheRead = 148_000_000, costUsd = 186.44, costEstimated = true)),
            MobileUsageCard("All time", MobileUsageBucket(input = 9_400_000, output = 1_800_000, cacheRead = 620_000_000, costUsd = 912.80, costKnown = false)),
        ),
        // The trailing 14 days, oldest first: a quiet weekend, a floor day, an estimated day and a free one.
        days = listOf(
            0.0 to 0L, 4.1 to 90_000L, 6.3 to 140_000L, 9.8 to 210_000L, 7.2 to 160_000L, 2.4 to 50_000L, 0.0 to 0L,
            12.5 to 300_000L, 15.9 to 380_000L, 11.0 to 250_000L, 3.6 to 80_000L, 8.4 to 190_000L, 18.2 to 430_000L, 0.31 to 12_000L,
        ).mapIndexed { i, (usd, tokens) ->
            MobileUsageDay(
                "2026-09-%02d".format(11 + i),
                if (tokens == 0L) MobileUsageBucket() else MobileUsageBucket(
                    input = tokens, output = tokens / 5, costUsd = usd,
                    costKnown = i != 8, costEstimated = i == 11,
                ),
            )
        },
        models = listOf(
            MobileUsageCard("Opus 5", MobileUsageBucket(input = 1_400_000, output = 310_000, costUsd = 148.20)),
            MobileUsageCard("Sonnet 5", MobileUsageBucket(input = 620_000, output = 88_000, costUsd = 31.40)),
            MobileUsageCard("Haiku 4.5", MobileUsageBucket(input = 80_000, output = 12_000, costUsd = 6.84, costEstimated = true)),
        ),
        projects = listOf(
            MobileUsageProject("Plugin", 41, MobileUsageBucket(input = 1_100_000, output = 240_000, costUsd = 121.60)),
            MobileUsageProject("mobile", 1, MobileUsageBucket(input = 210_000, output = 31_000, costUsd = 9.05, costEstimated = true)),
            MobileUsageProject("scratch", 7, MobileUsageBucket(input = 12_000, output = 2_000, costUsd = 0.42, costKnown = false)),
        ),
        caps = listOf(
            MobileUsageCap(
                "Your custom chat budget", "hands off at \$5.00 · stops at \$10.00",
                "Your default per-chat spend limit, from Settings › Chat. This is the plugin's own stop — not a cap your organization or Anthropic applies.",
            ),
            MobileUsageCap(
                "Gateway spend limit — your organization set this", "Weekly",
                "Your organization's Claude Code gateway refused a run on its weekly spend cap 2 hours ago. The gateway states the cap only when it binds, so the amount is not known here.",
            ),
        ),
        accounts = listOf(
            MobileUsageAccount(
                id = "work", vendor = AgentVendor.CLAUDE, label = "Work", active = true,
                windows = listOf(
                    MobileUsageWindow("weekly", 100, resetAtMs = NOW + 2 * DAY, resetText = "Aug 3 09:00", reached = true),
                    MobileUsageWindow("weekly Opus", 78, resetAtMs = NOW + 2 * DAY, resetText = "Aug 3 09:00"),
                    MobileUsageWindow("5-hour", 12, resetAtMs = NOW + 3 * HOUR, resetText = "23:00"),
                ),
            ),
            MobileUsageAccount(
                id = "personal", vendor = AgentVendor.CLAUDE, label = "Personal",
                windows = listOf(MobileUsageWindow("5-hour", 46, resetAtMs = NOW + HOUR, resetText = "21:00")),
            ),
            MobileUsageAccount(
                id = "codex", vendor = AgentVendor.CODEX, label = "alena@example.com",
                note = "Codex reports its limits only while it runs, and this account has not run recently.",
            ),
        ),
        // Both agents have spend and two accounts can be told apart, so both chip rows show — the
        // "(Claude)" tag is the machine's own spelling for a list that mixes the agents.
        filter = MobileUsageFilter(
            agents = listOf(AgentVendor.CLAUDE, AgentVendor.CODEX),
            accounts = listOf(
                MobileUsageFilterAccount("work", "Work (Claude)"),
                MobileUsageFilterAccount("personal", "Personal (Claude)"),
                MobileUsageFilterAccount("codex", "alena@example.com (Codex)"),
            ),
        ),
        generatedAtMs = NOW,
    )

    private val USAGE_UNMEASURED = MobileUsageReport(
        cards = USAGE.cards.map { MobileUsageCard(it.label, MobileUsageBucket()) },
        // Vendor-correct sentences, because the two vendors are empty for different reasons and
        // the reader's next move differs: Claude's numbers come from an endpoint the IDE polls,
        // Codex's are whatever its last local run recorded. This is the route's own wording.
        accounts = USAGE.accounts.map {
            MobileUsageAccount(
                it.id, it.vendor, it.label,
                note = when (it.vendor) {
                    AgentVendor.CLAUDE -> "No plan usage has been read for this account yet."
                    AgentVendor.CODEX ->
                        "Codex reports its limits only while it runs, and this account has not run recently."
                },
                active = it.active,
            )
        },
        generatedAtMs = NOW,
        indexing = true,
    )

    private val HELLO_LIMIT = HELLO_USAGE.copy(
        capabilities = HELLO_USAGE.capabilities + MobileProtocol.Capability.ACCOUNTS,
        accounts = mapOf(
            AgentVendor.CLAUDE to listOf(
                MobileScheduleAccountOption("work", "Work", resetAtMs = NOW + 2 * DAY, weeklyLimit = true),
                MobileScheduleAccountOption("personal", "Personal"),
            ),
        ),
        activeAccounts = mapOf(AgentVendor.CLAUDE to "work"),
    )

    private fun limitRow() = MobileFleetRow(
        key = "convo-1", vendor = AgentVendor.CLAUDE, accountId = "work", accountLabel = "Work",
        projectPath = "/Users/dev/Plugin", projectName = "Plugin", gitBranch = "main",
        title = "Fix the flaky pairing test", attention = null, waitingReason = null,
        lastActivityMs = NOW - MINUTE, costUsd = 1.37, costKnown = true, contextPct = 44, messageCount = 6,
    )

    private fun usage() = settings().copy(
        screen = Screen.Usage,
        hello = HELLO_USAGE,
        usage = USAGE,
    )

    private fun settings() = DeckState(
        screen = Screen.Settings,
        machine = MACHINE,
        machines = listOf(MACHINE),
        link = Link.Live,
        hello = HELLO,
        // A LAN address, because that is the state the Diagnostics row exists to make legible:
        // a machine reachable from the desk and from nowhere else (MP-07).
        lastGood = LastGoodHost(MACHINE.hosts.first(), NOW - 4 * MINUTE, HostReach.LAN),
        snapshot = fleet(backlog = 12).snapshot,
        update = CURRENT,
    )

    /** Every finished row but the last carries the desk's "R of T reviewed"; the last is an older plugin's, with none. */
    private fun review(backlog: Int) = settings().copy(
        screen = Screen.Review,
        hello = HELLO.copy(capabilities = HELLO.capabilities + MobileProtocol.Capability.REVIEW),
        snapshot = fleet(backlog = backlog).snapshot?.let { snapshot ->
            snapshot.copy(
                rows = snapshot.rows.map { row ->
                    val n = row.key.removePrefix("done-").toIntOrNull()
                    if (n == null || n == backlog) row else row.copy(changedFiles = n + 2, reviewedFiles = (n - 1) % 3)
                },
            )
        },
    )

    private fun scheduled() = DeckState(
        screen = Screen.Scheduled,
        machine = MACHINE,
        machines = listOf(MACHINE),
        link = Link.Live,
        hello = HELLO,
        snapshot = fleet(backlog = 12).snapshot,
        // Due tomorrow and due yesterday, so MU-05's day stamp has something to prove — and
        // one row per state the machine can actually send. Two of these said `"pending"`, which
        // **no machine has ever sent**: `MobileActions.scheduled()` emits exactly
        // MobileScheduledRow.RUNNING / PAUSED / QUEUED. So the committed golden photographed a
        // state the app cannot receive, and the screen's one state-sensitive branch was
        // exercised by a single row out of three (MP-10).
        scheduled = listOf(
            // Repeating, queued, and it failed last night — the row that read exactly like one
            // that had never run until the wire gained a past tense (MP-10). The two other rows
            // are the control: neither carries an outcome, and `queued` is all they can say.
            MobileScheduledRow(
                "s1", "Run the nightly regression sweep", "/Users/dev/Plugin", null,
                NOW + DAY, MobileScheduledRow.QUEUED, true,
                lastRunAtMs = NOW - DAY, lastRunFailed = true,
            ),
            MobileScheduledRow("s2", "Summarise yesterday's review queue", "/Users/dev/site", "abc", NOW - DAY, MobileScheduledRow.PAUSED, false),
            MobileScheduledRow("s3", "Bump the changelog for 2.1", "/Users/dev/Plugin", null, NOW - 2 * MINUTE, MobileScheduledRow.RUNNING, false),
        ),
    )

    private fun newChat() = DeckState(
        screen = Screen.NewChat,
        machine = MACHINE,
        link = Link.Live,
        snapshot = fleet(backlog = 12).snapshot,
        hello = HELLO,
        // No model named, deliberately. `ModelRows` keeps a model *in use* even where the
        // machine advertised nothing — so a fixture that named one would supply the gate's own
        // input, and the negative control would photograph a picker with the gate bypassed.
        // It did, once. The pair now differs in the capability and in nothing else.
        newChatTarget = NewChatTarget("/Users/dev/Plugin"),
        drafts = mapOf(dev.agentdeck.companion.data.NEW_CHAT_DRAFT_KEY to "add a smoke test for the tunnel"),
    )
}
