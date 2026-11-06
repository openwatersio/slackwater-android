package io.openwaters.slackwater.android.map

import android.app.Activity
import android.os.Bundle
import android.os.Trace
import android.widget.Button
import io.openwaters.slackwater.android.core.map.PinBox
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Fixed cameras from the pinned iOS NationalScaleTests, run in the optimized target process. */
class MapBenchmarkActivity : Activity() {
    private val scope: CoroutineScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = StationMapModel(this)
        val harbour = intent.getStringExtra("camera") == "harbour"
        val box = if (harbour) PinBox(32.70, -80.05, 32.87, -79.83) else PinBox(25.0, -95.0, 45.0, -66.0)
        val zoom = if (harbour) 11.5 else 4.2
        val time = Instant.ofEpochSecond(1_785_000_000)
        val button = Button(this)
        setContentView(button)
        scope.launch {
            val warm = model.pins(box, zoom, null, null, time)
            check(warm.isNotEmpty())
            button.text = "Measure"
            button.setOnClickListener {
                button.isEnabled = false
                scope.launch {
                    Trace.beginAsyncSection("visibleStationStates", 1)
                    val count = try { model.pins(box, zoom, null, null, time).size }
                        finally { Trace.endAsyncSection("visibleStationStates", 1) }
                    check(count == warm.size)
                    button.text = "Measured $count stations"
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
