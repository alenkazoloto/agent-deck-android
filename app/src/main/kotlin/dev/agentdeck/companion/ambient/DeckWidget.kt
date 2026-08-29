package dev.agentdeck.companion.ambient

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.agentdeck.companion.MainActivity
import dev.agentdeck.companion.Navigation
import dev.agentdeck.companion.R
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.SecureStore

/**
 * What the machine is doing and the row to open, on the home screen. [ambientSummary] owns both
 * lines, so the tile cannot rank the counts differently.
 *
 * The cheapest way to stop being "another app to open": the answer to *is anything waiting on
 * me* is on the wallpaper, and tapping it lands on the conversation rather than on a list to
 * search. It reads the **cached** snapshot rather than fetching — a widget update runs on a
 * schedule the user did not choose, and spending a TLS handshake per tick to redraw a number
 * that a live app already writes to disk would drain the battery for nothing.
 */
class DeckWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, render(context)) }
    }

    companion object {

        /** Called whenever the snapshot changes, so the widget is not only as fresh as its tick. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, DeckWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = render(context)
            ids.forEach { id -> runCatching { manager.updateAppWidget(id, views) } }
        }

        private fun render(context: Context): RemoteViews {
            val store = SecureStore(context)
            val machine = store.paired()
            val snapshot = machine?.let { store.cachedSnapshot(it.id) }
            val summary = ambientSummary(snapshot, System.currentTimeMillis(), machine?.machineName)
            val top = topWaiting(snapshot)

            val views = RemoteViews(context.packageName, R.layout.widget_deck)
            views.setTextViewText(R.id.widget_count, summary.headline)
            views.setTextViewText(R.id.widget_top, summary.detail)
            // The tap lands on the row it is naming, not on a list the user then has to search.
            val screen = top?.let { Screen.Conversation(it.key, it.title, it.vendor, it.projectPath) }
                ?: Screen.Fleet
            views.setOnClickPendingIntent(R.id.widget_root, open(context, screen))
            return views
        }

        private fun open(context: Context, screen: Screen): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(Navigation.link(screen).orEmpty())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                WIDGET_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val WIDGET_REQUEST = 7
    }
}
