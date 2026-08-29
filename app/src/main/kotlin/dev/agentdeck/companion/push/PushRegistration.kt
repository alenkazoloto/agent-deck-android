package dev.agentdeck.companion.push

import android.content.Context
import android.util.Log
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobilePush
import dev.agentdeck.companion.data.BridgeClient
import dev.agentdeck.companion.data.NotifyTrigger
import dev.agentdeck.companion.data.SecureStore
import dev.agentdeck.companion.notify.Alert
import dev.agentdeck.companion.notify.AlertPlan
import dev.agentdeck.companion.notify.DeckNotifications

/**
 * Ties the phone's three push facts together: the distributor it picked, the endpoint that
 * distributor issued, and the machine that must be told about it.
 *
 * **Why an endpoint is not enough on its own.** The relay only forwards; the thing that decides
 * *to* send is the paired IDE, and it cannot until it has this phone's endpoint and public key.
 * So every path that produces or invalidates an endpoint ends here, and every one of them talks
 * to the machine — a registration the machine never heard about is a phone that has done all the
 * work of arranging to be woken and will not be.
 *
 * All methods block on the network and are called from a receiver's `goAsync` worker or from a
 * view-model dispatcher; none may run on the main thread.
 */
object PushRegistration {

    /**
     * Asks the chosen distributor for an endpoint. The answer arrives later, at
     * [UnifiedPushReceiver], which is where [publish] then runs.
     */
    fun requestEndpoint(context: Context) {
        val store = SecureStore(context)
        val distributor = store.pushDistributor() ?: return
        // Minted here rather than at the receiver: the token is what ties a NEW_ENDPOINT back
        // to the registration that asked for it, so it has to exist before the ask.
        UnifiedPush.register(context, distributor, store.pushInstanceToken(), vapidKey(context, store))
    }

    /**
     * The machine's RFC 8292 application-server key, refreshed from `/v1/hello` and remembered.
     *
     * Asked here, at the one act that arms a registration, rather than left to whenever the
     * Settings screen last fetched a hello: a distributor that requires VAPID refuses a
     * `REGISTER` that names no application server, so this key is an *input to the ask* and
     * not a screen's cached fact. The stored copy is the fallback for the ordinary case where
     * the machine is asleep or off this network — a key from the last hello still registers,
     * and the machine signs with the same one until its own settings are reset.
     */
    private fun vapidKey(context: Context, store: SecureStore): String? {
        val machine = store.paired() ?: return store.vapidPublicKey()
        val fetched = runCatching { BridgeClient(machine).hello().vapidPublicKey }
            .onFailure { Log.i(TAG, "The machine did not answer /v1/hello before registering push", it) }
            .getOrNull()
        if (fetched != null) store.saveVapidPublicKey(fetched)
        return fetched ?: store.vapidPublicKey()
    }

    /**
     * Re-registers when the machine has started naming a *different* application server.
     *
     * A distributor binds the endpoint it issued to the key the registration carried, so a
     * machine whose keypair was replaced — settings restored, `claudeAgentsMobile.xml`
     * removed — would go on posting tokens the distributor rejects, with no error anywhere
     * this app can see. Silent when the key is unchanged or absent, which is every ordinary
     * `/v1/hello`.
     */
    fun adoptVapidKey(context: Context, key: String?) {
        if (key == null || !MobilePush.isVapidPublicKey(key)) return
        val store = SecureStore(context)
        if (key == store.vapidPublicKey()) return
        store.saveVapidPublicKey(key)
        val distributor = store.pushDistributor() ?: return
        UnifiedPush.register(context, distributor, store.pushInstanceToken(), key)
    }

    /**
     * Hands the endpoint and this phone's public key to the paired machine.
     *
     * Returns the machine's own refusal sentence, or null when it accepted. A refusal is worth
     * carrying rather than logging: the commonest one is "push is switched off on that machine",
     * which is a thing the user fixes at their desk and would otherwise experience as silence.
     */
    fun publish(context: Context, endpoint: String): String? {
        val store = SecureStore(context)
        val machine = store.paired() ?: return "This phone is not paired with a machine."
        val identity = store.orMintPushIdentity()
        store.savePushEndpoint(endpoint)
        return runCatching {
            BridgeClient(machine).registerPush(
                MobilePush.Subscription(endpoint, identity.publicKeyB64, identity.authSecretB64),
            )
            null
        }.getOrElse { failure ->
            Log.w(TAG, "Could not register this phone's push endpoint", failure)
            failure.message ?: "The machine could not be reached."
        }
    }

    /**
     * Stops push at both ends, in the order that cannot leave a machine posting into the void:
     * the machine forgets the subscription first, and only then is the distributor released.
     */
    fun disable(context: Context) {
        val store = SecureStore(context)
        store.paired()?.let { machine ->
            runCatching { BridgeClient(machine).unregisterPush() }
                .onFailure { Log.w(TAG, "The machine was not told push is off", it) }
        }
        store.pushDistributor()?.let { UnifiedPush.unregister(context, it, store.pushInstanceToken()) }
        store.savePushDistributor(null)
    }

    /**
     * Turns one delivered push into whatever the shade should say.
     *
     * The payload carries a count and opaque keys and nothing else ([MobilePush]), so the words
     * come from the fleet snapshot this phone already cached — which is exactly the promise the
     * payload's shape is making: the relay learned nothing a title would have told it.
     *
     * A key the cache has never seen still produces a row. It is the *new* conversation case —
     * an agent started while the app was closed is precisely what a push is for — and a row
     * that named nothing would be a buzz with no way to act on it.
     */
    fun show(context: Context, payload: MobilePush.Payload) {
        val store = SecureStore(context)
        val settings = store.settings()
        val trigger = NotifyTrigger.of(payload.trigger)
        if (!settings.notifies(trigger)) return
        val snapshot = store.paired()?.let { store.cachedSnapshot(it.id) }
        val alerts = payload.keys.map { key -> alert(key, trigger, snapshot) }
        if (alerts.isEmpty()) return
        DeckNotifications.apply(
            context,
            AlertPlan(
                post = alerts,
                cancel = emptyList(),
                // The shade's own group summary counts what this push says is waiting, so a
                // phone woken about two of five still reads "5 agents need you".
                seen = alerts.associate { it.key to trigger.id },
            ),
        )
    }

    private fun alert(key: String, trigger: NotifyTrigger, snapshot: MobileFleetSnapshot?): Alert {
        val row = snapshot?.rows?.firstOrNull { it.key == key }
        return Alert(
            key = key,
            trigger = trigger,
            title = row?.title?.takeIf { it.isNotBlank() } ?: trigger.title,
            body = row?.liveLine?.takeIf { it.isNotBlank() }
                ?: row?.projectName?.takeIf { it.isNotBlank() }
                ?: trigger.description,
            vendor = row?.vendor ?: AgentVendor.CLAUDE,
            projectPath = row?.projectPath.orEmpty(),
        )
    }

    private const val TAG = "AgentDeck"
}
