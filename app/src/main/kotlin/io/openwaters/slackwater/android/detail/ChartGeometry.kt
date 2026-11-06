package io.openwaters.slackwater.android.detail

import android.graphics.*
import io.openwaters.slackwater.android.core.timeline.*
import io.openwaters.slackwater.android.core.station.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

internal data class ChartScale(val centre: Float, val half: Float) {
    fun y(value: Double) = 245f - (72 * (value - centre) / half).coerceIn(-72.0, 72.0).toFloat()
    fun shouldAdopt(next: ChartScale, padding: Double) =
        abs(next.centre - centre) + next.half / padding > half * 0.97 || next.half < half * 0.65
}

internal fun ChartScene.fit(selected: Instant, width: Float, fixed: Boolean): ChartScale {
    if (derived) return ChartScale(0f, 1.05f)
    val radius = (max(3.0, width / 36.0) * 3600 + 3600).toLong()
    val visible =
        if (fixed) points else points.filter { abs(it.at.epochSecond - selected.epochSecond) <= radius }
    val low = visible.minOfOrNull { it.value } ?: 0.0
    val high = visible.maxOfOrNull { it.value } ?: 0.0
    return if (tide) ChartScale(
        ((high + low) / 2).toFloat(),
        max(0.01, (high - low) * 0.53).toFloat()
    )
    else ChartScale(0f, (maxOf(0.01, abs(low), abs(high)) * 1.05).toFloat())
}

private val paper = Color.WHITE
private val blue = 0xff38bdf8.toInt()
private val go = 0xff88b868.toInt()
private val yellow = 0xfff5c96b.toInt()
private val amber = 0xfffbbf24.toInt()
private val teal = 0xff2dd4bf.toInt()
private val navy = 0xff05122a.toInt()
private fun mix(a: Int, b: Int, f: Double): Int {
    fun n(x: Int, y: Int) = (x + (y - x) * f.coerceIn(0.0, 1.0)).roundToInt()
    return Color.rgb(
        n(Color.red(a), Color.red(b)),
        n(Color.green(a), Color.green(b)),
        n(Color.blue(a), Color.blue(b))
    )
}

private fun opacity(color: Int, alpha: Double) = Color.argb(
    (255 * alpha.coerceIn(0.0, 1.0)).roundToInt(),
    Color.red(color),
    Color.green(color),
    Color.blue(color)
)

private fun ramp(v: Double, tide: Boolean): Int {
    val start = if (tide) 0.6 else 0.5
    val full = if (tide) 1.0 else 3.0
    val orange = if (tide) 1.5 else 8.0
    val end = if (tide) 1.8 else 12.0
    return when {
        v < full -> opacity(yellow, (v - start) / (full - start))
        v < orange -> mix(yellow, 0xffe8763c.toInt(), (v - full) / (orange - full))
        else -> mix(0xffe8763c.toInt(), 0xffc93a32.toInt(), (v - orange) / (end - orange))
    }
}

private val skyColors = listOf(
    -18 to (0xff04060f.toInt() to 0xff0b1023.toInt()),
    -12 to (0xff0b1430.toInt() to 0xff2a2a52.toInt()),
    -6 to (0xff17264a.toInt() to 0xff8d4a63.toInt()),
    0 to (0xff2b4a7a.toInt() to 0xfff8a15f.toInt()),
    10 to (0xff2f7fd4.toInt() to 0xffbde3fb.toInt()),
)

private fun skyColors(sky: SkyFrame): Pair<Int, Int> {
    val alt = sky.sun.altitude + (-9 - sky.sun.altitude) * sky.obscuration.pow(4)
    val i =
        skyColors.indexOfFirst { it.first >= alt }.let { if (it < 0) 4 else it.coerceAtLeast(1) }
    val (a, colorsA) = skyColors[i - 1]
    val (b, colorsB) = skyColors[i]
    val f = (alt - a) / (b - a)
    val alpha = 0.55 + 0.45 * (-alt / 6).coerceIn(0.0, 1.0)
    return mix(navy, mix(colorsA.first, colorsB.first, f), alpha) to mix(
        navy,
        mix(colorsA.second, colorsB.second, f),
        alpha
    )
}

internal fun warningInk(rate: Double, sky: SkyFrame?): Int {
    val color = ramp(maxOf(rate, 1.0), true)
    val ground = Color.luminance(sky?.let { skyColors(it).second } ?: navy)
    for (percent in 25..100 step 5) {
        val ink = mix(color, paper, percent / 100.0)
        if ((Color.luminance(ink) + 0.05) / (ground + 0.05) >= 4.5) return ink
    }
    return paper
}

internal class ChartDrawing(
    private val c: Canvas,
    private val width: Float,
    private val selected: Instant,
    private val now: Instant
) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; textAlign = Paint.Align.CENTER
    }

    private fun x(time: Instant) =
        width / 2 + (time.toEpochMilli() - selected.toEpochMilli()) / 200000f

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, weight: Float = 1f) {
        pen.color = color; pen.strokeWidth = weight; c.drawLine(x1, y1, x2, y2, pen)
    }

    private fun disc(x: Float, y: Float, r: Float, color: Int) {
        pen.color = color; c.drawCircle(x, y, r, pen)
    }

    private fun halo(x: Float, y: Float, r: Float) {
        pen.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        disc(x, y, r, paper)
        pen.xfermode = null
    }

    private fun label(s: String, x: Float, y: Float, size: Float, color: Int = paper) {
        pen.color = color; pen.textSize = size
        pen.setShadowLayer(1.4f, 0f, 0.7f, navy)
        c.drawText(s, x, y, pen)
        pen.clearShadowLayer()
    }

    fun sky(sky: SkyFrame?, horizon: Float, twinkle: Double, leadWidth: Float, leadHeight: Float) {
        c.drawColor(navy)
        if (sky == null) return
        val (top, bottom) = skyColors(sky)
        val alt = sky.sun.altitude + (-9 - sky.sun.altitude) * sky.obscuration.pow(4)
        pen.color = paper
        pen.shader = LinearGradient(0f, 0f, 0f, horizon, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width, horizon + 240, pen); pen.shader = null
        val bodyLayer = c.saveLayer(0f, 0f, width, horizon + 30, null)
        fun cx(body: SkyBody, r: Float): Float {
            val px = (body.x * (width + 2 * r) - r).toFloat()
            if (!body.hold || body.altitude <= 0 || width < 4 * r) return px
            val inset = r * (3 * (body.altitude / 10).coerceAtMost(1.0) - 1).toFloat()
            return px.coerceIn(inset, width - inset)
        }

        fun cy(body: SkyBody) = horizon - (3 * body.altitude).toFloat()
        for ((index, star) in sky.stars.withIndex()) disc(
            (star.body.x * width).toFloat(),
            cy(star.body),
            max(0.5, 1.6 - star.magnitude * 0.3).toFloat(),
            opacity(
                paper,
                (0.86 + 0.14 * sin(index * 2.3 + twinkle)) * 0.7 * ((-alt - 6) / 12).coerceIn(
                    0.0,
                    1.0
                ) * ((star.body.altitude + 10) / 50).coerceIn(0.0, 1.0)
            )
        )
        val sx = cx(sky.sun, 8f);
        val sy = cy(sky.sun)
        if (sky.sun.altitude > -9) {
            pen.shader = RadialGradient(
                sx,
                sy,
                27f,
                opacity(yellow, 0.24),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            disc(sx, sy, 27f, paper); pen.shader = null; disc(sx, sy, 8f, 0xfff0c860.toInt())
        }
        var mx = cx(sky.moon, 11f);
        var my = cy(sky.moon)
        if (sky.obscuration > 0) {
            val angle = atan2(my - sy, mx - sx)
            mx = sx + cos(angle) * 19 * (1 - sky.obscuration).toFloat(); my =
                sy + sin(angle) * 19 * (1 - sky.obscuration).toFloat()
            disc(mx, my, 10f, 0xff04060f.toInt())
        } else if (sky.moon.altitude > -4) {
            val glare = ((hypot(mx - sx, my - sy) - 19) / 19).coerceIn(0f, 1f)
            val glow = 12f + 20f * sky.phase.toFloat()
            val light = mix(0xffe6eeff.toInt(), 0xffe8b08c.toInt(), sky.wash)
            pen.shader = RadialGradient(
                mx,
                my,
                glow,
                opacity(
                    light,
                    glare * (0.1 + 0.66 * sky.phase) * (1 - 0.75 * sky.umbra.coerceIn(0.0, 1.0))
                ),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            disc(mx, my, glow, paper); pen.shader = null
            disc(mx, my, 10f, opacity(navy, 0.85 * glare))
            val tilt = Math.toRadians(sky.limbDegrees)
            val angle = when {
                sky.sun.facesSouth != sky.moon.facesSouth && sky.sun.altitude > 0 ->
                    atan2(sin(tilt), abs(cos(tilt)) * ((sx - mx) / 40).coerceIn(-1f, 1f))

                sky.moon.facesSouth -> PI - tilt
                else -> tilt
            }
            c.save(); c.rotate(Math.toDegrees(angle).toFloat(), mx, my)
            for (i in -9..9) {
                val half = sqrt(100.0 - i * i).toFloat()
                line(
                    mx + half * (1 - 2 * sky.phase.toFloat()),
                    my + i,
                    mx + half,
                    my + i,
                    opacity(light, glare.toDouble()),
                    1.3f
                )
            }
            c.restore()
            if (sky.wash > 0) disc(mx, my, 10f, opacity(0xff6b2a18.toInt(), 0.42 * sky.wash))
            if (sky.umbra > 0) {
                c.save(); c.clipPath(Path().apply { addCircle(mx, my, 10f, Path.Direction.CW) })
                disc(
                    mx + 20f * (1 - sky.umbra.coerceIn(0.0, 1.0)).toFloat(),
                    my,
                    10f,
                    opacity(0xff6b2a18.toInt(), 0.94)
                ); c.restore()
            }
        }
        // The disc stays at its true sky position. Fade celestial detail behind the measured lead.
        if (leadWidth > 0 && leadHeight > 0) {
            c.save()
            c.translate(width / 2, leadHeight / 2)
            c.scale(leadWidth * 0.82f + 14, leadHeight * 0.85f + 10)
            pen.color = paper
            pen.shader = RadialGradient(
                0f, 0f, 1f, intArrayOf(paper, paper, Color.TRANSPARENT),
                floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
            pen.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            c.drawCircle(0f, 0f, 1f, pen)
            pen.xfermode = null; pen.shader = null
            c.restore()
        }
        c.restoreToCount(bodyLayer)
    }

    fun plot(
        scene: ChartScene,
        scale: ChartScale,
        days: List<TimelineDay>,
        zone: ZoneId,
        locale: Locale,
        clock: DateTimeFormatter,
        feet: Boolean,
        speed: SpeedUnitKind,
        reach: Boolean,
        alerts: List<Instant>,
        eclipses: List<Instant>,
        showSky: Boolean,
        controls: Boolean,
        parked: Boolean,
        relativeDays: List<String>
    ) {
        val extra = if (reach) 20f else 0f
        val floor = 413f + extra
        val data = scene.points
        val radius = (width * 100 + 3800).toLong()
        val start = selected.minusSeconds(radius);
        val end = selected.plusSeconds(radius)
        val insertion = data.binarySearchBy(start) { it.at }
        val first = (if (insertion < 0) -insertion - 2 else insertion).coerceAtLeast(0)
        val path = Path()
        var previous: ChartPoint? = null
        val segments = ArrayList<Pair<ChartPoint, ChartPoint>>()
        for (i in first until data.size) {
            val p = data[i]
            if (previous == null) path.moveTo(x(p.at), scale.y(p.value)) else {
                path.lineTo(x(p.at), scale.y(p.value)); segments.add(previous to p)
            }
            previous = p
            if (p.at > end) break
        }
        val layer = c.saveLayer(0f, 155f, width, floor, null)
        if (scene.derived && data.isNotEmpty()) {
            if (x(data.first().at) > 0) line(
                0f,
                scale.y(0.0),
                x(data.first().at),
                scale.y(0.0),
                blue,
                2.5f
            )
            if (x(data.last().at) < width) line(
                x(data.last().at),
                scale.y(0.0),
                width,
                scale.y(0.0),
                blue,
                2.5f
            )
        }
        if (segments.isNotEmpty()) {
            path.lineTo(
                x(segments.last().second.at),
                floor
            ); path.lineTo(x(segments.first().first.at), floor); path.close()
            if (scene.tide && abs(scale.centre) <= scale.half) {
                val datum = scale.y(0.0)
                c.save(); c.clipRect(0f, datum, width, 320f); c.clipOutPath(path)
                pen.color = paper
                pen.shader = LinearGradient(
                    0f,
                    datum,
                    0f,
                    320f,
                    Color.TRANSPARENT,
                    opacity(amber, 0.5),
                    Shader.TileMode.CLAMP
                )
                c.drawRect(
                    x(segments.first().first.at),
                    datum,
                    x(segments.last().second.at),
                    320f,
                    pen
                )
                pen.shader = null; c.restore()
            }
            c.save(); c.clipPath(path); pen.color = navy; c.drawRect(0f, 155f, width, floor, pen)
            val bottom = if (scene.tide) 320f else scale.y(0.0)
            pen.color = paper; pen.shader = LinearGradient(
                0f,
                170f,
                0f,
                bottom,
                opacity(blue, 0.5),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            if (!scene.tide) c.drawRect(0f, 170f, width, bottom, pen); pen.shader = null
            for ((a, b) in scene.sun.zipWithNext()) if (a.stop != b.stop && x(b.at) >= -14 && x(a.at) <= width + 14) {
                val color =
                    if (a.stop == ChartStop.Sunrise) opacity(0xffa8cae0.toInt(), 0.22) else opacity(
                        0xff00101f.toInt(),
                        0.7
                    )
                val left = x(a.at) - 13.5f;
                val right = x(b.at) + 13.5f
                val edge = (27f / (right - left)).coerceAtMost(0.5f)
                val horizontal = LinearGradient(
                    left,
                    0f,
                    right,
                    0f,
                    intArrayOf(Color.TRANSPARENT, color, color, Color.TRANSPARENT),
                    floatArrayOf(0f, edge, 1f - edge, 1f),
                    Shader.TileMode.CLAMP
                )
                val vertical = LinearGradient(
                    0f,
                    170f + (floor - 170f) * 0.35f,
                    0f,
                    floor,
                    paper,
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                pen.shader = ComposeShader(horizontal, vertical, PorterDuff.Mode.DST_IN)
                c.drawRect(left, 170f, right, floor, pen); pen.shader = null
            }
            c.restore()
        }
        if (!scene.derived && abs(scale.centre) <= scale.half) {
            pen.pathEffect = DashPathEffect(floatArrayOf(1f, 3f), 0f)
            line(0f, scale.y(0.0), width, scale.y(0.0), opacity(paper, 0.35))
            pen.pathEffect = null
        }
        val slacks =
            scene.water.filter { it.stop == ChartStop.Slack && it.at >= start && it.at <= end }
        val runs = scene.windows.filter { it.end >= start && it.start <= end }
        val gaps = scene.slackInkEdges.filter { it.second >= start && it.first <= end }
            .flatMap { (begin, finish) ->
                listOf(
                    begin.minusSeconds(500) to begin,
                    finish to finish.plusSeconds(500)
                )
            }
        pen.strokeCap = Paint.Cap.BUTT
        for ((a, b) in segments) {
            val cuts = mutableListOf(a.at, b.at)
            if (now > a.at && now < b.at) cuts.add(now)
            if (!scene.derived && !scene.tide) {
                for ((left, right) in gaps) for (t in listOf(
                    left,
                    right
                )) if (t > a.at && t < b.at) cuts.add(t)
                for (run in runs) for (t in listOf(
                    run.start,
                    run.end
                )) if (t > a.at && t < b.at) cuts.add(t)
                for (mark in slacks) for (t in listOf(
                    mark.at.minusSeconds(600),
                    mark.at.plusSeconds(600)
                )) if (t > a.at && t < b.at) cuts.add(t)
            }
            cuts.sort()
            for ((s, e) in cuts.zipWithNext()) {
                if (s == e) continue
                val mid = Instant.ofEpochMilli((s.toEpochMilli() + e.toEpochMilli()) / 2)
                if (gaps.any { mid >= it.first && mid < it.second }) continue
                val alpha = if (mid < now) 0.35 else 1.0
                val marked =
                    !scene.derived && slacks.any { abs(it.at.epochSecond - mid.epochSecond) <= 600 }
                val window = runs.any { mid >= it.start && mid <= it.end }
                val base = when {
                    marked -> go; window || scene.derived -> blue; scene.tide -> if ((a.strength + b.strength) / 2 >= 0.6) ramp(
                        maxOf(1.0, (a.strength + b.strength) / 2),
                        true
                    ) else blue; else -> yellow
                }
                val y1 = scale.y(scene.at(s) ?: a.value);
                val y2 = scale.y(scene.at(e) ?: b.value)
                line(x(s), y1, x(e), y2, opacity(base, alpha), if (window || marked) 3.5f else 2.5f)
                if (!scene.tide && !scene.derived && !window && !marked) {
                    val r = ramp((a.strength + b.strength) / 2, scene.tide)
                    line(x(s), y1, x(e), y2, opacity(r, alpha * Color.alpha(r) / 255), 2f)
                }
            }
        }
        pen.strokeCap = Paint.Cap.ROUND
        val rows = mutableMapOf<Int, Float>()
        val lastRight = mutableMapOf<Int, Float>()
        fun measured(
            text: String,
            px: Float,
            y: Float,
            size: Float,
            color: Int,
            row: Int,
            spacing: Float = 0f
        ): Boolean {
            pen.textSize = size;
            val half = pen.measureText(text) / 2
            if (px - half < 1 || px + half > width - 1 || px - half < (lastRight[row]
                    ?: -999f) + 4 ||
                px - (rows[row] ?: -999f) < spacing
            ) return false
            label(text, px, y, size, color); rows[row] = px; lastRight[row] = px + half; return true
        }
        for (mark in scene.water) {
            if (data.isEmpty()) break
            val px = x(mark.at)
            if (px !in 27f..(width - 27f) || mark.at < data.first().at.plusSeconds(5400) ||
                mark.at > data.last().at.minusSeconds(5400)
            ) continue
            val value = scene.at(mark.at) ?: continue
            val y = scale.y(value)
            val alpha = if (mark.at < now) 0.45 else 1.0
            val ink = opacity(
                when (mark.stop) {
                    ChartStop.High -> teal; ChartStop.Low -> amber; ChartStop.Open, ChartStop.Slack -> go; else -> paper
                }, alpha
            )
            val peak = mark.stop == ChartStop.Flood || mark.stop == ChartStop.Ebb
            val turn = mark.stop == ChartStop.High || mark.stop == ChartStop.Low
            if (turn || scene.derived && mark.stop == ChartStop.Slack) {
                halo(px, y, 5f); disc(px, y, 2.5f, ink)
            }
            if (peak || turn) {
                val below = if (peak) (scene.at(mark.at.minusSeconds(600))
                    ?: value) <= value else mark.stop == ChartStop.High
                val side = if (below) 1 else -1
                val reading = if (turn) formatHeightValue(
                    value,
                    feet,
                    locale
                ) else formatSpeedValue(abs(value), speed, locale)
                if (measured(
                        reading,
                        px,
                        y + side * 30 + 6,
                        18f,
                        opacity(paper, alpha),
                        if (below) 3 else 4
                    )
                ) {
                    if (turn) {
                        label(mark.stop.glyph, px, y + side * 15 + 4, 12f, ink)
                        val cap = y + side * 15 + (if (mark.stop == ChartStop.High) -7 else 5)
                        line(px - 3f, cap, px + 3f, cap, ink)
                    } else (scene.series as? io.openwaters.slackwater.android.core.prediction.PredictionSeries.Current)?.let {
                        val bearing =
                            if (mark.stop == ChartStop.Flood) it.floodBearing else it.ebbBearing
                        if (bearing != null) {
                            c.save(); c.rotate(bearing.toFloat(), px, y + side * 15); label(
                                "↑",
                                px,
                                y + side * 15 + 4,
                                12f,
                                ink
                            ); c.restore()
                        }
                    }
                }
            }
            val run = runs.firstOrNull { mark.at >= it.start && mark.at <= it.end }
            val multiple =
                run != null && slacks.count { it.at >= run.start && it.at <= run.end } > 1
            val axis = turn || peak && reach || mark.stop == ChartStop.Open && !multiple ||
                    mark.stop == ChartStop.Slack && (run == null || multiple)
            if (axis) measured(
                clock.format(mark.at.atZone(zone)),
                px,
                if (peak) 358f else 338f,
                if (peak) 12f else 14f,
                ink,
                if (peak) 2 else 1,
                64f
            )
        }
        val weekdayFormat = DateTimeFormatter.ofPattern("EEE", locale)
        val dayFormat = DateTimeFormatter.ofPattern(
            android.text.format.DateFormat.getBestDateTimePattern(
                locale,
                "MMMd"
            ), locale
        )
        val anchors = days.map { day -> day to x(day.dateAnchor(days, zone)) }
            .filter { (_, px) -> px in -80f..width + 80f }
        for ((day, px) in anchors) {
            if (px !in 0f..width) continue
            val offset = day.date.toEpochDay() - now.atZone(zone).toLocalDate().toEpochDay()
            val dayName =
                if (offset in -1L..1L) relativeDays[offset.toInt() + 1] else day.date.format(
                    weekdayFormat
                )
            measured(dayName, px, 364f + extra, 13f, paper, 5)
            measured(day.date.format(dayFormat), px, 381f + extra, 10f, paper, 6)
        }
        if (showSky) for (mark in scene.sun) {
            val px = x(mark.at)
            if (px !in -70f..width + 70f) continue
            // Reserve each local date's measured rectangle before admitting an adjacent sun clock.
            val text = mark.stop.glyph + " " + clock.format(mark.at.atZone(zone)); pen.textSize =
                11f
            val sunHalf = pen.measureText(text) / 2
            if (anchors.any { (day, anchor) ->
                    val dateText = day.date.format(dayFormat)
                    abs(anchor - px) < sunHalf + pen.measureText(dateText) / 2 + 4
                }) continue
            measured(text, px, 381f + extra, 11f, paper, 7)
        }
        for (t in eclipses) if (x(t) in 0f..width) label("🌘", x(t), 395f + extra, 7f)
        for (t in alerts) if (x(t) in -5f..width + 5f) {
            val px = x(t)
            line(px, 168f, px, 320f, opacity(paper, 0.55), 1.5f)
            pen.color = opacity(paper, 0.90)
            c.drawRoundRect(px - 3f, 157f, px + 3f, 163f, 3f, 3f, pen)
            line(px - 3.5f, 163.5f, px + 3.5f, 163.5f, pen.color)
            disc(px, 165f, 1f, pen.color)
        }
        if (x(now) in 0f..width) scene.at(now)
            ?.let { halo(x(now), scale.y(it), 6f); disc(x(now), scale.y(it), 3.5f, paper) }
        c.restoreToCount(layer)
        if (controls || parked) line(
            width / 2,
            160f,
            width / 2,
            if (parked) floor else 320f,
            opacity(paper, 0.18)
        )
        scene.at(selected)?.let {
            val r = if (scene.tide) 6.5f else 5f; disc(
            width / 2,
            scale.y(it),
            r + 3,
            opacity(paper, 0.30)
        ); disc(width / 2, scale.y(it), r, paper)
        }
    }
}
