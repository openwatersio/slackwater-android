package io.openwaters.slackwater.android.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import android.graphics.drawable.Icon
import io.openwaters.slackwater.android.MainActivity
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

internal enum class ShortcutKind(val label: Int) {
    LOW(R.string.launcher_low), HIGH(R.string.launcher_high), SLACK(R.string.launcher_slack)
}

internal data class ShortcutRequest(val kind: ShortcutKind, val place: String? = null, val date: LocalDate? = null, val stationId: StationId? = null)

internal object ShortcutPublisher {
    private fun manager(context: Context) = context.getSystemService(ShortcutManager::class.java)

    fun publish(context: Context) {
        val service = manager(context) ?: return
        val activity = ComponentName(context, MainActivity::class.java)
        val capacity = (service.maxShortcutCountPerActivity - service.manifestShortcuts.count { it.activity == activity }).coerceAtLeast(0)
        val desired = ShortcutKind.entries.take(capacity).mapIndexed { rank, kind ->
            info(context, ShortcutRequest(kind), rank)
        }
        val current = service.dynamicShortcuts.sortedBy { it.rank }
        if (!service.isRateLimitingActive && current.map { it.id to it.shortLabel.toString() } != desired.map { it.id to it.shortLabel.toString() }) {
            service.dynamicShortcuts = desired
        }
    }

    fun canPin(context: Context) = manager(context)?.isRequestPinShortcutSupported == true

    fun pin(context: Context, request: ShortcutRequest): Boolean =
        canPin(context) && manager(context)?.requestPinShortcut(info(context, request, 0), null) == true

    internal fun intent(context: Context, request: ShortcutRequest): Intent {
        val uri = Uri.Builder().scheme("slackwater-query").authority("prediction").appendPath(request.kind.name)
        request.place?.let { uri.appendQueryParameter("place", it) }
        request.stationId?.let { uri.appendQueryParameter("station", it.value) }
        request.date?.let { uri.appendQueryParameter("date", it.toString()) }
        return Intent(Intent.ACTION_VIEW, uri.build(), context, QueryActivity::class.java)
    }

    fun read(intent: Intent): ShortcutRequest? {
        val uri = intent.data ?: return null
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "slackwater-query" || uri.authority != "prediction") return null
        val kind = ShortcutKind.entries.find { it.name == uri.pathSegments.singleOrNull() } ?: return null
        val place = uri.getQueryParameter("place")
        if (place != null && place.isBlank()) return null
        val date = try { uri.getQueryParameter("date")?.let(LocalDate::parse) } catch (_: DateTimeParseException) { return null }
        if (date != null && date.year !in 1..9999) return null
        val station = uri.getQueryParameter("station")?.let { if (it.isBlank()) return null else StationId(it) }
        return ShortcutRequest(kind, place, date, station)
    }

    private fun info(context: Context, request: ShortcutRequest, rank: Int): ShortcutInfo {
        val target = intent(context, request)
        val id = if (request.place == null && request.date == null && request.stationId == null) "query-" + request.kind.name else
            "query-" + UUID.nameUUIDFromBytes(target.dataString!!.toByteArray(Charsets.UTF_8))
        val title = listOfNotNull(context.getString(request.kind.label), request.place, request.date?.toString()).joinToString(" · ")
        return ShortcutInfo.Builder(context, id).setActivity(ComponentName(context, MainActivity::class.java))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_prediction_shortcut))
            .setShortLabel(title).setLongLabel(title).setIntent(target).setRank(rank).build()
    }
}
