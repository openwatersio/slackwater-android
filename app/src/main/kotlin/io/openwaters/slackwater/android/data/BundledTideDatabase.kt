package io.openwaters.slackwater.android.data

import android.content.res.AssetManager
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.database.Station
import io.openwaters.slackwater.database.StationDatabase
import java.io.FileInputStream

class BundledTideDatabase(private val assets: AssetManager) {
    private val database by lazy {
        assets.openFd("slackwater/stations.tcdb").use { asset ->
            FileInputStream(asset.fileDescriptor).channel.use { channel ->
                StationDatabase.open(channel, asset.startOffset, asset.length)
            }
        }
    }

    fun station(id: StationId): Station? = database.station(id.value)
}
