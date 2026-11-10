package dev.agentdeck.companion.data

import java.io.BufferedReader

/** Keep-alive comments count as liveness even when no SSE frames arrive (MP-02). */
internal object SseReader {

    /** Reads until the stream ends or [onFrame] returns false. Blocking, by design. */
    fun read(reader: BufferedReader, onAlive: () -> Unit, onFrame: (BridgeClient.SseFrame) -> Boolean) {
        var id: String? = null
        var event: String? = null
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            // Before the dispatch, and for every line including comments: this is the signal
            // that the socket is alive, and frames alone would not carry it.
            onAlive()
            when {
                line.isEmpty() -> {
                    if (event != null || data.isNotEmpty()) {
                        if (!onFrame(BridgeClient.SseFrame(id, event, data.toString()))) return
                    }
                    event = null
                    data.setLength(0)
                }
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                // ":" comments and unknown fields carry no frame — but they did carry a line,
                // which is the whole point.
            }
        }
    }
}
