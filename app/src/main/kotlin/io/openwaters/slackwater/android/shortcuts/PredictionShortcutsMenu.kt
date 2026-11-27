package io.openwaters.slackwater.android.shortcuts

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.openwaters.slackwater.android.R

/** The launcher's prediction shortcuts, offered inside the app from Settings. */
@Composable
internal fun PredictionShortcutsMenu() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.prediction_shortcuts)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShortcutKind.entries.forEach { kind ->
                DropdownMenuItem(text = { Text(stringResource(kind.label)) }, onClick = {
                    open = false
                    context.startActivity(ShortcutPublisher.intent(context, ShortcutRequest(kind)))
                })
            }
        }
    }
}
