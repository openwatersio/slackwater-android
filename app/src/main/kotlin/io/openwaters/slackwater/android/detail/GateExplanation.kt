package io.openwaters.slackwater.android.detail

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.prediction.CurrentGateEvidence
import io.openwaters.slackwater.android.core.station.SpeedUnitKind
import io.openwaters.slackwater.android.core.station.formatSpeedValue
import io.openwaters.slackwater.android.data.SpeedUnit
import io.openwaters.slackwater.android.map.unitLabel

@Composable
internal fun GateExplanation(gate: CurrentGateEvidence?, unit: SpeedUnit) {
    if (gate == null) return
    if (gate.online) Text(gate.onlineExplanation(LocalContext.current, unit))
}

internal fun CurrentGateEvidence.onlineExplanation(context: Context, unit: SpeedUnit): String {
    val place = when (missContext) {
        "slow_reversal" -> R.string.gate_slow_reversal
        "tidal_falls" -> R.string.gate_tidal_falls
        "rapids" -> R.string.gate_rapids
        else -> null
    }?.let { context.getString(it) }
    val miss = onlineMiss
    val value = miss?.value
    val locale = context.resources.configuration.locales[0]
    val explanation = when {
        miss == null -> context.getString(R.string.gate_no_evidence)
        miss.nearBar -> context.getString(R.string.gate_near_bar)
        value == null -> context.getString(R.string.gate_too_few)
        else -> {
            val number = "%.1f".format(locale, value)
            when (miss.clause) {
                "reversed_axis" -> context.getString(R.string.gate_reversed_axis, number)
                "invented_slack" -> context.getString(R.string.gate_invented_slack, value.toInt())
                "missed_slack" -> context.getString(R.string.gate_missed_slack, value.toInt())
                "slack_worst" -> context.getString(R.string.gate_slack_worst, number)
                "slack_median" -> context.getString(R.string.gate_slack_median, number)
                "extremum_timing" -> context.getString(R.string.gate_extremum_timing, number)
                "peak_speed" -> context.getString(R.string.gate_peak_speed,
                    formatSpeedValue(value, SpeedUnitKind.valueOf(unit.name), locale), context.getString(unit.unitLabel))
                else -> context.getString(R.string.gate_unknown)
            }
        }
    }
    return listOfNotNull(place, explanation).joinToString(" ")
}
