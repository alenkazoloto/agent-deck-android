package dev.agentdeck.companion.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.Dictation

/**
 * Speak a prompt instead of typing it.
 *
 * It hands off to the system recognizer through [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]
 * rather than driving `SpeechRecognizer` in-process. That app owns the microphone, so this
 * one needs no `RECORD_AUDIO` permission and no runtime prompt at all — a plain intent, and
 * the audio never passes through Agent Deck.
 *
 * The button is absent, not disabled, where nothing can answer that intent: a control whose
 * only possible outcome is an error message changes no decision (`CLAUDE.md`, progressive
 * disclosure). Seeing it requires the `<queries>` entry in the manifest — without it the
 * resolve comes back empty on Android 11+ and the button silently never appears.
 *
 * [onSpoken] receives the phrase; every caller merges it with [Dictation.append] so a
 * dictation adds to the draft instead of replacing what is already typed.
 */
@Composable
fun DictateButton(
    onSpoken: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "Speak your prompt",
) {
    val context = LocalContext.current
    val available = remember(context) { recognizerAvailable(context.packageManager) }
    if (!available) return

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        // The recognizer ranks its guesses; the first is the one it stands behind.
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(onSpoken)
    }

    IconButton(
        onClick = { launcher.launch(recognizeIntent(prompt)) },
        modifier = modifier,
    ) {
        Icon(MicIcon, contentDescription = "Dictate a prompt")
    }
}

private fun recognizeIntent(prompt: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }

private fun recognizerAvailable(packageManager: PackageManager): Boolean =
    packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

/**
 * Material's `mic`, inlined. The project depends on `material-icons-core`, which does not
 * carry it, and `material-icons-extended` is a thousand vectors for this one.
 */
private val MicIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Mic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(MIC_PATH).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private const val MIC_PATH =
    "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" +
        "M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"
