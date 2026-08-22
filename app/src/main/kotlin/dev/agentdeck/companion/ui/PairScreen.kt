package dev.agentdeck.companion.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.agentdeck.companion.DeckState

/**
 * Pairing. The manual form is **not** a fallback: it is always on screen, because it is how
 * this gets driven without a camera and how a user pairs when the QR will not scan. The
 * scanner fills the same four fields the form does and submits through the same path.
 */
@Composable
fun PairScreen(
    state: DeckState,
    onPair: (hosts: List<String>, port: Int, fingerprint: String, code: String, label: String) -> Unit,
    onScanned: (raw: String, label: String) -> Boolean,
    onDismissError: () -> Unit,
    /** Non-null only when a machine is already paired — this is "add another", not "start". */
    onCancel: (() -> Unit)? = null,
) {
    // Typed fields survive rotation and process death; a 64-character fingerprint is not
    // something a user retypes after a call interrupts them. `scanProblem` is feedback from
    // the last scan attempt, not input, so it stays ephemeral.
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("63350") }
    var code by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android phone") }
    var scanProblem by remember { mutableStateOf<String?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        when {
            contents == null -> Unit // Cancelled; the form is still standing.
            !onScanned(contents, label) ->
                scanProblem = "That QR code is not an Agent Deck pairing code."
            else -> scanProblem = null
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanner.launch(ScanOptions().setBeepEnabled(false).setPrompt("Scan the pairing QR in the IDE"))
        } else {
            scanProblem = "Camera access is off, so type the details below instead."
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (onCancel == null) "Pair with a machine" else "Pair with another machine",
            style = MaterialTheme.typography.headlineSmall,
        )
        // A way out only when there is somewhere to go back to. On a first run this screen is
        // the whole app, and a "Cancel" that led nowhere would be a dead control.
        onCancel?.let { cancel ->
            OutlinedButton(onClick = cancel, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Settings")
            }
        }
        Text(
            "In the IDE: Settings › Connections › Mobile › Pair a device. The code is good " +
                "for two minutes.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("This device's name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = { camera.launch(android.Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
            Text("Scan the QR code")
        }

        scanProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Or enter it by hand", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host or IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(8) },
                label = { Text("8-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.weight(1.4f),
            )
        }
        OutlinedTextField(
            value = fingerprint,
            onValueChange = { fingerprint = it.trim() },
            label = { Text("Certificate fingerprint") },
            supportingText = { Text("Shown beside the QR code in the IDE.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val missingField = missingPairField(host, port, code, fingerprint)
        Button(
            onClick = { onPair(listOf(host.trim()), port.toInt(), fingerprint, code, label) },
            enabled = missingField == null && !state.pairing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Pair")
        }
        // Progressive disclosure: this is the button's only explanation for being disabled,
        // and it goes away the moment the form is ready — never a permanent second control.
        missingField?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (state.pairing) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp))
                Text("Pairing…")
            }
        }

        state.pairError?.let { problem ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The plugin's own sentence when the machine authored one.
                    Text(problem, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }
    }
}

/**
 * Which field the disabled Pair button is waiting for, in the form's own top-to-bottom order
 * so the copy always names the single next thing to fill in rather than every problem at once.
 * Pure decision, no Compose — this is the half of MU-06 a JVM test can hold.
 */
fun missingPairField(host: String, port: String, code: String, fingerprint: String): String? = when {
    host.isBlank() -> "Enter the host or IP."
    port.toIntOrNull() !in 1..65535 -> "Enter a valid port."
    code.length != 8 -> "Enter the 8-digit pairing code."
    fingerprint.isBlank() -> "Enter the certificate fingerprint."
    else -> null
}
