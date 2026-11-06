package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt

internal class DetailPageScroll(
    private var width: Int = 0,
    private var atSchedule: Boolean = false,
) {
    private var viewport: LayoutCoordinates? = null
    private var schedule: LayoutCoordinates? = null
    var destination by mutableStateOf<Boolean?>(null)
        private set
    var scheduleOffset by mutableStateOf<Int?>(null)
        private set

    val viewportModifier = Modifier.nestedScroll(object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) cancelRestore()
            return Offset.Zero
        }
    })

    fun measureViewport(coordinates: LayoutCoordinates, scroll: ScrollState) {
        val measuredWidth = coordinates.size.width
        if (width > 0 && width != measuredWidth) {
            destination = atSchedule
            scheduleOffset = null
        }
        width = measuredWidth
        viewport = coordinates
        measure(scroll)
    }

    fun scheduleModifier(scroll: ScrollState) = Modifier.onGloballyPositioned {
        schedule = it
        measure(scroll)
    }

    fun cancelRestore() { destination = null }

    private fun measure(scroll: ScrollState) {
        val view = viewport?.takeIf { it.isAttached } ?: return
        val card = schedule?.takeIf { it.isAttached && it.size.height > 0 } ?: return
        val relative = card.positionInWindow().y - view.positionInWindow().y
        scheduleOffset = (relative + scroll.value).roundToInt()
        if (destination == null) atSchedule = relative < view.size.height / 2f && relative + card.size.height > 0f
    }

    companion object {
        val Saver = listSaver<DetailPageScroll, Any>(
            save = { listOf(it.width, it.destination ?: it.atSchedule) },
            restore = { DetailPageScroll(it[0] as Int, it[1] as Boolean) },
        )
    }
}

@Composable
internal fun rememberDetailPageScroll(scroll: ScrollState): DetailPageScroll {
    val page = rememberSaveable(saver = DetailPageScroll.Saver) { DetailPageScroll() }
    LaunchedEffect(page.destination, page.scheduleOffset) {
        val schedule = page.destination ?: return@LaunchedEffect
        val offset = if (schedule) page.scheduleOffset ?: return@LaunchedEffect else 0
        scroll.scrollTo(offset)
        page.cancelRestore()
    }
    return page
}
