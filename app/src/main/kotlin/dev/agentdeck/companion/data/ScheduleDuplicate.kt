package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileScheduleEditDetail
import com.github.claudeagents.core.mobile.MobileScheduledRow

/**
 * The desk's "Duplicate…" on a scheduled prompt, on the phone: the create dialog opened on the
 * row's prompt, project, agent, account and run choices, with When left for the reader to pick.
 *
 * Only a prompt that starts a chat is duplicated. One bound to a conversation would need that
 * chat's own form ("Schedule into this chat"), which is where a second run of it belongs; a
 * running row is left out as the desk's menu leaves it out.
 */
object ScheduleDuplicate {
    fun offered(row: MobileScheduledRow): Boolean =
        row.sessionId.isNullOrBlank() && row.state != MobileScheduledRow.RUNNING

    /** The create dialog's opening target for [detail], or null when its agent is not one the phone can start. */
    fun target(detail: MobileScheduleEditDetail): NewChatTarget? {
        if (!detail.sessionId.isNullOrBlank()) return null
        val vendor = enumValues<AgentVendor>().firstOrNull { it.name == detail.vendor } ?: return null
        return NewChatTarget(
            projectPath = detail.projectPath.orEmpty(),
            vendor = vendor,
            model = detail.model?.takeIf { it.isNotBlank() },
            accountId = detail.accountId.takeIf { it.isNotBlank() },
            effort = detail.effort?.takeIf { it.isNotBlank() },
            permissionMode = detail.permissionMode?.takeIf { it.isNotBlank() },
            fastMode = detail.fastMode,
            thinking = detail.thinking,
        )
    }

    /** Whether the copy opens with Repeat ticked: the source's own cadence, which the dialog re-derives from the When pick. */
    fun repeats(detail: MobileScheduleEditDetail): Boolean = detail.repeatEveryMs > 0 || !detail.repeatAtTime.isNullOrBlank()
}
