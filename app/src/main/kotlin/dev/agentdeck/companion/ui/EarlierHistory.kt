package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EarlierHistory(loading: Boolean, error: String?, expired: Boolean, onLoad: () -> Unit, onRefresh: () -> Unit, locating: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading && locating) Text("Locating earlier history…", style = MaterialTheme.typography.labelSmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        DeckIconButton(
            if (expired) "Refresh conversation history" else if (error != null) "Retry earlier messages" else if (locating) "Continue loading history" else "Load earlier messages",
            if (expired) Icons.Filled.Refresh else Icons.Filled.KeyboardArrowUp,
            if (expired) onRefresh else onLoad,
            enabled = !loading,
        )
    }
}
