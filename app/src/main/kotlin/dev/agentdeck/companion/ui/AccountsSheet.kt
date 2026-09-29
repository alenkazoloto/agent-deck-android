package dev.agentdeck.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileScheduleAccountOption

/**
 * A bare `/acc`: this agent's accounts, each starting a new chat in this chat's project on that account. The desk's
 * bare `/acc` only says to add a name; a phone popup pick has no name to add, so the list is the answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountsSheet(
    accounts: List<MobileScheduleAccountOption>,
    onPick: (MobileScheduleAccountOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("accounts-sheet")) {
            Text("New chat on…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "This chat keeps its account; a new chat in the same project starts on the one you pick.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(accounts, key = { it.id }) { account ->
                    Text(
                        account.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(account) }
                            .padding(vertical = 16.dp).testTag("account-${account.id}"),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
