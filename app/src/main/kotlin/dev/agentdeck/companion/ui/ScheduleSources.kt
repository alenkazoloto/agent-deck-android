package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import com.github.claudeagents.core.mobile.MobileScheduleDependencySource

/** What one project's prompts can wait for, as the machine last listed it; [error] is a sentence for the reader. */
data class ScheduleSourcesState(
    val project: String,
    val loading: Boolean = true,
    val sources: List<MobileScheduleDependencySource> = emptyList(),
    val error: String? = null,
)

/** The dialogs' door to "After sessions finish": the last listing, and a way to ask for another project's. */
class ScheduleSourcesOffer(
    val state: ScheduleSourcesState?,
    val onLoad: (project: String) -> Unit,
)

/**
 * The desk dialog's "Schedule after sessions…" list for [project], read when the choice opens
 * rather than with every dialog: most prompts wait for nothing. A listing for another project
 * is not shown, because its ids belong to that project and the machine would refuse them.
 */
@Composable
internal fun ScheduleSourcesPicker(
    project: String,
    offer: ScheduleSourcesOffer,
    selected: List<MobileScheduleDependencySelection>,
    onChange: (List<MobileScheduleDependencySelection>) -> Unit,
) {
    LaunchedEffect(project) { offer.onLoad(project) }
    val state = offer.state?.takeIf { it.project == project }
    Column(Modifier.testTag("schedule-sources").heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        when {
            state == null || (state.loading && state.sources.isEmpty()) -> {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Text("Loading sessions…")
            }
            state.error != null -> {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { offer.onLoad(project) }) { Text("Retry") }
            }
            else -> ScheduleSourceChoices(state.sources, selected, onChange)
        }
    }
}
