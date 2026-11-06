package io.openwaters.slackwater.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*

@Composable
internal fun <T> EventList(events: List<T>, onSelect: ((T) -> Unit)? = null, row: @Composable (T) -> Unit) {
    Column(Modifier.semantics { collectionInfo = CollectionInfo(events.size, 1) }) {
        events.forEachIndexed { index, event ->
            key(event) {
                val select = if (onSelect == null) Modifier else Modifier
                    .fillMaxWidth().heightIn(min = 48.dp).clickable { onSelect(event) }
                Column(select.semantics(mergeDescendants = true) {
                    collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                }, verticalArrangement = Arrangement.Center) { row(event) }
            }
        }
    }
}
