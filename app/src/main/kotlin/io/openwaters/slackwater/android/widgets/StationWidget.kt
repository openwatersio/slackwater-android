package io.openwaters.slackwater.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import kotlinx.coroutines.CancellationException
import java.io.IOException
import android.widget.RemoteViews
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.detail.internalStationLink
import java.time.Instant

class StationWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED ->
                WidgetRefreshWorker.refresh(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetRefreshWorker.schedule(context)
        WidgetRefreshWorker.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetRefreshWorker.refresh(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { deleteWidgetSettings(context, it) }
    }

    override fun onDisabled(context: Context) {
        if (!hasStationWidgets(context)) clearWidgetSettings(context)
        WidgetRefreshWorker.cancel(context)
    }
}

internal fun hasStationWidgets(context: Context): Boolean =
    context.getSystemService(AppWidgetManager::class.java)
        ?.getAppWidgetIds(ComponentName(context, StationWidget::class.java))?.isNotEmpty() == true

internal suspend fun updateStationWidgets(context: Context) {
    val manager = context.getSystemService(AppWidgetManager::class.java) ?: return
    val ids = manager.getAppWidgetIds(ComponentName(context, StationWidget::class.java))
    val now = Instant.now()
    var retry: IOException? = null
    for (id in ids) {
        try {
            updateStationWidget(context, manager, id, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The launcher keeps this widget's timestamped snapshot while the remaining widgets refresh.
            Log.w("StationWidget", "Could not refresh widget $id", failure)
            if (failure is IOException) retry = failure
        }
    }
    retry?.let { throw it }
}

private suspend fun updateStationWidget(context: Context, manager: AppWidgetManager, id: Int, now: Instant) {
    val station = widgetStation(context, id)
    val tap = widgetTap(context, id, station)
    val click = PendingIntent.getActivity(context, id, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val options = manager.getAppWidgetOptions(id)
    val orientations = orientationWidgetSizes(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
    )
    suspend fun card(size: WidgetSize, variants: Int) = renderStationWidget(context, id, station, size, variants, now).apply {
        setOnClickPendingIntent(R.id.widget_card, click)
    }
    val views = if (Build.VERSION.SDK_INT >= 31) {
        @Suppress("DEPRECATION")
        val supplied = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            .orEmpty().map { WidgetSize(it.width, it.height) }
        val sizes = launcherWidgetSizes(supplied, orientations)
        val layouts = sizes.associate { SizeF(it.width, it.height) to card(it, sizes.size) }
        RemoteViews(layouts)
    } else {
        val (portrait, landscape) = orientations
        if (portrait == landscape) card(portrait, 1) else RemoteViews(card(landscape, 2), card(portrait, 2))
    }
    manager.updateAppWidget(id, views)
}

internal data class WidgetSize(val width: Float, val height: Float) {
    val wide get() = width >= 250f
}

internal fun orientationWidgetSizes(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int): Pair<WidgetSize, WidgetSize> {
    fun dimension(value: Int, fallback: Float) = if (value > 0) value.toFloat() else fallback
    return WidgetSize(dimension(minWidth, 140f), dimension(maxHeight, 110f)) to
        WidgetSize(dimension(maxWidth, 250f), dimension(minHeight, 110f))
}

internal fun launcherWidgetSizes(supplied: List<WidgetSize>, orientations: Pair<WidgetSize, WidgetSize>): List<WidgetSize> {
    val exact = supplied.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }.distinct()
    // Android accepts at most sixteen responsive layouts in one RemoteViews.
    return if (exact.size in 1..16) exact else orientations.toList().distinct()
}

internal fun widgetTap(context: Context, id: Int, station: StationId?): Intent = when {
    station != null -> Intent(Intent.ACTION_VIEW, internalStationLink(station), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    hasNearestChoice(context, id) -> Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    else -> Intent(context, WidgetConfigurationActivity::class.java)
        .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
}
