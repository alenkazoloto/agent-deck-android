package dev.agentdeck.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import com.github.claudeagents.core.mobile.MobilePendingPermission

/**
 * The tool call the run is parked on, with the three answers the desk's permission dialog offers.
 *
 * It states what would run — the command or the path, in monospace, as the machine bounded it —
 * because that is what the reader is approving. "Always allow" names the rule it would write, so a
 * button never claims a wider grant than it gives; it is absent when the machine would refuse one.
 * A tap locks the card until the machine answers, and a refused decision lifts the lock
 * ([decideFailures]) so the same card can be tried again instead of stranding the run.
 *
 * Shown only when the machine advertises `permission-decisions`, which its owner switches on: a
 * machine that has not would refuse the tap, and a card that invites a refusal is worse than none.
 */
@Composable
internal fun PermissionCard(
    permission: MobilePendingPermission,
    decideFailures: Int,
    onDecide: (requestId: String, decision: String) -> Unit,
) {
    var sent by rememberSaveable(permission.requestId, decideFailures) { mutableStateOf(false) }
    val decide = { decision: String ->
        sent = true
        onDecide(permission.requestId, decision)
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("permission-card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (permission.subagent) "A subagent wants to use ${permission.tool}" else "Allow ${permission.tool}?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (permission.title != permission.tool) {
                Text(
                    permission.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            permission.detail?.let { detail ->
                Surface(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp).testTag("permission-detail"),
                    )
                }
            }
            permission.reason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Button(
                onClick = { decide(MobileDecisionRequest.ALLOW) },
                enabled = !sent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 10.dp),
            ) { Text("Allow") }
            permission.rememberScope?.let { scope ->
                OutlinedButton(
                    onClick = { decide(MobileDecisionRequest.ALWAYS) },
                    enabled = !sent,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp),
                ) { Text("Always allow $scope") }
            }
            OutlinedButton(
                onClick = { decide(MobileDecisionRequest.DENY) },
                enabled = !sent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp),
            ) { Text("Deny", color = MaterialTheme.colorScheme.error) }
        }
    }
}
