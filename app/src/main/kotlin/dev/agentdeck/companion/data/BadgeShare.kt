package dev.agentdeck.companion.data

import android.content.Intent

/**
 * A badge leaving the app through Android's share sheet: the desk's own share line as plain text.
 *
 * Text, not a picture — the desk's image card is drawn by the IDE's Swing renderer, which a phone
 * cannot ask for, so the phone shares the sentence the desk's "Copy share text" copies and posts
 * nowhere itself. Nothing but catalog wording and one number is in it.
 */
object BadgeShare {

    /** The chooser for [text]; [title] is the subject a mail app shows. */
    fun chooser(title: String, text: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Share badge").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
