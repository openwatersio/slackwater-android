package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.core.station.StationId

data class CurrentGateFit(val capability: StationCapabilities, val slackBoundMinutes: Int? = null)

data class CurrentGateMiss(val clause: String, val value: Double?, val nearBar: Boolean)

data class CurrentGateEvidence(
    val id: StationId,
    val fitDays: Double,
    val recipe: String?,
    val online: Boolean = false,
    val slackBoundMinutes: Int? = null,
    val provisionalSlackMinutes: Int? = null,
    val onlineMiss: CurrentGateMiss? = null,
    val missContext: String? = null,
    val magnitudeNote: String? = null,
    val tideReference: StationId? = null,
) {
    fun compatibleFit(stationId: String?, storedRecipe: String?, storedDays: Double?): CurrentGateFit? {
        if (online || stationId != id.value || recipe != ChsCurrentFit.STAMP || storedRecipe != recipe) return null
        if (ChsCurrentFit.days.none { it.toDouble() == fitDays }) return null
        if (storedDays == fitDays) {
            return if (slackBoundMinutes == null) CurrentGateFit(StationCapabilities.FINAL_CURRENT)
                else CurrentGateFit(StationCapabilities.BOUNDED_CURRENT, slackBoundMinutes)
        }
        if (storedDays == PROVISIONAL_DAYS && fitDays > PROVISIONAL_DAYS && provisionalSlackMinutes != null) {
            return CurrentGateFit(StationCapabilities.PROVISIONAL_CURRENT, provisionalSlackMinutes)
        }
        return null
    }

    private companion object {
        const val PROVISIONAL_DAYS = 60.0
    }
}
