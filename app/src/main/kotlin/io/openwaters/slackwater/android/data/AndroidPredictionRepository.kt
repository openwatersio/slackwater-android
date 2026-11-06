package io.openwaters.slackwater.android.data

import android.content.Context
import io.openwaters.slackwater.*
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.prediction.PredictionResult.Reason
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.database.HeightOffsetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

class AndroidPredictionRepository(
    context: Context,
    private val models: ModelStore = ModelStore.get(context),
) : PredictionRepository {
    private val assets = context.applicationContext.assets
    private val catalog = BundledStationCatalog(assets)
    private val tides = BundledTideDatabase(assets)

    private fun rows(file: String): Map<String, JSONObject> =
        assets.open("slackwater/$file.json").bufferedReader().use { reader ->
            val array = JSONArray(reader.readText())
            (0 until array.length()).map(array::getJSONObject).associateBy { it.getString("id") }
        }

    private val currents = BundledCurrentRecords(assets)
    private val gates by lazy { rows("chs-current-gates") }
    private val derived by lazy { rows("chs-gates") }

    /** Identifies only this station's inputs. Bounded previews and online cache blocks are never calendar sources. */
    internal suspend fun calendarSource(station: StationIdentity): String? {
        val id = station.id
        val reference: StationId
        val kind: ModelKind
        val catalogRecord: String
        when (station.kind) {
            StationKind.DerivedGate -> {
                val gate = derived[id.value] ?: return "missing-derived:${id.value}"
                reference = StationId(gate.getString("reference"))
                kind = ModelKind.TIDE
                catalogRecord = gate.toString()
            }
            StationKind.CanadianCurrent -> {
                val gate = gates[id.value] ?: return "missing-current:${id.value}"
                reference = id
                kind = ModelKind.CURRENT
                catalogRecord = "${ChsCurrentFit.STAMP}:$gate"
            }
            StationKind.CanadianTide -> {
                reference = id
                kind = ModelKind.TIDE
                catalogRecord = ""
            }
            else -> return "${station.kind}:${id.value}"
        }
        val record = when (val stored = models.read(reference, kind)) {
            is StoredModel.Ready -> stored.record.toString()
            StoredModel.Missing -> "missing"
            is StoredModel.Unavailable -> if (stored.reason == StoredModel.Unavailable.Reason.UNREADABLE) return null else stored.reason.name
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$catalogRecord\u0000$record".toByteArray(Charsets.UTF_8))
        return "${station.kind}:${reference.value}:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
    }

    override suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult =
        withContext(Dispatchers.IO) {
            try {
                val resolved = catalog.resolve(id)
                val item = when (resolved) {
                    is StationResolution.Live -> resolved.identity
                    is StationResolution.Successor -> (resolved.target as? StationResolution.Live)?.identity
                    else -> null
                } ?: return@withContext PredictionResult.Unavailable(Reason.UNKNOWN_STATION)
                val prediction = when (item.kind) {
                    StationKind.Tide -> bundledTide(item.id, span)
                    StationKind.Current -> bundledCurrent(item.id, span)
                    StationKind.CanadianTide -> canadianTide(item.id, span, purpose)
                    StationKind.CanadianCurrent -> canadianCurrent(item.id, span, purpose)
                    StationKind.DerivedGate -> if (StationCapabilities.DERIVED_TIMING.supports(purpose)) derivedGate(item.id, span)
                        else PredictionResult.Unavailable(Reason.UNSUPPORTED_PURPOSE)
                    else -> PredictionResult.Unavailable(Reason.UNKNOWN_STATION)
                }
                if (resolved !is StationResolution.Successor) prediction else when (prediction) {
                    is PredictionResult.Ready -> prediction.copy(successor = resolved)
                    is PredictionResult.Disjoint -> prediction.copy(blocks = prediction.blocks.map { it.copy(successor = resolved) })
                    is PredictionResult.Unavailable -> prediction
                }
            } catch (error: BadPrediction) {
                PredictionResult.Unavailable(error.reason)
            } catch (_: JSONException) {
                PredictionResult.Unavailable(Reason.INVALID_MODEL)
            } catch (_: IllegalArgumentException) {
                PredictionResult.Unavailable(Reason.INVALID_MODEL)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                PredictionResult.Unavailable(Reason.INVALID_MODEL)
            } catch (_: java.time.DateTimeException) {
                PredictionResult.Unavailable(Reason.INVALID_MODEL)
            } catch (_: IOException) {
                PredictionResult.Unavailable(Reason.INVALID_MODEL)
            }
        }

    private fun reference(record: io.openwaters.slackwater.database.Station): Station {
        require(record.tideOffsets == null)
        return Station(record.constituents.map { HarmonicConstituent(it.name, it.amplitude, it.phase) }
            .also { require(it.isNotEmpty()) }, record.chartDatumShift ?: 0.0)
    }

    private fun bundledTide(id: StationId, span: TimeSpan): PredictionResult {
        val record = tides.station(id) ?: bad()
        val offsets = record.tideOffsets
        if (offsets == null) {
            val station = reference(record)
            return PredictionResult.Ready(id, span, record.attribution, StationCapabilities.HARMONIC_TIDE,
                EngineAdapter.tide(station, span), chartModel = ChartModel.Tide(station::heights))
        }
        val ref = reference(tides.station(StationId(offsets.reference)) ?: bad())
        val height = if (offsets.heightType == HeightOffsetType.RATIO) HeightOffset.Ratio(offsets.heightHigh, offsets.heightLow)
            else HeightOffset.Fixed(offsets.heightHigh, offsets.heightLow)
        val station = EngineAdapter.subordinateTide(ref, offsets.timeHigh, offsets.timeLow, height)
        return PredictionResult.Ready(id, span, record.attribution, StationCapabilities.SUBORDINATE_TIDE, EngineAdapter.tide(station, span), chartModel = ChartModel.Tide(station::heights))
    }

    private fun bundledCurrent(id: StationId, span: TimeSpan): PredictionResult {
        val row = currents[id.value.removePrefix("current:")] ?: bad()
        val ref = row.optString("reference")
        val (capability, series, model) = if (ref.isEmpty()) {
            val station = row.currentStation()
            Triple(StationCapabilities.HARMONIC_CURRENT, EngineAdapter.current(station, span).copy(
                hasCrossAxis = (row.optJSONArray("minorConstituents")?.length() ?: 0) > 0 || row.optDouble("minorMeanFlow", 0.0) != 0.0,
            ), ChartModel.Current(station::speeds, station::events))
        } else {
            val station = SubordinateCurrentStation(currents[ref]?.currentStation() ?: bad(),
                row.getDouble("slackBeforeFloodOffset"), row.getDouble("slackBeforeEbbOffset"),
                row.getDouble("floodTimeOffset"), row.getDouble("ebbTimeOffset"),
                row.getDouble("floodSpeedRatio"), row.getDouble("ebbSpeedRatio"),
                row.optional("floodDirection"), row.optional("ebbDirection"))
            Triple(StationCapabilities.SUBORDINATE_CURRENT, EngineAdapter.current(station, span), ChartModel.Current(station::speeds, station::events))
        }
        return PredictionResult.Ready(id, span, "NOAA", capability, series, chartModel = model)
    }

    private suspend fun record(id: StationId, kind: ModelKind): JSONObject? = when (val stored = models.read(id, kind)) {
        is StoredModel.Ready -> stored.record
        StoredModel.Missing -> null
        is StoredModel.Unavailable -> bad()
    }

    private fun fittedTide(model: JSONObject) = Station(model.constants(), model.getDouble("offset"))

    private suspend fun canadianTide(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
        val stored = models.read(id, ModelKind.TIDE)
        if (stored is StoredModel.Ready) {
            val station = try {
                fittedTide(stored.record)
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (station != null) return PredictionResult.Ready(id, span, "CHS", StationCapabilities.FITTED_TIDE,
                EngineAdapter.tide(station, span), chartModel = ChartModel.Tide(station::heights))
        }
        val preview = record(id, ModelKind.TIDE_PREVIEW)
            ?: bad(if (stored == StoredModel.Missing) Reason.MISSING_DOWNLOAD else Reason.INVALID_MODEL)
        if (!StationCapabilities.TIDE_PREVIEW.supports(purpose)) bad(Reason.UNSUPPORTED_PURPOSE)
        val samples = preview.getJSONArray("samples")
        val points = (0 until samples.length()).map {
            val sample = samples.getJSONObject(it)
            TidePoint(Instant.parse(sample.getString("time")), sample.getDouble("height"))
        }
        return BoundedPredictions.tide(id, span, points)
    }

    private suspend fun canadianCurrent(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult {
        val gate = gates[id.value] ?: bad()
        if (gate.optBoolean("online")) {
            if (!StationCapabilities.ONLINE_CURRENT.supports(purpose)) bad(Reason.UNSUPPORTED_PURPOSE)
            val window = record(id, ModelKind.ONLINE_WINDOW) ?: bad(Reason.MISSING_DOWNLOAD)
            val blocks = window.optJSONArray("blocks") ?: JSONArray().put(window)
            val cached = (0 until blocks.length()).map { i ->
                val block = blocks.getJSONObject(i)
                val times = block.getJSONArray("times")
                val speeds = block.getJSONArray("speeds")
                require(times.length() > 0 && times.length() == speeds.length())
                val points = (0 until times.length()).map { n ->
                    val epoch = times.getDouble(n)
                    require(epoch.isFinite() && epoch == epoch.toLong().toDouble())
                    CurrentPoint(Instant.ofEpochSecond(epoch.toLong()), speeds.getDouble(n).also { require(it.isFinite()) })
                }
                require(points.zipWithNext().all { (a,b) -> a.time < b.time })
                OnlineBlock(points, block.getDouble("floodDirection"), block.getDouble("ebbDirection"))
            }
            val covered = mutableListOf<PredictionResult.Ready>()
            for (block in mergeBlocks(cached)) {
                when (val result = BoundedPredictions.current(id, span, block.points, block.flood, block.ebb)) {
                    is PredictionResult.Ready -> covered += result
                    is PredictionResult.Disjoint -> covered.addAll(result.blocks)
                    is PredictionResult.Unavailable -> if (result.reason != Reason.OUTSIDE_COVERAGE) return result
                }
            }
            val sorted = covered.sortedBy { it.coverage.start }
            if (sorted.isEmpty()) bad(Reason.OUTSIDE_COVERAGE)
            require(sorted.zipWithNext().all { (a,b) -> a.coverage.end < b.coverage.start })
            if (purpose in setOf(PredictionPurpose.QUERY, PredictionPurpose.WIDGET) && sorted.singleOrNull()?.coverage != span) bad(Reason.OUTSIDE_COVERAGE)
            return if (sorted.size == 1) sorted.single() else PredictionResult.Disjoint(sorted)
        }
        val model = record(id, ModelKind.CURRENT) ?: bad(Reason.MISSING_DOWNLOAD)
        val required = gate.getDouble("fitDays")
        val days = model.getDouble("fitDays")
        require(required in ChsCurrentFit.days.map { it.toDouble() })
        require(gate.getString("recipe") == ChsCurrentFit.STAMP && model.getString("recipe") == ChsCurrentFit.STAMP)
        val capability = when {
            days == required && !gate.isNull("slackBoundMinutes") -> StationCapabilities.BOUNDED_CURRENT
            days == required -> StationCapabilities.FINAL_CURRENT
            required > days && days == 60.0 && !gate.isNull("provisionalSlackMinutes") -> StationCapabilities.PROVISIONAL_CURRENT
            else -> bad()
        }
        if (!capability.supports(purpose)) bad(Reason.UNSUPPORTED_PURPOSE)
        val station = CurrentStation(model.constants(), model.getDouble("floodDirection"),
            model.getDouble("ebbDirection"), model.getDouble("offset"))
        val errorBound = when (capability) {
            StationCapabilities.BOUNDED_CURRENT -> gate.getInt("slackBoundMinutes")
            StationCapabilities.PROVISIONAL_CURRENT -> gate.getInt("provisionalSlackMinutes")
            else -> null
        }
        return PredictionResult.Ready(id, span, "CHS", capability, EngineAdapter.current(station, span), errorBound,
            chartModel = ChartModel.Current(station::speeds, station::events))
    }

    private suspend fun derivedGate(id: StationId, span: TimeSpan): PredictionResult {
        val gate = derived[id.value] ?: bad()
        val reference = StationId(gate.getString("reference"))
        val station = fittedTide(record(reference, ModelKind.TIDE) ?: bad(Reason.MISSING_DOWNLOAD))
        val high = gate.getLong("hwLagMinutes") * 60
        val low = gate.getLong("lwLagMinutes") * 60
        val slacks = station.extremes(span.start.minusSeconds(maxOf(high, low)), span.end.minusSeconds(minOf(high, low)))
            .map { PredictionSeries.DerivedSlack(it.time.plusSeconds(if (it.kind == ExtremeKind.HIGH) high else low), it.kind) }
            .filter { it.time in span }.sortedBy { it.time }
        return PredictionResult.Ready(id, span, reference.value, StationCapabilities.DERIVED_TIMING, PredictionSeries.Derived(slacks))
    }

    private class BadPrediction(val reason: Reason) : Exception()
    private fun bad(reason: Reason = Reason.INVALID_MODEL): Nothing = throw BadPrediction(reason)
}

private fun JSONObject.optional(key: String): Double? = if (isNull(key)) null else getDouble(key)
private fun JSONObject.constants(key: String = "constituents"): List<HarmonicConstituent> {
    val array = getJSONArray(key)
    require(key != "constituents" || array.length() > 0)
    return (0 until array.length()).map {
        val c = array.getJSONObject(it)
        HarmonicConstituent(c.getString("name"), c.getDouble("amplitude"), c.getDouble("phase"))
    }
}

private fun JSONObject.currentStation() = CurrentStation(constants(), optional("floodDirection"), optional("ebbDirection"),
    getDouble("meanFlow"), if (isNull("minorConstituents")) null else constants("minorConstituents"),
    optional("minorMeanFlow") ?: 0.0, optional("azimuth"))

private data class OnlineBlock(val points: List<CurrentPoint>, val flood: Double, val ebb: Double)

/** Adjacent weeks form one trace. On duplicate timestamps the later block supplies the sample. */
private fun mergeBlocks(blocks: List<OnlineBlock>): List<OnlineBlock> {
    val merged = mutableListOf<OnlineBlock>()
    for (block in blocks.sortedBy { it.points.first().time }) {
        val last = merged.lastOrNull()
        if (last == null || java.time.Duration.between(last.points.last().time, block.points.first().time) > java.time.Duration.ofMinutes(15)) {
            merged.add(block)
        } else {
            val points = (last.points + block.points).associateBy { it.time }.values.sortedBy { it.time }
            merged[merged.lastIndex] = block.copy(points = points)
        }
    }
    return merged
}
