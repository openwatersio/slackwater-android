package io.openwaters.slackwater.android.core.shortcuts

import io.openwaters.slackwater.android.core.station.*
import java.text.Normalizer

sealed interface QueryPlace {
    data object Nearby : QueryPlace
    data class Named(val text: String) : QueryPlace
    data class Selected(val id: StationId) : QueryPlace
}

sealed interface QueryPlaces {
    /** Ordered by distance for Nearby; exactly one concrete station for a resolved explicit place. */
    data class Candidates(val stations: List<StationIdentity>) : QueryPlaces
    data class Ambiguous(val stations: List<StationIdentity>) : QueryPlaces
    data object NeedsLocation : QueryPlaces
    data object Missing : QueryPlaces
}

internal fun StationIdentity.acceptsQuery(type: StationSeries) = series == type && kind != StationKind.DerivedGate

fun nearbyQueryPlaces(catalog: List<StationIdentity>, series: StationSeries, fix: Coordinate?): QueryPlaces {
    if (fix == null) return QueryPlaces.NeedsLocation
    val ranked = rankByDistance(catalog.filter { it.acceptsQuery(series) }, fix)
    return if (ranked.isEmpty()) QueryPlaces.Missing else QueryPlaces.Candidates(ranked.map { it.identity })
}

fun selectedQueryPlace(station: StationIdentity?, series: StationSeries): QueryPlaces =
    if (station != null && station.acceptsQuery(series)) QueryPlaces.Candidates(listOf(station)) else QueryPlaces.Missing

fun namedQueryPlaces(
    catalog: List<StationIdentity>,
    series: StationSeries,
    text: String,
    fix: Coordinate?,
    favorites: List<StationId>,
    recents: List<StationId>,
    chosen: Set<StationId>,
): QueryPlaces {
    fun fold(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase()
    val query = fold(text.trim())
    if (query.isEmpty()) return QueryPlaces.Missing
    val local = if (fix == null) emptyList() else {
        val ranked = rankByDistance(catalog.filter { it.series != null }, fix)
        val places = Namesakes(ranked, chosen)
        heroes(ranked).map { places.shownFor(it.id)?.id ?: it.id }
    }
    data class Match(val station: StationIdentity, val rank: Int, val km: Double)
    val matches = catalog.filter { it.acceptsQuery(series) }.mapNotNull { station ->
        val name = fold(station.name)
        val rank = when {
            name == query -> 0
            query in name -> 1
            query in fold(station.region) -> 2
            station.aliases.any { query in fold(it) } -> 3
            else -> return@mapNotNull null
        }
        Match(station, rank, fix?.let { distanceKm(it, station.coordinate) } ?: 0.0)
    }
    if (matches.isEmpty()) return QueryPlaces.Missing
    val byId = matches.associateBy { it.station.id }
    val preferred = (local + favorites + recents).firstNotNullOfOrNull(byId::get)
    if (preferred != null) return QueryPlaces.Candidates(listOf(preferred.station))
    if (matches.size == 1) return QueryPlaces.Candidates(listOf(matches.single().station))
    return QueryPlaces.Ambiguous(matches.sortedWith(compareBy(
        { it.rank }, { it.km }, { it.station.name }, { it.station.region }, { it.station.id.value },
    )).map { it.station })
}
