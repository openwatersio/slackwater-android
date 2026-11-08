package io.openwaters.slackwater.android.map

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.openwaters.slackwater.android.core.station.LocationFix
import io.openwaters.slackwater.android.core.station.LocationPermission
import io.openwaters.slackwater.android.data.ListPreferencesStore
import io.openwaters.slackwater.android.stations.FixSource
import io.openwaters.slackwater.android.stations.LocationRepository
import io.openwaters.slackwater.android.stations.StationListModel
import io.openwaters.slackwater.android.stations.StationListScreen
import io.openwaters.slackwater.android.stations.StationsHome
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class MapLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val model = StationListModel.get(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun aMapLocationPromptSuppressesTheAutomaticListPrompt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ListPreferencesStore.open(File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb"), scope)
        val source = object : FixSource {
            override fun permission() = LocationPermission.NOT_GRANTED
            override fun lastKnown() = emptyList<LocationFix>()
            override suspend fun current(): LocationFix? = null
        }
        val isolated = StationListModel(context, store, LocationRepository(source), scope)
        var prompts = 0
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                                             options: ActivityOptionsCompat?) {
                    prompts++
                    dispatchResult(requestCode, emptyMap<String, Boolean>())
                }
            }
        }
        try {
            val map = mutableStateOf(true)
            val engine = StationMapModel(context)
            val now = Instant.now()
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    if (map.value) MapScreen(isolated, engine, now, null, 0, {}, { map.value = false }, {})
                    else StationListScreen(isolated, {}, {}, {}, now)
                }
            }
            compose.onNodeWithText("My Location").performClick()
            compose.waitUntil { prompts == 1 }
            assertFalse(isolated.claimLocationAsk(storedAsked = false))
            compose.waitUntil { isolated.preferences.value?.locationAsked == true }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("List").performClick()
            compose.waitUntil(30_000) { isolated.state.value != null }
            compose.onNodeWithText("Search").assertIsDisplayed()
            assertEquals(1, prompts)
        } finally {
            scope.cancel()
        }
    }

    @Test fun foldingAnAutomaticDetailReturnsToTheListWithoutRecordingAVisit() {
        val wide = mutableStateOf(true)
        val before = runBlocking { model.places.places.first().getOrThrow().recents }
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val scale = with(density) { maxWidth.toPx() } / 720f
                CompositionLocalProvider(LocalDensity provides Density(scale)) {
                    Box(Modifier.width(if (wide.value) 720.dp else 390.dp)) { StationsHome(onCredits = {}) }
                }
            }
        }
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("detailTimeline").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Map").assertIsDisplayed()
        compose.runOnIdle { wide.value = false }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Map").assertIsDisplayed()
        assertEquals(before, runBlocking { model.places.places.first().getOrThrow().recents })
        compose.runOnIdle { wide.value = true }
        compose.onNodeWithText("Map").performClick()
        compose.onNodeWithTag("station-map").assertExists()
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("List").assertDoesNotExist()
    }
}
