package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileRunSelection
import dev.agentdeck.companion.data.ComposerPicks

/**
 * Model, effort, mode, Fast and Thinking for the next send, as one line that opens them all.
 *
 * Five pills in a sideways-scrolling row competed with the composer for its one line and hid
 * whichever pills scrolled off (PLAN-MOBILE-REDESIGN M2, "compact run-choice summary and
 * complete options sheet"). The line still spells every value the send will name — nothing is
 * collapsed into a count — and the sheet holds the same selectors New chat and Schedule draw.
 *
 * Only a machine advertising [MobileProtocol.Capability.EFFORT] says what the desk would send;
 * against an older one a "Default" value would misname the chat's model, so the line is absent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerRunPills(
    hello: MobileHello?,
    vendor: AgentVendor,
    selection: MobileRunSelection,
    onOpen: () -> Unit,
) {
    if (!RunChoiceSummary.offered(hello)) return
    val summary = RunChoiceSummary.of(hello, vendor, selection)
    if (summary.isEmpty()) return
    Button(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .padding(bottom = 8.dp)
            .heightIn(min = 48.dp)
            .testTag("composer-run-pills")
            .semantics {
                role = Role.Button
                contentDescription = "Run settings: $summary"
            },
    ) {
        Text(
            summary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The sheet [ComposerRunPills] opens. Drawn by the screen, not inside the line: the line shows
 * only while a draft exists, and a typed `/model` opens this sheet as it empties the composer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunSettingsSheet(
    hello: MobileHello?,
    vendor: AgentVendor,
    selection: MobileRunSelection,
    onPick: (ComposerPicks.Field, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (hello == null) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                .testTag("run-settings-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Run settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "For this chat's next message.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelSelector(hello, vendor, selection.model, { onPick(ComposerPicks.Field.MODEL, it) })
            EffortSelector(hello, vendor, selection.effort, { onPick(ComposerPicks.Field.EFFORT, it) })
            ModeSelector(hello, vendor, selection.permissionMode, { onPick(ComposerPicks.Field.MODE, it) })
            RunToggleSelector(hello, vendor, "Fast", selection.fastMode, { onPick(ComposerPicks.Field.FAST, it?.toString()) }, vendors = RunOptionRows.FAST_VENDORS)
            RunToggleSelector(hello, vendor, "Thinking", selection.thinking, { onPick(ComposerPicks.Field.THINKING, it?.toString()) })
        }
    }
}

/**
 * The one line [ComposerRunPills] draws: each offered field's current row label, in the sheet's
 * order. A toggle left on the desk's default says nothing — it is not a choice the send makes.
 */
object RunChoiceSummary {
    /** Only a machine advertising `effort` says what the desk would send; see [ComposerRunPills]. */
    fun offered(hello: MobileHello?): Boolean =
        hello != null && MobileProtocol.Capability.EFFORT in hello.capabilities

    fun of(hello: MobileHello?, vendor: AgentVendor, selection: MobileRunSelection): String {
        fun <T> label(rows: List<SelectorOption<T>>, value: T, default: String?): String? {
            if (rows.isEmpty()) return null
            val row = rows.firstOrNull { it.value == value } ?: return "$value"
            return if (row.value == null) default else row.label
        }
        val toggles = RunOptionRows.togglesOffered(hello)
        fun toggle(name: String, on: Boolean?, vendors: Set<AgentVendor>): String? = when {
            !toggles || vendor !in vendors || on == null -> null
            on -> name
            else -> "$name off"
        }
        return listOfNotNull(
            label(ModelRows.of(hello, vendor, selection.model), selection.model, "Default model"),
            label(RunOptionRows.effort(hello, vendor, selection.effort), selection.effort, RunOptionRows.DEFAULT_EFFORT),
            label(RunOptionRows.mode(hello, vendor, selection.permissionMode), selection.permissionMode, "Default mode"),
            toggle("Fast", selection.fastMode, RunOptionRows.FAST_VENDORS),
            toggle("Thinking", selection.thinking, setOf(AgentVendor.CLAUDE)),
        ).joinToString(" · ")
    }
}
