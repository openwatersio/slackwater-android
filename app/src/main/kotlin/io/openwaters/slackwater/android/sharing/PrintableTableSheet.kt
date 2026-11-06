package io.openwaters.slackwater.android.sharing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.alerts.ReminderAccessStore
import io.openwaters.slackwater.android.core.planning.*
import io.openwaters.slackwater.android.core.prediction.StationCapabilities
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.data.alertInputLock
import kotlinx.coroutines.sync.withLock
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import io.openwaters.slackwater.android.data.retryAlertReads
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintableTableSheet(stationName: String, load: suspend (PrintSpan) -> PrintableTable, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val access = remember(context) { ReminderAccessStore.get(context) }
    val premium by produceState<Boolean?>(null, access) {
        access.changes.retryAlertReads().collectLatest { entitlement ->
            value = entitlement.allows(Instant.now())
            if (entitlement is ReminderEntitlement.FreeDay && value == true) {
                delay(Duration.between(Instant.now(), entitlement.ends).toMillis().coerceAtLeast(0) + 1)
                value = false
            }
        }
    }
    var span by remember { mutableStateOf(PrintSpan.WEEK) }
    val loader by rememberUpdatedState(load)
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var message by remember(span, premium) { mutableIntStateOf(0) }
    var document by remember(span, premium, attempt, locale, stationName) { mutableStateOf<TableExport?>(null) }
    var hint by remember(span, premium, attempt, locale, stationName) { mutableIntStateOf(0) }
    var failed by remember(span, premium, attempt, locale, stationName) { mutableStateOf(false) }

    LaunchedEffect(span, premium, attempt, locale, stationName) {
        if (premium != true) return@LaunchedEffect
        var owned: TableExport? = null
        try {
            val (pdf, guidance) = withContext(Dispatchers.IO) {
                val table = loader(span)
                val prefs = ReadingPreferencesStore.get(context).preferences.first().getOrThrow()
                val export = TableExport.create(context, stationName, span, table, prefs, locale).also { owned = it }
                val guidance = when {
                    export.coverageNote == null -> 0
                    StationCapabilities.TIDE_PREVIEW in table.capabilities ||
                        table.station.kind == StationKind.CanadianTide -> R.string.table_sheet_download_tide
                    StationCapabilities.ONLINE_CURRENT in table.capabilities ||
                        table.station.kind == StationKind.CanadianCurrent -> R.string.table_sheet_download_current
                    else -> 0
                }
                export to guidance
            }
            document = pdf
            hint = guidance
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            // withContext can discard its result when a period switch cancels the return to Main.
            withContext(NonCancellable + Dispatchers.IO) { owned?.releasePreview() }
        }
    }

    fun export(print: Boolean) {
        val pdf = document ?: return
        scope.launch {
            try {
                alertInputLock.withLock {
                    if (!access.current().allows(Instant.now())) {
                        message = R.string.table_sheet_locked
                        return@withLock
                    }
                    if (!pdf.file.isFile) throw java.io.IOException("Preview file unavailable")
                    if (print) {
                        val media = if (pdf.letter) PrintAttributes.MediaSize.NA_LETTER else PrintAttributes.MediaSize.ISO_A4
                        val attributes = PrintAttributes.Builder().setMediaSize(media).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
                        val adapter = TablePrintAdapter(context, pdf)
                        pdf.startPrint()
                        try {
                            checkNotNull((context.getSystemService(Context.PRINT_SERVICE) as PrintManager)
                                .print(pdf.file.nameWithoutExtension, adapter, attributes))
                        } catch (error: Exception) {
                            adapter.onFinish()
                            throw error
                        }
                    } else {
                        pdf.handToShare { context.startActivity(pdf.shareIntent(context)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = R.string.table_sheet_export_failed
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.table_sheet_title), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.table_sheet_done)) }
            }
            if (premium == false) {
                Text(stringResource(R.string.table_sheet_locked))
            } else if (premium == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (period in PrintSpan.entries) {
                        FilterChip(selected = span == period, onClick = { span = period }, label = {
                            Text(stringResource(if (period == PrintSpan.WEEK) R.string.table_sheet_week else R.string.table_sheet_month))
                        })
                    }
                }
                if (message != 0) Text(stringResource(message))
                val pdf = document
                if (pdf != null) {
                    Text(stringResource(if (pdf.letter) R.string.table_sheet_letter else R.string.table_sheet_a4),
                        style = MaterialTheme.typography.bodySmall)
                    pdf.coverageNote?.let { Text(it) }
                    if (hint != 0) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { export(false) }) { Text(stringResource(R.string.table_sheet_share)) }
                        OutlinedButton(onClick = { export(true) }) { Text(stringResource(R.string.table_sheet_print)) }
                    }
                    key(pdf) { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pdf.pageCount) { index -> PdfPage(pdf, index) { attempt++ } }
                    } }
                } else if (failed) {
                    Text(stringResource(R.string.table_sheet_failed))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.table_sheet_retry)) }
                } else {
                    CircularProgressIndicator()
                }
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: TableExport, index: Int, retry: () -> Unit) {
    val ratio = if (pdf.letter) 612f / 792f else 595f / 842f
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(ratio)) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx().coerceIn(1, 1800) }
        val bitmap by produceState<Result<Bitmap>?>(null, pdf, index, width) {
            value = withContext(Dispatchers.IO) {
                try {
                    val pixels = PdfRenderer(ParcelFileDescriptor.open(pdf.file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                        renderer.openPage(index).use { page ->
                            Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                    Result.success(pixels)
                } catch (error: java.io.IOException) {
                    Result.failure(error)
                }
            }
        }
        bitmap?.getOrNull()?.let {
            Image(it.asImageBitmap(), stringResource(R.string.table_sheet_page, index + 1, pdf.pageCount), Modifier.fillMaxSize())
        }
        if (bitmap?.isFailure == true) TextButton(onClick = retry) { Text(stringResource(R.string.table_sheet_retry)) }
    }
}
