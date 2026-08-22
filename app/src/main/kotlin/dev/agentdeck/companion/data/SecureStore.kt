package dev.agentdeck.companion.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.github.claudeagents.core.mobile.MobileFleetSnapshot
import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

/**
 * Where the pairings live, and where each one's last fleet snapshot, drafts and read
 * transcripts are cached so a disconnected app can still show something true.
 *
 * The token is the only credential on the device. It goes into [EncryptedSharedPreferences]
 * when the keystore cooperates and into ordinary private prefs when it does not — a phone
 * whose keystore refuses should still be usable, and private prefs are already
 * app-sandboxed. The fallback is recorded so the Settings screen can say which is in use.
 *
 * **Everything cached is per machine.** A laptop and a desktop are two fleets, two sets of
 * drafts and two sets of transcripts; keeping one of each is what made switching machines cost
 * every unsent draft, which is the reason `SecureStore` used to hold exactly one pairing.
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
    fun forget(id: String): PairedMachine? {
        val remaining = machines().filterNot { it.id == id }
        writeMachines(remaining)
        wipeCache(id)
        val next = remaining.firstOrNull()
        prefs.edit().apply {
            if (next == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, next.id)
        }.apply()
        return next
    }

    private fun writeMachines(machines: List<PairedMachine>) {
        val body = JsonObject().apply {
            addProperty("v", MobileProtocol.VERSION)
            add("machines", JsonArray().also { arr -> machines.forEach { arr.add(it.toJson()) } })
        }
        prefs.edit().putString(KEY_MACHINES, body.toString()).apply()
    }

    /**
     * The pre-multi-machine key held one pairing under `machine`, with its caches in
     * `last-fleet.json` and `drafts.json`. Read once and rewritten in the new shape, so an
     * app updated over the top keeps its pairing and its unsent drafts instead of landing on
     * the pairing screen with an empty composer.
     */
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

    private fun wipeCache(id: String) {
        runCatching { snapshotFile(id).delete() }
        runCatching { draftsFile(id).delete() }
        runCatching { transcriptDir(id).deleteRecursively() }
    }

    /**
     * Cached verbatim as the plugin sent it, including `generatedAtMs`. The disconnected
     * view stamps itself from that field, so a stale snapshot can never be mistaken for a
     * live one — and the age comes from the machine that produced it, not from when this
     * phone happened to write the file.
     */
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

    // ---- read transcripts ------------------------------------------------------------

    /**
     * The last [TRANSCRIPT_CACHE_SIZE] conversations opened on this machine, so one read five
     * minutes ago on Wi-Fi is still readable in a tunnel. The page keeps its own
     * `generatedAtMs`, which is what the screen stamps itself with — a cached transcript is
     * never presented as live.
     *
     * Trimmed by last-read time rather than by size: the cap is about how much of the user's
     * reading history is worth keeping, and a transcript is a few tens of kilobytes.
     */
    fun cacheTranscript(id: String, page: MobileTranscriptPage) {
        runCatching {
            val dir = transcriptDir(id).apply { mkdirs() }
            File(dir, "${slug(page.key)}.json").writeText(page.toJson().toString())
            val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
            files.drop(TRANSCRIPT_CACHE_SIZE).forEach { it.delete() }
        }.onFailure { Log.w(TAG, "Could not cache a transcript", it) }
    }

    fun cachedTranscript(id: String, key: String): MobileTranscriptPage? = runCatching {
        val file = File(transcriptDir(id), "${slug(key)}.json")
        if (!file.isFile) return null
        // Touched on read so the trim above evicts what was read longest ago, not what was
        // fetched longest ago — those differ for a conversation opened again and again.
        file.setLastModified(System.currentTimeMillis())
        MobileProtocol.parseObject(file.readText())?.let(MobileTranscriptPage::fromJson)
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

    /**
     * Not in [AppSettings]: neither of these is a preference. They are what the app remembers
     * about its own last check — when it ran, and which published build the user has already
     * waved away — and putting them in the settings blob would mean every automatic check
     * rewrote the object the Settings screen edits.
     */
    fun updateCheckedAt(): Long = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0)

    fun saveUpdateCheckedAt(ms: Long) {
        prefs.edit().putLong(KEY_UPDATE_CHECKED_AT, ms).apply()
    }

    fun updateDismissed(): Long = prefs.getLong(KEY_UPDATE_DISMISSED, 0)

    fun saveUpdateDismissed(versionCode: Long) {
        prefs.edit().putLong(KEY_UPDATE_DISMISSED, versionCode).apply()
    }

    private companion object {
        const val TAG = "AgentDeck"
        const val KEY_LEGACY_MACHINE = "machine"
        const val KEY_MACHINES = "machines"
        const val KEY_ACTIVE = "active-machine"
        const val KEY_SETTINGS = "settings"
        const val KEY_SCREEN = "screen"
        const val KEY_UPDATE_CHECKED_AT = "update-checked-at"
        const val KEY_UPDATE_DISMISSED = "update-dismissed"
        const val TRANSCRIPT_CACHE_SIZE = 20
    }
}
