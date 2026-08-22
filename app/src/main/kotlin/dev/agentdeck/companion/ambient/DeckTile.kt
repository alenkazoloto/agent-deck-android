package dev.agentdeck.companion.ambient

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import dev.agentdeck.companion.MainActivity
import dev.agentdeck.companion.Navigation
import dev.agentdeck.companion.R
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.SecureStore

/**
 * The same answer, one swipe from anywhere: a quick-settings tile whose subtitle is the waiting
 * count and whose tap opens the conversation at the top of it.
 *
 * Reads the cached snapshot for the same reason [DeckWidget] does — the system asks for this
 * whenever the shade opens, and that is not a moment to dial a machine.
 */
@RequiresApi(Build.VERSION_CODES.N)
class DeckTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val store = SecureStore(this)
        val machine = store.paired()
        val waiting = machine?.let { store.cachedSnapshot(it.id)?.badgeCount } ?: 0
        tile.state = if (machine == null) Tile.STATE_UNAVAILABLE
        else if (waiting > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_deck)
        tile.label = "Agent Deck"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                machine == null -> "Not paired"
                waiting == 0 -> "Nothing waiting"
                waiting == 1 -> "1 waiting"
                else -> "$waiting waiting"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(Navigation.link(Screen.Fleet).orEmpty())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    TILE_REQUEST,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TILE_REQUEST = 8
    }
}
