package dev.agentdeck.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * What the desk dialog's `In` and `At` radios carry beside them: an amount and unit, or a 24-hour
 * time. Shown only while that pill is picked; the picked due's `valid` gates Schedule.
 */
@Composable
internal fun ScheduleDueFields(pick: ScheduleDuePick) {
    when (pick.custom) {
        ScheduleDuePick.CustomDue.IN -> Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = pick.delayText,
                onValueChange = { text -> if (text.length <= 3 && text.all(Char::isDigit)) pick.delayText = text },
                label = { Text("Amount") }, singleLine = true,
                isError = !pick.delay().valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("schedule-delay-amount"),
            )
            Selector(
                listOf(SelectorOption("hours", false), SelectorOption("minutes", true)),
                pick.delayMinutes, { pick.delayMinutes = it },
            )
        }
        ScheduleDuePick.CustomDue.AT -> OutlinedTextField(
            value = pick.clockText,
            onValueChange = { if (it.length <= 5) pick.clockText = it },
            label = { Text("Time (HH:mm)") }, singleLine = true,
            isError = pick.clockText.isNotBlank() && !pick.clock().valid,
            supportingText = {
                Text(if (pick.clockText.isNotBlank() && !pick.clock().valid) "Enter a 24-hour time, such as 14:30" else "24-hour · a past time runs tomorrow")
            },
            modifier = Modifier.fillMaxWidth().testTag("schedule-at-time"),
        )
        null -> Unit
    }
}
