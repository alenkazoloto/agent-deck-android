package dev.agentdeck.companion.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.claudeagents.core.mobile.MobilePairing
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
    // Saved, not remembered: a rotation while the phone is held up to the IDE is not a cancel.
    var scanning by rememberSaveable { mutableStateOf(false) }
    // True once Android has stopped showing the camera prompt: only the app's settings page can
    // turn it back on, and `launch` alone would fail silently forever (t3code #6486).
    var cameraBlocked by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scanProblem = null
            cameraBlocked = false
            scanning = true
        } else {
            val denial = cameraDenial(canAskAgain = context.canAskCameraAgain())
            scanProblem = denial.message
            cameraBlocked = denial.blocked
        }
    }

    // Coming back from the settings page: the grant may have happened there, so the message
    // and the button that led there go away without another Scan tap.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (cameraBlocked && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            cameraBlocked = false
            scanProblem = null
        }
    }

    if (scanning) {
        QrScannerOverlay(prompt = "Scan the pairing QR in the IDE") { contents ->
            scanning = false
            when {
                contents == null -> Unit // Cancelled; the form is still standing.
                !onScanned(contents, label) ->
                    scanProblem = "That QR code is not an Agent Deck pairing code."
                else -> scanProblem = null
            }
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
                Text("Back to Workspace")
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

        Button(onClick = { camera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
            Text("Scan the QR code")
        }

        scanProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (cameraBlocked) {
            OutlinedButton(
                onClick = { if (!openAppSettings(context)) scanProblem = CAMERA_SETTINGS_FALLBACK },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open app settings")
            }
        }

        Text("Or enter it by hand", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host or IP") },
            singleLine = true,
            // The IDE prints "host:port" as one line; whatever was pasted settles into the two
            // fields once typing leaves this one, not per keystroke (a typed "…:6" would jump).
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (!focus.isFocused) splitPairTarget(host, port).let { host = it.host; port = it.port }
            },
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
            onClick = {
                val target = splitPairTarget(host, port)
                onPair(listOf(target.host), target.port.toInt(), fingerprint, code, label)
            },
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

/** What the form says once the camera prompt is refused, and whether only Settings can undo it. */
data class CameraDenial(val message: String, val blocked: Boolean)

const val CAMERA_SETTINGS_FALLBACK =
    "Camera access is blocked for this app. Turn it on under Settings › Apps › Agent Deck › Permissions."

/**
 * Android shows the camera prompt at most twice; after the second refusal it answers "no"
 * without showing anything, so a second Scan tap would change nothing. `canAskAgain` is the
 * platform's own `shouldShowRequestPermissionRationale` read right after the refusal.
 */
fun cameraDenial(canAskAgain: Boolean): CameraDenial =
    if (canAskAgain) {
        CameraDenial("Camera access is off, so type the details below instead.", blocked = false)
    } else {
        CameraDenial(
            "Camera access is blocked for this app. Allow it in the app's settings to scan, " +
                "or type the details below instead.",
            blocked = true,
        )
    }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

// With no activity to ask, the prompt is assumed to still be available: the pre-#6486 behaviour.
private fun Context.canAskCameraAgain(): Boolean =
    activity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: true

/** False when the phone has no settings screen to open, so the caller can say where to go instead. */
private fun openAppSettings(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * Which field the disabled Pair button is waiting for, in the form's own top-to-bottom order
 * so the copy always names the single next thing to fill in rather than every problem at once.
 * Pure decision, no Compose — this is the half of MU-06 a JVM test can hold.
 */
fun missingPairField(host: String, port: String, code: String, fingerprint: String): String? {
    val target = splitPairTarget(host, port)
    return when {
        target.host.isBlank() -> "Enter the host or IP."
        target.port.toIntOrNull() !in 1..65535 -> "Enter a valid port."
        code.length != 8 -> "Enter the 8-digit pairing code."
        fingerprint.isBlank() -> "Enter the certificate fingerprint."
        !MobilePairing.isFingerprint(fingerprint) ->
            "The fingerprint is ${MobilePairing.FINGERPRINT_LENGTH} letters, digits, - and _. Check it against the IDE."
        else -> null
    }
}

/** What the form sends: the bare host and the port that goes with it. */
data class PairTarget(val host: String, val port: String)

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val BRACKETED = Regex("^\\[([^\\]]*)](?::(\\d*))?$")
private val HOST_COLON_PORT = Regex("^([^:]*):(\\d*)$")

/**
 * The host field as pasted from the IDE's "Address" row (`host:port`), or from a browser bar
 * (`https://host:port/path`): the scheme and path are dropped and a trailing `:port` overrides the
 * Port field. A colon left in the host reads as IPv6 to `URL`, so the pair failed as "could not
 * reach this machine" (t3code #3995). A bare IPv6 literal, with several colons, is left alone.
 */
fun splitPairTarget(host: String, port: String): PairTarget {
    val bare = host.trim().replace(SCHEME, "").substringBefore('/').substringBefore('?').substringBefore('#')
    BRACKETED.matchEntire(bare)?.let { return PairTarget(it.groupValues[1], it.groupValues[2].ifEmpty { port }) }
    HOST_COLON_PORT.matchEntire(bare)?.let { return PairTarget(it.groupValues[1], it.groupValues[2].ifEmpty { port }) }
    return PairTarget(bare, port)
}
