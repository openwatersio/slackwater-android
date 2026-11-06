package io.openwaters.slackwater.android.sharing

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.allows
import java.time.Instant
import kotlinx.coroutines.*

internal fun selectedRanges(requested: Array<out PageRange>, pageCount: Int): List<PageRange> {
    val result = mutableListOf<PageRange>()
    var start = -1
    for (page in 0..pageCount) {
        val included = page < pageCount && requested.any { page in it.start..it.end }
        if (included && start == -1) start = page
        if (!included && start >= 0) {
            result += PageRange(start, page - 1)
            start = -1
        }
    }
    return result
}

internal class TablePrintAdapter(private val context: Context, private val document: TableExport) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
                          callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(document.file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(document.pageCount).build()
        callback.onLayoutFinished(info, oldAttributes == null)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal,
                         callback: WriteResultCallback) {
        scope.launch {
            try {
                val ranges = selectedRanges(pages, document.pageCount)
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.AutoCloseOutputStream(destination).use { out ->
                        signal.throwIfCanceled()
                        if (!access()) throw SecurityException()
                        val selected = ranges.flatMap { it.start..it.end }.toSet()
                        require(selected.isNotEmpty())
                        if (selected.size == document.pageCount) {
                            document.file.inputStream().use { it.copyTo(out) }
                        } else {
                            val bytes = document.renderPages(selected)
                            signal.throwIfCanceled()
                            out.write(bytes)
                        }
                        signal.throwIfCanceled()
                        if (!access()) throw SecurityException()
                    }
                }
                callback.onWriteFinished(ranges.toTypedArray())
            } catch (_: android.os.OperationCanceledException) {
                callback.onWriteCancelled()
            } catch (cancelled: CancellationException) {
                callback.onWriteCancelled()
                throw cancelled
            } catch (_: SecurityException) {
                callback.onWriteFailed(context.getString(R.string.table_sheet_locked))
            } catch (_: Exception) {
                callback.onWriteFailed(context.getString(R.string.table_sheet_export_failed))
            }
        }
    }

    private suspend fun access() = ReminderAccessStore.get(context).current().allows(Instant.now())
    override fun onFinish() {
        if (scope.isActive) {
            scope.cancel()
            document.finishPrint()
        }
    }
}
