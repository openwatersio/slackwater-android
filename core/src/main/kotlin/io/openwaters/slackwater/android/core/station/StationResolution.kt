package io.openwaters.slackwater.android.core.station

data class StationSuccessor(val id: StationId, val datumShiftMetres: Double? = null)

sealed class StationResolution(val id: StationId) {
    sealed class Known(val identity: StationIdentity) : StationResolution(identity.id)
    class Live(identity: StationIdentity) : Known(identity)
    class Tombstone(identity: StationIdentity) : Known(identity)
    class Restricted(identity: StationIdentity) : Known(identity)
    class Successor(val savedId: StationId, val target: Known, val datumShiftMetres: Double?) :
        StationResolution(target.id)
    class Unknown(id: StationId) : StationResolution(id)
}
