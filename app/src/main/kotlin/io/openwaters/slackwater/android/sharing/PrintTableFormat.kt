package io.openwaters.slackwater.android.sharing

import android.content.Context
import android.content.res.Configuration
import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import android.text.format.DateFormat
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.core.station.series
import io.openwaters.slackwater.android.data.*
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

class PrintTableFormat(
    context: Context, private val table: PrintableTable, private val preferences: ReadingPreferences,
    private val locale: Locale = context.resources.configuration.locales[0],
) {
    internal val strings = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(this@PrintTableFormat.locale) })
    internal val tide = table.station.series == StationSeries.Tide
    internal val derived = table.station.kind == StationKind.DerivedGate
    private val imperial = preferences.heightUnits(locale) == HeightUnits.IMPERIAL
    private val number = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        roundingMode = RoundingMode.HALF_UP
        val precision = if (tide && !imperial) 2 else 1
        minimumFractionDigits = precision
        maximumFractionDigits = precision
    }
    private fun decimal(value: Double): String {
        val unit = if (number.maximumFractionDigits == 2) 0.01 else 0.1
        return number.format(if (kotlin.math.abs(value) < unit / 2) 0.0 else value)
    }
    private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(table.zone)
    private fun dateFormat(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        .withZone(table.zone)
    private val day = dateFormat("EEEMMMd")
    internal fun time(value: Instant) = clock.format(value)
    internal fun date(day: PrintDay) = this.day.format(day.date)
    fun dayLabel(day: PrintDay) = date(day) + "     " + strings.getString(R.string.pdf_daylight,
        day.sunrise?.let(::time) ?: "—", day.sunset?.let(::time) ?: "—")

    internal fun name(kind: PrintEventKind) = strings.getString(when (kind) {
        PrintEventKind.HIGH -> R.string.pdf_high
        PrintEventKind.LOW -> R.string.pdf_low
        PrintEventKind.SLACK -> R.string.pdf_slack
        PrintEventKind.MAX_FLOOD -> R.string.pdf_flood
        PrintEventKind.MAX_EBB -> R.string.pdf_ebb
        PrintEventKind.SOLAR_ECLIPSE -> R.string.pdf_solar
        PrintEventKind.LUNAR_ECLIPSE -> R.string.pdf_lunar
    })

    private fun set(degrees: Double): String {
        val angle = (degrees % 360.0 + 360.0) % 360.0
        if (preferences.setFormat == SetFormat.DEGREES) return String.format(locale, "%03d°", angle.roundToInt() % 360)
        val points = strings.resources.getStringArray(R.array.pdf_compass)
        return points[(angle / 22.5).roundToInt() % points.size]
    }

    private fun value(entry: PrintEntry): String? {
        entry.heightM?.let { return decimal(if (imperial) it / 0.3048 else it) }
        return entry.speedKnots?.let { knots -> decimal(when (preferences.speedUnit) {
            SpeedUnit.KNOTS -> knots
            SpeedUnit.KILOMETRES_PER_HOUR -> knots * 1.852
            SpeedUnit.METRES_PER_SECOND -> knots * 1.852 / 3.6
        }) }
    }

    internal fun monthToken(entry: PrintEntry): String {
        val showSet = when (entry.kind) {
            PrintEventKind.MAX_FLOOD -> table.floodDegrees == null
            PrintEventKind.MAX_EBB -> table.ebbDegrees == null
            else -> false
        }
        val bearing = if (showSet) entry.setDegrees?.let(::set) else null
        return listOfNotNull(time(entry.time), value(entry), bearing).joinToString(" ")
    }
    fun weekCells(entry: PrintEntry): List<String> {
        val label = entry.peak?.let { strings.getString(R.string.pdf_peak, name(entry.kind), time(it)) } ?: name(entry.kind)
        return buildList {
            add(time(entry.time)); add(label)
            if (!derived) add(value(entry) ?: "—")
            if (!tide && !derived) add(entry.setDegrees?.let(::set) ?: "—")
        }
    }

    internal fun scheduleValue(entry: PrintEntry): String? {
        val unit = if (tide) {
            if (imperial) "ft" else "m"
        } else when (preferences.speedUnit) {
            SpeedUnit.KNOTS -> "kn"
            SpeedUnit.KILOMETRES_PER_HOUR -> "km/h"
            SpeedUnit.METRES_PER_SECOND -> "m/s"
        }
        return value(entry)?.let { "$it $unit" }
    }

    fun scheduleRow(entry: PrintEntry): String {
        val cells = weekCells(entry)
        return listOfNotNull(cells[0], cells[1], scheduleValue(entry),
            cells.getOrNull(3)?.takeUnless { it == "—" }).joinToString(" · ")
    }

    internal val groups = when {
        tide -> listOf(PrintEventKind.HIGH, PrintEventKind.LOW)
        derived -> listOf(PrintEventKind.SLACK)
        else -> listOf(PrintEventKind.SLACK, PrintEventKind.MAX_FLOOD, PrintEventKind.MAX_EBB)
    }
    internal fun headings() = if (table.span == PrintSpan.MONTH) listOf(strings.getString(R.string.pdf_day)) + groups.map(::name)
        else buildList {
            add(strings.getString(R.string.pdf_time)); add(strings.getString(R.string.pdf_event))
            if (!derived) add(strings.getString(if (tide) R.string.pdf_height else R.string.pdf_speed))
            if (!tide && !derived) add(strings.getString(R.string.pdf_set))
        }

    private fun interval(skeleton: String) = DateIntervalFormat.getInstance(skeleton, locale).apply {
        timeZone = android.icu.util.TimeZone.getTimeZone(table.zone.id)
    }

    private fun TimeSpan.dateInterval() = DateInterval(start.toEpochMilli(), end.minusNanos(1).toEpochMilli())

    internal fun title(): String {
        val dates = if (table.span == PrintSpan.MONTH) dateFormat("yMMMM").format(table.range.start)
            else interval("yMMMd").format(table.range.dateInterval())
        return "${table.station.name} · $dates"
    }

    internal fun facts(): String {
        val zone = DateTimeFormatter.ofPattern("z", locale).withZone(table.zone)
        val zones = listOf(zone.format(table.range.start), zone.format(table.range.end.minusNanos(1))).distinct().joinToString("/")
        val unit = if (tide) {
            if (imperial) R.string.pdf_feet else R.string.pdf_metres
        } else when (preferences.speedUnit) {
            SpeedUnit.KNOTS -> R.string.pdf_knots
            SpeedUnit.KILOMETRES_PER_HOUR -> R.string.pdf_kmh
            SpeedUnit.METRES_PER_SECOND -> R.string.pdf_ms
        }
        val flood = table.floodDegrees
        val ebb = table.ebbDegrees
        val sets = if (derived || tide) null else when {
            flood != null && ebb != null -> strings.getString(R.string.pdf_sets, set(flood), set(ebb))
            flood != null -> strings.getString(R.string.pdf_flood_set, set(flood))
            ebb != null -> strings.getString(R.string.pdf_ebb_set, set(ebb))
            else -> null
        }
        return listOfNotNull(table.station.region, zones, if (derived) null else strings.getString(unit), table.datum,
            if (derived) strings.getString(R.string.pdf_derived, table.source) else table.source, sets)
            .filter(String::isNotBlank).joinToString(" · ")
    }

    internal fun coverage(): String? {
        if (table.covered.singleOrNull() == table.range) return null
        if (table.covered.isEmpty()) return strings.getString(R.string.pdf_empty)
        val formatter = interval("MMMd")
        val parts = table.covered.map { formatter.format(it.dateInterval()) }
        return strings.getString(R.string.pdf_coverage, parts.joinToString(", "))
    }

    internal fun printed() = strings.getString(R.string.pdf_printed, dateFormat("yMMMd").format(table.printedAt))
}
