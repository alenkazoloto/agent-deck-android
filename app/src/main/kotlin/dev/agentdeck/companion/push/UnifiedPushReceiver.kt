package dev.agentdeck.companion.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.claudeagents.core.mobile.MobilePush
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.SecureStore
import java.util.concurrent.Executors

/**
 * Everything a distributor says to this app.
 *
 * **Why `goAsync`.** Two of the four branches do network I/O — publishing a new endpoint to the
 * paired machine, and acknowledging a delivered message — and a `BroadcastReceiver`'s
 * `onReceive` runs on the main thread with a ten-second budget it may not block. `goAsync`
 * buys the process a worker; the `finish()` is in a `finally` because a receiver that never
 * finishes is an ANR the user sees as the whole phone stuttering.
 *
 * **Why an exported receiver is safe here.** Any app can broadcast a `MESSAGE` at this class.
 * Nothing acts on one that does not decrypt under this device's own key, and AES-GCM is what
 * decides that — a forged push is an authentication failure, not a trusted-sender question. The
 * token check below is a cheap first pass, not the guarantee.
 */
class UnifiedPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        WORKER.execute {
            try {
                handle(app, intent)
            } catch (failure: Throwable) {
                Log.w(TAG, "A push broadcast could not be handled: ${intent.action}", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val store = SecureStore(context)
        val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)
        // A registration this app did not ask for — a leftover from a previous install, or
        // another app's token misrouted — must not overwrite the endpoint in use.
        if (token != null && token != store.pushInstanceToken()) {
            Log.i(TAG, "Ignored a push broadcast for a token this install did not mint")
            return
        }
        when (intent.action) {
            UnifiedPush.ACTION_NEW_ENDPOINT -> {
                val endpoint = intent.getStringExtra(UnifiedPush.EXTRA_ENDPOINT).orEmpty()
                if (endpoint.isBlank()) return
                PushRegistration.publish(context, endpoint)
            }

            UnifiedPush.ACTION_MESSAGE -> deliver(context, store, intent)

            // The distributor has revoked this app's registration — the user removed it from
            // that app's own list, or the relay was reset. The endpoint is dropped so nothing
            // reports a transport that is gone; the *machine* is left to discover it from its
            // own 404, because this broadcast may arrive with no network to tell it on.
            UnifiedPush.ACTION_UNREGISTERED -> store.savePushEndpoint(null)

            UnifiedPush.ACTION_REGISTRATION_FAILED -> {
                store.savePushEndpoint(null)
                Log.w(
                    TAG,
                    "The push distributor refused this app: " +
                        intent.getStringExtra(UnifiedPush.EXTRA_REASON).orEmpty().ifBlank { "no reason given" },
                )
            }
        }
    }

    /**
     * One delivered message: decrypt, show, acknowledge.
     *
     * The acknowledgement is not optional and is deliberately last. A distributor may redeliver
     * anything unacknowledged inside thirty seconds, so a branch that returned early — an
     * undecryptable body, a trigger the reader switched off — without acknowledging would turn
     * one push into one every half minute for as long as the relay kept trying.
     */
    private fun deliver(context: Context, store: SecureStore, intent: Intent) {
        val body = intent.getByteArrayExtra(UnifiedPush.EXTRA_BYTES_MESSAGE)
        try {
            val identity = store.pushIdentity() ?: return
            val plaintext = body?.let { WebPushKeys.decrypt(identity, it) } ?: return
            val payload = MobilePush.Payload.fromJson(
                MobileProtocol.parseObject(plaintext.decodeToString()),
            ) ?: return
            PushRegistration.show(context, payload)
        } finally {
            val id = intent.getStringExtra(UnifiedPush.EXTRA_MESSAGE_ID)
            val distributor = store.pushDistributor()
            if (id != null && distributor != null) {
                UnifiedPush.acknowledge(context, distributor, store.pushInstanceToken(), id)
            }
        }
    }

    private companion object {
        const val TAG = "AgentDeck"

        /**
         * Shared and single-threaded: two pushes arriving together must not race each other
         * into the notification manager, and a receiver-scoped pool would be created and
         * discarded per broadcast.
         */
        val WORKER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-deck-push").apply { isDaemon = true }
        }
    }
}
