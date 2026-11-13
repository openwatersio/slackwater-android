package io.openwaters.slackwater.android.shortcuts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openwaters.slackwater.android.R
import io.openwaters.slackwater.android.SlackwaterTheme
import io.openwaters.slackwater.android.enableWaterEdgeToEdge
import io.openwaters.slackwater.android.core.settings.ReadingSettings
import io.openwaters.slackwater.android.core.shortcuts.*
import io.openwaters.slackwater.android.core.station.StationIdentity
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationSeries
import io.openwaters.slackwater.android.data.AndroidPredictionRepository
import io.openwaters.slackwater.android.data.ReadingPreferences
import io.openwaters.slackwater.android.data.ReadingPreferencesStore
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class QueryActivity : ComponentActivity() {
    private var launchIntent by mutableStateOf<Intent?>(null)
    private var generation by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        generation++
        launchIntent = intent
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("generation", generation)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        generation = savedInstanceState?.getInt("generation") ?: 0
        launchIntent = intent
        enableWaterEdgeToEdge()
        setContent {
            SlackwaterTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val request = launchIntent?.let(ShortcutPublisher::read)
                        if (request == null) Text(stringResource(R.string.query_invalid))
                        else key(generation) { QueryResultScreen(request) }
                    }
                }
            }
        }
    }
}

private sealed interface ScreenResult {
    data class Answer(val station: StationIdentity, val prediction: QueryAnswer, val prefs: ReadingPreferences, val queriedAt: Instant) : ScreenResult
    data class Choose(val stations: List<StationIdentity>) : ScreenResult
    data object NeedsLocation : ScreenResult
    data object Missing : ScreenResult
    data object Failed : ScreenResult
}

private class AnswerLoader(context: Context) {
    private val places = QueryPlaceResolver(context)
    private val predictions = AndroidPredictionRepository(context)
    private val preferences = ReadingPreferencesStore.get(context)

    suspend fun load(request: ShortcutRequest): ScreenResult = withContext(Dispatchers.Default) {
        val place = request.stationId?.let { QueryPlace.Selected(it) }
            ?: request.place?.let { QueryPlace.Named(it) } ?: QueryPlace.Nearby
        val kind = when (request.kind) {
            ShortcutKind.LOW -> QueryKind.LOW
            ShortcutKind.HIGH -> QueryKind.HIGH
            ShortcutKind.SLACK -> QueryKind.SLACK
        }
        val series = if (kind == QueryKind.SLACK) StationSeries.Current else StationSeries.Tide
        when (val resolved = places.resolve(place, series)) {
            QueryPlaces.NeedsLocation -> ScreenResult.NeedsLocation
            QueryPlaces.Missing -> if (place == QueryPlace.Nearby) ScreenResult.Failed else ScreenResult.Missing
            is QueryPlaces.Ambiguous -> ScreenResult.Choose(resolved.stations)
            is QueryPlaces.Candidates -> {
                val prefs = preferences.preferences.first().getOrThrow()
                val settings = ReadingSettings(prefs.slackWindowKnots, prefs.stationDeltasKn)
                val now = Instant.now()
                for (station in resolved.stations) {
                    val answer = queryPrediction(predictions, station, kind, now, request.date, settings)
                    if (answer != QueryAnswer.Unavailable || place != QueryPlace.Nearby)
                        return@withContext ScreenResult.Answer(station, answer, prefs, now)
                }
                ScreenResult.Failed
            }
        }
    }
}

@Composable
private fun QueryResultScreen(initial: ShortcutRequest) {
    val context = LocalContext.current
    val loader = remember { AnswerLoader(context.applicationContext) }
    var appliedPlace by rememberSaveable { mutableStateOf(initial.place) }
    var appliedDate by rememberSaveable { mutableStateOf(initial.date?.toString()) }
    var stationId by rememberSaveable { mutableStateOf(initial.stationId?.value) }
    var place by rememberSaveable { mutableStateOf(initial.place.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(initial.date?.toString().orEmpty()) }
    val parsed = remember(date) {
        try { LocalDate.parse(date.trim()).takeIf { it.year in 1..9999 } } catch (_: DateTimeParseException) { null }
    }
    val invalid = date.isNotBlank() && parsed == null
    var attempt by remember { mutableIntStateOf(0) }
    val request = initial.copy(place = appliedPlace, date = appliedDate?.let(LocalDate::parse), stationId = stationId?.let(::StationId))
    val dirty = place.trim().ifEmpty { null } != appliedPlace || invalid || parsed != request.date
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { attempt++ }
    val result by key(request, attempt) {
        produceState<ScreenResult?>(null) {
            value = try { loader.load(request) } catch (_: IOException) { ScreenResult.Failed }
        }
    }
    Text(stringResource(when (initial.kind) {
        ShortcutKind.LOW -> if (request.date == null) R.string.query_low_title else R.string.query_low_day
        ShortcutKind.HIGH -> if (request.date == null) R.string.query_high_title else R.string.query_high_day
        ShortcutKind.SLACK -> if (request.date == null) R.string.query_slack_title else R.string.query_slack_day
    }), style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.query_place)) },
        supportingText = { Text(stringResource(R.string.query_nearby_hint)) })
    OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), singleLine = true, isError = invalid,
        label = { Text(stringResource(R.string.query_date)) },
        supportingText = { Text(stringResource(R.string.query_date_hint)) })
    Button(enabled = !invalid, onClick = {
        val newPlace = place.trim().ifEmpty { null }
        if (newPlace != appliedPlace) stationId = null
        appliedPlace = newPlace
        appliedDate = parsed?.toString()
        attempt++
    }) { Text(stringResource(R.string.query_show)) }

    if (dirty) return
    when (val current = result) {
        null -> CircularProgressIndicator()
        ScreenResult.Missing -> Text(stringResource(R.string.query_missing, request.place ?: request.stationId?.value.orEmpty()))
        ScreenResult.Failed -> Text(stringResource(R.string.query_unavailable))
        ScreenResult.NeedsLocation -> {
            Text(stringResource(R.string.query_location))
            TextButton(onClick = {
                permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }) { Text(stringResource(R.string.query_use_location)) }
        }
        is ScreenResult.Choose -> {
            Text(stringResource(R.string.query_choose))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(current.stations, key = { it.id.value }) { station ->
                    TextButton(onClick = { stationId = station.id.value }) {
                        Text(listOf(station.name, station.region, station.id.value).filter(String::isNotBlank).joinToString(" · "))
                    }
                }
            }
        }
        is ScreenResult.Answer -> {
            QueryAnswerView(current.station, current.prediction, current.prefs, request, current.queriedAt)
            if (ShortcutPublisher.canPin(context)) {
                var failed by remember(request) { mutableStateOf(false) }
                TextButton(onClick = {
                    val pin = if (request.place != null) request.copy(stationId = current.station.id) else request
                    failed = !ShortcutPublisher.pin(context, pin)
                }) {
                    Text(stringResource(R.string.query_pin))
                }
                if (failed) Text(stringResource(R.string.query_pin_failed))
            }
        }
    }
}
