package dev.agentdeck.companion.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileSendRequest
import com.github.claudeagents.core.mobile.MobileStopRequest
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.SecureStore
import kotlin.concurrent.thread

/**
 * Reply and Stop, from the shade.
 *
 * Runs with no activity and no view model, so it talks to the bridge directly — the pairing is
 * in [SecureStore] and that is all a call needs. Two rules it inherits from the composer,
 * because breaking either here would break them everywhere the user can reach:
 *
 * 1. **A refused send keeps the text.** It goes back into the conversation's draft, so opening
 *    the app finds it in the composer rather than nowhere. A shade reply that vanished on
 *    failure would be indistinguishable from one that was delivered.
 * 2. **The machine's own sentence is what is shown.** The re-posted notification carries the
 *    refusal verbatim rather than this app's guess at what went wrong.
 */
class NotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotBlank() } ?: return
        val vendor = AgentVendor.entries
            .firstOrNull { it.name == intent.getStringExtra(EXTRA_VENDOR) } ?: AgentVendor.CLAUDE
        val project = intent.getStringExtra(EXTRA_PROJECT).orEmpty()
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(DeckNotifications.REPLY_KEY)?.toString()?.trim()

        val app = context.applicationContext
        // The row goes as soon as the gesture is understood: leaving "Needs you" on the shade
        // while the send is in flight reads as a reply that did not take.
        NotificationManagerCompat.from(app).cancel(DeckNotifications.idOf(key))

        val pending = goAsync()
        thread(name = "deck-notify-action") {
            try {
                val store = SecureStore(app)
                val machine = store.paired() ?: return@thread
                val client = BridgeClient(machine)
                when (intent.action) {
                    ACTION_STOP -> client.stop(MobileStopRequest(key))
                    ACTION_REPLY -> {
                        if (reply.isNullOrBlank()) return@thread
                        runCatching {
                            client.send(
                                MobileSendRequest(
                                    key = key,
                                    projectPath = project,
                                    prompt = reply,
                                    vendor = vendor,
                                    model = null,
                                    effort = null,
                                    permissionMode = null,
                                    newChat = false,
                                ),
                            )
                        }.onFailure { error ->
                            keepDraft(store, machine.id, key, reply)
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "A shade action for $key did not reach the machine", error)
            } finally {
                pending.finish()
            }
        }
    }

    /** Merged into whatever the composer already held, never replacing it. */
    private fun keepDraft(store: SecureStore, machineId: String, key: String, text: String) {
        val drafts = store.drafts(machineId)
        val existing = drafts[key].orEmpty()
        val merged = if (existing.isBlank()) text else "$existing\n$text"
        store.saveDrafts(machineId, drafts + (key to merged))
    }

    companion object {
        const val ACTION_REPLY = "dev.agentdeck.companion.REPLY"
        const val ACTION_STOP = "dev.agentdeck.companion.STOP"
        const val EXTRA_KEY = "key"
        const val EXTRA_VENDOR = "vendor"
        const val EXTRA_PROJECT = "project"
        private const val TAG = "AgentDeck"
    }
}
