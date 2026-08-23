package dev.agentdeck.companion.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.UpdateState

/**
 * The strip above every screen: one sentence, and at most the controls that act on it.
 *
 * Shared by the connection's row and the app's own update row so the two cannot drift into
 * different shapes, and out here rather than inside `MainActivity` because a banner declared
 * private to the activity is one no camera can reach — the update row's first pair of goldens
 * photographed the *screen behind it* and reported success.
 */
@Composable
internal fun Banner(
    text: String,
    container: Color,
    action: @Composable (() -> Unit)? = null,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = container,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            action?.invoke()
        }
    }
}

/**
 * The app's own "there is a newer one of me" row.
 *
 * Agent Deck is sideloaded: no store watches this app, so without this the only way anyone
 * learns a build has shipped is by opening Settings and pressing a button they have no reason
 * to press. It appears **only** while it changes a decision ([AppUpdate.bannerWorthy]), carries
 * the size before the tap that spends it, and has two ways out that are not the same thing: ✕
 * dismisses *this* version, and Settings › About holds the switch that stops the announcement
 * entirely — a dismissible notice's ✕ is not an off switch.
 *
 * [sdkInt] is a parameter rather than a read of [Build.VERSION] inside, so a golden can pin the
 * phone a published build is being judged against instead of inheriting the harness's.
 */
@Composable
fun UpdateBanner(
    update: UpdateState,
    notices: Boolean,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    if (!AppUpdate.bannerWorthy(update, notices, sdkInt)) return
    val release = update.release ?: return
    val downloading = update.downloadPercent != null
    val text = when {
        downloading -> "Downloading Agent Deck ${release.label} — ${update.downloadPercent}%"
        update.readyApk != null -> "Agent Deck ${release.label} is downloaded."
        else -> listOfNotNull(
            "Agent Deck ${release.label} is available",
            AppUpdate.size(release.sizeBytes).takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }
    Banner(text, MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                // A download already running has no second button to offer: the only thing left
                // to decide is whether to keep watching it.
                downloading -> Unit
                update.readyApk != null -> TextButton(onClick = onInstall) { Text("Install") }
                else -> TextButton(onClick = onUpdate) { Text("Update") }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Not now")
            }
        }
    }
}
