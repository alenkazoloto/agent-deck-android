package dev.agentdeck.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobilePush
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.github.claudeagents.core.reach.RouteMemory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.agentdeck.companion.push.WebPushKeys
import java.io.File

/**
 * Persist pairings and per-machine fleet, draft and transcript caches for disconnected use.
 * Use encrypted preferences when the keystore works; record private-preference fallback for Settings.
 */
class SecureStore(private val context: Context) {

    private val prefs: SharedPreferences
    val encrypted: Boolean

    init {
        var usedEncryption = true
        val store = runCatching {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "agent-deck-secure",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { error ->
            Log.w(TAG, "Keystore unavailable; falling back to private prefs", error)
            usedEncryption = false
            context.getSharedPreferences("agent-deck", Context.MODE_PRIVATE)
        }
        prefs = store
        encrypted = usedEncryption
        migrateSingleMachine()
    }

    // ---- pairings --------------------------------------------------------------------

    /** Every paired machine, in the order they were paired. */
    fun machines(): List<PairedMachine> {
        val raw = prefs.getString(KEY_MACHINES, null) ?: return emptyList()
        val array = runCatching { MobileProtocol.parseObject(raw)?.getAsJsonArray("machines") }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull {
            it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(PairedMachine::fromJson)
        }
    }

    /** The machine the app is showing, or null when nothing is paired. */
    fun paired(): PairedMachine? {
        val all = machines()
        val active = prefs.getString(KEY_ACTIVE, null)
        return all.firstOrNull { it.id == active } ?: all.firstOrNull()
    }

    /** Adds a pairing or replaces the one with the same [PairedMachine.id], and activates it. */
    fun save(machine: PairedMachine) {
        val kept = machines().filterNot { it.id == machine.id }
        writeMachines(kept + machine)
        prefs.edit().putString(KEY_ACTIVE, machine.id).apply()
    }

    fun activate(id: String) {
        if (machines().none { it.id == id }) return
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /**
     * Forgets one pairing and everything cached under it, then falls back to whichever
     * machine is left. Returns the machine now active, or null when that was the last one.
     */
    fun forget(id: String): PairedMachine? = synchronized(transcriptCacheLock) {
        val remaining = machines().filterNot { it.id == id }
        writeMachines(remaining)
        wipeCache(id)
        saveRouteMemory(routeMemory().forget(id))
        val next = remaining.firstOrNull()
        prefs.edit().apply {
            if (next == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, next.id)
        }.apply()
        next
    }

    /** Which route won and which failed, per pairing and network; a hint, so unreadable means empty. */
    fun routeMemory(): RouteMemory =
        RouteMemory.fromJson(prefs.getString(KEY_ROUTE_MEMORY, null)?.let(MobileProtocol::parseObject))

    fun saveRouteMemory(memory: RouteMemory) {
        prefs.edit().putString(KEY_ROUTE_MEMORY, memory.toJson().toString()).apply()
    }

    private fun writeMachines(machines: List<PairedMachine>) {
        val body = JsonObject().apply {
            addProperty("v", MobileProtocol.VERSION)
            add("machines", JsonArray().also { arr -> machines.forEach { arr.add(it.toJson()) } })
        }
        prefs.edit().putString(KEY_MACHINES, body.toString()).apply()
    }

    /** Migrate the former single pairing and caches once so updates preserve pairing and unsent drafts. */
    private fun migrateSingleMachine() {
        val legacy = prefs.getString(KEY_LEGACY_MACHINE, null) ?: return
        val machine = MobileProtocol.parseObject(legacy)?.let(PairedMachine::fromJson)
        prefs.edit().remove(KEY_LEGACY_MACHINE).apply()
        if (machine == null || machines().isNotEmpty()) return
        writeMachines(listOf(machine))
        prefs.edit().putString(KEY_ACTIVE, machine.id).apply()
        runCatching { File(context.filesDir, "last-fleet.json").renameTo(snapshotFile(machine.id)) }
        runCatching { File(context.filesDir, "drafts.json").renameTo(draftsFile(machine.id)) }
    }

    // ---- per-machine caches ----------------------------------------------------------

    /** Filenames come from a pairing token, so nothing but `[a-z0-9]` reaches the path. */
    private fun slug(id: String): String = id.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

    private fun snapshotFile(id: String) = File(context.filesDir, "fleet-${slug(id)}.json")

    private fun draftsFile(id: String) = File(context.filesDir, "drafts-${slug(id)}.json")

    private fun transcriptDir(id: String) = File(context.filesDir, "transcripts-${slug(id)}")

    private fun outgoingFile(id: String) = File(context.filesDir, "outgoing-${slug(id)}.json")

    private fun wipeCache(id: String) {
        prefs.edit().remove("reading-$id").remove("schedule-habit-$id").remove("schedule-chats-$id").apply()
        runCatching { snapshotFile(id).delete() }
        runCatching { draftsFile(id).delete() }
        runCatching { outgoingFile(id).delete() }
        runCatching { transcriptDir(id).deleteRecursively() }
    }

    /** Keep the machine's generatedAtMs verbatim: cache-write time must not make stale snapshots look fresh. */
    fun cacheSnapshot(id: String, snapshot: MobileFleetSnapshot) {
        runCatching { snapshotFile(id).writeText(snapshot.toJson().toString()) }
            .onFailure { Log.w(TAG, "Could not cache the fleet snapshot", it) }
    }

    fun cachedSnapshot(id: String): MobileFleetSnapshot? = runCatching {
        val file = snapshotFile(id)
        if (!file.isFile) return null
        MobileProtocol.parseObject(file.readText())?.let(MobileFleetSnapshot::fromJson)
    }.getOrNull()

    // ---- composer drafts -------------------------------------------------------------

    /**
     * Unsent composer text, keyed by conversation. Persisted rather than kept in the view
     * model alone so a draft survives process death as well as navigation — the plugin's
     * `ChatDrafts` rule, applied on the phone.
     */
    fun drafts(id: String): Map<String, String> = runCatching {
        val file = draftsFile(id)
        if (!file.isFile) return emptyMap()
        val obj = MobileProtocol.parseObject(file.readText()) ?: return emptyMap()
        obj.entrySet()
            .mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }
            .toMap()
    }.getOrElse { emptyMap() }

    fun saveDrafts(id: String, drafts: Map<String, String>) {
        runCatching {
            val obj = JsonObject()
            drafts.forEach { (k, v) -> if (v.isNotEmpty()) obj.addProperty(k, v) }
            draftsFile(id).writeText(obj.toString())
        }.onFailure { Log.w(TAG, "Could not persist drafts", it) }
    }

    // ---- the outgoing queue ------------------------------------------------------------

    /** Persist queued sends across Android process death, per machine so pairing switches cannot redirect prompts. */
    fun outgoing(id: String): OutgoingQueue = runCatching {
        val file = outgoingFile(id)
        if (!file.isFile) return OutgoingQueue()
        OutgoingQueue.fromJson(MobileProtocol.parseObject(file.readText()))
    }.getOrElse { OutgoingQueue() }

    fun saveOutgoing(id: String, queue: OutgoingQueue) {
        runCatching {
            if (queue.isEmpty) outgoingFile(id).delete()
            else outgoingFile(id).writeText(OutgoingQueue.toJson(queue).toString())
        }.onFailure { Log.w(TAG, "Could not persist the outgoing queue", it) }
    }

    fun readingPositions(id: String): ReadingPositions {
        val raw = prefs.getString("reading-$id", null)?.takeIf { it.length <= ReadingPositions.MAX_JSON }
        return ReadingPositions.fromJson(raw?.let { MobileProtocol.parseObject(it) })
    }

    fun saveReadingPositions(id: String, positions: ReadingPositions) {
        prefs.edit().putString("reading-$id", positions.toJson().toString()).apply()
    }

    /** Per machine: a remembered project path or account id names that machine's own. */
    fun scheduleHabit(id: String): ScheduleHabit {
        val raw = prefs.getString("schedule-habit-$id", null)?.takeIf { it.length <= ScheduleHabit.MAX_JSON }
        return ScheduleHabit.fromJson(raw?.let { MobileProtocol.parseObject(it) })
    }

    fun saveScheduleHabit(id: String, habit: ScheduleHabit) {
        prefs.edit().putString("schedule-habit-$id", habit.toJson().toString()).apply()
    }

    /** Per machine, keyed by chat: the desk's per-chat form, which wins over [scheduleHabit] in that chat. */
    fun scheduleChatForms(id: String): ScheduleChatForms {
        val raw = prefs.getString("schedule-chats-$id", null)?.takeIf { it.length <= ScheduleChatForms.MAX_JSON }
        return ScheduleChatForms.fromJson(raw?.let { MobileProtocol.parseObject(it) })
    }

    fun saveScheduleChatForms(id: String, forms: ScheduleChatForms) {
        prefs.edit().putString("schedule-chats-$id", forms.toJson().toString()).apply()
    }

    // ---- read transcripts ------------------------------------------------------------

    /** Cache recent reads per machine, trimmed by read time. Preserve generatedAtMs so cached pages never look live. */
    fun cacheTranscript(id: String, page: MobileTranscriptPage, shouldWrite: () -> Boolean = { true }) {
        val encoded = TranscriptCacheEncoding.encode(page) ?: return
        synchronized(transcriptCacheLock) {
            if (!shouldWrite() || machines().none { it.id == id }) return
            runCatching {
                val dir = transcriptDir(id).apply { mkdirs() }
                File(dir, "${slug(page.key)}.json").writeText(encoded)
                val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                files.drop(TRANSCRIPT_CACHE_SIZE).forEach { it.delete() }
            }.onFailure { Log.w(TAG, "Could not cache a transcript", it) }
        }
    }

    fun cachedTranscript(id: String, key: String): MobileTranscriptPage? = runCatching {
        val raw = synchronized(transcriptCacheLock) {
            val file = File(transcriptDir(id), "${slug(key)}.json")
            if (!file.isFile || file.length() > TranscriptCacheEncoding.MAX_BYTES) return null
            file.setLastModified(System.currentTimeMillis())
            file.readText()
        }
        MobileProtocol.parseObject(raw)?.let(MobileTranscriptPage::fromJson)
    }.getOrNull()

    // ---- app settings and the last destination ---------------------------------------

    fun settings(): AppSettings = runCatching {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        MobileProtocol.parseObject(raw)?.let(AppSettings::fromJson) ?: AppSettings()
    }.getOrElse { AppSettings() }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_SETTINGS, settings.toJson().toString()).apply()
    }

    /**
     * Where the app was, so a cold start after process death returns there rather than to the
     * fleet. Written on every navigation, which is cheap: one small string into prefs.
     */
    fun saveScreen(json: JsonObject?) {
        prefs.edit().apply {
            if (json == null) remove(KEY_SCREEN) else putString(KEY_SCREEN, json.toString())
        }.apply()
    }

    fun screen(): JsonObject? = prefs.getString(KEY_SCREEN, null)?.let(MobileProtocol::parseObject)

    // ---- self-update ------------------------------------------------------------------

    /** Keep update-check state outside AppSettings; automatic checks must not rewrite preferences being edited. */
    fun updateCheckedAt(): Long = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0)

    fun saveUpdateCheckedAt(ms: Long) {
        prefs.edit().putLong(KEY_UPDATE_CHECKED_AT, ms).apply()
    }

    fun updateDismissed(): Long = prefs.getLong(KEY_UPDATE_DISMISSED, 0)

    fun saveUpdateDismissed(versionCode: Long) {
        prefs.edit().putLong(KEY_UPDATE_DISMISSED, versionCode).apply()
    }

    // ---- push ---------------------------------------------------------------------------

    /**
     * Push identity is per app install and shares credential storage with pairing tokens, not AppSettings.
     * Write all key parts atomically: a half-written identity silently makes registered push unreadable.
     */
    fun pushIdentity(): WebPushKeys.Identity? = WebPushKeys.restore(
        prefs.getString(KEY_PUSH_PRIVATE, null),
        prefs.getString(KEY_PUSH_PUBLIC, null),
        prefs.getString(KEY_PUSH_AUTH, null),
    )

    fun savePushIdentity(identity: WebPushKeys.Identity) {
        prefs.edit()
            .putString(KEY_PUSH_PRIVATE, identity.privateB64)
            .putString(KEY_PUSH_PUBLIC, identity.publicSpkiB64)
            .putString(KEY_PUSH_AUTH, identity.authSecretB64)
            .apply()
    }

    /** The identity this phone already has, or a fresh one saved on the spot. */
    fun orMintPushIdentity(): WebPushKeys.Identity =
        pushIdentity() ?: WebPushKeys.generate().also(::savePushIdentity)

    /** The distributor package the user picked; null until they have. */
    fun pushDistributor(): String? = prefs.getString(KEY_PUSH_DISTRIBUTOR, null)?.takeIf { it.isNotBlank() }

    /** The per-registration token this app gave the distributor; minted on first use. */
    fun pushInstanceToken(): String = prefs.getString(KEY_PUSH_TOKEN, null)
        ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_PUSH_TOKEN, it).apply()
        }

    /** The endpoint the distributor handed back, or null while none has arrived. */
    fun pushEndpoint(): String? = prefs.getString(KEY_PUSH_ENDPOINT, null)?.takeIf { it.isNotBlank() }

    fun savePushDistributor(packageName: String?) {
        prefs.edit().apply {
            if (packageName.isNullOrBlank()) remove(KEY_PUSH_DISTRIBUTOR)
            else putString(KEY_PUSH_DISTRIBUTOR, packageName)
            // A new distributor mints a new endpoint, so the old one is not merely stale — it
            // belongs to a relay this phone no longer listens to, and leaving it would let the
            // Settings page report a working transport over a URL nothing reads.
            remove(KEY_PUSH_ENDPOINT)
        }.apply()
    }

    fun savePushEndpoint(endpoint: String?) {
        prefs.edit().apply {
            if (endpoint.isNullOrBlank()) remove(KEY_PUSH_ENDPOINT) else putString(KEY_PUSH_ENDPOINT, endpoint)
        }.apply()
    }

    /**
     * Cache the pairing's public application-server key: registration may occur while its machine is unreachable.
     * UnifiedPush 3.x distributors can refuse REGISTER without it; the phone never signs with this key.
     */
    fun vapidPublicKey(): String? =
        prefs.getString(KEY_PUSH_VAPID, null)?.takeIf(MobilePush::isVapidPublicKey)

    fun saveVapidPublicKey(key: String?) {
        prefs.edit().apply {
            if (MobilePush.isVapidPublicKey(key)) putString(KEY_PUSH_VAPID, key) else remove(KEY_PUSH_VAPID)
        }.apply()
    }

    private companion object {
        val transcriptCacheLock = Any()
        const val TAG = "AgentDeck"
        const val KEY_LEGACY_MACHINE = "machine"
        const val KEY_MACHINES = "machines"
        const val KEY_ACTIVE = "active-machine"
        const val KEY_ROUTE_MEMORY = "route-memory"
        const val KEY_SETTINGS = "settings"
        const val KEY_SCREEN = "screen"
        const val KEY_UPDATE_CHECKED_AT = "update-checked-at"
        const val KEY_UPDATE_DISMISSED = "update-dismissed"
        const val KEY_PUSH_PRIVATE = "push-private"
        const val KEY_PUSH_PUBLIC = "push-public"
        const val KEY_PUSH_AUTH = "push-auth"
        const val KEY_PUSH_DISTRIBUTOR = "push-distributor"
        const val KEY_PUSH_TOKEN = "push-token"
        const val KEY_PUSH_ENDPOINT = "push-endpoint"
        const val KEY_PUSH_VAPID = "push-vapid"
        const val TRANSCRIPT_CACHE_SIZE = 20
    }
}
