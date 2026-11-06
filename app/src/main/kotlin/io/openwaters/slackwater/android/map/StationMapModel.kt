package io.openwaters.slackwater.android.map

import android.content.Context
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.map.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

data class StationMapPin(val station: StationIdentity, val state: MapPinState, val vectorBearing: Double? = null)

class StationMapModel(context: Context, private val models: ModelStore = ModelStore.get(context)) {
    private val context = context.applicationContext
    private val assets = this.context.assets
    private val catalog = BundledStationCatalog(assets)
    private val tides = BundledTideDatabase(assets)
    private val repository = AndroidPredictionRepository(context, models)
    val revision = models.revision
    private val referenceEvents = ReferenceCurrentEvents()
    private val ranks = ConcurrentHashMap<StationId, Double>()

    // A camera reads many current rows together. Index this map session's catalog once,
    // instead of reopening the compressed asset from the beginning for each pin.
    private val currents by lazy {
        val rows = assets.open("slackwater/currents.json").bufferedReader().use { JSONArray(it.readText()) }
        (0 until rows.length()).map(rows::getJSONObject).associateBy { it.getString("id") }
    }

    suspend fun pins(
        bounds: PinBox, zoom: Double, filter: StationSeries?, selected: StationId?, at: Instant,
        prefs: ReadingPreferences = ReadingPreferences(),
    ): List<StationMapPin> = withContext(Dispatchers.Default) {
        val candidates = buildList {
            for (station in catalog.mapIdentities()) {
                currentCoroutineContext().ensureActive()
                // Restricted records in this catalog are tide gauges.
                if (filter != null && (station.series ?: StationSeries.Tide) != filter) continue
                if (station.id != selected && !bounds.contains(station.latitude, station.longitude)) continue
                add(PinCandidate(station, ranks.getOrPut(station.id) { readPinRecord(25.0) { rank(station) } }))
            }
        }
        val format = PinFormat(context, prefs)
        visibleStations(bounds, zoom, candidates, selected).map { candidate ->
            currentCoroutineContext().ensureActive()
            val station = candidate.station
            readPinRecord(StationMapPin(station, MapPinState())) {
                val detailed = zoom >= PIN_READING_MIN_ZOOM
                when (station.kind) {
                    StationKind.Tide -> StationMapPin(station, bundledTide(station.id, at, detailed, format))
                    StationKind.Current -> StationMapPin(station, bundledCurrent(station.id, at, detailed, format, referenceEvents))
                    StationKind.Restricted, StationKind.Retired -> StationMapPin(station, MapPinState())
                    else -> canadian(station, at, detailed, format)
                }
            }
        }
    }

    private inline fun <T> readPinRecord(fallback: T, read: () -> T): T = try {
        read()
    } catch (error: Exception) {
        when (error) {
            is CancellationException -> throw error
            is IllegalArgumentException, is IllegalStateException, is java.time.DateTimeException,
            is JSONException, is IOException -> fallback
            else -> throw error
        }
    }

    private fun rank(station: StationIdentity): Double = when (station.kind) {
        StationKind.Tide -> tides.station(station.id)?.let { record ->
            pinRank(record.tideOffsets != null, record.constituents.sumOf { it.amplitude })
        } ?: 25.0
        StationKind.Current -> currents[station.id.value.removePrefix("current:")]?.let { row ->
            val constants = row.getJSONArray("constituents")
            val signal = abs(row.getDouble("meanFlow")) + (0 until constants.length()).sumOf { constants.getJSONObject(it).getDouble("amplitude") }
            pinRank(row.has("reference"), signal)
        } ?: 25.0
        StationKind.CanadianTide, StationKind.CanadianCurrent -> 5.0
        StationKind.DerivedGate -> 19.0
        else -> 25.0
    }

    private fun bundledTide(id: StationId, at: Instant, detailed: Boolean, format: PinFormat): MapPinState {
        val record = tides.station(id) ?: return MapPinState()
        val offsets = record.tideOffsets
        if (offsets == null) {
            val station = tideReference(record)
            val range = tideRangeProxy(record.constituents.associate { it.name to it.amplitude })
            return tidePinState(at, detailed, range, station::heights, station::extremes, format::height)
        }
        val ref = tides.station(StationId(offsets.reference)) ?: return MapPinState()
        val station = tideSubordinate(record, tideReference(ref))
        return tidePinState(at, detailed, 0.0, station::heights, station::extremes, format::height)
    }

    private fun bundledCurrent(
        id: StationId, at: Instant, detailed: Boolean, format: PinFormat, cache: ReferenceCurrentEvents,
    ): MapPinState {
        val row = currents[id.value.removePrefix("current:")] ?: return MapPinState()
        val reference = row.optString("reference")
        if (reference.isEmpty()) {
            val station = row.currentStation()
            return format.current(id, station.speeds(at, at, 1.0).speedAt(at),
                station.floodDirectionDegrees, station.ebbDirectionDegrees, detailed)
        }
        val harmonic = currents[reference]?.currentStation() ?: return MapPinState()
        val station = row.subordinateCurrent(harmonic)
        val events = cache.events(StationId(reference), at, harmonic::events)
        val speed = subordinateCurrentSpeed(at, events, station) ?: station.speeds(at, at, 1.0).speedAt(at)
        return format.current(id, speed, station.floodDirectionDegrees, station.ebbDirectionDegrees, detailed)
    }

    private suspend fun canadian(station: StationIdentity, at: Instant, detailed: Boolean, format: PinFormat): StationMapPin {
        if (station.kind == StationKind.CanadianTide) {
            val model = (models.read(station.id, ModelKind.TIDE) as? StoredModel.Ready)?.record
            val harmonic = try {
                model?.fittedTide()
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (model != null && harmonic != null) {
                val range = tideRangeProxy(model.constants().associate { it.name to it.amplitude })
                return StationMapPin(station, tidePinState(at, detailed, range, harmonic::heights, harmonic::extremes, format::height))
            }
        }
        val span = when (station.kind) {
            StationKind.CanadianCurrent -> TimeSpan(at, at)
            StationKind.CanadianTide -> TimeSpan(at.minusSeconds(15 * 3600), at.plusSeconds(30 * 3600))
            else -> cardSpan(at)
        }
        val result = repository.load(station.id, span, PredictionPurpose.DISPLAY)
        val ready = when (result) {
            is PredictionResult.Ready -> result.takeIf { at in it.coverage }
            is PredictionResult.Disjoint -> result.blocks.firstOrNull { at in it.coverage }
            is PredictionResult.Unavailable -> null
        } ?: return StationMapPin(station, MapPinState())
        val state = when (val series = ready.series) {
            is PredictionSeries.Current -> {
                val signed = series.points.speedAt(at)
                if (ready.capabilities == StationCapabilities.ONLINE_CURRENT) {
                    val vector = if (station.id.value == "chs-dodd-narrows")
                        doddMapVector(signed, series.floodBearing, series.ebbBearing, at) else null
                    return StationMapPin(station, MapPinState(), vector?.directionDegrees)
                }
                format.current(station.id, signed, series.floodBearing, series.ebbBearing, detailed)
            }
            is PredictionSeries.Tide -> {
                val rising = series.events.firstOrNull { it.time > at }?.let { it.kind == ExtremeKind.HIGH }
                    ?: series.rates.rateAt(at)?.let { it >= 0.0 } ?: return StationMapPin(station, MapPinState())
                val height = if (detailed) series.points.heightAt(at) else null
                val gaugeTurns = series.events.filter { it.time <= at.plusSeconds(15 * 3600) }
                tidePinReadout(rising, height, gaugeTurns, at, format::height)
            }
            is PredictionSeries.Derived -> MapPinState(when (derivedPhase(series.slacks, at)) {
                CardCurrentState.SLACK -> "slack"
                CardCurrentState.FLOODING -> "flood"
                CardCurrentState.EBBING -> "ebb"
                null -> "unknown"
            })
        }
        return StationMapPin(station, state)
    }
}

private class PinFormat(private val context: Context, private val prefs: ReadingPreferences) {
    private val locale = context.resources.configuration.locales[0]
    private val imperial = prefs.heightUnits(locale) == HeightUnits.IMPERIAL
    private val speedUnit = SpeedUnitKind.valueOf(prefs.speedUnit.name)
    fun height(value: Double): String = context.getString(R.string.card_value_unit, formatHeightValue(value, imperial, locale),
        context.getString(if (imperial) R.string.unit_feet else R.string.unit_metres))

    fun current(id: StationId, signed: Double?, flood: Double?, ebb: Double?, detailed: Boolean): MapPinState =
        currentPinState(signed, flood, ebb, prefs.effectiveThresholdKn(id), detailed) { speed ->
            context.getString(R.string.card_value_unit, formatSpeedValue(speed, speedUnit, locale),
                context.getString(prefs.speedUnit.unitLabel))
        }
}
