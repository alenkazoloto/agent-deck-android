package dev.agentdeck.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import dev.agentdeck.companion.data.NewChat

/**
 * Which account a phone-started prompt runs on. One composable for New chat and the
 * schedule-create dialog, for the reason [ModelSelector] is one: both post the same request.
 *
 * Absent unless the machine advertised accounts and [vendor] has two or more
 * ([NewChat.accountOptions]). The pill shows the account the request will name — the user's
 * pick, else the machine's active one — never a placeholder.
 */
@Composable
fun AccountSelector(
    hello: MobileHello?,
    vendor: AgentVendor,
    /** The user's pick, or null for the machine's active account. */
    picked: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = "Account:",
) {
    val options = NewChat.accountOptions(hello, vendor)
    if (options.isEmpty()) return
    val nowMs = LocalNow.current()
    Selector(
        options = options.map { SelectorOption(NewChat.accountLabel(it, nowMs, Times::clock), it.id) },
        selected = NewChat.accountFor(hello, vendor, picked).orEmpty(),
        onSelect = onSelect,
        modifier = modifier,
        prefix = prefix,
    )
}
