package io.openwaters.slackwater.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalDialogSession = staticCompositionLocalOf { 0 }

/** Dialogs survive recreation of the current destination, but a new destination starts with them closed. */
@Composable
internal fun <T> rememberStationDialog(initial: T, vararg inputs: Any?): MutableState<T> {
    val session = LocalDialogSession.current
    var savedSession by rememberSaveable { mutableStateOf(session) }
    val state = rememberSaveable(*inputs) { mutableStateOf(initial) }
    if (savedSession != session) {
        state.value = initial
        savedSession = session
    }
    return state
}

/** Discards saved picker selections, draft fields and nested sheets when reopening after an interruption. */
@Composable
internal fun StationDialogContent(content: @Composable () -> Unit) {
    val session = LocalDialogSession.current
    val holder = rememberSaveableStateHolder()
    var savedSession by rememberSaveable { mutableStateOf(session) }
    if (savedSession != session) {
        holder.removeState(savedSession)
        savedSession = session
    }
    holder.SaveableStateProvider(session, content)
}
