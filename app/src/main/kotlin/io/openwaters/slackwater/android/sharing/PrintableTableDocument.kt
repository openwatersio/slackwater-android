package io.openwaters.slackwater.android.sharing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.floor

private const val MARGIN = 36f
private const val NIGHT = 0xffe8ecf2.toInt()
private const val DAY = 0xfff1f2f2.toInt()
private const val INK = 0xff172628.toInt()
private const val SUBTLE = 0xff415254.toInt()

/** All dimensions are PDF points. Page selections refer to indices of the original paginated document. */
class PrintableTableDocument(private val context: Context) {
    fun coverageNote(table: PrintableTable, locale: Locale = context.resources.configuration.locales[0]) =
        PrintTableFormat(context, table, ReadingPreferences(), locale).coverage()

    fun render(table: PrintableTable, preferences: ReadingPreferences,
               locale: Locale = context.resources.configuration.locales[0], pages: Set<Int>? = null): ByteArray {
        val format = PrintTableFormat(context, table, preferences, locale)
        val width = if (isLetter(locale)) 612 else 595
        val height = if (isLetter(locale)) 792 else 842
        return TableLayout(table, format, locale, width, height).render(pages)
    }

    companion object {
        fun isLetter(locale: Locale): Boolean {
            locale.getUnicodeLocaleType("ms")?.let { return it == "ussystem" }
            val uLocale = ULocale.forLocale(locale)
            if (Build.VERSION.SDK_INT >= 28) return LocaleData.getMeasurementSystem(uLocale) == LocaleData.MeasurementSystem.US
            return ULocale.addLikelySubtags(uLocale).country in setOf("US", "LR")
        }
    }
}

private data class Ink(val text: String, val x: Float, val paint: TextPaint, val night: Boolean = false)
private data class Token(val text: String, val night: Boolean = false)
private data class Row(
    val lines: List<List<Ink>>, val step: Float, val pad: Float = 2f, val background: Int = Color.TRANSPARENT,
    val rule: Boolean = false, val keepNext: Boolean = false,
) {
    val height get() = lines.size * step + pad * 2
    fun draw(canvas: Canvas, y: Float, width: Float, brush: Paint) {
        if (background != Color.TRANSPARENT) {
            brush.color = background
            canvas.drawRect(MARGIN, y, MARGIN + width, y + height, brush)
        }
        for ((index, marks) in lines.withIndex()) for (mark in marks) {
            val left = MARGIN + mark.x
            val top = y + pad + index * step
            if (mark.night) {
                brush.color = NIGHT
                canvas.drawRect(left - 1f, top, left + mark.paint.measureText(mark.text) + 1f, top + step, brush)
            }
            val baseline = top + (step - (mark.paint.descent() - mark.paint.ascent())) / 2 - mark.paint.ascent()
            canvas.drawText(mark.text, left, baseline, mark.paint)
        }
        if (rule) {
            brush.color = 0xffc7cecf.toInt()
            canvas.drawRect(MARGIN, y + height - 0.4f, MARGIN + width, y + height, brush)
        }
    }
}

private class TableLayout(
    private val table: PrintableTable, private val fmt: PrintTableFormat, private val locale: Locale,
    private val width: Int, private val height: Int,
) {
    private val content = width - 2 * MARGIN
    private val month = table.span == PrintSpan.MONTH
    private val body = type(if (month && !fmt.tide && !fmt.derived) 8f else 9f)
    private val small = type(8f, color = SUBTLE)
    private val head = type(9f, bold = true)
    private val positions = run {
        val first = if (month) .22f else .18f
        val fractions = when {
            // Slack occurs twice as often as either current maximum in a typical tidal day.
            month && !fmt.tide && !fmt.derived -> listOf(.17f, .35f, .24f, .24f)
            month -> listOf(first) + List(fmt.groups.size) { (1f - first) / fmt.groups.size }
            fmt.derived -> listOf(first, 1f - first)
            fmt.tide -> listOf(first, .58f, .24f)
            else -> listOf(first, .40f, .22f, .20f)
        }
        fractions.runningFold(0f) { x, fraction -> x + content * fraction }
    }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun type(size: Float, bold: Boolean = false, color: Int = INK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        textLocale = locale
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

    /** Use Android's Unicode line breaking for prose; time/value tokens use the separate atomic path below. */
    private fun prose(text: String, paint: TextPaint, x: Float, maxWidth: Float): List<List<Ink>> {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidth.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        return (0 until layout.lineCount).map { index ->
            listOf(Ink(text.substring(layout.getLineStart(index), layout.getLineEnd(index)).trimEnd(), x, paint))
        }
    }

    private fun tokens(values: List<Token>, paint: TextPaint, x: Float, maxWidth: Float): List<List<Ink>> {
        val lines = mutableListOf<List<Ink>>()
        var line = mutableListOf<Ink>()
        var used = 0f
        for (value in values) {
            val measured = paint.measureText(value.text)
            val fitted = if (measured > maxWidth) TextPaint(paint).apply { textScaleX = maxWidth / measured } else paint
            val w = fitted.measureText(value.text)
            if (used > 0 && used + w > maxWidth) {
                lines.add(line)
                line = mutableListOf()
                used = 0f
            }
            line.add(Ink(value.text, x + used, fitted, value.night))
            used += w + 5f
        }
        lines.add(line)
        return lines
    }

    private fun paragraph(text: String, paint: TextPaint, pad: Float = 1.5f) =
        Row(prose(text, paint, 0f, content), paint.textSize * 1.4f, pad)

    private fun cells(paint: TextPaint, background: Int = Color.TRANSPARENT,
                      cell: (Int, Float, Float) -> List<List<Ink>>): Row {
        val columns = (0 until positions.lastIndex).map { i -> cell(i, positions[i] + 3, positions[i + 1] - positions[i] - 6) }
        val lines = List(columns.maxOf { it.size }) { i -> columns.flatMap { it.getOrNull(i).orEmpty() } }
        return Row(lines, paint.textSize * 1.37f, if (month) 2f else 1.5f, background, rule = true)
    }

    private fun tableRows(): List<Row> = buildList {
        for (day in table.days) {
            if (month) {
                add(cells(body) { i, x, w ->
                    if (i == 0) {
                        val labels = prose(fmt.date(day), body, x, w).toMutableList()
                        day.entries.filter { it.kind !in fmt.groups }.forEach {
                            labels += prose(fmt.name(it.kind), body, x, w)
                            labels += tokens(listOf(Token(fmt.time(it.time), it.night)), body, x, w)
                        }
                        labels
                    } else tokens(day.entries.filter { it.kind == fmt.groups[i - 1] }
                        .map { Token(fmt.monthToken(it), it.night) }, body, x, w)
                })
            } else {
                add(Row(prose(fmt.dayLabel(day), head, 3f, content - 6), 12.5f, 3f, DAY, keepNext = day.entries.isNotEmpty()))
                for (entry in day.entries) {
                    val text = fmt.weekCells(entry)
                    add(cells(body, if (entry.night) NIGHT else Color.TRANSPARENT) { i, x, w ->
                        if (i == 1) {
                            val peak = entry.peak?.let(fmt::time)
                            val event = if (peak == null) text[i] else text[i].replace(peak, peak.replace(' ', '\u00a0'))
                            prose(event, body, x, w)
                        }
                        else tokens(listOf(Token(text[i])), body, x, w)
                    })
                }
            }
        }
        table.attribution?.takeIf { it.isNotBlank() }?.let { add(paragraph(it, small, 4f)) }
    }

    fun render(selection: Set<Int>?): ByteArray {
        val headings = fmt.headings()
        val header = buildList {
            add(paragraph(fmt.title(), type(16f, bold = true), 0f))
            add(paragraph(fmt.facts(), small, 2.5f))
            fmt.coverage()?.let { add(paragraph(it, head, 2f)) }
            add(cells(head, DAY) { i, x, w -> prose(headings[i], head, x, w) })
        }
        val footer = buildList {
            table.link?.takeIf { it.isNotBlank() }?.let { add(paragraph(it, small, 0.5f)) }
            add(paragraph(fmt.printed(), small, 0.5f))
            add(paragraph(fmt.strings.getString(R.string.pdf_disclaimer), small, 0.5f))
        }
        val footerTop = height - MARGIN - footer.sumOf { it.height.toDouble() }.toFloat() - 14f
        val firstY = MARGIN + header.sumOf { it.height.toDouble() }.toFloat()
        val available = footerTop - 8f - firstY
        require(available >= 40) { "Page metadata exceeds printable area" }
        val pages = mutableListOf<MutableList<Row>>(mutableListOf())
        var remaining = available
        fun nextPage() { pages.add(mutableListOf()); remaining = available }
        val rows = tableRows()
        for ((index, row) in rows.withIndex()) {
            val nextHeight = if (row.keepNext) rows.getOrNull(index + 1)?.height ?: 0f else 0f
            if (pages.last().isNotEmpty() && row.height + nextHeight > remaining) nextPage()
            var offset = 0
            while (offset < row.lines.size) {
                val capacity = floor((remaining - 2 * row.pad) / row.step).toInt()
                if (capacity < 1) { nextPage(); continue }
                val end = minOf(row.lines.size, offset + capacity)
                val part = row.copy(lines = row.lines.subList(offset, end))
                pages.last().add(part)
                remaining -= part.height
                offset = end
                if (offset < row.lines.size) nextPage()
            }
        }

        val bytes = ByteArrayOutputStream()
        val pdf = PdfDocument()
        try {
            for ((i, rowsOnPage) in pages.withIndex()) {
                if (selection != null && i !in selection) continue
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, i + 1).create())
                val canvas = page.canvas
                var y = MARGIN
                for (row in header + rowsOnPage) { row.draw(canvas, y, content, brush); y += row.height }
                y = footerTop
                brush.color = INK
                canvas.drawRect(MARGIN, y - 2f, width - MARGIN, y - 1.4f, brush)
                for (row in footer) { row.draw(canvas, y, content, brush); y += row.height }
                val number = fmt.strings.getString(R.string.pdf_page, i + 1, pages.size)
                canvas.drawText(number, width - MARGIN - small.measureText(number), height - MARGIN - small.descent(), small)
                pdf.finishPage(page)
            }
            pdf.writeTo(bytes)
        } finally {
            pdf.close()
        }
        return bytes.toByteArray()
    }
}
