package io.openwaters.slackwater.android.widgets

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.ChsCurrentFit
import io.openwaters.slackwater.CurrentEventKind
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.core.prediction.*
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.data.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetRenderingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-07-15T12:00:00Z")

    private suspend fun rendered(repo: PredictionRepository, id: StationId, at: Instant = now,
                                prefs: ReadingPreferences = ReadingPreferences(), wide: Boolean = true): View {
        val remote = renderStationWidget(context, 0, id, WidgetSize(if (wide) 320f else 140f, 160f), 1, at, repo, prefs)
        lateinit var view: View
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view = remote.apply(context, FrameLayout(context))
        }
        return view
    }

    @Test fun provisionalSettingsAndInvalidRecordReachTheLauncherCard() = runBlocking {
        val directory = File(context.cacheDir, "widget-fit-${UUID.randomUUID()}")
        val store = ModelStore(directory)
        val repo = AndroidPredictionRepository(context, store)
        val id = StationId("chs-johnstone-strait-central")
        val fit = JSONObject("""{"offset":0.0,"constituents":[{"name":"M2","amplitude":3.0,"phase":10}]}""")
            .put("recipe", ChsCurrentFit.STAMP).put("fitDays", 60).put("floodDirection", 20).put("ebbDirection", 215)
        try {
            store.write(id, ModelKind.CURRENT, fit)
            val prediction = repo.load(id, widgetSpan(now, true), PredictionPurpose.WIDGET) as PredictionResult.Ready
            val slack = (prediction.series as PredictionSeries.Current).events.first { it.kind == CurrentEventKind.SLACK }.time
            val nearSlack = slack.plusSeconds(60)
            val prefs = ReadingPreferences(speedUnit = SpeedUnit.METRES_PER_SECOND, slackWindowKnots = 0.3,
                stationDeltasKn = mapOf(id to 0.2))
            val card = rendered(repo, id, nearSlack, prefs)
            val reading = card.findViewById<TextView>(R.id.widget_reading).text.toString()
            assertTrue(reading, reading.contains("~") && reading.contains(context.getString(R.string.unit_metres_per_second)))
            val close = context.getString(R.string.chart_closes)
            assertTrue(card.findViewById<TextView>(R.id.widget_event).text.toString().contains(close))
            val off = rendered(repo, id, nearSlack, prefs.copy(stationDeltasKn = mapOf(id to -0.3)))
            assertFalse(off.findViewById<TextView>(R.id.widget_event).text.toString().contains(close))
            store.write(id, ModelKind.CURRENT, fit.put("recipe", "obsolete"))
            val invalid = rendered(repo, id)
            assertEquals(context.getString(R.string.card_unavailable), invalid.findViewById<TextView>(R.id.widget_reading).text)
            assertEquals(View.GONE, invalid.findViewById<View>(R.id.widget_curve).visibility)
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun cachedOnlineDataCanCoverTheSmallCardWithoutCoveringTheWideCard() = runBlocking {
        val directory = File(context.cacheDir, "widget-online-${UUID.randomUUID()}")
        val store = ModelStore(directory)
        val repo = AndroidPredictionRepository(context, store)
        val id = StationId("chs-arran-rapids")
        try {
            val span = widgetSpan(now, false)
            val samples = generateSequence(span.start.epochSecond - 900) { it + 900 }
                .takeWhile { it <= span.end.epochSecond + 900 }.toList()
            store.write(id, ModelKind.ONLINE_WINDOW, JSONObject()
                .put("times", JSONArray(samples))
                .put("speeds", JSONArray(samples.map { kotlin.math.sin((it - now.epochSecond) / 7200.0) }))
                .put("floodDirection", 20).put("ebbDirection", 215))
            val covered = rendered(repo, id, wide = false)
            assertEquals(View.VISIBLE, covered.findViewById<View>(R.id.widget_curve).visibility)
            val partial = rendered(repo, id, wide = true)
            assertEquals(context.getString(R.string.card_unavailable), partial.findViewById<TextView>(R.id.widget_reading).text)
            assertEquals(View.GONE, partial.findViewById<View>(R.id.widget_curve).visibility)
        } finally { directory.deleteRecursively() }
        Unit
    }

    @Test fun expiredOnlineCoverageCannotShowAnOldCurveOrClosingTime() = runBlocking {
        val directory = File(context.cacheDir, "widget-expired-${UUID.randomUUID()}")
        val store = ModelStore(directory)
        val id = StationId("chs-arran-rapids")
        try {
            val yesterday = now.minusSeconds(86400).epochSecond
            store.write(id, ModelKind.ONLINE_WINDOW, JSONObject()
                .put("times", JSONArray(listOf(yesterday, yesterday + 900)))
                .put("speeds", JSONArray(listOf(-1.0, 1.0))).put("floodDirection", 20).put("ebbDirection", 215))
            val card = rendered(AndroidPredictionRepository(context, store), id)
            assertEquals(context.getString(R.string.card_unavailable), card.findViewById<TextView>(R.id.widget_reading).text)
            assertEquals(View.GONE, card.findViewById<View>(R.id.widget_event).visibility)
            assertEquals(View.GONE, card.findViewById<View>(R.id.widget_curve).visibility)
        } finally { directory.deleteRecursively() }
        Unit
    }
}
