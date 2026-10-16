package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.github.claudeagents.core.mobile.MobileReviewFileDiff

/**
 * The desk `/diff` side's Base: which "before" a changed file is held against.
 *
 * The desk's control is an editable combo — suggestions beside a field that takes any revision
 * git does — so the phone keeps both halves: the suggestions as a [Selector], and "Other
 * revision…" opening [DiffBaseField] in place, never a guessed list standing in for the field. A
 * typed revision stays in the list once chosen, so the button always names the base in force.
 * [base] null is the session's own start.
 */
@Composable
fun DiffBaseSelector(base: String?, onOther: () -> Unit, onBase: (String?) -> Unit) {
    val suggested = MobileReviewFileDiff.SUGGESTED_BASES
    val options: List<SelectorOption<String?>> = listOf(SelectorOption<String?>(MobileReviewFileDiff.SESSION_START_BASE, null)) +
        (suggested + listOfNotNull(base?.takeIf { it !in suggested })).map { SelectorOption<String?>(it, it) } +
        SelectorOption<String?>("Other revision…", OTHER_REVISION)
    Selector(
        options = options,
        selected = base,
        onSelect = { if (it == OTHER_REVISION) onOther() else onBase(it) },
        prefix = "Base:",
        modifier = Modifier.testTag("changes-base"),
    )
}

/**
 * "Other revision…"'s field, on its own line under the selectors. Compare applies what was typed;
 * blank text is the session's start. Inline rather than in an AlertDialog, whose intrinsic
 * measure of a single-line field never settled (an OOM in `ChangesTabTest`).
 */
@Composable
fun DiffBaseField(base: String?, onBase: (String?) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(base.orEmpty()) }
    // The desk's parse: blank or the default's own name is the session's start, not a revision.
    val apply = { onBase(text.trim().takeIf { it.isNotEmpty() && !it.equals(MobileReviewFileDiff.SESSION_START_BASE, ignoreCase = true) }) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Branch, tag or commit") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { apply() }),
            modifier = Modifier.weight(1f).testTag("changes-base-field"),
        )
        TextButton(onClick = apply) { Text("Compare") }
    }
}

/** The Selector's value for "Other revision…"; a NUL can never be a revision a reader types. */
private const val OTHER_REVISION = "\u0000other"
