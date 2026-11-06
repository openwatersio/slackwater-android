package io.openwaters.slackwater.android.sharing

import androidx.compose.runtime.Composable
import io.openwaters.slackwater.android.core.prediction.PredictionRepository
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.detail.SchedulePanel
import java.time.Instant

@Composable
internal fun StationSchedule(
    station: StationIdentity, moment: Instant, repository: PredictionRepository,
    preferences: ReadingPreferences, modelRevision: Long,
) = SchedulePanel(station, moment, repository, preferences, modelRevision)
