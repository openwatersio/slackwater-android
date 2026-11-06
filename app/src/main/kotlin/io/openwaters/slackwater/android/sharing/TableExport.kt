package io.openwaters.slackwater.android.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.icu.text.BreakIterator
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.planning.PrintSpan
import io.openwaters.slackwater.android.core.planning.PrintableTable
import io.openwaters.slackwater.android.data.ReadingPreferences
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TableFileProvider : FileProvider()

internal fun safeTablePlace(stationName: String): String {
    // Preserve joined emoji and combining marks, while stripping path separators and bidi overrides.
    val clean = stationName.replace(Regex("""[/\\\p{Cc}\p{Cs}\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]"""), "_").trim()
    val clusters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(clean) }
    var end = clusters.first()
    var bytes = 0
    repeat(50) {
        val next = clusters.next()
        if (next == BreakIterator.DONE) return clean.substring(0, end).ifBlank { "Slackwater" }
        bytes += clean.substring(end, next).toByteArray(Charsets.UTF_8).size
        if (bytes > 160) return clean.substring(0, end).ifBlank { "Slackwater" }
        end = next
    }
    return clean.substring(0, end)
}

/** Keep a copy for the receiving app after the sheet closes. Each export has a distinct URI. */
internal class TableExport(
    val file: File, val pageCount: Int, val letter: Boolean, val coverageNote: String?,
    val renderPages: (Set<Int>) -> ByteArray,
) {
    private var previewOpen = true
    private var shared = false
    private var printing = 0

    @Synchronized fun releasePreview() {
        previewOpen = false
        cleanup()
    }

    @Synchronized fun handToShare(launch: () -> Unit) {
        check(previewOpen)
        launch()
        shared = true
    }

    @Synchronized fun startPrint() {
        check(previewOpen)
        printing++
    }

    @Synchronized fun finishPrint() {
        printing--
        cleanup()
    }

    private fun cleanup() {
        if (previewOpen || printing > 0) return
        val dir = file.parentFile ?: return
        if (!shared) {
            file.delete()
            dir.delete()
        }
        liveDirectories.remove(dir)
    }

    companion object {
        private val liveDirectories = ConcurrentHashMap.newKeySet<File>()
        fun create(context: Context, stationName: String, span: PrintSpan, table: PrintableTable,
                   preferences: ReadingPreferences, locale: Locale): TableExport {
            val renderer = PrintableTableDocument(context)
            val pdf = renderer.render(table, preferences, locale)
            val root = File(context.cacheDir, "shared-tables")
            val cutoff = System.currentTimeMillis() - Duration.ofDays(2).toMillis()
            root.listFiles()?.filter { it.isDirectory && it !in liveDirectories && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
            val dir = File(root, UUID.randomUUID().toString())
            if (!dir.mkdirs()) throw IOException("Could not create the PDF directory")
            try {
                val place = safeTablePlace(stationName)
                val period = context.getString(if (span == PrintSpan.WEEK) R.string.table_sheet_week else R.string.table_sheet_month)
                val file = File(dir, "$place – $period.pdf")
                file.writeBytes(pdf)
                val count = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
                return TableExport(file, count, PrintableTableDocument.isLetter(locale), renderer.coverageNote(table, locale)) {
                    renderer.render(table, preferences, locale, pages = it)
                }.also { liveDirectories.add(dir) }
            } catch (error: Exception) {
                dir.deleteRecursively()
                throw error
            }
        }
    }

    fun shareIntent(context: Context): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.tables", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null)
    }
}
