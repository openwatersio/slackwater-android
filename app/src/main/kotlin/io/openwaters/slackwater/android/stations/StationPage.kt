package io.openwaters.slackwater.android.stations

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.detail.StationDetailScreen
import java.time.Instant

@Composable
fun StationPage(model: StationListModel, id: StationId, now: Instant, initialMoment: Instant? = null, onBack: () -> Unit) {
    var path by rememberSaveable(id.value) { mutableStateOf(arrayListOf(id.value)) }
    val pages = rememberSaveableStateHolder()
    val current = path.last()
    fun back() {
        if (path.size == 1) onBack() else {
            pages.removeState(current)
            path = ArrayList(path.dropLast(1))
        }
    }
    BackHandler(path.size > 1) { back() }
    pages.SaveableStateProvider(current) {
        StationDetailScreen(model, StationId(current), now, if (path.size == 1) initialMoment else null,
            onOpen = { next ->
                val existing = path.indexOf(next.value)
                if (existing < 0) path = ArrayList(path + next.value) else {
                    path.drop(existing + 1).forEach(pages::removeState)
                    path = ArrayList(path.take(existing + 1))
                }
            }, onBack = ::back)
    }
}
