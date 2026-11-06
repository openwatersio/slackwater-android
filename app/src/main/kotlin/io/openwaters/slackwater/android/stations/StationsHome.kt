package io.openwaters.slackwater.android.stations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.openwaters.slackwater.android.core.station.StationId
import java.time.Instant
import kotlinx.coroutines.delay

/** Visible cards refresh this often while the app is active. */
private const val CARD_REFRESH_MILLIS = 60_000L

/**
 * The list with search and the station page over it. The list stays composed beneath them, so its scroll position
 * and filter survive, and is hidden from assistive technology while covered.
 */
@Composable
fun StationsHome(onCredits: () -> Unit, footerExtras: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val model = remember { StationListModel.get(context) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // At once on becoming active, then every minute while active; the clock pauses in the background.
            while (true) {
                now = Instant.now()
                delay(CARD_REFRESH_MILLIS)
            }
        }
    }
    fun open(id: StationId) {
        searching = false
        opened = id.value
    }
    BackHandler(enabled = searching || opened != null) {
        if (opened != null) opened = null else searching = false
    }
    Box(Modifier.fillMaxSize()) {
        val covered = searching || opened != null
        Box(if (covered) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
            StationListScreen(model, ::open, onSearch = { searching = true }, onCredits = onCredits, now = now, footerExtras = footerExtras)
        }
        if (searching && opened == null) SearchOverlay(model, now, ::open) { searching = false }
        opened?.let { StationPage(model, StationId(it), now) { opened = null } }
    }
}
