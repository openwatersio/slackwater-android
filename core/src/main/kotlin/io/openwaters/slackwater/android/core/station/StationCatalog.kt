package io.openwaters.slackwater.android.core.station

interface StationCatalog {
    suspend fun identities(): List<StationIdentity>
    suspend fun resolve(id: StationId): StationResolution
}

class StationIdentityIndex(
    identities: List<StationIdentity>,
    successors: Map<StationId, StationSuccessor>,
) {
    private fun priority(row: StationIdentity) = when (row.kind) {
        StationKind.Restricted -> 2
        StationKind.Retired -> 1
        else -> 0
    }

    // A returning station is live again even if a historical catalog still names it.
    private val byId: Map<StationId, StationIdentity> = buildMap {
        for (row in identities) {
            val existing = get(row.id)
            if (existing == null || priority(row) < priority(existing)) put(row.id, row)
        }
    }

    val identities = byId.values.filter { priority(it) == 0 }.sortedWith(compareBy({ it.name }, { it.id.value }))
    private val successors = successors.toMap()
    val mapIdentities by lazy {
        byId.values.filter { priority(it) == 0 || (it.kind == StationKind.Restricted && it.id !in successors) }
            .sortedWith(compareBy({ it.name }, { it.id.value }))
    }

    fun resolve(savedId: StationId): StationResolution {
        var id = savedId
        var shift: Double? = 0.0
        val visited = mutableSetOf<StationId>()
        while (byId[id]?.let(::priority) != 0) {
            val successor = successors[id] ?: break
            if (!visited.add(id)) return StationResolution.Unknown(savedId)
            shift = if (shift != null && successor.datumShiftMetres != null) shift + successor.datumShiftMetres else null
            id = successor.id
        }
        val row = byId[id] ?: return StationResolution.Unknown(savedId)
        val target = when (row.kind) {
            StationKind.Restricted -> StationResolution.Restricted(row)
            StationKind.Retired -> StationResolution.Tombstone(row)
            else -> StationResolution.Live(row)
        }
        return if (id == savedId) target else StationResolution.Successor(savedId, target, shift)
    }
}

suspend fun StationCatalog.remapSavedIds(ids: List<StationId>): List<StationId> =
    ids.map { resolve(it).id }.distinct()
