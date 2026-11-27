package io.openwaters.slackwater.android.data

import android.content.Context
import io.openwaters.slackwater.android.core.station.Coordinate
import io.openwaters.slackwater.android.core.station.LocationSlot
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.fixCoordinate
import io.openwaters.slackwater.android.stations.StationListModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** The last fix any candidate list saw in this process, for a radius measured off screen. */
@Volatile private var lastFix: Coordinate? = null

/**
 * The Canadian stations the automatic queue fits: the phone selection over the station list, with
 * the open station first and cancelled stations removed. The activity and the background job both
 * feed this to [TidePreviewDownloads.fitWhileStarted], so they agree on what is wanted.
 *
 * With [holdRadius], used by the background job, a radius is measured from the last fix seen:
 * nothing refreshes the location off screen and a fix lasts ten minutes, so otherwise a long run
 * would lose its radius partway through. Only the origin is held, so selection, favorite and current
 * changes still apply. [heldOrigin] says when the origin is dropped.
 */
internal fun automaticFitCandidates(context: Context, holdRadius: Boolean = false): Flow<List<StationId>> {
    val model = StationListModel.get(context)
    val store = model.places.downloads
    val fits = TidePreviewDownloads.get(context)
    val candidates = combine(model.state.filterNotNull(), store.selection) { list, selection ->
        val fix = list.slot.fixCoordinate
        // The visible app records it too, so a run that starts after the fix expired still has it.
        lastFix = heldOrigin(list.slot, lastFix)
        selection.stations(list, if (holdRadius) lastFix else fix)
    }.flowOn(Dispatchers.Default).distinctUntilChanged()
    return combine(candidates, store.state, fits.openStation) { ids, intent, opened ->
        val saved = intent.getOrNull()
        if (saved == null) emptyList() else (listOfNotNull(opened) + ids).distinct().filter { it !in saved.cancelled }
    }.distinctUntilChanged()
}

/**
 * The origin a background run measures its radius from: the slot's fix, or [held] once that fix has
 * expired. A held origin has no age limit while location is allowed. Nothing is held once the list
 * reads location as not allowed, and a run going at that moment loses its radius. The list reads the
 * permission only while the app is on screen. iOS `LocationService.rankingAnchor` gates its fix on
 * authorization the same way and puts no age limit on it.
 */
internal fun heldOrigin(slot: LocationSlot, held: Coordinate?): Coordinate? = when (slot) {
    is LocationSlot.Located -> slot.fix.coordinate
    LocationSlot.NotDetermined, LocationSlot.Denied -> null
    LocationSlot.Locating, LocationSlot.Failed -> held
}

/** The online current gates to keep current: the automatic candidates and every explicit request, while online. */
internal fun automaticOnlineCandidates(context: Context, fitCandidates: Flow<List<StationId>>): Flow<Set<StationId>> {
    val store = StationListModel.get(context).places.downloads
    val downloads = OnlineGateDownloads.get(context)
    return combine(fitCandidates, store.state, downloads.online) { ids, intent, online ->
        if (!online) emptySet() else (ids + intent.getOrNull()?.requested.orEmpty()).toSet()
    }
}
