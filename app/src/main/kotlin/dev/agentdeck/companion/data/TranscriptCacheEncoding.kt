package dev.agentdeck.companion.data

import com.github.claudeagents.core.mobile.MobileTranscriptPage
import com.google.gson.Gson
import java.io.Writer

internal object TranscriptCacheEncoding {
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_CHARS = 2 * 1024 * 1024

    /** Keep the previous complete saved page when an intentionally expanded history exceeds the cache budget. */
    fun encode(page: MobileTranscriptPage): String? {
        if (page.turns.sumOf { it.text.length.toLong() } > MAX_CHARS) return null
        val result = StringBuilder()
        return runCatching {
            val writer = object : Writer() {
                override fun write(buffer: CharArray, offset: Int, length: Int) {
                    check(result.length + length <= MAX_CHARS)
                    result.append(buffer, offset, length)
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            Gson().toJson(page.toJson(), writer)
            result.toString()
        }.getOrNull()
    }
}
