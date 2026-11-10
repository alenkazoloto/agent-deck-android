package dev.agentdeck.companion.notify

import com.github.claudeagents.core.mobile.MobilePush

/**
 * The words of a retry-loop notification: which tool, how many times, and the desk's own name
 * for the failure ([MobilePush.RetryLoopAlert.label]) — so a failure kind this build has never
 * heard of still reads as a sentence. Never the error text: the push carries none.
 */
object RetryLoopNotice {

    fun body(loop: MobilePush.RetryLoopAlert): String {
        val tool = loop.tool.ifBlank { "A tool" }
        val head = "$tool failed ${loop.run} times in a row"
        return if (loop.label.isBlank()) "$head." else "$head: ${loop.label.replaceFirstChar(Char::lowercase)}."
    }
}
