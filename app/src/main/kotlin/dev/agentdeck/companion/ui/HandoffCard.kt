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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileDecisionRequest
import com.github.claudeagents.core.mobile.MobilePendingHandoff

/**
 * The desk's "Ask before handing off" bar on the phone: the machine's own question and its two
 * answers. The run keeps going while it stands, so the card says so; the hard limit still stops it.
 * A tap locks the card until the machine answers, and [decideFailures] lifts the lock, as
 * [PlanCard] does. Shown only when the machine advertises `permission-decisions`.
 */
@Composable
internal fun HandoffCard(
    handoff: MobilePendingHandoff,
    decideFailures: Int,
    onDecide: (requestId: String, decision: String) -> Unit,
) {
    var sent by rememberSaveable(handoff.requestId, decideFailures) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("handoff-card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(handoff.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "The run keeps going until you answer; the hard limit still stops it. " +
                    "Keep going: this chat is not asked again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = {
                    sent = true
                    onDecide(handoff.requestId, MobileDecisionRequest.HAND_OFF)
                },
                enabled = !sent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 10.dp),
            ) { Text("Hand off now") }
            OutlinedButton(
                onClick = {
                    sent = true
                    onDecide(handoff.requestId, MobileDecisionRequest.KEEP_GOING)
                },
                enabled = !sent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 6.dp),
            ) { Text("Keep going") }
        }
    }
}
