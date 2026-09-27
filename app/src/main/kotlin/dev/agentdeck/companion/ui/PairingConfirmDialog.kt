package dev.agentdeck.companion.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.claudeagents.core.mobile.MobilePairingPayload

/**
 * Prefill from a pairing link, one tap from pairing (PLAN-MOBILE-QR-LINK.md Q1 — a link only
 * ever prefills; an in-app scan is the one path that pairs without this card). Shown above
 * whatever screen the link arrived on, because a QR can be scanned from anywhere in the app.
 */
@Composable
fun PairingConfirmDialog(
    payload: MobilePairingPayload,
    /** True when a paired machine already carries this certificate — offered as a re-pair, not blocked. */
    alreadyPaired: Boolean,
    pairing: Boolean,
    pairError: String?,
    onConfirm: (label: String) -> Unit,
    onDismiss: () -> Unit,
    onDismissError: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android phone") }
    Dialog(onDismissRequest = { if (!pairing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Pair with ${payload.machineName.ifBlank { "this machine" }}", style = MaterialTheme.typography.headlineSmall)
                if (alreadyPaired) {
                    Text(
                        "This phone is already paired with this machine. Pairing again replaces the saved connection.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(payload.hosts.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Fingerprint: ${payload.spkiFingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("This device's name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                pairError?.let { problem ->
                    Text(problem, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !pairing, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    TextButton(
                        onClick = { onConfirm(label) },
                        enabled = !pairing && label.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        if (pairing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(end = 8.dp))
                                Text("Pairing…")
                            }
                        } else {
                            Text(if (alreadyPaired) "Re-pair" else "Pair")
                        }
                    }
                }
            }
        }
    }
}
