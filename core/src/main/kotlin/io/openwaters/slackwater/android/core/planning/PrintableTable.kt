package io.openwaters.slackwater.android.core.planning

import io.openwaters.almanac.*
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.ExtremeKind
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

enum class PrintSpan { WEEK, MONTH }
enum class PrintEventKind { HIGH, LOW, SLACK, MAX_FLOOD, MAX_EBB, SOLAR_ECLIPSE, LUNAR_ECLIPSE }

data class PrintEntry(
    val time: Instant, val kind: PrintEventKind, val heightM: Double? = null, val speedKnots: Double? = null,
    val setDegrees: Double? = null, val peak: Instant? = null, val night: Boolean = false,
)

data class PrintDay(val date: LocalDate, val sunrise: Instant?, val sunset: Instant?, val entries: List<PrintEntry>)

/** Range and coverage are half-open. Entries retain engine units for the screen and PDF to format alike. */
data class PrintableTable(
    val station: StationIdentity, val span: PrintSpan, val range: TimeSpan, val zone: ZoneId,
    val covered: List<TimeSpan>, val days: List<PrintDay>, val floodDegrees: Double?, val ebbDegrees: Double?,
    val source: String, val datum: String?, val link: String?, val printedAt: Instant,
    val attribution: String? = null, val capabilities: Set<StationCapabilities> = emptySet(),
    val unavailableReason: PredictionResult.Reason? = null,
)

fun printRange(span: PrintSpan, anchor: Instant, zone: ZoneId): TimeSpan {
    val date = anchor.atZone(zone).toLocalDate()
    val start = if (span == PrintSpan.MONTH) date.withDayOfMonth(1) else date
    val end = if (span == PrintSpan.MONTH) start.plusMonths(1) else start.plusDays(7)
    return TimeSpan(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant())
}

private fun PredictionSeries.entries(): List<PrintEntry> = when (this) {
    is PredictionSeries.Tide -> events.map {
        PrintEntry(it.time, if (it.kind == ExtremeKind.HIGH) PrintEventKind.HIGH else PrintEventKind.LOW, heightM = it.heightMetres)
    }
    is PredictionSeries.Derived -> slacks.map { PrintEntry(it.time, PrintEventKind.SLACK) }
    is PredictionSeries.Current -> events.map {
        when (it.kind) {
            CurrentEventKind.SLACK -> PrintEntry(it.time, PrintEventKind.SLACK)
            CurrentEventKind.MAX_FLOOD -> PrintEntry(it.time, PrintEventKind.MAX_FLOOD, speedKnots = abs(it.speedKnots), setDegrees = floodBearing)
            CurrentEventKind.MAX_EBB -> PrintEntry(it.time, PrintEventKind.MAX_EBB, speedKnots = abs(it.speedKnots), setDegrees = ebbBearing)
        }
    }
}

private fun displayCoverage(blocks: List<PredictionResult.Ready>, range: TimeSpan): List<TimeSpan> {
    val result = mutableListOf<TimeSpan>()
    for (block in blocks.sortedBy { it.coverage.start }) {
        val start = maxOf(range.start, block.coverage.start)
        val end = minOf(range.end, block.coverage.end.plusNanos(1))
        if (start >= end) continue
        val last = result.lastOrNull()
        // Official online requests abut one sample apart. Merge the caption, but still filter events by each original block.
        val join = if (block.capabilities == StationCapabilities.ONLINE_CURRENT) Duration.ofMinutes(15) else Duration.ZERO
        if (last != null && start <= last.end.plus(join)) result[result.lastIndex] = TimeSpan(last.start, maxOf(last.end, end))
        else result += TimeSpan(start, end)
    }
    return result
}

private fun sourceLabel(station: StationIdentity, publisher: String?, reference: String?): String {
    if (station.kind == StationKind.DerivedGate) return reference.orEmpty()
    if (station.kind == StationKind.CanadianTide || station.kind == StationKind.CanadianCurrent) return "CHS"
    val id = station.id.value.removePrefix("current:")
    if (!id.contains('/')) return id
    val code = id.substringAfter('/')
    if (id.startsWith("noaa/")) return "NOAA $code"
    return publisher?.takeIf { it.isNotBlank() }?.let { "$it $code" } ?: id
}

suspend fun printableTable(
    station: StationIdentity, span: PrintSpan, anchor: Instant, printedAt: Instant, repository: PredictionRepository,
    datum: String? = null, link: String? = null, publisher: String? = null, referencePortName: String? = null,
    attribution: String? = null,
): PrintableTable {
    val zone = station.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
    val range = printRange(span, anchor, zone)
    val result = repository.load(station.id, range, PredictionPurpose.PRINT)
    val blocks = when (result) {
        is PredictionResult.Ready -> listOf(result)
        is PredictionResult.Disjoint -> result.blocks
        is PredictionResult.Unavailable -> emptyList()
    }
    val covered = displayCoverage(blocks, range)
    val water = blocks.flatMap { block -> block.series.entries().filter { it.time in block.coverage } }
    val days = if (covered.isEmpty()) emptyList() else {
        val observer = if (station.latitude in -90.0..90.0 && station.longitude in -180.0..180.0)
            Observer(station.latitude, station.longitude) else null
        val sun = observer?.let {
            sunEvents(range.start.minusSeconds(86400), range.end.plusSeconds(86400), it)
                .filter { edge -> edge.kind == SunEventKind.RISE || edge.kind == SunEventKind.SET }
        }.orEmpty()
        val intervals = sun.zipWithNext()
        val sky = observer?.let {
            val lunar = SunWindows.visibleLunarEclipses(range, it).map { eclipse ->
                PrintEntry(eclipse.u1 ?: eclipse.p1, PrintEventKind.LUNAR_ECLIPSE, peak = eclipse.peak)
            }
            val solar = solarEclipses(range.start, range.end.plusSeconds(86400), it).map { eclipse ->
                PrintEntry(eclipse.c1, PrintEventKind.SOLAR_ECLIPSE, peak = eclipse.peak)
            }
            (lunar + solar).filter { entry -> blocks.any { entry.time in it.coverage } }
        }.orEmpty()
        val sunshine = sun.groupBy { it.time.atZone(zone).toLocalDate() }
        (water + sky).filter { it.time >= range.start && it.time < range.end }
            .distinctBy { it.time to it.kind }.sortedBy { it.time }
            .map { row ->
                val interval = intervals.firstOrNull { (a, b) -> row.time >= a.time && row.time < b.time }
                val night = interval?.let { it.first.kind == SunEventKind.SET }
                    ?: (observer != null && !sunIsUp(row.time, observer))
                row.copy(night = night)
            }
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .map { (date, rows) ->
                val edges = sunshine[date].orEmpty()
                PrintDay(date, edges.firstOrNull { it.kind == SunEventKind.RISE }?.time,
                    edges.firstOrNull { it.kind == SunEventKind.SET }?.time, rows)
            }
    }
    val currents = blocks.mapNotNull { it.series as? PredictionSeries.Current }
    // Each online block retains its own sets on its rows. A header set is meaningful only when all blocks agree.
    val flood = currents.map { it.floodBearing }.distinct().singleOrNull()
    val ebb = currents.map { it.ebbBearing }.distinct().singleOrNull()
    return PrintableTable(station, span, range, zone, covered, days, flood, ebb,
        sourceLabel(station, publisher, referencePortName ?: blocks.firstOrNull()?.source),
        datum, link, printedAt, attribution, blocks.map { it.capabilities }.toSet(), (result as? PredictionResult.Unavailable)?.reason)
}
