package dev.agentdeck.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.DeckState
import dev.agentdeck.companion.PushState
import dev.agentdeck.companion.Link
import dev.agentdeck.companion.data.AppSettings
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.HostReach
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
    onRefreshPush: () -> Unit,
    onChoosePush: (String?) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onReleasePage: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(state.machine?.id) { onRefreshHello() }
    // After hello, not beside it: whether the *machine* will send a push is one of the four
    // facts this screen reports, and it is the only one that comes off the wire.
    LaunchedEffect(state.machine?.id, state.hello) { onRefreshPush() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        MachineSection(state, onSwitchMachine, onAddMachine, onUnpair)
        NotificationSection(state, context, onSettings, onChoosePush)
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
    // Hoisted: the row list is built outside composition, and the reader's clock is a
    // composition-local read.
    val now = LocalNow.current()
    Section("Machine") {
        if (machine == null) {
            detail(Icons.Filled.Info, "Nothing paired", "Pair a machine to see its agents here.")
        } else {
            // More than one is the only case where *which* machine is a question, so the list
            // appears only then; a single pairing shows its own details and nothing else.
            if (state.machines.size > 1) {
                state.machines.forEach { other ->
                    row { MachineRow(other, active = other.id == machine.id) { onSwitch(other.id) } }
                }
            }
            detail(Icons.Filled.AccountBox, "Name", machine.machineName.ifBlank { "(unnamed)" })
            val answered = machine.preferredHost ?: machine.hosts.firstOrNull().orEmpty()
            detail(Icons.Filled.Place, "Address", "$answered:${machine.port}")
            // The *others*, not all of them: repeating the address the row above already
            // names makes a two-address machine look like it has three.
            machine.hosts.filterNot { it == answered }.takeIf { it.isNotEmpty() }?.let {
                detail(Icons.Filled.LocationOn, "Other addresses", it.joinToString(", "))
            }
            // A prefix, not the whole 64-character digest: it is here to be *compared* with
            // what the IDE shows, and eight bytes is what a human compares.
            detail(Icons.Filled.Lock, "Key fingerprint", machine.spkiFingerprint.take(16) + "…")
            detail(
                Icons.Filled.DateRange,
                "Last snapshot",
                state.snapshot?.generatedAtMs?.takeIf { it > 0 }
                    ?.let { Times.clock(it, now) }
                    ?: "None since this app started",
            )
            action(Icons.Filled.Add, "Pair another machine", onClick = onAdd)
            action(
                Icons.Filled.Delete,
                "Unpair ${machine.machineName.ifBlank { "this machine" }}…",
                // A dialog, not a destination: a chevron here would promise a page.
                chevron = false,
                destructive = true,
            ) { confirming = true }
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
    onChoosePush: (String?) -> Unit,
) {
    val allowed = DeckNotifications.allowed(context)
    Section("Notifications") {
        NotifyTrigger.entries.forEach { trigger ->
            toggle(
                icon = trigger.icon,
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
            action(Icons.Filled.Settings, "Sound and importance for “${trigger.title}”") {
                openChannelSettings(context, trigger.id)
            }
        }
        if (!allowed) {
            detail(
                Icons.Filled.Lock,
                "Notifications are off",
                "Android is blocking them for Agent Deck. Turn them on below and this list " +
                    "starts working.",
            )
            action(Icons.Filled.Settings, "Open Agent Deck's notification settings") {
                openAppNotificationSettings(context)
            }
        }
        pushRows(state.push, onChoosePush)
    }
}

/**
 * How an alert reaches this phone, and the one control that changes it.
 *
 * The *sentence* is [PushCopy]'s decision, taken over four separate facts rather than one
 * "push works" boolean: a reader whose phone is silent needs to know which piece is missing,
 * and the four have four different repairs — install an app, tap a row here, wait for a relay,
 * or flip a switch in the IDE at their desk. Only one of those is on this screen, and a single
 * "Push: off" row would be true in every case and useful in none.
 */
private fun SectionRows.pushRows(push: PushState, onChoosePush: (String?) -> Unit) {
    val copy = PushCopy.of(push)
    detail(Icons.Filled.Info, copy.title, copy.body)
    if (copy.offerStop) {
        action(Icons.Filled.Close, "Stop notifying this phone when the app is closed") {
            onChoosePush(null)
        }
    }
    copy.offer.forEach { distributor ->
        action(Icons.Filled.Notifications, "Notify me through ${distributor.label}") {
            onChoosePush(distributor.packageName)
        }
    }
}

@Composable
private fun ConnectionSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Connection") {
        toggle(
            icon = Icons.Filled.Refresh,
            title = "Stay connected in the background",
            subtitle = "Behind an ongoing notification that says what it is holding.",
            checked = state.settings.stayConnected,
            onChange = { onSettings(state.settings.copy(stayConnected = it)) },
        )
    }
}

@Composable
private fun AppearanceSection(state: DeckState, onSettings: (AppSettings) -> Unit) {
    Section("Appearance") {
        row {
            ControlRow(Icons.Filled.Face, "Theme") {
                // No `prefix`: the headline names the setting, and the pill's 160 dp label
                // clipped "Match the system" back to "Match the syst…" once it carried both.
                Selector(
                    options = ThemeChoice.entries.map { SelectorOption(it.label, it) },
                    selected = state.settings.theme,
                    onSelect = { onSettings(state.settings.copy(theme = it)) },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            toggle(
                icon = Icons.Filled.Star,
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
        detail(Icons.Filled.Info, "Agent Deck", update.installedName.ifBlank { "unknown" })
        detail(Icons.Filled.Refresh, "Updates", AppUpdate.status(update, Build.VERSION.SDK_INT))
        when {
            update.readyApk != null -> action(
                Icons.Filled.Star,
                "Install ${release?.label.orEmpty()}",
                chevron = false,
                onClick = onInstallUpdate,
            )
            offered -> action(
                Icons.Filled.Star,
                listOfNotNull(
                    "Download and install ${release?.label.orEmpty()}",
                    AppUpdate.size(release?.sizeBytes ?: 0).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                chevron = false,
                onClick = onDownloadUpdate,
            )
        }
        if (!update.busy) {
            action(Icons.Filled.Search, "Check for updates", chevron = false, onClick = onCheckUpdate)
        }
        // The way out of every refusal above — a wrong signing key, an installer this phone does
        // not have, a checksum that did not match — is the page the APK can be fetched from by
        // hand. Offered only while one of those is on screen, never as decoration.
        if (offered || update.error != null) {
            action(Icons.Filled.Share, "Open the release page", onClick = onReleasePage)
        }
        toggle(
            icon = Icons.Filled.Notifications,
            title = "Tell me about new versions",
            subtitle = "A banner at the top of the app when a newer build is published. The rows " +
                "above still offer it when this is off.",
            checked = state.settings.updateNotices,
            onChange = { onSettings(state.settings.copy(updateNotices = it)) },
        )
        state.hello?.let { hello ->
            detail(
                Icons.Filled.Build,
                "IDE",
                listOf(hello.ideName, hello.pluginVersion).filter { it.isNotBlank() }.joinToString(" "),
            )
            detail(Icons.Filled.Call, "Protocol", "v${hello.protocolVersion}")
            // What the machine says it can do — the honest source for which surfaces exist.
            detail(
                Icons.Filled.CheckCircle,
                "This machine can",
                hello.capabilities.sorted().joinToString(", ").ifBlank { "nothing it named" },
            )
            hello.servedByOtherIde?.let { detail(Icons.Filled.Person, "Served by", it) }
        }
    }
}

@Composable
private fun DiagnosticsSection(state: DeckState) {
    // Hoisted for the same reason the Machine section hoists it: the rows are built outside
    // composition and the reader's clock is a composition-local read.
    val now = LocalNow.current()
    Section("Diagnostics") {
        detail(
            Icons.Filled.Call,
            "Connection",
            when (val link = state.link) {
                Link.Live -> "Connected"
                Link.Connecting -> "Connecting"
                Link.Offline -> "This phone is offline"
                is Link.Stale -> link.reason
                is Link.Repair -> link.reason
            },
        )
        // Which address actually carried a call, as opposed to which one is remembered.
        // The machine section's "Address" is the phone's *preference*; this is the answer, and
        // the gap between the two is the whole of MP-07: a user who reads "cannot reach your
        // machine" needs to know it is dialling a LAN address from the train. Named only once
        // something has answered — "none yet" is a real state and the sentence says so.
        detail(
            Icons.Filled.Place,
            "Last answered by",
            state.lastGood
                ?.let { "${it.host} · ${Times.clock(it.atWallClockMs, now)} · ${it.reach.phrase}" }
                ?: "Nothing has answered since this app started",
        )
        detail(
            Icons.AutoMirrored.Filled.List,
            "Conversations known",
            (state.snapshot?.rows?.size ?: 0).toString(),
        )
        state.snapshot?.usageLine?.takeIf { it.isNotBlank() }?.let {
            detail(Icons.Filled.DateRange, "Plan usage", it)
        }
    }
}

/**
 * What an address is worth away from the desk, in the words a reader can act on.
 *
 * The enum's own names are about routing; these are about the user's next move. Deliberately
 * not "private"/"public" — a Tailscale address *is* private by RFC 6598 and travels anywhere,
 * which is the one case where the technical word would send someone to fix the wrong thing.
 */
private val HostReach.phrase: String
    get() = when (this) {
        HostReach.LOOPBACK -> "this device"
        HostReach.LAN -> "its own network only"
        HostReach.OVERLAY -> "reachable anywhere on your overlay"
        HostReach.PUBLIC -> "reachable from anywhere"
    }

// ---- pieces ---------------------------------------------------------------------------

/** Android's minimum touch target, held explicitly on every row that takes a gesture. */
private val MIN_TARGET = 48.dp

/** Where a `ListItem`'s text starts: 16 dp of padding, a 24 dp icon, 16 dp more. */
private val TEXT_INSET = 56.dp

/** Which glyph names a notification, rather than three rows carrying the same bell. */
private val NotifyTrigger.icon: ImageVector
    get() = when (this) {
        NotifyTrigger.NEEDS_YOU -> Icons.Filled.Person
        NotifyTrigger.FAILED -> Icons.Filled.Warning
        NotifyTrigger.FINISHED -> Icons.Filled.CheckCircle
    }

/**
 * The rows of one grouped card, collected as plain Kotlin before any of them is composed.
 *
 * The dividers go *between* rows, so a row has to know whether it is the first — and a counter
 * kept in composition keeps counting when the section's body recomposes on its own. Building
 * the list first makes the index a fact about the list rather than about the recomposition.
 */
private class SectionRows {
    val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }

    fun detail(icon: ImageVector, title: String, value: String) = row { DetailRow(icon, title, value) }

    fun action(
        icon: ImageVector,
        title: String,
        chevron: Boolean = true,
        destructive: Boolean = false,
        onClick: () -> Unit,
    ) = row { ActionRow(icon, title, chevron, destructive, onClick) }

    fun toggle(
        icon: ImageVector,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) = row { ToggleRow(icon, title, subtitle, checked, onChange) }
}

@Composable
private fun Section(title: String, content: SectionRows.() -> Unit) {
    val rows = SectionRows().apply(content).rows
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 6.dp),
        )
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            Modifier.padding(start = TEXT_INSET),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    row()
                }
            }
        }
    }
}

/**
 * A `ListItem` paints its own container, and its default is `surface` — which would erase the
 * tonal card the rows are grouped inside.
 */
@Composable
private fun rowColors(headline: Color = MaterialTheme.colorScheme.onSurface) =
    ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        headlineColor = headline,
    )

/** Decoration: a described icon would be a second node in a row whose name is its headline. */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
}

/** A fact. Nothing merges an unclickable row, so two Texts would announce twice. */
@Composable
private fun DetailRow(icon: ImageVector, title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { RowIcon(icon) },
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET).semantics(mergeDescendants = true) {},
    )
}

/** A row that does something. 48 dp minimum, and it announces itself once, as a button. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    chevron: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val trailing: (@Composable () -> Unit)? =
        if (chevron) {
            { RowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight) }
        } else {
            null
        }
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { RowIcon(icon, if (destructive) error else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = trailing,
        colors = rowColors(headline = if (destructive) error else MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.heightIn(min = MIN_TARGET).clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { RowIcon(icon) },
        // `onCheckedChange = null` keeps the switch out of the focus order: the row is the
        // control, so it is a 48 dp target rather than a 32 dp bullseye, and it announces once.
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET).clickable(role = Role.Switch) { onChange(!checked) },
    )
}

/** A row whose value is a control the user operates in place, so nothing here merges it away. */
@Composable
private fun ControlRow(icon: ImageVector, title: String, control: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { RowIcon(icon) },
        trailingContent = control,
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET),
    )
}

@Composable
private fun MachineRow(machine: PairedMachine, active: Boolean, onSelect: () -> Unit) {
    val trailing: (@Composable () -> Unit)? =
        if (active) {
            {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Showing this machine",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            null
        }
    ListItem(
        headlineContent = { Text(machine.machineName.ifBlank { "(unnamed)" }) },
        supportingContent = {
            Text(machine.hosts.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { RowIcon(Icons.Filled.Home) },
        trailingContent = trailing,
        colors = rowColors(),
        modifier = Modifier.heightIn(min = MIN_TARGET).clickable(role = Role.Button, onClick = onSelect),
    )
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
