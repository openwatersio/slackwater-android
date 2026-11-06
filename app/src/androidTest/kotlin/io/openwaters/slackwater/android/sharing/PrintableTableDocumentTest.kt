package io.openwaters.slackwater.android.sharing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.TimeSpan
import io.openwaters.slackwater.android.core.station.*
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.data.SetFormat
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PrintableTableDocumentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val document = PrintableTableDocument(context)
    private val zone = ZoneId.of("America/Vancouver")
    private val first = LocalDate.of(2025, 11, 1)
    private val station = StationIdentity(StationId("fixture/current"), "Boundary Pass", "Gulf Islands",
        48.7, -123.0, StationKind.Current, timezone = zone.id)

    private fun table(span: PrintSpan, busy: Boolean = false): PrintableTable {
        val count = if (span == PrintSpan.MONTH) 30 else 7
        val days = List(count) { index ->
            val date = first.plusDays(index.toLong())
            val start = date.atStartOfDay(zone).toInstant()
            val entries = List(if (busy) 75 else 8) { n ->
                val kind = listOf(PrintEventKind.SLACK, PrintEventKind.MAX_FLOOD, PrintEventKind.SLACK, PrintEventKind.MAX_EBB)[n % 4]
                val max = kind != PrintEventKind.SLACK
                PrintEntry(start.plusSeconds(n * if (busy) 1100L else 10800L), kind,
                    speedKnots = if (max) 2.4 else null, setDegrees = if (max) 110.0 else null, night = n < 2)
            }
            PrintDay(date, start.plusSeconds(27000), start.plusSeconds(60000), entries)
        }
        val range = TimeSpan(first.atStartOfDay(zone).toInstant(), first.plusDays(count.toLong()).atStartOfDay(zone).toInstant())
        return PrintableTable(station, span, range, zone, listOf(range), days, 110.0, 290.0,
            "NOAA PUG1600", null, "https://slackwater.xyz/current/boundary-pass", range.start)
    }

    private fun inspect(bytes: ByteArray, name: String, check: (PdfRenderer) -> Unit) {
        val export = InstrumentationRegistry.getArguments().getString("retainPrintSamples") == "true"
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        try { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use(check) }
        finally { if (!export) file.delete() }
    }

    private fun raster(page: PdfRenderer.Page): Bitmap {
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        return bitmap
    }

    @Test fun usualCurrentMonthFitsOneLetterSheetAndNightMarksStayWithinMargins() {
        val sample = table(PrintSpan.MONTH)
        val bytes = document.render(sample, ReadingPreferences(), Locale.US)
        inspect(bytes, "current-month-letter.pdf") { pdf ->
            assertEquals(1, pdf.pageCount)
            pdf.openPage(0).use { page ->
                assertEquals(612, page.width)
                assertEquals(792, page.height)
                val image = raster(page)
                var nightPixels = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val pixel = image.getPixel(x, y)
                    if (x < 36 || y < 36 || x >= image.width - 36 || y >= image.height - 36) {
                        if (pixel != Color.WHITE) fail("Ink in margin at ($x,$y)")
                    }
                    if (pixel == 0xffe8ecf2.toInt()) nightPixels++
                }
                assertTrue("Night event markers should be visible", nightPixels > 200)
                image.recycle()
            }
        }
        val fmt = PrintTableFormat(context, sample, ReadingPreferences(), Locale.US)
        assertTrue(fmt.facts().contains("PDT/PST"))
        assertTrue(PrintableTableDocument.isLetter(Locale.forLanguageTag("fr-CA-u-ms-ussystem")))
        assertFalse(PrintableTableDocument.isLetter(Locale.forLanguageTag("en-US-u-ms-metric")))
    }

    @Test fun weekHeaderIdentifiesThePredictionYearAndBothSidesOfNewYear() {
        val sample = table(PrintSpan.WEEK)
        fun header(start: LocalDate): String {
            val range = TimeSpan(start.atStartOfDay(zone).toInstant(), start.plusDays(7).atStartOfDay(zone).toInstant())
            return PrintTableFormat(context, sample.copy(range = range), ReadingPreferences(), Locale.US).title()
        }
        val january = header(LocalDate.of(2027, 1, 1))
        assertTrue(january, listOf("Jan", "1", "7", "2027").all { it in january })
        val newYear = header(LocalDate.of(2026, 12, 29))
        assertTrue(newYear, listOf("Dec", "29", "2026", "Jan", "4", "2027").all { it in newYear })
    }

    @Test fun monthPrintsBlockSpecificSetsAndRoundsSmallNegativeSpeedsToPositiveZero() {
        val sample = table(PrintSpan.MONTH).copy(floodDegrees = null, ebbDegrees = 270.0)
        val prefs = ReadingPreferences(setFormat = SetFormat.DEGREES)
        val fmt = PrintTableFormat(context, sample, prefs, Locale.US)
        val flood = PrintEntry(sample.range.start, PrintEventKind.MAX_FLOOD, speedKnots = 2.1, setDegrees = 355.0)
        assertTrue(fmt.monthToken(flood).endsWith("2.1 355°"))
        assertTrue(fmt.monthToken(flood.copy(setDegrees = 350.0)).endsWith("2.1 350°"))
        assertFalse("°" in fmt.monthToken(flood.copy(kind = PrintEventKind.MAX_EBB, setDegrees = 270.0)))
        assertTrue("270°" in fmt.facts())
        for (speed in listOf(-0.0, -0.001)) assertEquals("0.0", fmt.weekCells(flood.copy(speedKnots = speed))[2])
    }

    @Test fun translatedDenseTablesRepeatHeadersAndSelectedPagesMatchThePreview() {
        val locale = Locale.CANADA_FRENCH
        val original = table(PrintSpan.MONTH, busy = true)
        val sample = original.copy(station = station.copy(name =
            "Passage maritime entre les îles et le chenal septentrional du détroit de Géorgie"),
            covered = listOf(TimeSpan(original.range.start.plusSeconds(86400), original.range.end.minusSeconds(86400))),
            attribution = "Service hydrographique du Canada. ".repeat(12))
        val bytes = document.render(sample, ReadingPreferences(), locale)
        assertTrue(document.coverageNote(sample, locale)!!.startsWith("Les prévisions"))
        val selected = document.render(sample, ReadingPreferences(), locale, pages = setOf(1))
        inspect(bytes, "courants-mensuels-a4.pdf") { full ->
            assertTrue(full.pageCount > 1)
            for (n in 0 until full.pageCount) full.openPage(n).use { page ->
                assertEquals(595, page.width)
                assertEquals(842, page.height)
                if (Build.VERSION.SDK_INT >= 35) {
                    val text = Api35PdfText.read(page)
                    assertTrue(text.contains("Flot maximal"))
                    assertTrue(text.contains("Imprimé"))
                    assertTrue(text.contains("navigation"))
                    assertTrue(text.contains("Passage maritime"))
                }
            }
            full.openPage(1).use { originalPage ->
                inspect(selected, "courants-selection.pdf") { extract ->
                    assertEquals(1, extract.pageCount)
                    extract.openPage(0).use { page ->
                        val a = raster(originalPage)
                        val b = raster(page)
                        assertTrue(a.sameAs(b))
                        a.recycle()
                        b.recycle()
                    }
                }
            }
        }
    }
}

// JUnit reflects on every test method on API 26. Keep API 35 types out of that class.
@androidx.annotation.RequiresApi(35)
private object Api35PdfText {
    fun read(page: PdfRenderer.Page): String =
        page.textContents.joinToString(" ") { it.text }
}
