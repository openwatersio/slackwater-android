package io.openwaters.slackwater.android.stations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visible cards refresh this often while the app is active. */
private const val CARD_REFRESH_MILLIS = 60_000L

/**
 * The list with search and the station page over it. The list stays composed beneath them, so its scroll position
 * and filter survive, and is hidden from assistive technology while covered.
 */
@Composable
fun StationsHome(onCredits: () -> Unit, coveredByAlert: Boolean = false, footerExtras: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val model = remember { StationListModel.get(context) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    // The tick loop below starts once, so it reads the latest value through this rather than the first one.
    val covered by rememberUpdatedState(coveredByAlert)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Location is asked for when the list becomes active, then on a tick while locating or once the held fix
            // is halfway to the reuse limit; the clock pauses in the background.
            launch { model.refreshLocation() }
            while (true) {
                now = Instant.now()
                delay(CARD_REFRESH_MILLIS)
                model.tick()
                val showing = !searching && opened == null && !covered
                if (showing && model.wantsFreshFix(SystemClock.elapsedRealtime())) launch { model.refreshLocation() }
            }
        }
    }
    val pageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun closePage() {
        opened?.let { pageState.removeState(it) }
        opened = null
    }
    fun open(id: StationId) {
        searching = false
        opened = id.value
    }
    // The list appears again when its search, page or an alert's screen closes.
    val showing = !searching && opened == null && !coveredByAlert
    LaunchedEffect(showing) { if (showing) model.refreshLocation() }
    // Under an alert's screen, Back belongs to that screen, not to a page or search hidden beneath it.
    BackHandler(enabled = !coveredByAlert && (searching || opened != null)) {
        if (opened != null) closePage() else searching = false
    }
    Box(Modifier.fillMaxSize()) {
        val covered = searching || opened != null
        Box(if (covered) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
            StationListScreen(model, ::open, onSearch = { searching = true }, onCredits = onCredits, now = now,
                showing = showing, footerExtras = footerExtras)
        }
        if (searching && opened == null) SearchOverlay(model, now, ::open) { searching = false }
        if (!coveredByAlert) opened?.let { id ->
            pageState.SaveableStateProvider(id) { StationPage(model, StationId(id), now, onBack = ::closePage) }
        }
    }
}
