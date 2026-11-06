package io.openwaters.slackwater.android.detail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.openwaters.almanac.*
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationIdentity
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Fact(val label: String, val time: Instant?, val explanation: String? = null, val orbitDistanceKm: Double? = null)
private data class SkyFacts(val kilometres: Long, val rows: List<Fact>)

@Composable
internal fun SummarySkyFacts(
    moon: Boolean, selected: Instant, station: StationIdentity, zone: ZoneId, stamp: DateTimeFormatter, onJump: (Instant) -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val facts by key(moon, selected, station, locale) {
        produceState<Result<SkyFacts>?>(null) {
            value = withContext(Dispatchers.Default) {
                try {
                    val observer = Observer(station.latitude, station.longitude)
                    val day = selected.atZone(zone).toLocalDate()
                    val start = day.atStartOfDay(zone).toInstant()
                    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
                    Result.success(if (moon) moonFacts(context, selected, start, end, observer) else sunFacts(context, selected, start, end, observer))
                } catch (error: AlmanacOutOfRangeException) { Result.failure(error) }
            }
        }
    }
    val result = facts
    if (result == null) { CircularProgressIndicator(); return }
    val data = result.getOrNull()
    if (data == null) { Text(stringResource(R.string.schedule_unavailable)); return }
    Text(stringResource(R.string.panel_earth_distance, NumberFormat.getIntegerInstance(locale).format(data.kilometres)))
    val orbit = data.rows.filter { it.orbitDistanceKm != null }
    val near = orbit.minByOrNull { requireNotNull(it.orbitDistanceKm) }
    val far = orbit.maxByOrNull { requireNotNull(it.orbitDistanceKm) }
    val outbound = if (near?.time != null && far?.time != null) {
        if (near.time < far.time) selected in near.time..far.time else selected !in far.time..near.time
    } else false
    OrbitFigure(moon, data.kilometres, near?.orbitDistanceKm, far?.orbitDistanceKm, outbound)
    var info by remember { mutableStateOf<Fact?>(null) }
    for (fact in data.rows) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled = fact.time != null, onClick = { fact.time?.let(onJump) }, modifier = Modifier.weight(1f)) {
                val time = fact.time?.let(stamp::format) ?: "—"
                if (LocalConfiguration.current.fontScale >= 1.5f) Column(Modifier.fillMaxWidth()) {
                    Text(fact.label)
                    Text(time)
                } else Text("${fact.label} · $time", Modifier.fillMaxWidth())

            }
            if (fact.explanation != null) {
                val description = stringResource(R.string.panel_explain, fact.label)
                TextButton(onClick = { info = fact }) { Text("ⓘ", Modifier.clearAndSetSemantics { contentDescription = description }) }
            }
        }
    }
    info?.let { fact -> AlertDialog(onDismissRequest = { info = null }, title = { Text(fact.label) },
        text = { Text(fact.explanation.orEmpty()) }, confirmButton = {
            TextButton(onClick = { info = null }) { Text(stringResource(android.R.string.ok)) }
        }) }
    Spacer(Modifier.height(16.dp))
}

private fun moonFacts(context: Context, selected: Instant, start: Instant, end: Instant, observer: Observer): SkyFacts {
    val rows = buildList {
        val lunar = moonEvents(start, end, observer)
        for (kind in MoonEventKind.entries) add(Fact(if (kind == MoonEventKind.RISE) context.getString(R.string.panel_moonrise) else context.getString(R.string.panel_moonset), lunar.firstOrNull { it.kind == kind }?.time))
        val apsides = moonApsides(selected.minus(Duration.ofDays(31)), selected.plus(Duration.ofDays(31)))
        for (kind in MoonApsisKind.entries) {
            val nearest = apsides.filter { it.kind == kind }.minByOrNull { Duration.between(it.time, selected).abs() }
            add(Fact(if (kind == MoonApsisKind.PERIGEE) context.getString(R.string.panel_closest) else context.getString(R.string.panel_farthest), nearest?.time, orbitDistanceKm = nearest?.distanceKm))
        }
        val phases = searchMoonPhases(selected, selected.plus(Duration.ofDays(32)))
        for (kind in listOf(MoonPhaseName.FULL, MoonPhaseName.NEW)) {
            val phase = phases.firstOrNull { it.phase == kind } ?: continue
            add(Fact(if (kind == MoonPhaseName.FULL) context.getString(R.string.panel_full) else context.getString(R.string.panel_new),
                phaseNight(phase.time, observer)))
        }
        fun visible(e: LunarEclipse): Boolean {
            val v = lunarEclipseVisibility(e, observer)
            val c = v.contactsVisible
            return v.visibleAtPeak || c.p1 || c.p4 || listOf(c.u1, c.u2, c.u3, c.u4).any { it == true }
        }
        val previous = lunarEclipses(selected.minus(Duration.ofDays(5 * 366L)), selected).lastOrNull(::visible)
        add(Fact(context.getString(R.string.panel_last_lunar), previous?.let { it.u1 ?: it.p1 }))
        add(Fact(context.getString(R.string.panel_last_solar), previousSolarEclipse(selected, observer).c1))
        lunarEclipses(selected, selected.plus(Duration.ofDays(5 * 366L))).filter(::visible).distinctBy { it.kind }.forEach {
            add(Fact(context.getString(when (it.kind) {
                LunarEclipseKind.TOTAL -> R.string.panel_next_total
                LunarEclipseKind.PARTIAL -> R.string.panel_next_partial
                LunarEclipseKind.PENUMBRAL -> R.string.panel_next_penumbral
            }), it.u1 ?: it.p1))
        }
        add(Fact(context.getString(R.string.panel_next_solar), nextSolarEclipse(selected, observer).c1))
    }
    return SkyFacts((moonPosition(selected).distanceKm / 100).roundToLong() * 100, rows)
}

private fun sunFacts(context: Context, selected: Instant, start: Instant, end: Instant, observer: Observer): SkyFacts {
    val events = sunEvents(start, end, observer)
    val rows = SunEventKind.entries.map { kind ->
        val (name, detail) = when (kind) {
            SunEventKind.RISE -> context.getString(R.string.panel_rise) to context.getString(R.string.panel_rise_def)
            SunEventKind.SET -> context.getString(R.string.panel_set) to context.getString(R.string.panel_set_def)
            SunEventKind.TRANSIT -> context.getString(R.string.panel_noon) to context.getString(R.string.panel_noon_def)
            SunEventKind.ASTRO_DAWN -> context.getString(R.string.panel_astro_dawn) to context.getString(R.string.panel_twilight_rising, 18)
            SunEventKind.ASTRO_DUSK -> context.getString(R.string.panel_astro_dusk) to context.getString(R.string.panel_twilight_setting, 18)
            SunEventKind.NAUTICAL_DAWN -> context.getString(R.string.panel_naut_dawn) to context.getString(R.string.panel_twilight_rising, 12)
            SunEventKind.NAUTICAL_DUSK -> context.getString(R.string.panel_naut_dusk) to context.getString(R.string.panel_twilight_setting, 12)
            SunEventKind.CIVIL_DAWN -> context.getString(R.string.panel_civil_dawn) to context.getString(R.string.panel_twilight_rising, 6)
            SunEventKind.CIVIL_DUSK -> context.getString(R.string.panel_civil_dusk) to context.getString(R.string.panel_twilight_setting, 6)
        }
        Fact(name, events.firstOrNull { it.kind == kind }?.time, detail)
    }.toMutableList()
    for (alt in listOf(-8.0, -4.0, 6.0)) {
        for (edge in sunAltitudeEvents(start, end, observer, alt)) {
            val rising = edge.kind == AltitudeEventKind.RISING
            val name = when (alt) {
                6.0 -> if (rising) context.getString(R.string.panel_golden_end) else context.getString(R.string.panel_golden_start)
                -4.0 -> if (rising) context.getString(R.string.panel_blue_end) else context.getString(R.string.panel_blue_start)
                else -> if (rising) context.getString(R.string.panel_blue_start) else context.getString(R.string.panel_blue_end)
            }
            rows.add(Fact(name, edge.time, context.getString(R.string.panel_edge_def, alt.toInt())))
        }
    }
    rows.sortBy { it.time ?: Instant.MAX }
    val apsides = earthApsides(selected.minus(Duration.ofDays(370)), selected.plus(Duration.ofDays(370)))
    for (kind in EarthApsisKind.entries) {
        val nearest = apsides.filter { it.kind == kind }.minByOrNull { Duration.between(it.time, selected).abs() }
        rows.add(Fact(if (kind == EarthApsisKind.PERIHELION) context.getString(R.string.panel_perihelion) else context.getString(R.string.panel_aphelion), nearest?.time, orbitDistanceKm = nearest?.distanceKm))
    }
    return SkyFacts((sunPosition(selected).distanceAu * 149597870.7 / 10000).roundToLong() * 10000, rows)
}

internal fun phaseNight(phase: Instant, observer: Observer): Instant {
    val twilight = sunEvents(phase.minus(Duration.ofHours(36)), phase.plus(Duration.ofHours(36)), observer)
        .filter { it.kind == SunEventKind.NAUTICAL_DUSK || it.kind == SunEventKind.NAUTICAL_DAWN }
    val night = twilight.zipWithNext().filter { (a, b) -> a.kind == SunEventKind.NAUTICAL_DUSK && b.kind == SunEventKind.NAUTICAL_DAWN }
        .minByOrNull { (a, b) -> Duration.between(phase.coerceIn(a.time, b.time), phase).abs() } ?: return phase
    val (dusk, dawn) = night
    val transits = moonTransits(dusk.time, dawn.time, observer).filter { it.kind == TransitEventKind.UPPER }.map { it.time }
    return (transits + listOf(dusk.time, dawn.time)).maxBy { moonAltAz(it, observer).altDeg }
}
