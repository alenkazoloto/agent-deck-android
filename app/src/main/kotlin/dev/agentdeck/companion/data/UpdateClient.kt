package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileProtocol
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The download route said no, in a sentence written for the user.
 *
 * Its own type because the app already maps a bare [IOException] to "Could not reach this
 * machine. The IDE has to be running." — true of the bridge and false of every failure here,
 * and a message that blames the wrong end of the wire sends the user to check an IDE that is
 * running perfectly.
 */
class UpdateFailure(message: String) : IOException(message)

/**
 * The two calls that reach GitHub: read the manifest, download the APK it names.
 *
 * Deliberately **not** [BridgeClient]'s connection: that one pins a self-signed certificate to
 * one paired machine, which is exactly right for a bridge on a LAN and exactly wrong for a
 * public host with a real CA chain. Sharing a transport does not mean sharing a trust rule.
 *
 * Both methods block and belong on a background dispatcher.
 */
class UpdateClient(private val manifestUrl: String) {

    /** The newest published build. Throws when the address, the network or the payload refuses. */
    fun latest(): UpdateRelease {
        val body = open(manifestUrl).use { connection ->
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateFailure("The download page answered ${connection.responseCode}.")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        }
        val json = MobileProtocol.parseObject(body)
            ?: throw UpdateFailure("The download page answered something that is not a release manifest.")
        return UpdateRelease.fromJson(json)
            ?: throw UpdateFailure("The published release does not name an APK this app can install.")
    }

    /**
     * Downloads [release] to [destination], reporting whole percents.
     *
     * The digest is computed **while streaming**, not by re-reading the file: a truncated
     * download is the common failure on a phone, and this is what makes it a refusal instead of
     * an APK the installer rejects with its own, less useful sentence. A file that fails the
     * check is deleted — leaving it behind would make the next attempt look already-downloaded.
     */
    fun download(release: UpdateRelease, destination: File, onProgress: (Int) -> Unit): File {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(release.apkUrl).use { connection ->
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw UpdateFailure("The download answered ${connection.responseCode}.")
                }
                // The manifest's own size when the server does not say; `-1` from either is a
                // download with no percentage rather than one with a wrong one.
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                var read = 0L
                var reported = -1
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != reported) {
                                    reported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!AppUpdate.digestMatches(release.sha256, actual)) {
                throw UpdateFailure("The download did not arrive intact — it does not match the published checksum.")
            }
            return destination
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun open(url: String): HttpURLConnection {
        if (!AppUpdate.isTrustedApkUrl(url)) {
            // Reached only if a manifest slipped an address past `UpdateRelease.fromJson`; the
            // download is the call that would hand bytes to the installer, so it asks again.
            throw UpdateFailure("Refusing to download from $url.")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "*/*")
        return connection
    }

    private fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }
}
