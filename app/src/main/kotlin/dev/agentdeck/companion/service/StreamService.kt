package dev.agentdeck.companion.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.agentdeck.companion.LiveLink
import dev.agentdeck.companion.MainActivity
import dev.agentdeck.companion.Navigation
import dev.agentdeck.companion.R
import dev.agentdeck.companion.Screen
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.notify.DeckNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the process open while the app is in the background, so an agent that needs the user
 * can still say so.
 *
 * **Opt-in, and it says what it is holding.** The ongoing row names the machine and what is
 * waiting on it, because a permanent notification that reads "Agent Deck is running" tells the
 * user nothing they can act on and is the reason people force-stop apps. Nothing here opens a
 * second connection: [LiveLink] is process-scoped and already has one — this service exists so
 * Android keeps that process, and its notification is the price Android charges for it.
 */
class StreamService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val link = LiveLink.of(this)
        // A service started before the pairing is restored would sit on the shade with nothing
        // to watch; binding here is also what makes it survive a process restart by the system.
        if (link.machine == null) link.bind(SecureStore(this).paired())

        DeckNotifications.ensureChannels(this)
        startForegroundCompat(notification(link))

        watcher?.cancel()
        // The ongoing row restates what is waiting, so glancing at the shade answers the
        // question the app exists for without opening it.
        watcher = scope.launch {
            link.fleet.collectLatest { notifyManagerUpdate(notification(link)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                DeckNotifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(DeckNotifications.ONGOING_ID, notification)
        }
    }

    private fun notifyManagerUpdate(notification: Notification) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        runCatching { manager.notify(DeckNotifications.ONGOING_ID, notification) }
    }

    private fun notification(link: LiveLink): Notification {
        val machine = link.machine?.machineName?.ifBlank { "this machine" } ?: "this machine"
        val open = PendingIntent.getActivity(
            this,
            DeckNotifications.ONGOING_ID,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(Navigation.link(Screen.Fleet).orEmpty())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DeckNotifications.ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_deck)
            .setContentTitle("Watching $machine")
            .setContentText(link.waitingSummary() ?: "No agent is waiting on you.")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    companion object {
        /**
         * Starts or stops the service to match the setting. Called on every settings write and
         * once at startup, so the running state is reconciled from the setting rather than
         * from whatever the last gesture happened to be.
         */
        fun reconcile(context: Context, enabled: Boolean) {
            val intent = Intent(context, StreamService::class.java)
            runCatching {
                if (!enabled) {
                    context.stopService(intent)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
