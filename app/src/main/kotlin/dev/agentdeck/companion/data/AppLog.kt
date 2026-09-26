package dev.agentdeck.companion.data

import android.os.Build
import com.github.claudeagents.core.mobile.MobilePhoneLog

/**
 * This app's own log, as text a reader can send or share.
 *
 * The source is the process's own logcat: an app may read its own lines without a permission, so
 * nothing here needs a file logger or a change at any of the `Log` call sites. What is read is
 * then made safe to leave the phone — [redact] — and bounded ([MobilePhoneLog.tail]).
 */
object AppLog {

    /** How many recent lines to ask logcat for; the character cap is what actually bounds it. */
    private const val LINES = 4000

    /** The log ready to send, or a sentence saying there was none. Blocking: call off the main thread. */
    fun capture(versionName: String, machineName: String?): String {
        val raw = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", LINES.toString(), "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrElse { "(the phone would not hand this app its log: ${it.javaClass.simpleName})" }
        return prepare(raw, header(versionName, machineName))
    }

    internal fun prepare(raw: String, header: String): String =
        MobilePhoneLog.tail(header + "\n" + raw.lineSequence().joinToString("\n", transform = ::redact).ifBlank { "(empty)" })

    private fun header(versionName: String, machineName: String?): String =
        "Agent Deck $versionName · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}" +
            (machineName?.takeIf { it.isNotBlank() }?.let { " · paired with $it" } ?: "")

    private val SECRETS = listOf(
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
        Regex("(?i)((?:token|secret|authorization|auth|p256dh|password|key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;&}]{6,}"),
    )

    /** A credential that reached a log line must not leave the phone with it. */
    internal fun redact(line: String): String =
        SECRETS.fold(line) { text, pattern -> pattern.replace(text) { it.groupValues[1] + "[redacted]" } }
}
