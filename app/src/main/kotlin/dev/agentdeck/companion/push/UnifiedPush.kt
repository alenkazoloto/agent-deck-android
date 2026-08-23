package dev.agentdeck.companion.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * The UnifiedPush distributor contract, spoken directly.
 *
 * **Why by hand and not through `org.unifiedpush.android:connector`.** `mobile/`'s dependency
 * rule (`ArchitectureRulesTest` rule 23) asks a third-party library for ten thousand stars or
 * fifty thousand downloads; the connector is a few hundred. What it wraps is this file — a
 * handful of broadcast actions and six string extras, specified at
 * [unifiedpush.org/developers/spec/android](https://unifiedpush.org/developers/spec/android/)
 * and stable across the 2.x and 3.x distributors in the wild. The encryption it would also
 * bring is already ours on both sides ([WebPushKeys], and the plugin's `WebPushEncrypt`).
 *
 * **Discovery is a `PackageManager` query, not the `unifiedpush://link` selector.** Both are in
 * the spec. The query is what lets the app show the distributors *by name inside its own
 * Settings*, next to the switch that explains what one is for — and the deep-link selector
 * hands that decision to a system dialog with no room to say why it is being asked.
 *
 * **The reply receiver is exported, and that is safe by construction.** Any app on the phone can
 * broadcast a `MESSAGE` at it. Nothing acts on a message that does not decrypt under this
 * device's own key ([WebPushKeys.decrypt] answers null), so a forged one produces a dropped
 * frame and nothing else — the payload is authenticated by AES-GCM, not by the sender's
 * identity.
 */
object UnifiedPush {

    // ---- what this app sends -----------------------------------------------------------

    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

    // ---- what a distributor sends back --------------------------------------------------

    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

    const val EXTRA_TOKEN = "token"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_MESSAGE_ID = "id"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    const val EXTRA_APPLICATION = "application"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_REASON = "reason"
    const val EXTRA_PENDING_INTENT = "pi"

    /** One installed distributor, with the label to show a user who has to choose between them. */
    data class Distributor(val packageName: String, val label: String)

    /**
     * Every app on this phone that can carry a push, in package order.
     *
     * Empty is the ordinary case, not an error: a phone with no distributor installed simply
     * cannot be reached while this app is closed, and the Settings screen says so with a link
     * rather than pretending the feature is broken.
     */
    fun distributors(context: Context): List<Distributor> = runCatching {
        val manager = context.packageManager
        manager.queryBroadcastReceivers(Intent(ACTION_REGISTER), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filterNot { it == context.packageName }
            .sorted()
            .map { name ->
                val label = runCatching {
                    manager.getApplicationLabel(manager.getApplicationInfo(name, 0)).toString()
                }.getOrDefault(name)
                Distributor(name, label)
            }
    }.getOrElse {
        Log.w(TAG, "Could not list push distributors", it)
        emptyList()
    }

    /**
     * Asks [distributor] for an endpoint. The answer arrives asynchronously as
     * [ACTION_NEW_ENDPOINT] on [UnifiedPushReceiver] — there is no synchronous form.
     *
     * [EXTRA_MESSAGE] is the sentence the distributor shows in *its* own list of registered
     * apps. It is the only string this app hands a third party, and it deliberately names the
     * app rather than the machine: a distributor's UI is not the place a paired laptop's
     * hostname should turn up.
     */
    fun register(context: Context, distributor: String, token: String) {
        val intent = Intent(ACTION_REGISTER).apply {
            `package` = distributor
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_APPLICATION, context.packageName)
            putExtra(EXTRA_MESSAGE, "Alerts when an agent needs you")
            // SDK<34 distributors resolve the caller's identity from a PendingIntent, because a
            // broadcast otherwise arrives anonymous. It is deliberately a no-op intent to this
            // app's own package: the distributor reads its creator, never sends it.
            putExtra(EXTRA_PENDING_INTENT, identityIntent(context))
        }
        send(context, intent, "register with $distributor")
    }

    fun unregister(context: Context, distributor: String, token: String) {
        send(
            context,
            Intent(ACTION_UNREGISTER).apply {
                `package` = distributor
                putExtra(EXTRA_TOKEN, token)
            },
            "unregister from $distributor",
        )
    }

    /**
     * Acknowledges one delivered message.
     *
     * Not optional: a distributor may redeliver anything unacknowledged within thirty seconds,
     * so skipping this turns one blocked agent into a notification every half minute — the
     * behaviour that gets an app silenced.
     */
    fun acknowledge(context: Context, distributor: String, token: String, messageId: String) {
        send(
            context,
            Intent(ACTION_MESSAGE_ACK).apply {
                `package` = distributor
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_MESSAGE_ID, messageId)
            },
            "acknowledge a push",
        )
    }

    private fun identityIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, UnifiedPushReceiver::class.java).setAction(ACTION_IDENTITY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Every outward broadcast, with the one failure that matters caught by name: a distributor
     * the user uninstalled while the app was closed is [PackageManager.NameNotFoundException]'s
     * shape, and it must leave the app usable rather than crash a receiver.
     */
    private fun send(context: Context, intent: Intent, what: String) {
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "Could not $what", it) }
    }

    /** Never handled; it exists only so a `PendingIntent` has something to name. */
    internal const val ACTION_IDENTITY = "dev.agentdeck.companion.PUSH_IDENTITY"

    /** Android 14 tightened implicit-broadcast delivery; the actions above are all explicit. */
    internal val NEEDS_EXPLICIT_PACKAGE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private const val TAG = "AgentDeck"
}
