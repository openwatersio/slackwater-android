package io.openwaters.slackwater.android.core.station

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class Coordinate(val latitude: Double, val longitude: Double)

/** Annapolis on Chesapeake Bay, where bundled NOAA tides and currents work on a first launch. */
val FIRST_RUN_ANCHOR = Coordinate(38.9750, -76.4550)

/** The other series' nearest station joins the nearest as a second hero within this distance of the fix. */
const val HERO_CROSS_SERIES_RADIUS_KM = 20.0
const val NEAR_ME_COUNT_WITH_FIX = 4
const val NEAR_ME_COUNT_WITHOUT_FIX = 5

/** Spherical haversine on the mean Earth radius, the distance every platform's list ranks by. */
fun distanceKm(a: Coordinate, b: Coordinate): Double {
    val p1 = Math.toRadians(a.latitude)
    val p2 = Math.toRadians(b.latitude)
    val dp = p2 - p1
    val dl = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * 6371.0088 * asin(min(1.0, sqrt(h)))
}

val StationIdentity.coordinate: Coordinate get() = Coordinate(latitude, longitude)

/**
 * Nautical miles as the list shows them, without the unit: one decimal when the distance is below 10 nm, else none,
 * chosen before rounding, so 9.97 nm reads "10.0". Halves round away from zero, in the locale's digits and decimal
 * separator, never grouped.
 */
fun formatNauticalMiles(km: Double, locale: Locale): String {
    val nm = km / 1.852
    val places = if (nm < 10) 1 else 0
    // Rounds the shortest decimal form, as ICU on Android and iOS does, so 0.15 nm reads "0.2", not the binary 0.1499….
    val decimal = BigDecimal(nm.toString()).setScale(places, RoundingMode.HALF_UP)
    return NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = places
        maximumFractionDigits = places
    }.format(decimal)
}

/** Where distances are measured from, in the order the list chooses: a fix, the last-opened place, then first run. */
sealed interface RankingAnchor {
    val coordinate: Coordinate

    data class Fix(override val coordinate: Coordinate) : RankingAnchor

    data class LastOpened(val station: StationIdentity) : RankingAnchor {
        override val coordinate get() = station.coordinate
    }

    data object FirstRun : RankingAnchor {
        override val coordinate get() = FIRST_RUN_ANCHOR
    }
}

/** [recents] most recent first; the first that is still in [live] anchors the list when there is no fix. */
fun rankingAnchor(fix: Coordinate?, recents: List<StationId>, live: Map<StationId, StationIdentity>): RankingAnchor =
    fix?.let(RankingAnchor::Fix)
        ?: recents.firstNotNullOfOrNull(live::get)?.let(RankingAnchor::LastOpened)
        ?: RankingAnchor.FirstRun

data class RankedStation(val identity: StationIdentity, val distanceKm: Double) {
    val id: StationId get() = identity.id
}

/** [catalog] nearest first. Equal distances keep catalog order, so the first station of a name is its nearest. */
fun rankByDistance(catalog: List<StationIdentity>, anchor: Coordinate): List<RankedStation> =
    catalog.withIndex()
        .map { (index, station) -> Triple(distanceKm(anchor, station.coordinate), index, station) }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { (km, _, station) -> RankedStation(station, km) }

/**
 * One entry per series and name, for Near Me: the person's chosen namesake when [chosen] holds one, else the nearest.
 * Search, Favorites and Recents are never collapsed.
 */
class Namesakes(ranked: List<RankedStation>, chosen: Set<StationId>) {
    private val byPlace: Map<StationPlace, List<RankedStation>> =
        ranked.filter { it.identity.place != null }.groupBy { it.identity.place!! }
    private val canonical: Map<StationId, RankedStation>

    init {
        val shownForPlace = byPlace.mapValues { (_, group) -> group.firstOrNull { it.id in chosen } ?: group.first() }
        canonical = ranked.associate { station -> station.id to (station.identity.place?.let(shownForPlace::get) ?: station) }
    }

    /** The ranking collapsed to one entry per place, nearest place first. */
    val shown: List<RankedStation> = ranked.map { canonical[it.id] ?: it }.distinctBy { it.id }

    /** What renders in place of [id]: itself, unless another station of its place is chosen or nearer. */
    fun shownFor(id: StationId): RankedStation? = canonical[id]

    /** Every station sharing [station]'s series and name, nearest first: the detail page's chooser rows. */
    fun matches(station: StationIdentity): List<RankedStation> = station.place?.let(byPlace::get).orEmpty()
}

/** The nearest station, and the nearest of the other series when it lies within [HERO_CROSS_SERIES_RADIUS_KM]. */
fun heroes(ranked: List<RankedStation>): List<RankedStation> {
    val first = ranked.firstOrNull() ?: return emptyList()
    val other = ranked.firstOrNull { it.identity.series != first.identity.series }
    return if (other != null && other.distanceKm <= HERO_CROSS_SERIES_RADIUS_KM) listOf(first, other) else listOf(first)
}

/**
 * Each station renders in at most one group: My Location, then Favorites, then Near Me, then Recents. The removal
 * happens here, at render time; the saved lists are never changed by it.
 */
data class ListGroups(
    val heroes: List<StationId>,
    val favorites: List<StationId>,
    val nearMe: List<StationId>,
    val recents: List<StationId>,
) {
    companion object {
        fun of(
            heroes: List<StationId>,
            favorites: List<StationId>,
            recents: List<StationId>,
            ranked: List<StationId>,
            nearCount: Int,
        ): ListGroups {
            val shownFavorites = favorites.filter { it !in heroes }
            val shown = (heroes + shownFavorites).toMutableSet()
            val nearMe = ranked.filter { it !in shown }.take(nearCount)
            shown += nearMe
            return ListGroups(heroes, shownFavorites, nearMe, recents.filter { it !in shown })
        }
    }
}

/** The list's station sections, before cards resolve readings. */
data class StationListSections(
    val anchor: RankingAnchor,
    /** Empty without a fix. Each carries its distance from the fix. */
    val heroes: List<RankedStation>,
    /** Saved order, including stations no longer live, which render as removed cards. */
    val favorites: List<StationId>,
    /** Each carries its distance from [anchor] for ranking; label it only when [showsDistances]. */
    val nearMe: List<RankedStation>,
    /** Most recent first, live stations only. */
    val recents: List<StationId>,
) {
    /**
     * Only a real fix earns distance labels. Ranked from the last-opened place or the first-run anchor, the order
     * still holds but a distance would claim a position the app does not have. This departs from iOS on purpose.
     */
    val showsDistances: Boolean get() = anchor is RankingAnchor.Fix
}

/**
 * Builds the list's sections. [catalog] is the live catalog in catalog order (`StationIdentityIndex.identities`);
 * [filter] narrows Near Me only, never heroes, favorites or recents. [rank] ranks the browsable catalog from an
 * anchor and must return it sorted by distance from that exact coordinate, which heroes and namesakes rely on.
 */
fun stationListSections(
    catalog: List<StationIdentity>,
    fix: Coordinate?,
    favorites: List<StationId>,
    recents: List<StationId>,
    chosen: Set<StationId>,
    filter: StationSeries?,
    rank: (List<StationIdentity>, Coordinate) -> List<RankedStation> = ::rankByDistance,
): StationListSections {
    val browsable = catalog.filter { it.series != null }
    val live = browsable.associateBy { it.id }
    val anchor = rankingAnchor(fix, recents, live)
    val byDistance = rank(browsable, anchor.coordinate)
    val namesakes = Namesakes(byDistance, chosen)
    // Heroes come from the uncollapsed ranking, then stand in for their chosen namesake, as in Near Me.
    val heroes = if (fix == null) emptyList() else heroes(byDistance).map { namesakes.shownFor(it.id) ?: it }
    val nearCandidates = namesakes.shown.filter { filter == null || it.identity.series == filter }
    val groups = ListGroups.of(
        heroes = heroes.map { it.id },
        favorites = favorites,
        recents = recents.filter { it in live },
        ranked = nearCandidates.map { it.id },
        nearCount = if (fix == null) NEAR_ME_COUNT_WITHOUT_FIX else NEAR_ME_COUNT_WITH_FIX,
    )
    val nearById = nearCandidates.associateBy { it.id }
    return StationListSections(
        anchor = anchor,
        heroes = heroes,
        favorites = groups.favorites,
        nearMe = groups.nearMe.map { nearById.getValue(it) },
        recents = groups.recents,
    )
}
