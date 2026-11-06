package io.openwaters.slackwater.android.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.openwaters.slackwater.android.core.planning.AlertRule
import io.openwaters.slackwater.android.core.planning.AlertTrigger
import io.openwaters.slackwater.android.core.planning.SunWindow
import io.openwaters.slackwater.android.core.station.StationCatalog
import io.openwaters.slackwater.android.core.station.StationId
import io.openwaters.slackwater.android.core.station.StationResolution
import java.io.IOException
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Each element is the original JSON text of one rule. Rewriting a neighboring
// rule must preserve even whitespace and escape sequences in an unreadable one.
private val rulesKey = stringPreferencesKey("alertRules")
private fun Preferences.records(): List<String> {
    val raw = this[rulesKey] ?: return emptyList()
    try {
        val rows = JSONArray(raw)
        return List(rows.length()) { index ->
            rows.get(index) as? String ?: throw IOException("Invalid stored alert rule list")
        }
    } catch (e: JSONException) {
        throw IOException("Invalid stored alert rule list", e)
    }
}

class AlertRuleStore private constructor(private val data: DataStore<Preferences>, private val catalog: StationCatalog) {
    val rules: Flow<List<AlertRule>> = data.data.map { prefs ->
        prefs.records().mapNotNull(::decodeAlertRule)
    }.distinctUntilChanged()

    suspend fun upsert(rule: AlertRule) = save(rule, heightConfirmed = false)

    /** A user-entered height is measured against the selected station's current datum. */
    suspend fun confirmHeight(rule: AlertRule, heightM: Double) {
        val crossing = requireNotNull(rule.trigger as? AlertTrigger.TideCrossing)
        save(rule.copy(trigger = crossing.copy(heightM = heightM)), heightConfirmed = true)
    }

    private suspend fun save(rule: AlertRule, heightConfirmed: Boolean) {
        val resolution = catalog.resolve(rule.stationId)
        edit { rows ->
            val index = rows.indexOfFirst { decodeAlertRule(it)?.id == rule.id }
            val previous = rows.getOrNull(index)?.let(::decodeAlertRule)
            val review = rule.trigger is AlertTrigger.TideCrossing && !heightConfirmed &&
                (rule.heightNeedsReview || previous?.heightNeedsReview == true)
            val updated = rule.copy(enabled = rule.enabled && !review, heightNeedsReview = review)
            val saved = if (resolution is StationResolution.Successor) updated.onSuccessor(resolution) else updated
            if (index < 0) rows.add(saved.encode()) else rows[index] = saved.encode()
        }
    }

    suspend fun remove(id: UUID) = edit { rows -> rows.removeAll { decodeAlertRule(it)?.id == id } }

    suspend fun expire(now: Instant) = edit { rows -> rows.removeAll { decodeAlertRule(it)?.isExpired(now) == true } }

    private suspend fun edit(change: (MutableList<String>) -> Unit): Unit = alertInputLock.withLock {
        data.edit { prefs ->
            val previous = prefs.records()
            val next = previous.toMutableList().apply(change)
            if (next != previous) prefs[rulesKey] = JSONArray(next).toString()
        }
    }

    companion object {
        private var shared: AlertRuleStore? = null

        @Synchronized
        fun get(context: Context): AlertRuleStore {
            val app = context.applicationContext
            return shared ?: open(
                File(app.noBackupFilesDir, "alerts.preferences_pb"), BundledStationCatalog(app.assets),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { shared = it }
        }

        internal fun open(file: File, catalog: StationCatalog, scope: CoroutineScope): AlertRuleStore {
            val successors = object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences) = currentData.contains(rulesKey)

                override suspend fun migrate(currentData: Preferences): Preferences {
                    val rows = currentData.records()
                    val moved = rows.map { raw ->
                        val rule = decodeAlertRule(raw) ?: return@map raw
                        val successor = catalog.resolve(rule.stationId) as? StationResolution.Successor ?: return@map raw
                        rule.onSuccessor(successor).encode()
                    }
                    if (rows == moved) return currentData
                    return currentData.toMutablePreferences().apply { this[rulesKey] = JSONArray(moved).toString() }
                }

                override suspend fun cleanUp() = Unit
            }
            val data = PreferenceDataStoreFactory.create(migrations = listOf(successors), scope = scope) { file }
            return AlertRuleStore(data, catalog)
        }
    }
}

private fun AlertRule.encode(): String = JSONObject().apply {
    put("version", 1)
    put("id", id.toString())
    put("stationID", stationId.value)
    put("once", once?.toString())
    put("lead", leadSeconds)
    put("sun", sun?.name)
    put("enabled", enabled)
    put("heightNeedsReview", heightNeedsReview)
    val type = when (val t = trigger) {
        AlertTrigger.SlackWindowOpens -> "slackWindowOpens"
        AlertTrigger.Slack -> "slack"
        AlertTrigger.Eclipse -> "eclipse"
        is AlertTrigger.CurrentPeak -> {
            put("flood", t.flood)
            "currentPeak"
        }
        is AlertTrigger.TideExtreme -> {
            put("high", t.high)
            put("ofDay", t.ofDay)
            "tideExtreme"
        }
        is AlertTrigger.TideCrossing -> {
            put("heightM", t.heightM)
            put("rising", t.rising)
            "tideCrossing"
        }
    }
    put("trigger", type)
}.toString()

private val commonFields = setOf("version", "id", "stationID", "trigger", "once", "lead", "sun", "daylightOnly", "enabled", "heightNeedsReview")

private fun decodeAlertRule(raw: String): AlertRule? {
    try {
        val row = JSONObject(raw)
        if (row.get("version") != 1) return null
        fun boolean(key: String) = row.get(key) as Boolean
        fun optionalBoolean(key: String) = row.has(key) && boolean(key)
        val type = row.get("trigger") as String
        val fields = when (type) {
            "currentPeak" -> setOf("flood")
            "tideExtreme" -> setOf("high", "ofDay")
            "tideCrossing" -> setOf("heightM", "rising")
            else -> emptySet()
        }
        if (row.keys().asSequence().any { it !in commonFields && it !in fields }) return null
        val trigger = when (type) {
            "slackWindowOpens" -> AlertTrigger.SlackWindowOpens
            "slack" -> AlertTrigger.Slack
            "eclipse" -> AlertTrigger.Eclipse
            "currentPeak" -> AlertTrigger.CurrentPeak(boolean("flood"))
            "tideExtreme" -> AlertTrigger.TideExtreme(boolean("high"), optionalBoolean("ofDay"))
            "tideCrossing" -> AlertTrigger.TideCrossing((row.get("heightM") as Number).toDouble(), boolean("rising"))
            else -> return null
        }
        val sun = (row.opt("sun") as String?)?.let(SunWindow::valueOf)
            ?: if (optionalBoolean("daylightOnly")) SunWindow.DAYLIGHT else null
        val lead = when (val seconds = row.get("lead")) {
            is Int -> seconds.toLong()
            is Long -> seconds
            else -> return null
        }
        return AlertRule(
            id = UUID.fromString(row.get("id") as String), stationId = StationId(row.get("stationID") as String),
            trigger = trigger, once = (row.opt("once") as String?)?.let(Instant::parse),
            leadSeconds = lead, sun = sun, enabled = boolean("enabled"),
            heightNeedsReview = optionalBoolean("heightNeedsReview"),
        )
    } catch (_: JSONException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: ClassCastException) {
        return null
    } catch (_: DateTimeParseException) {
        return null
    }
}
