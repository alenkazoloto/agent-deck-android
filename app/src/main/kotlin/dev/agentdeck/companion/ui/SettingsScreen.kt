package dev.agentdeck.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.Link
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.ThemeChoice
import dev.agentdeck.companion.notify.DeckNotifications

/**
 * The screen that did not exist.
 *
 * The overflow menu held exactly one item, Unpair, so there was nowhere to see which machine
 * answered, on what address, when it last said anything, which app this is — or to turn a
 * single notification off. Every section here answers a question a user actually asks; nothing
 * here is a toggle for a signal the wire does not carry, which is why the notification list
 * has three rows and not the six the plan sketched.
 */
@Composable
fun SettingsScreen(
    state: DeckState,
    onSettings: (AppSettings) -> Unit,
    onSwitchMachine: (String) -> Unit,
    onAddMachine: () -> Unit,
    onUnpair: () -> Unit,
    onRefreshHello: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onReleasePage: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(state.machine?.id) { onRefreshHello() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        MachineSection(state, onSwitchMachine, onAddMachine, onUnpair)
        NotificationSection(state, context, onSettings)
        ConnectionSection(state, onSettings)
        AppearanceSection(state, onSettings)
        AboutSection(
            state = state,
            onSettings = onSettings,
            onCheckUpdate = onCheckUpdate,
            onDownloadUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate,
            onReleasePage = onReleasePage,
        )
        DiagnosticsSection(state)
    }
}

// ---- sections ---------------------------------------------------------------------------

@Composable
private fun MachineSection(
    state: DeckState,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onUnpair: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val machine = state.machine
    Section("Machine") {
        if (machine == null) {
            Detail("Nothing paired", "Pair a machine to see its agents here.")
        } else {
            // More than one is the only case where *which* machine is a question, so the list
            // appears only then; a single pairing shows its own details and nothing else.
            if (state.machines.size > 1) {
                state.machines.forEach { other ->
                    MachineRow(other, active = other.id == machine.id) { onSwitch(other.id) }
                }
            }
            Detail("Name", machine.machineName.ifBlank { "(unnamed)" })
            val answered = machine.preferredHost ?: machine.hosts.firstOrNull().orEmpty()
            Detail("Address", "$answered:${machine.port}")
            // The *others*, not all of them: repeating the address the row above already
            // names makes a two-address machine look like it has three.
            machine.hosts.filterNot { it == answered }.takeIf { it.isNotEmpty() }?.let {
                Detail("Other addresses", it.joinToString(", "))
            }
            // A prefix, not the whole 64-character digest: it is here to be *compared* with
            // what the IDE shows, and eight bytes is what a human compares.
            Detail("Key fingerprint", machine.spkiFingerprint.take(16) + "…")
            Detail(
                "Last snapshot",
                state.snapshot?.generatedAtMs?.takeIf { it > 0 }?.let(Times::clock)
                    ?: "None since this app started",
            )
            Action("Pair another machine", onAdd)
            Action("Unpair ${machine.machineName.ifBlank { "this machine" }}…") { confirming = true }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Unpair ${machine?.machineName?.ifBlank { "this machine" } ?: "this machine"}?") },
            text = {
                Text(
                    "This phone will forget the machine, its cached conversations and any unsent " +
                        "drafts, and the machine will forget this device. Pairing again needs a " +
                        "new code from the IDE.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onUnpair()
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NotificationSection(
    state: DeckState,
    context: Context,
    onSettings: (AppSettings) -> Unit,
) {
    Section("Notifications") {
        NotifyTrigger.entries.forEach { trigger ->
            Toggle(
                title = trigger.title,
                subtitle = trigger.description,
                checked = trigger in state.settings.triggers,
                onChange = { on ->
                    val triggers = if (on) state.settings.triggers + trigger
                    else state.settings.triggers - trigger
                    onSettings(state.settings.copy(triggers = triggers))
                },
            )
            // Sound, importance and bypass belong to Android, not to a screen this app would
            // have to reinvent — so each row hands off to its own system channel.
            Action("Sound and importance for “${trigger.title}”") {
                openChannelSettings(context, trigger.id)
            }
        }
        if (!DeckNotifications.allowed(context)) {
            Detail(
                "Notifications are off",
                "Android is blocking them for Agent Deck. Turn them on in the system settings " +
                    "below and this list starts working.",
            )
            Action("Open Agent Deck's notification settings") { openAppNotificationSettings(context) }
        }
        // The honest degraded story, stated once: there is no push transport yet, so the phone
        // learns about an agent over the connection it is already holding.
        Detail(
            "How these arrive",
            "Over the connection to the machine — there is no push server in the middle. " +
                "They stop while the phone has no network, and “Stay connected” below " +
                "is what keeps them arriving with the app in the background.",
        )
    }
}

@Composable
private fun ConnectionSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Connection") {
        Toggle(
            title = "Stay connected in the background",
            subtitle = "Keeps watching the machine after you leave the app, behind an ongoing " +
                "notification that says what it is holding.",
            checked = state.settings.stayConnected,
            onChange = { onSettings(state.settings.copy(stayConnected = it)) },
        )
    }
}

@Composable
private fun AppearanceSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Appearance") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Selector(
                options = ThemeChoice.entries.map { SelectorOption(it.label, it) },
                selected = state.settings.theme,
                onSelect = { onSettings(state.settings.copy(theme = it)) },
                prefix = "Theme:",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Toggle(
                title = "Colour from the wallpaper",
                subtitle = "Material You. Off keeps Agent Deck's own palette.",
                checked = state.settings.dynamicColor,
                onChange = { onSettings(state.settings.copy(dynamicColor = it)) },
            )
        }
    }
}

/**
 * The app's own version, and the only route this app has out of it.
 *
 * Agent Deck is sideloaded, so no store will ever offer its owner a newer build — which is why
 * an update lives here rather than behind a link to a download page, and why the status line is
 * permanent while the *buttons* appear only when there is something to press.
 */
@Composable
private fun AboutSection(
    state: DeckState,
    onSettings: (AppSettings) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onReleasePage: () -> Unit,
) {
    val update = state.update
    val release = update.release
    val offered = update.available && !AppUpdate.tooOld(release, Build.VERSION.SDK_INT)
    Section("About") {
        // The installed version comes from the package manager through the view model, not from
        // `BuildConfig`: after a sideload the APK on the phone and the sources that built this
        // screen are the same only by luck, and it is the *installed* one being compared.
        Detail("Agent Deck", update.installedName.ifBlank { "unknown" })
        Detail("Updates", AppUpdate.status(update, Build.VERSION.SDK_INT))
        when {
            update.readyApk != null -> Action("Install ${release?.label.orEmpty()}", onInstallUpdate)
            offered -> Action(
                listOfNotNull(
                    "Download and install ${release?.label.orEmpty()}",
                    AppUpdate.size(release?.sizeBytes ?: 0).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                onDownloadUpdate,
            )
        }
        if (!update.busy) Action("Check for updates", onCheckUpdate)
        // The way out of every refusal above — a wrong signing key, an installer this phone does
        // not have, a checksum that did not match — is the page the APK can be fetched from by
        // hand. Offered only while one of those is on screen, never as decoration.
        if (offered || update.error != null) Action("Open the release page", onReleasePage)
        Toggle(
            title = "Tell me about new versions",
            subtitle = "A banner over the fleet when a newer build is published. The rows above " +
                "still offer it when this is off.",
            checked = state.settings.updateNotices,
            onChange = { onSettings(state.settings.copy(updateNotices = it)) },
        )
        state.hello?.let { hello ->
            Detail("IDE", listOf(hello.ideName, hello.pluginVersion).filter { it.isNotBlank() }.joinToString(" "))
            Detail("Protocol", "v${hello.protocolVersion}")
            // What the machine says it can do — the honest source for which surfaces exist.
            Detail("This machine can", hello.capabilities.sorted().joinToString(", ").ifBlank { "nothing it named" })
            hello.servedByOtherIde?.let { Detail("Served by", it) }
        }
    }
}

@Composable
private fun DiagnosticsSection(state: DeckState) {
    Section("Diagnostics") {
        Detail(
            "Connection",
            when (val link = state.link) {
                Link.Live -> "Connected"
                Link.Connecting -> "Connecting"
                Link.Offline -> "This phone is offline"
                is Link.Stale -> link.reason
                is Link.Repair -> link.reason
            },
        )
        Detail("Conversations known", (state.snapshot?.rows?.size ?: 0).toString())
        state.snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let { Detail("Plan usage", it) }
    }
}

// ---- pieces ---------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun Detail(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A row that does something. 48 dp minimum, and it announces itself as a button. */
@Composable
private fun Action(title: String, onClick: () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // The whole row is the target, so the switch is not a 32 dp bullseye — and the
            // row announces once, as a switch, rather than twice.
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun MachineRow(machine: PairedMachine, active: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(machine.machineName.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.bodyMedium)
            Text(
                machine.hosts.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Showing this machine",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The top bar's machine switcher, shown only when more than one is paired.
 *
 * Icon-only by the repo's rule: the destination lives in the accessible name, and the machine
 * the app is showing is already the title of the Fleet screen.
 */
@Composable
fun MachineMenu(machines: List<PairedMachine>, active: String?, onSwitch: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Switch machine")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        machines.forEach { machine ->
            DropdownMenuItem(
                text = { Text(machine.machineName.ifBlank { "(unnamed)" }) },
                leadingIcon = {
                    if (machine.id == active) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    open = false
                    onSwitch(machine.id)
                },
            )
        }
    }
}

private fun openChannelSettings(context: Context, channelId: String) {
    val intent = Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(AndroidSettings.EXTRA_CHANNEL_ID, channelId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
