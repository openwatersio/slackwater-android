package io.openwaters.slackwater.android.core.station

import java.text.Normalizer

const val SEARCH_RESULT_LIMIT = 60

data class SearchResults(val stations: List<RankedStation>) {
    /** Exactly the limit came back, so the screen says to keep typing. */
    val truncated: Boolean get() = stations.size == SEARCH_RESULT_LIMIT
}

/**
 * Plain substring search over each station's lowercased name, region and aliases, with no word, prefix or diacritic
 * folding. Name matches rank first, then region, then alias; ties break by distance from the anchor, then by id.
 * Namesakes are not collapsed. Build once per catalog: the lowercasing happens here, not per keystroke.
 *
 * Text is NFC-normalized on both sides, as namesake grouping is, so a composed query finds a decomposed name.
 * Stations without a series (retired or restricted) are never results.
 */
class StationSearch(catalog: List<StationIdentity>) {
    private class Key(val name: String, val region: String, val aliases: List<String>)

    private val catalog = catalog.filter { it.series != null }
    private val keys = this.catalog.map { station ->
        Key(fold(station.name), fold(station.region), station.aliases.map(::fold))
    }

    /** An empty query returns the nearest stations. [series] narrows the scan, so the limit fills with that series. */
    fun search(query: String, anchor: Coordinate, series: StationSeries? = null): SearchResults {
        val needle = fold(query.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() })
        val matches = ArrayList<Triple<Int, Double, StationIdentity>>()
        for ((station, key) in catalog.zip(keys)) {
            if (series != null && station.series != series) continue
            val rank = when {
                needle.isEmpty() || needle in key.name -> 0
                needle in key.region -> 1
                key.aliases.any { needle in it } -> 2
                else -> continue
            }
            matches += Triple(rank, distanceKm(anchor, station.coordinate), station)
        }
        matches.sortWith(compareBy({ it.first }, { it.second }, { it.third.id.value }))
        return SearchResults(matches.take(SEARCH_RESULT_LIMIT).map { (_, km, station) -> RankedStation(station, km) })
    }

    private companion object {
        fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
    }
}
