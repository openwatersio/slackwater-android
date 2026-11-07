package io.openwaters.slackwater.android.map

import androidx.compose.runtime.staticCompositionLocalOf
import io.openwaters.slackwater.android.core.station.StationIdentity

/** Detail and its Nearby inset hand off the actual station, including a restricted identity. */
internal val LocalOpenStationMap = staticCompositionLocalOf<((StationIdentity) -> Unit)?> { null }
