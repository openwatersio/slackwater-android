package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.*
import kotlin.math.*

@Composable
internal fun NearbyMap(station: StationIdentity, rows: List<RankedStation>, open: (StationId) -> Unit) {
    val map = io.openwaters.slackwater.android.map.LocalOpenStationMap.current
    val points = remember(station.id, rows) { rows.map {
        val longitude = (it.identity.longitude - station.longitude + 540.0) % 360.0 - 180.0
        longitude * cos(Math.toRadians(station.latitude)) to (it.identity.latitude - station.latitude)
    } }
    val label = stringResource(R.string.detail_map)
    BoxWithConstraints(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.large)
        .background(Color(0xff152d3b)).then(if (map != null && station.kind != StationKind.Retired) Modifier.clickable(onClickLabel = label) { map(station) } else Modifier)) {
        val width = maxWidth.value
        val height = maxHeight.value
        val halfWidth = points.maxOfOrNull { abs(it.first) }?.coerceAtLeast(0.0001) ?: 1.0
        val halfHeight = points.maxOfOrNull { abs(it.second) }?.coerceAtLeast(0.0001) ?: 1.0
        val scale = min((width / 2 - 25) / halfWidth, (height / 2 - 25) / halfHeight).coerceAtLeast(0.0)
        Canvas(Modifier.fillMaxSize()) {
            for (i in 1..3) {
                val x = size.width * i / 4
                val y = size.height * i / 4
                drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, size.height))
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, y), Offset(size.width, y))
            }
            drawCircle(Color(0xfff0c860), 6.dp.toPx(), center)
        }
        rows.forEachIndexed { i, row ->
            val (east, north) = points[i]
            Box(Modifier.offset((width / 2 + east * scale - 24).dp, (height / 2 - north * scale - 24).dp)
                .size(48.dp).clickable(onClickLabel = row.identity.name) { open(row.id) }
                .semantics { contentDescription = row.identity.name }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(11.dp).background(if (row.identity.series == StationSeries.Current) Color(0xff94d5af) else Color(0xff97d6f0), CircleShape))
            }
        }
    }
}

internal fun bearingText(origin: Coordinate, target: Coordinate): String {
    val lat = Math.toRadians(origin.latitude)
    val other = Math.toRadians(target.latitude)
    val delta = Math.toRadians(target.longitude - origin.longitude)
    val degrees = Math.toDegrees(atan2(sin(delta) * cos(other), cos(lat) * sin(other) - sin(lat) * cos(other) * cos(delta)))
    return formatSet((degrees + 360) % 360, false)
}
