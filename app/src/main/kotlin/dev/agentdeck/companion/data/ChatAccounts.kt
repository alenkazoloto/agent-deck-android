package dev.agentdeck.companion.data

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption

/**
 * The desk's `/acc <name>` on an open chat.
 *
 * The desk's own answer on a chat that has started is a fresh chat on the picked account (`accountSelectionNeedsFreshChat`):
 * a conversation cannot change account under its own history. The phone does the same — New chat opens in this chat's
 * project and agent with that account picked — and the chat it came from stays where it is.
 */
object ChatAccounts {

    /** The accounts this vendor's chat can move to: none unless the machine lists several, as the desk offers `/acc` only then. */
    fun offered(hello: MobileHello?, vendor: AgentVendor): List<MobileScheduleAccountOption> =
        if (hello == null || MobileProtocol.Capability.ACCOUNTS !in hello.capabilities) emptyList()
        else hello.accounts[vendor].orEmpty().takeIf { it.size > 1 }.orEmpty()

    /**
     * The exact label, else the first whose label starts with, then contains, [query] — case aside. The desk matches with the
     * IDE's speed search, which a phone has none of; the three tiers are the rule `/p` uses for the same kind of name.
     */
    fun find(accounts: List<MobileScheduleAccountOption>, query: String): MobileScheduleAccountOption? {
        val wanted = query.trim().ifEmpty { return null }
        return accounts.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
            ?: accounts.firstOrNull { it.label.startsWith(wanted, ignoreCase = true) }
            ?: accounts.firstOrNull { it.label.contains(wanted, ignoreCase = true) }
    }

    /** New chat's target for [accountId]: this chat's project and agent when it is still listed, else the last pick with the account. */
    fun target(previous: NewChatTarget, projectPath: String?, vendor: AgentVendor?, accountId: String): NewChatTarget =
        (if (vendor != null) previous.withVendor(vendor) else previous)
            .copy(projectPath = projectPath?.takeIf { it.isNotBlank() } ?: previous.projectPath, accountId = accountId)

    fun notFound(query: String): String = "No account matches “${query.trim()}”."
}
