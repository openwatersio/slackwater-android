package io.openwaters.slackwater.android.data

import io.openwaters.slackwater.android.core.settings.ReadingSettings
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationCatalog
import androidx.datastore.core.DataMigration
import kotlinx.coroutines.sync.withLock
import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import io.openwaters.slackwater.android.widgets.WidgetRefreshWorker
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Stored values match the iOS and web settings, so a value means the same thing on every platform.
enum class HeightUnits(internal val stored: String) { IMPERIAL("imperial"), METRIC("metric") }

enum class SpeedUnit(internal val stored: String) { KNOTS("kn"), KILOMETRES_PER_HOUR("kmh"), METRES_PER_SECOND("ms") }

/** How a current's set reads: a compass point ("SW") or a three-digit true bearing ("225°"). */
enum class SetFormat(internal val stored: String) { COMPASS("compass"), DEGREES("degrees") }

data class ReadingPreferences(
    /** Null until the person picks one, so the regional default follows the device locale. */
    val savedHeightUnits: HeightUnits? = null,
    val speedUnit: SpeedUnit = SpeedUnit.KNOTS,
    val setFormat: SetFormat = SetFormat.COMPASS,
    /** The comfort current: the speed that bounds a slack window. Zero turns the window off. */
    val slackWindowKnots: Double = DEFAULT_SLACK_WINDOW_KNOTS,
    val stationDeltasKn: Map<StationId, Double> = emptyMap(),
    val showSky: Boolean = true,
) {
    fun heightUnits(locale: Locale = Locale.getDefault()): HeightUnits =
        savedHeightUnits ?: if (locale.country == "US") HeightUnits.IMPERIAL else HeightUnits.METRIC

    fun effectiveThresholdKn(id: StationId) = ReadingSettings(slackWindowKnots, stationDeltasKn).effectiveThresholdKn(id)

    val slackWindowOff: Boolean get() = slackWindowKnots <= 0

    companion object {
        const val DEFAULT_SLACK_WINDOW_KNOTS = 0.5
        val SLACK_WINDOW_KNOTS_RANGE = 0.0..10.0

        /** Rounded to the setting's tenth first, so stepping 0.3 down three times reads as off, not as a bad value. */
        fun normalizedSlackWindowKnots(value: Double): Double {
            if (!value.isFinite()) return DEFAULT_SLACK_WINDOW_KNOTS
            // Half away from zero, as iOS rounds, so -0.05 is out of range there and here; + 0.0 drops -0.0.
            val tenth = sign(value) * floor(abs(value) * 10 + 0.5) / 10 + 0.0
            return if (tenth in SLACK_WINDOW_KNOTS_RANGE) tenth else DEFAULT_SLACK_WINDOW_KNOTS
        }
    }
}

private val skyKey = booleanPreferencesKey("showSky")
private val heightUnitsKey = stringPreferencesKey("heightUnits")
private val speedUnitKey = stringPreferencesKey("speedUnit")
private val setFormatKey = stringPreferencesKey("setFormat")
private const val DELTA_PREFIX = "slackWindowDelta/"
private val slackWindowKey = doublePreferencesKey("slackWindowKnots")

// A value this build cannot read (a newer build's unit, a wrong type) reads as the default
// and stays on disk until the person picks another.
private inline fun <reified T> Preferences.value(key: Preferences.Key<*>): T? = asMap()[key] as? T

private fun <E> Preferences.choice(key: Preferences.Key<String>, values: List<E>, stored: (E) -> String): E? =
    value<String>(key)?.let { saved -> values.firstOrNull { stored(it) == saved } }

private fun Preferences.readPreferences() = ReadingPreferences(
    showSky = value<Boolean>(skyKey) ?: true,
    stationDeltasKn = asMap().mapNotNull { (key, value) ->
        if (key.name.startsWith(DELTA_PREFIX) && value is Double && value.isFinite() && value in -10.0..10.0)
            StationId(key.name.removePrefix(DELTA_PREFIX)) to value else null
    }.toMap(),
    savedHeightUnits = choice(heightUnitsKey, HeightUnits.entries) { it.stored },
    speedUnit = choice(speedUnitKey, SpeedUnit.entries) { it.stored } ?: SpeedUnit.KNOTS,
    setFormat = choice(setFormatKey, SetFormat.entries) { it.stored } ?: SetFormat.COMPASS,
    slackWindowKnots = ReadingPreferences.normalizedSlackWindowKnots(
        value<Double>(slackWindowKey) ?: ReadingPreferences.DEFAULT_SLACK_WINDOW_KNOTS,
    ),
)

/** How readings are shown: units, set format and the comfort current. Device-local, in the no-backup directory. */
class ReadingPreferencesStore private constructor(private val data: DataStore<Preferences>, private val catalog: StationCatalog?, private val onReadingChanged: () -> Unit) {
    /**
     * A failed read, such as a file the app cannot open, emits a failure and ends the flow; collect again to retry.
     * Readings can show `getOrDefault(ReadingPreferences())` meanwhile.
     */
    val preferences: Flow<Result<ReadingPreferences>> = data.data
        .map { Result.success(it.readPreferences()) }
        .catch { error ->
            if (error !is IOException) throw error
            emit(Result.failure(error))
        }
        .distinctUntilChanged()

    private suspend fun editReading(change: (MutablePreferences) -> Unit): Preferences =
        data.edit { change(it) }.also { onReadingChanged() }

    /** Null restores inheritance from the global setting. Keys retain the complete station ID. */
    suspend fun setStationThresholdDeltaKn(id: StationId, delta: Double?) {
        require(delta == null || (delta.isFinite() && delta in -10.0..10.0))
        val canonical = catalog?.resolve(id)?.id ?: id
        val key = doublePreferencesKey(DELTA_PREFIX + canonical.value)
        alertInputLock.withLock { editReading { if (delta == null) it.remove(key) else it[key] = delta } }
    }

    suspend fun setShowSky(show: Boolean) { data.edit { it[skyKey] = show } }

    suspend fun setHeightUnits(units: HeightUnits) = editReading { it[heightUnitsKey] = units.stored }

    suspend fun setSpeedUnit(unit: SpeedUnit) = editReading { it[speedUnitKey] = unit.stored }

    suspend fun setSetFormat(format: SetFormat) = editReading { it[setFormatKey] = format.stored }

    /** Clamped into range, so stepping past either end keeps the nearest setting instead of resetting it. */
    suspend fun setSlackWindowKnots(knots: Double) {
        require(knots.isFinite()) { "Comfort current must be finite: $knots" }
        val range = ReadingPreferences.SLACK_WINDOW_KNOTS_RANGE
        val clamped = knots.coerceIn(range.start, range.endInclusive)
        alertInputLock.withLock { editReading { it[slackWindowKey] = ReadingPreferences.normalizedSlackWindowKnots(clamped) } }
    }

    companion object {
        private var shared: ReadingPreferencesStore? = null

        @Synchronized
        fun get(context: Context): ReadingPreferencesStore {
            val app = context.applicationContext
            return shared ?: open(
                File(app.noBackupFilesDir, "reading_preferences.preferences_pb"),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                BundledStationCatalog(app.assets), { WidgetRefreshWorker.refresh(app) },
            ).also { shared = it }
        }

        internal fun open(file: File, scope: CoroutineScope, catalog: StationCatalog? = null, onReadingChanged: () -> Unit = {}) = ReadingPreferencesStore(
            PreferenceDataStoreFactory.create(
                // Unreadable settings are cheap to choose again; replacing them keeps the settings writable.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = if (catalog == null) emptyList() else listOf(object : DataMigration<Preferences> {
                    override suspend fun shouldMigrate(currentData: Preferences) =
                        currentData.asMap().keys.any { it.name.startsWith(DELTA_PREFIX) }

                    override suspend fun migrate(currentData: Preferences): Preferences {
                        val next = currentData.toMutablePreferences()
                        for ((id, delta) in currentData.readPreferences().stationDeltasKn.entries.sortedBy { it.key.value }) {
                            val canonical = catalog.resolve(id).id
                            if (canonical == id) continue
                            val key = doublePreferencesKey(DELTA_PREFIX + canonical.value)
                            // A choice already saved at the successor wins a collision.
                            if (!next.contains(key)) next[key] = delta
                            next.remove(doublePreferencesKey(DELTA_PREFIX + id.value))
                        }
                        return next
                    }
                    override suspend fun cleanUp() = Unit
                }),
                scope = scope,
                produceFile = { file },
            ), catalog, onReadingChanged,
        )
    }
}
