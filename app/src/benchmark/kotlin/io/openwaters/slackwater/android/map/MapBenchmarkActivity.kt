package io.openwaters.slackwater.android.map

import android.app.Activity
import android.os.Bundle
import android.os.Trace
import android.widget.Button
import io.openwaters.slackwater.android.core.map.PinBox
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationKind
import io.openwaters.slackwater.android.data.ModelKind
import io.openwaters.slackwater.android.data.ModelStore
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Fixed US and Canadian cameras, run in the optimized target process. */
class MapBenchmarkActivity : Activity() {
    private val scope: CoroutineScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val camera = intent.getStringExtra("camera")
        val (box, zoom) = when (camera) {
            "harbour" -> PinBox(32.70, -80.05, 32.87, -79.83) to 11.5
            "canada", "canadaFitted" -> PinBox(48.3, -124.2, 49.5, -122.7) to 9.5
            else -> PinBox(25.0, -95.0, 45.0, -66.0) to 4.2
        }
        val time = Instant.ofEpochSecond(1_785_000_000)
        val button = Button(this)
        setContentView(button)
        val fitted = camera == "canadaFitted"
        val store = if (fitted) ModelStore(File(cacheDir, "map-benchmark-fits")) else ModelStore.get(this)
        scope.launch {
            if (fitted) seedTides(store, box)
            button.text = "Measure"
        }
        button.setOnClickListener {
            button.isEnabled = false
            scope.launch {
                Trace.beginAsyncSection("coldVisibleStations", 1)
                val model: StationMapModel
                val coldCount = try {
                    model = StationMapModel(this@MapBenchmarkActivity, store)
                    val pins = model.pins(box, zoom, null, null, time)
                    if (fitted) {
                        val tides = pins.filter { it.station.kind == StationKind.CanadianTide }
                        check(tides.isNotEmpty() && tides.all { it.state.reading != null })
                        android.util.Log.i("MapBenchmark", "Fitted Canadian tides: ${tides.size}")
                    }
                    pins.size
                } finally { Trace.endAsyncSection("coldVisibleStations", 1) }
                Trace.beginAsyncSection("warmVisibleStations", 2)
                val warmCount = try { model.pins(box, zoom, null, null, time).size }
                    finally { Trace.endAsyncSection("warmVisibleStations", 2) }
                check(coldCount > 0 && coldCount == warmCount)
                button.text = "Measured $warmCount stations"
            }
        }
    }

    // Use the pinned Victoria mixed-tide fixture for every local gauge to exercise fitted-file reads.
    private suspend fun seedTides(store: ModelStore, box: PinBox) {
        val record = resources.openRawResource(R.raw.map_benchmark_tide).bufferedReader().use { JSONObject(it.readText()) }
        val rows = assets.open("slackwater/chs-stations.json").bufferedReader().use { JSONArray(it.readText()) }
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (box.contains(row.getDouble("latitude"), row.getDouble("longitude"))) {
                store.write(StationId(row.getString("id")), ModelKind.TIDE, record)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
