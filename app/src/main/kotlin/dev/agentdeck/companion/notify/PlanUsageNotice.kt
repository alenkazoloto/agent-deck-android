package dev.agentdeck.companion.notify

import com.github.claudeagents.core.mobile.MobilePush

/**
 * The words of a plan-usage notification. The window is always the 5-hour one — the desk's
 * warning is raised for it alone — and the reset is spelled by the machine ([MobilePush.PlanUsageAlert.resetText]),
 * so the phone never re-zones a moment the reader's desk named.
 */
object PlanUsageNotice {

    fun title(usage: MobilePush.PlanUsageAlert): String = "Plan usage at ${usage.percent}%"

    fun body(usage: MobilePush.PlanUsageAlert): String =
        if (usage.resetText.isBlank()) "The 5-hour window is filling up."
        else "The 5-hour window resets ${usage.resetText}."
}
