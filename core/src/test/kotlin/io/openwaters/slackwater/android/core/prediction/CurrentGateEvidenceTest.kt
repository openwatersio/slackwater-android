package io.openwaters.slackwater.android.core.prediction

import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.android.core.station.StationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentGateEvidenceTest {
    private val gate = CurrentGateEvidence(StationId("chs-gate"), 210.0, ChsCurrentFit.STAMP)
    private fun fit(
        evidence: CurrentGateEvidence = gate,
        station: String? = gate.id.value,
        recipe: String? = ChsCurrentFit.STAMP,
        days: Double? = gate.fitDays,
    ) = evidence.compatibleFit(station, recipe, days)

    @Test fun requiresIdentityBothRecipeStampsAndRecordedWindow() {
        assertNull(fit(station = "other"))
        assertNull(fit(station = null))
        assertNull(fit(recipe = null))
        assertNull(fit(recipe = "old"))
        assertNull(fit(gate.copy(recipe = null)))
        assertNull(fit(gate.copy(recipe = "old"), recipe = "old"))
        assertNull(fit(days = null))
        assertNull(fit(days = 209.5))
        assertNull(fit(gate.copy(fitDays = 90.0), days = 90.0))
        assertNull(fit(gate.copy(online = true)))
    }

    @Test fun onlyPublishedProvisionalWindowUsesItsMeasuredBound() {
        assertNull(fit(days = 60.0))
        val evidence = gate.copy(provisionalSlackMinutes = 35, slackBoundMinutes = 20)
        assertEquals(CurrentGateFit(StationCapabilities.PROVISIONAL_CURRENT, 35), fit(evidence, days = 60.0))
        assertNull(fit(evidence, days = 61.0))
        assertEquals(CurrentGateFit(StationCapabilities.BOUNDED_CURRENT, 20), fit(evidence))
    }

    @Test fun completedShortFitsAreFinalAndBoundUpdatesReuseTheSameModel() {
        val short = gate.copy(fitDays = 60.0, provisionalSlackMinutes = 35)
        assertEquals(CurrentGateFit(StationCapabilities.FINAL_CURRENT), fit(short, days = 60.0))
        for (minutes in listOf(45, 40)) {
            assertEquals(CurrentGateFit(StationCapabilities.BOUNDED_CURRENT, minutes),
                fit(short.copy(slackBoundMinutes = minutes), days = 60.0))
        }
    }
}
